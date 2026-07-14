package com.aifinalshell.provider.openai;

import com.aifinalshell.ai.SseStreamParser;
import com.aifinalshell.provider.AiProvider;
import com.aifinalshell.provider.ChatMessage;
import com.aifinalshell.provider.ChatResult;
import com.aifinalshell.provider.ModelInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * OpenAI-compatible provider with streaming SSE and native function calling support.
 * Works with OpenAI, Zhipu/GLM, SiliconFlow, Doubao, and any OpenAI-compatible API.
 *
 * Architecture inspired by opencode-dev's Protocol/Route design:
 * - Protocol: OpenAI Chat Completions API
 * - Auth: Bearer token
 * - Framing: SSE event-stream parsing
 */
public class OpenAiProvider implements AiProvider {
    private static final Logger logger = LoggerFactory.getLogger(OpenAiProvider.class);
    private final HttpClient httpClient;
    private final HttpClient streamHttpClient;
    private final ObjectMapper objectMapper;

    public OpenAiProvider() {
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
        return "openai";
    }

    @Override
    public boolean supportsFunctionCalling() {
        return true;
    }

    @Override
    public List<ModelInfo> listModels(String apiKey, String baseUrl) {
        List<ModelInfo> models = new ArrayList<>();
        String url = (baseUrl != null && !baseUrl.isEmpty()) ? baseUrl : "https://api.openai.com/v1";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/models"))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode data = root.get("data");
                if (data != null && data.isArray()) {
                    for (JsonNode node : data) {
                        String id = node.get("id").asText();
                        ModelInfo mi = new ModelInfo(id, id, "openai", false);
                        // Mark known models with capabilities
                        configureModelCapabilities(mi);
                        models.add(mi);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to list OpenAI models: {}", e.getMessage());
        }

        // B6: 空列表直接返回，不注入假模型，由 UI 提示用户检查 API Key / 网络
        return models;
    }

    private void configureModelCapabilities(ModelInfo mi) {
        String id = mi.getId().toLowerCase();
        if (id.contains("gpt-4o") || id.contains("gpt-4-turbo")) {
            mi.setSupportsTools(true);
            mi.setSupportsVision(true);
            mi.setContextWindow(128000);
            mi.setMaxOutputTokens(16384);
        } else if (id.contains("gpt-4")) {
            mi.setSupportsTools(true);
            mi.setContextWindow(8192);
            mi.setMaxOutputTokens(4096);
        } else if (id.contains("gpt-3.5")) {
            mi.setSupportsTools(true);
            mi.setContextWindow(16385);
            mi.setMaxOutputTokens(4096);
        } else if (id.contains("glm-4") || id.contains("glm-4-flash")) {
            mi.setSupportsTools(true);
            mi.setContextWindow(128000);
            mi.setMaxOutputTokens(4096);
        } else if (id.contains("deepseek")) {
            mi.setSupportsTools(true);
            mi.setContextWindow(64000);
            mi.setMaxOutputTokens(8192);
        } else if (id.contains("qwen")) {
            mi.setSupportsTools(true);
            mi.setContextWindow(32768);
            mi.setMaxOutputTokens(8192);
        }
    }

    @Override
    public String chat(String model, List<ChatMessage> messages, String apiKey, String baseUrl,
                       double temperature, int maxTokens) {
        String url = resolveBaseUrl(baseUrl);

        try {
            String requestBody = buildRequestBody(model, messages, temperature, maxTokens, false, null);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode choices = root.get("choices");
                if (choices != null && choices.size() > 0) {
                    JsonNode message = choices.get(0).get("message");
                    JsonNode content = message.get("content");
                    return content != null ? content.asText() : "";
                }
            } else {
                logger.error("OpenAI API error {}: {}", response.statusCode(), response.body());
                return "API Error " + response.statusCode() + ": " + response.body();
            }
        } catch (Exception e) {
            logger.error("OpenAI chat failed", e);
            return "Error: " + e.getMessage();
        }

        return "No response from API";
    }

    @Override
    public ChatResult chatWithTools(String model, List<ChatMessage> messages,
                                     JsonNode tools, String apiKey, String baseUrl,
                                     double temperature, int maxTokens) {
        String url = resolveBaseUrl(baseUrl);

        try {
            String requestBody = buildRequestBody(model, messages, temperature, maxTokens, false, tools);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode choices = root.get("choices");
                if (choices != null && choices.size() > 0) {
                    JsonNode message = choices.get(0).get("message");
                    String content = message.has("content") && !message.get("content").isNull()
                            ? message.get("content").asText() : "";

                    // Parse tool calls from response
                    List<ChatMessage.ToolCall> toolCalls = null;
                    if (message.has("tool_calls") && message.get("tool_calls").isArray()) {
                        toolCalls = new ArrayList<>();
                        for (JsonNode tc : message.get("tool_calls")) {
                            String id = tc.get("id").asText();
                            String name = tc.get("function").get("name").asText();
                            String args = tc.get("function").get("arguments").asText();
                            toolCalls.add(new ChatMessage.ToolCall(id, name, args));
                        }
                    }

                    return new ChatResult(content, toolCalls);
                }
            } else {
                logger.error("OpenAI API error {}: {}", response.statusCode(), response.body());
                return new ChatResult("API Error " + response.statusCode() + ": " + response.body(), null);
            }
        } catch (Exception e) {
            logger.error("OpenAI chatWithTools failed", e);
            return new ChatResult("Error: " + e.getMessage(), null);
        }

        return new ChatResult("No response from API", null);
    }

    @Override
    public void chatStream(String model, List<ChatMessage> messages, String apiKey,
                           String baseUrl, double temperature, int maxTokens,
                           Consumer<String> onToken, Runnable onComplete,
                           Consumer<Exception> onError) {
        String url = resolveBaseUrl(baseUrl);

        try {
            String requestBody = buildRequestBody(model, messages, temperature, maxTokens, true, null);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            streamHttpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                    .thenAccept(response -> {
                        if (response.statusCode() == 200) {
                            SseStreamParser.parse(response.body(), onToken, onComplete, onError);
                        } else {
                            onError.accept(new RuntimeException(
                                    "API Error " + response.statusCode()));
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
        return "https://api.openai.com/v1";
    }

    /**
     * Build OpenAI Chat Completions request body.
     * Uses ChatMessage.toOpenAiJson() for full tool call support.
     */
    private String buildRequestBody(String model, List<ChatMessage> messages,
                                     double temperature, int maxTokens, boolean stream,
                                     JsonNode tools) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);

        // Use ChatMessage.toOpenAiJson() which handles tool_calls, tool_call_id, etc.
        ArrayNode msgArray = objectMapper.createArrayNode();
        for (ChatMessage msg : messages) {
            msgArray.add(msg.toOpenAiJson());
        }
        body.set("messages", msgArray);

        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        if (stream) body.put("stream", true);

        // Add tools if provided (native function calling)
        if (tools != null && tools.isArray() && tools.size() > 0) {
            body.set("tools", tools);
            body.put("tool_choice", "auto");
        }

        return objectMapper.writeValueAsString(body);
    }
}
