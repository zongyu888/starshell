package com.aifinalshell.provider.anthropic;

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
 * Anthropic Claude provider with streaming SSE support.
 */
public class AnthropicProvider implements AiProvider {
    private static final Logger logger = LoggerFactory.getLogger(AnthropicProvider.class);
    private final HttpClient httpClient;
    private final HttpClient streamHttpClient;
    private final ObjectMapper objectMapper;

    public AnthropicProvider() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.streamHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getName() {
        return "anthropic";
    }

    @Override
    public List<ModelInfo> listModels(String apiKey, String baseUrl) {
        List<ModelInfo> models = new ArrayList<>();
        models.add(new ModelInfo("claude-sonnet-4-20250514", "Claude Sonnet 4", "anthropic", false));
        models.add(new ModelInfo("claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet", "anthropic", false));
        models.add(new ModelInfo("claude-3-haiku-20240307", "Claude 3 Haiku", "anthropic", false));
        models.add(new ModelInfo("claude-3-opus-20240229", "Claude 3 Opus", "anthropic", false));
        return models;
    }

    @Override
    public String chat(String model, List<ChatMessage> messages, String apiKey, String baseUrl,
                       double temperature, int maxTokens) {
        String url = resolveBaseUrl(baseUrl);

        try {
            String requestBody = buildRequestBody(model, messages, temperature, maxTokens, false);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/v1/messages"))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode content = root.get("content");
                if (content != null && content.isArray() && content.size() > 0) {
                    return content.get(0).get("text").asText();
                }
            } else {
                logger.error("Anthropic API error {}: {}", response.statusCode(), response.body());
                return "API Error " + response.statusCode() + ": " + response.body();
            }
        } catch (Exception e) {
            logger.error("Anthropic chat failed", e);
            return "Error: " + e.getMessage();
        }

        return "No response from API";
    }

    @Override
    public void chatStream(String model, List<ChatMessage> messages, String apiKey,
                           String baseUrl, double temperature, int maxTokens,
                           Consumer<String> onToken, Runnable onComplete,
                           Consumer<Exception> onError) {
        String url = resolveBaseUrl(baseUrl);

        try {
            String requestBody = buildRequestBody(model, messages, temperature, maxTokens, true);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/v1/messages"))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            streamHttpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                    .thenAccept(response -> {
                        if (response.statusCode() == 200) {
                            SseStreamParser.parseAnthropic(response.body(), onToken, onComplete, onError);
                        } else {
                            onError.accept(new RuntimeException(
                                    "Anthropic API Error " + response.statusCode()));
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
        return apiKey != null && !apiKey.isEmpty();
    }

    private String resolveBaseUrl(String baseUrl) {
        if (baseUrl != null && !baseUrl.isEmpty()) return baseUrl;
        return "https://api.anthropic.com";
    }

    private String buildRequestBody(String model, List<ChatMessage> messages,
                                     double temperature, int maxTokens, boolean stream) throws Exception {
        String systemPrompt = "";
        List<Map<String, String>> msgList = new ArrayList<>();

        for (ChatMessage msg : messages) {
            if ("system".equals(msg.getRole())) {
                systemPrompt = msg.getContent();
            } else {
                Map<String, String> m = new HashMap<>();
                m.put("role", msg.getRole());
                m.put("content", msg.getContent());
                msgList.add(m);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        if (!systemPrompt.isEmpty()) {
            body.put("system", systemPrompt);
        }
        body.put("messages", msgList);
        if (stream) body.put("stream", true);

        return objectMapper.writeValueAsString(body);
    }
}
