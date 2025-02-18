package searchengine.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.services.IndexingService;
import searchengine.services.StatisticsService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam ;
import searchengine.services.PageIndexingService;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api")
public class ApiController {
    private static final Logger logger = LoggerFactory.getLogger(ApiController.class);
    private final StatisticsService statisticsService;
    private final IndexingService indexingService;
    private final ExecutorService executorService;
    private final PageIndexingService pageIndexingService;  // Исправленное имя переменной

    public ApiController(StatisticsService statisticsService, PageIndexingService pageIndexingService, IndexingService indexingService, ExecutorService executorService) {
        this.statisticsService = statisticsService;
        this.indexingService = indexingService;
        this.executorService = executorService;
        this.pageIndexingService = pageIndexingService;  // Конструктор правильно инициализирует переменную
    }



    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics() {
        return ResponseEntity.ok(statisticsService.getStatistics());
    }

    @GetMapping("/startIndexing")
    public ResponseEntity<Map<String, Object>> startIndexing() {
        if (indexingService.isIndexingInProgress()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("result", false);
            errorResponse.put("error", "Индексация уже запущена");
            return ResponseEntity.badRequest().body(errorResponse);
        }

        // Запуск асинхронной индексации
        executorService.submit(indexingService::startFullIndexing);

        Map<String, Object> successResponse = new HashMap<>();
        successResponse.put("result", true);
        return ResponseEntity.ok(successResponse);
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<Map<String, Object>> stopIndexing() {
        if (!indexingService.isIndexingInProgress()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("result", false);
            errorResponse.put("error", "Индексация не запущена");
            return ResponseEntity.badRequest().body(errorResponse);
        }

        indexingService.stopIndexing();

        Map<String, Object> successResponse = new HashMap<>();
        successResponse.put("result", true);
        return ResponseEntity.ok(successResponse);
    }

    @PostMapping(value = "/indexPage", consumes = "application/x-www-form-urlencoded")
    public ResponseEntity<Map<String, Object>> indexPage(@RequestParam String url,
                                                         @RequestParam(required = false, defaultValue = "3") int depth) { // depth с дефолтным значением 3
        Map<String, Object> response = new HashMap<>();

        // Максимальная допустимая глубина
        int maxDepth = 3;

        // Проверяем, что глубина не превышает максимальную
        if (depth > maxDepth) {
            return pageIndexingService.createErrorResponse(
                    response,
                    "Глубина индексации не может превышать " + maxDepth,
                    HttpStatus.BAD_REQUEST
            );
        }

        if (url == null || url.isEmpty()) {
            return pageIndexingService.createErrorResponse(response, "URL страницы не указан", HttpStatus.BAD_REQUEST);
        }

        if (!pageIndexingService.isUrlWithinConfiguredSites(url)) {
            return pageIndexingService.createErrorResponse(
                    response,
                    "Данная страница находится за пределами сайтов, указанных в конфигурационном файле",
                    HttpStatus.BAD_REQUEST
            );
        }

        try {
            pageIndexingService.indexSite(url, depth); // передаем глубину в метод
            response.put("result", true);
            logger.info("Страница успешно проиндексирована: {}", url);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Ошибка индексации страницы: {}", url, e);
            return pageIndexingService.createErrorResponse(
                    response,
                    "Ошибка при индексации страницы: " + e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }


}
