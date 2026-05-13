package com.lark.imcollab.harness.document.iteration.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class PexelsDocumentImageSearchService implements DocumentImageSearchService {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public PexelsDocumentImageSearchService(
            ObjectMapper objectMapper,
            @Value("${pexels.api-key:}") String apiKey
    ) {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(6))
                .build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
    }

    @Override
    public String searchFirstImageUrl(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalStateException("Pexels query is blank");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("pexels.api-key is missing");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.pexels.com/v1/search?per_page=1&orientation=landscape&query=" + encode(query.trim())))
                    .timeout(Duration.ofSeconds(6))
                    .header("Authorization", apiKey)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Pexels search failed with status " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode photos = root.path("photos");
            if (!photos.isArray() || photos.isEmpty()) {
                throw new IllegalStateException("Pexels search returned no photos");
            }
            JsonNode first = photos.get(0);
            String url = first.path("src").path("large2x").asText("");
            if (url.isBlank()) {
                url = first.path("src").path("large").asText("");
            }
            if (url.isBlank() || !url.startsWith("https://")) {
                throw new IllegalStateException("Pexels search returned invalid image url");
            }
            if (!(url.contains("pexels.com") || url.contains("images.pexels.com"))) {
                throw new IllegalStateException("Pexels search returned unsupported domain");
            }
            return url;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Pexels search interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Pexels search failed", e);
        }
    }

    private String encode(String query) {
        return query.replace(" ", "%20");
    }
}
