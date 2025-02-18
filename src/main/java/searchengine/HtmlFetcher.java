package searchengine;

import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.apache.lucene.morphology.english.EnglishLuceneMorphology;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import searchengine.config.SitesList;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.IndexingStatus;
import searchengine.model.Lemma;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.repository.LemmaRepository;
import org.jsoup.Jsoup;  // Добавь в импорты, если используешь Jsoup
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@SpringBootApplication
public class HtmlFetcher implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(HtmlFetcher.class);

    private final SitesList sitesList;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;

    private LuceneMorphology russianMorphology;
    private LuceneMorphology englishMorphology;

    @Autowired
    public HtmlFetcher(SitesList sitesList, SiteRepository siteRepository, PageRepository pageRepository, LemmaRepository lemmaRepository) throws IOException {
        this.sitesList = sitesList;
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.lemmaRepository = lemmaRepository;

        // Инициализация лемматизаторов для русского и английского языков
        russianMorphology = new RussianLuceneMorphology();
        englishMorphology = new EnglishLuceneMorphology();
    }

    public void fetchAll() {
        if (sitesList.getSites() == null || sitesList.getSites().isEmpty()) {
            logger.error("❌ Список сайтов пуст. Проверь конфигурацию!");
            return;
        }

        logger.info("🔍 Начинаем загрузку сайтов...");

        // Параллельная обработка сайтов
        List<CompletableFuture<Void>> futures = sitesList.getSites().stream()
                .map(site -> CompletableFuture.runAsync(() -> processSite(site)))
                .collect(Collectors.toList());

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        } catch (InterruptedException | ExecutionException e) {
            logger.error("❌ Ошибка при загрузке сайтов: ", e);
        }

        logger.info("🎉 Все сайты обработаны!");
    }

    private void processSite(searchengine.config.ConfigSite siteConfig) {
        logger.info("🔄 Начинаю обработку сайта: {}", siteConfig.getUrl());

        String html = fetchHtml(siteConfig.getUrl());

        if (html.startsWith("Ошибка загрузки")) {
            logger.error("❌ Ошибка загрузки сайта {}: {}", siteConfig.getUrl(), html);
            return;
        }

        // Логируем HTML (первые 1000 символов)
        logger.info("📄 HTML загружен (фрагмент): \n{}", truncateHtml(html, 1000));

        Site savedSite = saveSiteIfNeeded(siteConfig);

        if (savedSite != null) {
            savePage(savedSite, html);
            logger.info("✅ Сайт успешно обработан: {}", siteConfig.getUrl());
        } else {
            logger.warn("⚠️ Не удалось сохранить сайт: {}", siteConfig.getUrl());
        }
    }


    private String fetchHtml(String siteUrl) {
        logger.debug("🕵️‍♂️ Загружаю HTML для сайта: {}", siteUrl);
        try {
            URL url = new URL(siteUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            try (Scanner scanner = new Scanner(connection.getInputStream())) {
                StringBuilder html = new StringBuilder();
                while (scanner.hasNext()) {
                    html.append(scanner.nextLine()).append("\n");
                }

                // Логируем полный HTML-контент (или его часть для более удобного вывода)
                String htmlSnippet = truncateHtml(html.toString(), 500); // Ограничиваем вывод 500 символами для удобства
                logger.debug("✅ HTML загружен для сайта: {}\n{}", siteUrl, htmlSnippet);

                return html.toString();
            }
        } catch (IOException e) {
            logger.error("❌ Ошибка загрузки сайта {}: {}", siteUrl, e.getMessage());
            return "Ошибка загрузки: " + e.getMessage();
        }
    }

    private String truncateHtml(String html, int maxLength) {
        if (html.length() > maxLength) {
            return html.substring(0, maxLength) + "\n...\n(HTML обрезан)";
        }
        return html;
    }

    private Site saveSiteIfNeeded(searchengine.config.ConfigSite siteConfig) {
        // Ищем сайт по URL
        Site existingSite = siteRepository.findByUrl(siteConfig.getUrl());

        if (existingSite != null) {
            logger.debug("🔄 Сайт уже существует в базе данных: {}", siteConfig.getUrl());
            return existingSite;
        } else {
            logger.info("🚀 Создаю новый сайт для URL: {}", siteConfig.getUrl());
            Site newSite = new Site();
            newSite.setUrl(siteConfig.getUrl());
            newSite.setName(siteConfig.getName());
            newSite.setStatus(IndexingStatus.INDEXING); // Устанавливаем статус INDEXING по умолчанию
            newSite.setStatusTime(LocalDateTime.now()); // Устанавливаем текущее время
            return siteRepository.save(newSite); // Сохраняем сайт и возвращаем его
        }
    }

    private void savePage(Site site, String html) {
        logger.debug("💾 Сохраняю страницу для сайта: {}", site.getUrl());

        Page page = new Page();
        page.setSite(site);
        page.setPath("");
        page.setContent(html);
        page.setCode(200);
        pageRepository.save(page);

        // Лемматизация
        Map<String, Integer> lemmaCounts = getLemmas(html);

        // Логируем леммы перед сохранением
        logger.info("📌 Леммы для сайта {}: {}", site.getUrl(), lemmaCounts);

        // Сохраняем леммы в БД
        for (Map.Entry<String, Integer> entry : lemmaCounts.entrySet()) {
            Lemma lemma = new Lemma();
            lemma.setSite(site);
            lemma.setLemma(entry.getKey());
            lemma.setFrequency(entry.getValue());
            lemmaRepository.save(lemma);
        }

        logger.debug("✅ Страница и леммы сохранены для сайта: {}", site.getUrl());
    }

    private Map<String, Integer> getLemmas(String html) {
        Map<String, Integer> lemmaCounts = new HashMap<>();

        // Удаляем HTML-теги перед анализом
        String text = Jsoup.parse(html).text();

        // Регулярное выражение для поиска слов
        Pattern wordPattern = Pattern.compile("\\p{L}+");
        Matcher matcher = wordPattern.matcher(text);

        while (matcher.find()) {
            String word = matcher.group().toLowerCase(); // Приводим к нижнему регистру

            if (word.matches(".*[а-яА-ЯёЁ].*")) {
                // Если слово содержит русские буквы, используем русский морфологический анализатор
                try {
                    List<String> normalForms = russianMorphology.getNormalForms(word);
                    if (!normalForms.isEmpty()) {
                        String lemma = normalForms.get(0);
                        lemmaCounts.put(lemma, lemmaCounts.getOrDefault(lemma, 0) + 1);
                    }
                } catch (Exception ignored) {}
            } else if (word.matches(".*[a-zA-Z].*")) {
                // Если слово содержит латинские буквы, используем английский морфологический анализатор
                try {
                    List<String> normalForms = englishMorphology.getNormalForms(word);
                    if (!normalForms.isEmpty()) {
                        String lemma = normalForms.get(0);
                        lemmaCounts.put(lemma, lemmaCounts.getOrDefault(lemma, 0) + 1);
                    }
                } catch (Exception ignored) {}
            }
        }

        return lemmaCounts;
    }

    @Override
    public void run(String... args) {
        logger.info("🚀 Приложение запущено!");
        fetchAll();
    }

    public static void main(String[] args) {
        SpringApplication.run(HtmlFetcher.class, args);
    }
}
