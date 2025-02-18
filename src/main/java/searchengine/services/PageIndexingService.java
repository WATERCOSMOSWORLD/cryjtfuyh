package searchengine.services;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import searchengine.config.SitesList;
import searchengine.config.ConfigSite;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.IndexingStatus;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import org.jsoup.Connection;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Random;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Service
public class PageIndexingService {

    @PersistenceContext
    private EntityManager entityManager;

    private final SitesList sitesList;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;

    @Autowired
    public PageIndexingService(SitesList sitesList, SiteRepository siteRepository, PageRepository pageRepository) {
        this.sitesList = sitesList;
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
    }

    // Проверка, входит ли URL в список настроенных сайтов
    public boolean isUrlWithinConfiguredSites(String url) {
        List<ConfigSite> sites = sitesList.getSites();
        for (ConfigSite site : sites) {
            if (url.startsWith(site.getUrl())) {
                return true;
            }
        }
        return false;
    }

    @Transactional
    public void indexSite(String baseUrl, int maxDepth) throws Exception {
        // Проверяем, входит ли URL в список настроенных сайтов
        if (!isUrlWithinConfiguredSites(baseUrl)) {
            throw new Exception("URL не принадлежит к списку настроенных сайтов: " + baseUrl);
        }

        // Проверяем, существует ли сайт в базе данных
        Site site = siteRepository.findByUrl(baseUrl);
        if (site != null) {
            // Удаляем старые данные, если сайт уже существует
            deleteSiteData(site);
        }

        // Создаем новый сайт для индексации
        site = new Site();
        site.setUrl(baseUrl);
        site.setName(baseUrl); // Можно заменить на реальное имя сайта
        site.setStatus(IndexingStatus.INDEXING);
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);

        Set<String> visitedUrls = new HashSet<>();
        Queue<UrlDepthPair> queue = new LinkedList<>();
        queue.add(new UrlDepthPair(baseUrl, 0)); // Начальная пара URL и глубина 0

        Random random = new Random();

        while (!queue.isEmpty()) {
            UrlDepthPair current = queue.poll();
            String currentUrl = current.getUrl();
            int currentDepth = current.getDepth();

            if (visitedUrls.contains(currentUrl) || currentDepth >= maxDepth) {
                continue;
            }
            visitedUrls.add(currentUrl);

            try {
                System.out.println("Обрабатываю страницу на глубине " + currentDepth + ": " + currentUrl);

                // Выполняем запрос к текущей странице
                Connection.Response response = Jsoup.connect(currentUrl).ignoreContentType(true).execute();
                String contentType = response.contentType();

                // Сохраняем страницы HTML и изображения (JPG, PNG и т.д.)
                if (isSupportedContentType(currentUrl, contentType)) {
                    savePageContent(response, currentUrl, site);
                }

                // Извлекаем ссылки только для HTML-страниц
                if (contentType != null && contentType.startsWith("text/html")) {
                    Document document = response.parse();
                    Elements links = document.select("a[href]");
                    for (Element link : links) {
                        String absUrl = link.attr("abs:href");
                        if (absUrl.startsWith(baseUrl) && !visitedUrls.contains(absUrl)) {
                            queue.add(new UrlDepthPair(absUrl, currentDepth + 1)); // Увеличиваем глубину
                        }
                    }
                }

                // Добавляем случайную задержку между запросами (от 0,5 до 5 секунд)
                int delay = random.nextInt(4500) + 500; // Генерирует значение от 500 до 5000 миллисекунд
                System.out.println("Задержка перед следующим запросом: " + delay + " миллисекунд.");
                Thread.sleep(delay);

            } catch (IOException e) {
                System.err.println("Ошибка загрузки страницы: " + currentUrl + " - " + e.getMessage());
            }

            // Обновляем статус времени после обработки каждой страницы (даже если страница не была успешно загружена)
            site.setStatusTime(LocalDateTime.now());
            siteRepository.save(site);
            System.out.println("Обновление времени в базе данных: " + LocalDateTime.now()); // Логирование обновления времени
        }

        // Завершаем индексацию
        site.setStatus(IndexingStatus.INDEXED);
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);

        System.out.println("Индексация сайта завершена: " + baseUrl);
    }

    private void savePageContent(Connection.Response response, String url, Site site) {
        try {
            String content = response.body();
            String contentType = response.contentType();
            String path = getPathFromUrl(url);
            int statusCode = response.statusCode();

            // Проверка, существует ли уже такая страница для данного сайта
            Optional<Page> existingPage = pageRepository.findBySiteAndPath(site, path);
            if (existingPage.isPresent()) {
                System.out.println("Страница уже существует: " + url);
                return; // Не сохраняем, если такая страница уже есть
            }

            Page page = new Page();
            page.setSite(site);
            page.setPath(path);
            page.setCode(statusCode);
            page.setContent(content);
            page.setContentType(contentType);

            pageRepository.save(page);
            System.out.println("Сохранено содержимое: " + url);
        } catch (Exception e) {
            System.err.println("Ошибка сохранения содержимого: " + url + " - " + e.getMessage());
        }
    }


    private boolean isSupportedContentType(String url, String contentType) {
        if (contentType == null) return false;

        // Поддерживаем текстовые и HTML страницы, изображения
        if (contentType.startsWith("text/") ||
                contentType.startsWith("application/xml") ||
                contentType.startsWith("image/")) {
            return true;
        }

        return false;
    }

    // Метод для создания ошибки
    public ResponseEntity<Map<String, Object>> createErrorResponse(
            Map<String, Object> response,
            String errorMessage,
            HttpStatus status) {

        response.put("result", false);
        response.put("error", errorMessage);
        return new ResponseEntity<>(response, status);
    }

    // Метод для извлечения пути из URL
    private String getPathFromUrl(String url) {
        try {
            return new java.net.URL(url).getPath();
        } catch (Exception e) {
            return "/";
        }
    }

    // Класс для хранения URL и глубины
    private static class UrlDepthPair {
        private final String url;
        private final int depth;

        public UrlDepthPair(String url, int depth) {
            this.url = url;
            this.depth = depth;
        }

        public String getUrl() {
            return url;
        }

        public int getDepth() {
            return depth;
        }
    }

    @Transactional
    private void deleteSiteData(Site site) {
        if (site != null) {
            boolean isActive = TransactionSynchronizationManager.isActualTransactionActive();
            System.out.println("Транзакция активна: " + isActive);

            // Подсчёт страниц перед удалением
            long pagesCount = pageRepository.countBySite(site);
            System.out.println("Удаляется " + pagesCount + " страниц с сайта: " + site.getUrl());

            // Удаляем страницы с использованием каскадного удаления, если это настроено
            pageRepository.deleteAllBySite(site);

            // Логируем информацию
            System.out.println("Удалены страницы для сайта: " + site.getUrl());

            // Удаляем сайт
            siteRepository.delete(site);
            entityManager.flush(); // Является обязательным для очистки всех каскадных операций

            // Detach the site entity explicitly
            entityManager.detach(site);

            // Логирование
            System.out.println("Удалён сайт с URL: " + site.getUrl());
        }
    }

}
