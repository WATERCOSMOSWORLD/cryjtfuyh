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
import searchengine.model.Lemma;
import searchengine.model.Index;
import searchengine.model.IndexingStatus;
import searchengine.repository.PageRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.SiteRepository;
import searchengine.repository.IndexRepository;
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
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.apache.lucene.morphology.english.EnglishLuceneMorphology;

@Service
public class PageIndexingService {

    @PersistenceContext
    private EntityManager entityManager;

    private final SitesList sitesList;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;

    @Autowired
    public PageIndexingService(SitesList sitesList,IndexRepository indexRepository,LemmaRepository lemmaRepository, SiteRepository siteRepository, PageRepository pageRepository) {
        this.sitesList = sitesList;
        this.siteRepository = siteRepository;
        this.lemmaRepository = lemmaRepository;
        this.pageRepository = pageRepository;
        this.indexRepository = indexRepository;
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

            Optional<Page> existingPage = pageRepository.findBySiteAndPath(site, path);
            if (existingPage.isPresent()) {
                Page pageToDelete = existingPage.get();

                // Удаляем индексы
                int deletedIndexes = indexRepository.deleteByPageId(pageToDelete.getId());
                System.out.println("Удалено записей из index: " + deletedIndexes);

                // Удаляем страницу
                pageRepository.delete(pageToDelete);
                System.out.println("Удалена старая страница: " + url);

                // Очищаем "осиротевшие" леммы
                int deletedLemmas = lemmaRepository.deleteOrphanLemmas();
                System.out.println("Удалено неиспользуемых лемм: " + deletedLemmas);
            } else {
                System.out.println("Страница не найдена в базе, создаем новую: " + url);
            }

            // Создаём новую страницу
            Page page = new Page();
            page.setSite(site);
            page.setPath(path);
            page.setCode(statusCode);
            page.setContent(content);
            page.setContentType(contentType);
            pageRepository.save(page);

            System.out.println("Сохранено содержимое: " + url);

            // Обрабатываем текст страницы
            processPageContent(page);

        } catch (Exception e) {
            System.err.println("Ошибка сохранения содержимого: " + url + " - " + e.getMessage());
        }
    }



    private void processPageContent(Page page) {
        try {
            // Парсим HTML с помощью Jsoup
            Document document = Jsoup.parse(page.getContent());
            String text = document.body().text(); // Извлекаем текст страницы

            // **Лемматизация текста**
            Map<String, Integer> lemmaCounts = lemmatizeText(text);

            for (Map.Entry<String, Integer> entry : lemmaCounts.entrySet()) {
                String lemmaText = entry.getKey();
                int count = entry.getValue();

                // **Обновление таблицы lemma**
                Lemma lemma = lemmaRepository.findByLemma(lemmaText)
                        .orElseGet(() -> {
                            Lemma newLemma = new Lemma();
                            newLemma.setLemma(lemmaText);
                            newLemma.setFrequency(0);
                            newLemma.setSite(page.getSite()); // Добавляем сайт
                            return newLemma;
                        });

                lemma.setFrequency(lemma.getFrequency() + 1);
                lemmaRepository.save(lemma);

                // **Связываем с index**
                Index index = new Index();
                index.setPage(page);
                index.setLemma(lemma);
                index.setRank((float) count);
                indexRepository.save(index);

                // Логирование сохраненных лемм
                System.out.println("Сохранена лемма: " + lemmaText + " (Частота: " + lemma.getFrequency() + ")");
            }

            System.out.println("Обработана страница: " + page.getPath());

        } catch (Exception e) {
            System.err.println("Ошибка обработки страницы: " + page.getPath() + " - " + e.getMessage());
        }
    }


    private Map<String, Integer> lemmatizeText(String text) {
        Map<String, Integer> lemmaCounts = new HashMap<>();

        try {
            LuceneMorphology russianMorphology = new RussianLuceneMorphology();
            LuceneMorphology englishMorphology = new EnglishLuceneMorphology();

            // Разделяем текст на слова (оставляем только буквы)
            String[] words = text.toLowerCase().split("\\P{L}+");

            for (String word : words) {
                if (word.isBlank()) continue;

                List<String> normalForms = new ArrayList<>();

                if (word.matches("^[а-яё]+$")) { // Только русские буквы
                    normalForms = russianMorphology.getNormalForms(word);
                } else if (word.matches("^[a-z]+$")) { // Только английские буквы
                    normalForms = englishMorphology.getNormalForms(word);
                } else {
                    // Игнорируем символы или смешанные слова
                    continue;
                }

                // Добавляем леммы в счетчик
                for (String lemma : normalForms) {
                    lemmaCounts.put(lemma, lemmaCounts.getOrDefault(lemma, 0) + 1);
                }

                // Логирование результатов
                System.out.println("Слово: " + word + " -> Леммы: " + normalForms);
            }
        } catch (Exception e) {
            System.err.println("Ошибка лемматизации: " + e.getMessage());
        }

        return lemmaCounts;
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

            long pagesCount = pageRepository.countBySite(site);
            System.out.println("Удаляется " + pagesCount + " страниц с сайта: " + site.getUrl());

            int deletedIndexes = indexRepository.deleteBySiteId(site.getId());
            System.out.println("Удалено записей из index: " + deletedIndexes);

            int deletedLemmas = lemmaRepository.deleteBySiteId((long) site.getId());

            System.out.println("Удалено записей из lemma: " + deletedLemmas);

            pageRepository.deleteAllBySite(site);
            System.out.println("Удалены страницы для сайта: " + site.getUrl());

            siteRepository.delete(site);
            entityManager.flush();
            entityManager.detach(site);

            System.out.println("Удалён сайт с URL: " + site.getUrl());
        }
    }
}
