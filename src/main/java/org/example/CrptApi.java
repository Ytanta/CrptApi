package org.example;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe CrptApi client with rate limiting for "Честный Знак".
 *
 * Supports:
 * - Получение аутентификационного токена (по схеме с УКЭП подписью)
 * - Создание документа ввода в оборот товара, произведенного в РФ
 *
 * Конструктор принимает ограничения на количество запросов в указанный интервал.
 *
 * Вся реализация в одном файле, внутренние классы.
 */
public class CrptApi {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RateLimiter rateLimiter;
    private final String baseApiUrl;

    /**
     * Создает CrptApi клиент с ограничением количества запросов.
     * @param timeUnit период времени (секунды, минуты и т.п.)
     * @param requestLimit максимально допустимое количество запросов в период
     */
    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this(timeUnit, requestLimit, "https://ismp.crpt.ru/api/v3");
    }

    /**
     * Конструктор с указанием базового URL API.
     */
    public CrptApi(TimeUnit timeUnit, int requestLimit, String baseApiUrl) {
        if (requestLimit <= 0) throw new IllegalArgumentException("Request limit must be positive");
        this.baseApiUrl = Objects.requireNonNull(baseApiUrl, "baseApiUrl cannot be null");
        this.rateLimiter = new TokenBucketRateLimiter(requestLimit, timeUnit);
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    // -----------------------------------
    // --- Аутентификация по УКЭП ----------
    // -----------------------------------

    /**
     * Запрашивает случайную строку для подписи (uuid и data).
     */
    public AuthKeyResponse getAuthKey() throws IOException, InterruptedException {
        rateLimiter.acquire();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseApiUrl + "/auth/cert/key"))
                .GET()
                .header("Accept", "application/json")
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        checkSuccess(resp);
        return objectMapper.readValue(resp.body(), AuthKeyResponse.class);
    }

    /**
     * Отправляет подписанные base64 данные, получает токен аутентификации.
     *
     * @param uuid полученный на шаге getAuthKey
     * @param signedDataBase64 открепленная подпись base64
     * @return токен для последующих запросов
     */
    public String getAuthToken(String uuid, String signedDataBase64) throws IOException, InterruptedException {
        rateLimiter.acquire();
        Map<String, String> body = Map.of("uuid", uuid, "data", signedDataBase64);
        String jsonBody = toJson(body);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseApiUrl + "/auth/cert/"))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Accept", "application/json")
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        checkSuccess(resp);
        Map<String, String> map = objectMapper.readValue(resp.body(), Map.class);
        if (!map.containsKey("token")) throw new RuntimeException("Token missing in auth response");
        return map.get("token");
    }

    // -----------------------------------
    // --- Метод создания документа ввода в оборот ---
    // -----------------------------------

    /**
     * Создает документ для ввода в оборот товара, произведенного в РФ.
     *
     * @param productGroup Код товарной группы (например "clothes", "milk" и т.п.)
     * @param documentDto Java объект документа (будет сериализован в JSON и закодирован base64)
     * @param documentSignatureBase64 Открепленная подпись документа в base64
     * @param authToken Токен аутентификации из getAuthToken
     * @return ApiResponse с HTTP статусом и телом ответа
     * @throws IOException, InterruptedException
     */
    public ApiResponse createIntroduceGoodsDocument(
            String productGroup,
            Object documentDto,
            String documentSignatureBase64,
            String authToken
    ) throws IOException, InterruptedException {
        rateLimiter.acquire();

        // Сериализация JSON и кодирование в base64
        String jsonDocument = toJson(documentDto);
        String productDocumentBase64 = Base64.getEncoder().encodeToString(jsonDocument.getBytes(StandardCharsets.UTF_8));

        Map<String, String> body = Map.of(
                "document_format", "MANUAL",
                "product_document", productDocumentBase64,
                "product_group", productGroup,
                "signature", documentSignatureBase64,
                "type", "LP_INTRODUCE_GOODS"
        );

        String requestBody = toJson(body);

        String url = baseApiUrl + "/lk/documents/create?pg=" + URLEncoder.encode(productGroup, StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + authToken)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

        return new ApiResponse(resp.statusCode(), resp.body());
    }

    // -----------------------------------
    // --- Вспомогательные методы и классы ---
    // -----------------------------------

    private void checkSuccess(HttpResponse<?> response) {
        int code = response.statusCode();
        if (code < 200 || code >= 300) {
            throw new RuntimeException("HTTP error " + code + ": " + response.body());
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON serialization error", e);
        }
    }

    // Ответ с ключом для подписи
    public static class AuthKeyResponse {
        public String uuid;
        public String data;

        public String getUuid() { return uuid; }
        public String getData() { return data; }
    }

    // Ответ API с кодом и телом
    public static class ApiResponse {
        private final int statusCode;
        private final String body;

        public ApiResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
        public int getStatusCode() { return statusCode; }
        public String getBody() { return body; }
    }

    // Интерфейс для rate limiter
    private interface RateLimiter {
        void acquire() throws InterruptedException;
    }

    /**
     * Token Bucket rate limiter.
     * Позволяет не более requestLimit запросов за 1 unit времени.
     * Блокирует поток при превышении лимита.
     */
    private static class TokenBucketRateLimiter implements RateLimiter {
        private final int capacity;
        private final long refillPeriodNanos;
        private double tokens;
        private long lastRefillTime;
        private final ReentrantLock lock = new ReentrantLock();

        public TokenBucketRateLimiter(int capacity, TimeUnit timeUnit) {
            this.capacity = capacity;
            this.tokens = capacity;
            this.refillPeriodNanos = timeUnit.toNanos(1);
            this.lastRefillTime = System.nanoTime();
        }

        @Override
        public void acquire() throws InterruptedException {
            lock.lock();
            try {
                refill();
                while (tokens < 1) {
                    long waitTime = (long) ((1 - tokens) * refillPeriodNanos / capacity);
                    if (waitTime > 0) {
                        TimeUnit.NANOSECONDS.sleep(waitTime);
                        refill();
                    }
                }
                tokens -= 1;
            } finally {
                lock.unlock();
            }
        }

        private void refill() {
            long now = System.nanoTime();
            double tokensToAdd = (now - lastRefillTime) * capacity / (double) refillPeriodNanos;
            if (tokensToAdd > 0) {
                tokens = Math.min(capacity, tokens + tokensToAdd);
                lastRefillTime = now;
            }
        }
    }
}
