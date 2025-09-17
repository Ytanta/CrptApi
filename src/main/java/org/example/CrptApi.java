package org.example;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

public class CrptApi {

    private final String baseApiUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final TokenBucketRateLimiter rateLimiter;

    public CrptApi(String baseApiUrl, TimeUnit timeUnit, int requestLimit) {
        this.baseApiUrl = baseApiUrl;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        this.rateLimiter = new TokenBucketRateLimiter(requestLimit, timeUnit.toMillis());
    }


    public AuthKeyResponse getAuthKey() {
        rateLimiter.acquire();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseApiUrl + "/auth/cert/key"))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkResponse(response);
            return objectMapper.readValue(response.body(), AuthKeyResponse.class);
        } catch (IOException | InterruptedException e) {
            throw new ApiException("Ошибка при получении auth-key", e);
        }
    }

    public AuthTokenResponse getAuthToken(String uuid, String signedData) {
        rateLimiter.acquire();
        AuthTokenRequest req = new AuthTokenRequest(uuid, signedData);
        try {
            String body = objectMapper.writeValueAsString(req);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseApiUrl + "/auth/cert/" + uuid))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkResponse(response);
            return objectMapper.readValue(response.body(), AuthTokenResponse.class);
        } catch (IOException | InterruptedException e) {
            throw new ApiException("Ошибка при получении auth-token", e);
        }
    }

    public String createIntroduceGoodsDocument(IntroduceGoodsDocument doc, String signature, String authToken) {
        rateLimiter.acquire();
        try {
            String jsonDoc = objectMapper.writeValueAsString(doc);
            String base64Doc = Base64.getEncoder().encodeToString(jsonDoc.getBytes(StandardCharsets.UTF_8));

            DocumentRequest requestDto = new DocumentRequest("LP_INTRODUCE_GOODS", base64Doc, signature);
            String body = objectMapper.writeValueAsString(requestDto);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseApiUrl + "/lk/documents/create"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + authToken)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkResponse(response);
            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new ApiException("Ошибка при создании документа LP_INTRODUCE_GOODS", e);
        }
    }

    private void checkResponse(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ApiException("Ошибка API: HTTP " + response.statusCode() + " → " + response.body());
        }
    }

    public static class AuthKeyResponse {
        public String uuid;
        public String data;
    }

    public static class AuthTokenRequest {
        public final String uuid;
        @JsonProperty("signed_data")
        public final String signedData;

        public AuthTokenRequest(String uuid, String signedData) {
            this.uuid = uuid;
            this.signedData = signedData;
        }
    }

    public static class AuthTokenResponse {
        @JsonProperty("auth_token")
        public String authToken;
    }

    public static class DocumentRequest {
        public final String document_format;
        public final String product_document;
        public final String signature;

        public DocumentRequest(String documentFormat, String productDocument, String signature) {
            this.document_format = documentFormat;
            this.product_document = productDocument;
            this.signature = signature;
        }
    }


    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class IntroduceGoodsDocument {
        public String participantInn;
        public String ownerInn;
        public String producerInn;
        public String productionDate;
        public String certificateDocument;
        public String certificateDocumentDate;
        public String certificateDocumentNumber;
        // можно добавить вложенные поля под товары
    }

    private static class TokenBucketRateLimiter {
        private final long refillIntervalMillis;
        private final int capacity;
        private int tokens;
        private long lastRefillTimestamp;
        private final ReentrantLock lock = new ReentrantLock();

        public TokenBucketRateLimiter(int capacity, long refillIntervalMillis) {
            this.capacity = capacity;
            this.refillIntervalMillis = refillIntervalMillis;
            this.tokens = capacity;
            this.lastRefillTimestamp = System.currentTimeMillis();
        }

        public void acquire() {
            lock.lock();
            try {
                refill();
                while (tokens == 0) {
                    long sleepTime = lastRefillTimestamp + refillIntervalMillis - System.currentTimeMillis();
                    if (sleepTime > 0) {
                        try {
                            Thread.sleep(sleepTime);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    refill();
                }
                tokens--;
            } finally {
                lock.unlock();
            }
        }

        private void refill() {
            long now = System.currentTimeMillis();
            if (now - lastRefillTimestamp >= refillIntervalMillis) {
                tokens = capacity;
                lastRefillTimestamp = now;
            }
        }
    }

    public enum TimeUnit {
        SECONDS(1000), MINUTES(60_000);

        private final long millis;

        TimeUnit(long millis) {
            this.millis = millis;
        }

        public long toMillis() {
            return millis;
        }
    }

    public static class ApiException extends RuntimeException {
        public ApiException(String message) {
            super(message);
        }

        public ApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
