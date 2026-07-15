package com.aifinalshell.provider.custom;

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

public class CustomProvider implements AiProvider {
    private static final Logger logger = LoggerFactory.getLogger(CustomProvider.class);
    private final HttpClient httpClient;
    private final HttpClient streamHttpClient;
    private final ObjectMapper objectMapper;

    public CustomProvider() {
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
        return "custom";
    }

    @Override
    public boolean supportsFunctionCalling() {
        return true;
    }

    @Override
    public List<ModelInfo> listModels(String apiKey, String baseUrl) {
        List<ModelInfo> models = new ArrayList<>();

        if (baseUrl == null || baseUrl.isEmpty() || apiKey == null || apiKey.isEmpty()) {
            return models;
        }

        try {
            String url = baseUrl.endsWith("/") ? baseUrl + "models" : baseUrl + "/models";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
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
                        ModelInfo mi = new ModelInfo(id, id, "custom", true);
                        mi.setSupportsTools(true);
                        models.add(mi);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to list custom models: {}", e.getMessage());
        }

        return models;
    }

    @Override
    public String chat(String model, List<ChatMessage> messages, String apiKey, String baseUrl,
                       double temperature, int maxTokens) {
        try {
            String url = resolveChatUrl(baseUrl);
            String requestBody = buildRequestBody(model, messages, temperature, maxTokens, false, null);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(300))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode choices = root.get("choices");
                if (choices != null && choices.size() > 0) {
                    JsonNode msg = choices.get(0).get("message");
                    if (msg != null && msg.has("content") && !msg.get("content").isNull()) {
                        return msg.get("content").asText();
                    }
                    return "";
                }
            } else {
                logger.error("Custom API error {}: {}", response.statusCode(), response.body());
                return "API Error " + response.statusCode() + ": " + response.body();
            }
        } catch (Exception e) {
            logger.error("Custom chat failed", e);
            return "Error: " + e.getMessage();
        }

        return "No response from API";
    }

    @Override
    public ChatResult chatWithTools(String model, List<ChatMessage> messages,
                                     JsonNode tools, String apiKey, String baseUrl,
                                     double temperature, int maxTokens) {
        try {
            String url = resolveChatUrl(baseUrl);
            String requestBody = buildRequestBody(model, messages, temperature, maxTokens, false, tools);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(300))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode choices = root.get("choices");
                if (choices != null && choices.size() > 0) {
                    JsonNode message = choices.get(0).get("message");
                    String content = "";
                    if (message.has("content") && !message.get("content").isNull()) {
                        content = message.get("content").asText();
                    }

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
                logger.error("Custom API error {}: {}", response.statusCode(), response.body());
                return new ChatResult("API Error " + response.statusCode() + ": " + response.body(), null);
            }
        } catch (Exception e) {
            logger.error("Custom chatWithTools failed", e);
            return new ChatResult("Error: " + e.getMessage(), null);
        }

        return new ChatResult("No response from API", null);
    }

    @Override
    public void chatStream(String model, List<ChatMessage> messages, String apiKey,
                           String baseUrl, double temperature, int maxTokens,
                           Consumer<String> onToken, Runnable onComplete,
                           Consumer<Exception> onError) {
        try {
            String url = resolveChatUrl(baseUrl);
            String requestBody = buildRequestBody(model, messages, temperature, maxTokens, true, null);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(300))
                    .build();

            streamHttpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                    .thenAccept(response -> {
                        if (response.statusCode() == 200) {
                            SseStreamParser.parse(response.body(), onToken, onComplete, onError);
                        } else {
                            try {
                                String errBody = new String(response.body().readAllBytes());
                                onError.accept(new RuntimeException(
                                        "API Error " + response.statusCode() + ": " + errBody));
                            } catch (Exception ex) {
                                onError.accept(new RuntimeException(
                                        "API Error " + response.statusCode()));
                            }
                        }
                    })
                    .exceptionally(ex -> {
                        onError.accept(ex instanceof Exception e ? e : new RuntimeException(ex));
                        return null;
                    });
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    @Override
    public void chatWithToolsStream(String model, List<ChatMessage> messages,
                                     JsonNode tools, String apiKey, String baseUrl,
                                     double temperature, int maxTokens,
                                     Consumer<String> onToken,
                                     Consumer<ChatResult> onComplete,
                                     Consumer<Exception> onError) {
        try {
            String url = resolveChatUrl(baseUrl);
            String requestBody = buildRequestBody(model, messages, temperature, maxTokens, true, tools);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(300))
                    .build();

            streamHttpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                    .thenAccept(response -> {
                        if (response.statusCode() == 200) {
                            SseStreamParser.parseWithTools(response.body(), onToken, onComplete, onError);
                        } else {
                            try {
                                String errBody = new String(response.body().readAllBytes());
                                onError.accept(new RuntimeException(
                                        "API Error " + response.statusCode() + ": " + errBody));
                            } catch (Exception ex) {
                                onError.accept(new RuntimeException(
                                        "API Error " + response.statusCode()));
                            }
                        }
                    })
                    .exceptionally(ex -> {
                        onError.accept(ex instanceof Exception e ? e : new RuntimeException(ex));
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

    public TestResult testConnection(String apiKey, String baseUrl, String model) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            return new TestResult(false, "Base URL 不能为空", null);
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return new TestResult(false, "API Key 不能为空", null);
        }

        String normalizedBase = baseUrl.trim();
        while (normalizedBase.endsWith("/")) {
            normalizedBase = normalizedBase.substring(0, normalizedBase.length() - 1);
        }

        List<String> availableModels;
        try {
            String url = normalizedBase + "/models";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 401 || code == 403) {
                return new TestResult(false, "认证失败 (HTTP " + code + ")：API Key 无效或无权限", null);
            }
            if (code == 404) {
                availableModels = null;
            } else if (code != 200) {
                return new TestResult(false, "连接失败 (HTTP " + code + ")：" + truncate(response.body(), 200), null);
            } else {
                availableModels = new ArrayList<>();
                try {
                    JsonNode root = objectMapper.readTree(response.body());
                    JsonNode data = root.get("data");
                    if (data != null && data.isArray()) {
                        for (JsonNode node : data) {
                            JsonNode idNode = node.get("id");
                            if (idNode != null) availableModels.add(idNode.asText());
                        }
                    }
                } catch (Exception parseEx) {
                    logger.debug("解析 /models 响应失败，继续模型测试: {}", parseEx.getMessage());
                }
            }
        } catch (java.net.ConnectException ce) {
            return new TestResult(false, "无法连接到 " + normalizedBase + "：连接被拒绝（检查地址/网络/代理）", null);
        } catch (Exception e) {
            return new TestResult(false, "连接异常：" + e.getMessage(), null);
        }

        if (model != null && !model.trim().isEmpty()) {
            String testModel = model.trim();
            try {
                String url = normalizedBase + "/chat/completions";
                String requestBody = buildRequestBody(testModel,
                        List.of(new ChatMessage("user", "hi")), 0.0, 1, false, null);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .timeout(Duration.ofSeconds(30))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int code = response.statusCode();
                if (code == 200) {
                    String detail = availableModels != null && !availableModels.isEmpty()
                            ? "模型 " + testModel + " 可用，共发现 " + availableModels.size() + " 个模型"
                            : "模型 " + testModel + " 可用";
                    return new TestResult(true, detail, availableModels);
                } else if (code == 404) {
                    return new TestResult(false, "模型 " + testModel + " 不存在 (HTTP 404)", availableModels);
                } else {
                    return new TestResult(false, "模型测试失败 (HTTP " + code + ")：" + truncate(response.body(), 200), availableModels);
                }
            } catch (Exception e) {
                return new TestResult(false, "模型测试异常：" + e.getMessage(), availableModels);
            }
        }

        String detail = availableModels != null && !availableModels.isEmpty()
                ? "连接成功，共发现 " + availableModels.size() + " 个可用模型"
                : "连接成功（/models 端点可用）";
        return new TestResult(true, detail, availableModels);
    }

    public static class TestResult {
        private final boolean success;
        private final String message;
        private final List<String> availableModels;

        public TestResult(boolean success, String message, List<String> availableModels) {
            this.success = success;
            this.message = message;
            this.availableModels = availableModels;
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public List<String> getAvailableModels() { return availableModels; }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private String resolveChatUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) return "";
        return baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
    }

    private String buildRequestBody(String model, List<ChatMessage> messages,
                                     double temperature, int maxTokens, boolean stream,
                                     JsonNode tools) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);

        ArrayNode msgArray = objectMapper.createArrayNode();
        for (ChatMessage msg : messages) {
            msgArray.add(msg.toOpenAiJson());
        }
        body.set("messages", msgArray);

        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        if (stream) body.put("stream", true);

        if (tools != null && tools.isArray() && tools.size() > 0) {
            body.set("tools", tools);
            body.put("tool_choice", "auto");
        }

        return objectMapper.writeValueAsString(body);
    }
}
