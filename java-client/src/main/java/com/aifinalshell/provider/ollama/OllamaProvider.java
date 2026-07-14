package com.aifinalshell.provider.ollama;

import com.aifinalshell.ai.SseStreamParser;
import com.aifinalshell.provider.AiProvider;
import com.aifinalshell.provider.ChatMessage;
import com.aifinalshell.provider.ModelInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/**
 * Ollama local model provider with streaming support.
 * Detects local Ollama service, supports /api/chat with NDJSON streaming.
 */
public class OllamaProvider implements AiProvider {
    private static final Logger logger = LoggerFactory.getLogger(OllamaProvider.class);
    private final HttpClient httpClient;
    private final HttpClient streamHttpClient;
    private final ObjectMapper objectMapper;
    private static final String DEFAULT_BASE_URL = "http://localhost:11434";

    public OllamaProvider() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.streamHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getName() {
        return "ollama";
    }

    @Override
    public List<ModelInfo> listModels(String apiKey, String baseUrl) {
        List<ModelInfo> models = new ArrayList<>();
        String url = resolveBaseUrl(baseUrl);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/api/tags"))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode modelsNode = root.get("models");
                if (modelsNode != null && modelsNode.isArray()) {
                    for (JsonNode model : modelsNode) {
                        String name = model.get("name").asText();
                        long size = model.has("size") ? model.get("size").asLong() : 0;
                        String sizeStr = formatSize(size);
                        models.add(new ModelInfo(name, name + " (" + sizeStr + ")", "ollama", true));
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to list Ollama models (is Ollama running?): {}", e.getMessage());
        }

        // B6: 空列表直接返回（Ollama 未安装模型时不注入假数据），由 UI 提示用户拉取模型
        return models;
    }

    @Override
    public String chat(String model, List<ChatMessage> messages, String apiKey, String baseUrl,
                       double temperature, int maxTokens) {
        String url = resolveBaseUrl(baseUrl);

        try {
            String requestBody = buildRequestBody(model, messages, temperature, false);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(300)) // longer timeout for local models
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode message = root.get("message");
                if (message != null) {
                    return message.get("content").asText();
                }
            } else {
                logger.error("Ollama API error {}: {}", response.statusCode(), response.body());
                return "API Error " + response.statusCode() + ": " + response.body();
            }
        } catch (java.net.ConnectException e) {
            return "Error: Cannot connect to Ollama at " + url + ". Is Ollama running? Start with: ollama serve";
        } catch (Exception e) {
            logger.error("Ollama chat failed", e);
            return "Error: " + e.getMessage();
        }

        return "No response from Ollama";
    }

    @Override
    public void chatStream(String model, List<ChatMessage> messages, String apiKey,
                           String baseUrl, double temperature, int maxTokens,
                           Consumer<String> onToken, Runnable onComplete,
                           Consumer<Exception> onError) {
        String url = resolveBaseUrl(baseUrl);

        try {
            String requestBody = buildRequestBody(model, messages, temperature, true);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(300))
                    .build();

            streamHttpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                    .thenAccept(response -> {
                        if (response.statusCode() == 200) {
                            SseStreamParser.parseOllama(response.body(), onToken, onComplete, onError);
                        } else {
                            onError.accept(new RuntimeException(
                                    "Ollama API Error " + response.statusCode()));
                        }
                    })
                    .exceptionally(ex -> {
                        onError.accept(ex instanceof Exception e ? e :
                                new RuntimeException(ex));
                        return null;
                    });
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    @Override
    public boolean isAvailable(String apiKey) {
        // Ollama is available if the local service is running
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(DEFAULT_BASE_URL + "/api/tags"))
                    .GET()
                    .timeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Pull a model from Ollama registry.
     */
    public boolean pullModel(String model, String baseUrl, Consumer<String> onProgress) {
        String url = resolveBaseUrl(baseUrl);

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("name", model);
            body.put("stream", true);

            String requestBody = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/api/pull"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(600)) // 10 min for large models
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                // Parse NDJSON progress
                String[] lines = response.body().split("\n");
                for (String line : lines) {
                    if (line.isEmpty()) continue;
                    JsonNode root = objectMapper.readTree(line);
                    if (root.has("status")) {
                        onProgress.accept(root.get("status").asText());
                    }
                }
                return true;
            }
        } catch (Exception e) {
            logger.error("Failed to pull Ollama model: {}", e.getMessage());
        }
        return false;
    }

    private String resolveBaseUrl(String baseUrl) {
        if (baseUrl != null && !baseUrl.isEmpty()) return baseUrl;
        return DEFAULT_BASE_URL;
    }

    private String buildRequestBody(String model, List<ChatMessage> messages,
                                     double temperature, boolean stream) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", stream);

        List<Map<String, String>> msgList = new ArrayList<>();
        for (ChatMessage msg : messages) {
            Map<String, String> m = new HashMap<>();
            m.put("role", msg.getRole());
            m.put("content", msg.getContent());
            msgList.add(m);
        }
        body.put("messages", msgList);

        Map<String, Object> options = new HashMap<>();
        options.put("temperature", temperature);
        body.put("options", options);

        return objectMapper.writeValueAsString(body);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
