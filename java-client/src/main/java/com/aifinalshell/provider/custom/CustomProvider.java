package com.aifinalshell.provider.custom;

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
 * Custom OpenAI-compatible provider with streaming SSE support.
 */
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
                        models.add(new ModelInfo(id, id, "custom", false));
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
            String requestBody = buildRequestBody(model, messages, temperature, maxTokens, false);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
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
                    return choices.get(0).get("message").get("content").asText();
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
    public void chatStream(String model, List<ChatMessage> messages, String apiKey,
                           String baseUrl, double temperature, int maxTokens,
                           Consumer<String> onToken, Runnable onComplete,
                           Consumer<Exception> onError) {
        try {
            String url = resolveChatUrl(baseUrl);
            String requestBody = buildRequestBody(model, messages, temperature, maxTokens, true);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
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

    /**
     * 测试自定义 OpenAI 兼容 API 的连通性与模型可用性。
     * 步骤：1) GET {baseUrl}/models 验证端点可达且 API Key 有效；
     *       2) 若指定 model，POST {baseUrl}/chat/completions 发送最小请求验证该模型可调用。
     *
     * @param apiKey API 密钥
     * @param baseUrl API 基础地址（如 https://api.deepseek.com/v1）
     * @param model  模型 ID（可为 null/空，表示仅测试连通性不验证具体模型）
     * @return TestResult 含成功标志、详细消息、可用模型列表
     */
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

        // === 步骤1：GET /models 验证连通性与密钥 ===
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
                // 某些兼容服务不支持 /models 端点，跳过列表步骤，直接进入模型测试
                availableModels = null;
            } else if (code != 200) {
                return new TestResult(false, "连接失败 (HTTP " + code + ")：" + truncate(response.body(), 200), null);
            } else {
                // 解析模型列表
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

        // === 步骤2：若指定模型，发送最小 chat 请求验证模型可调用 ===
        if (model != null && !model.trim().isEmpty()) {
            String testModel = model.trim();
            try {
                String url = normalizedBase + "/chat/completions";
                String requestBody = buildRequestBody(testModel,
                        List.of(new ChatMessage("user", "hi")), 0.0, 1, false);

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

        // 未指定模型：仅靠 /models 连通性判断
        String detail = availableModels != null && !availableModels.isEmpty()
                ? "连接成功，共发现 " + availableModels.size() + " 个可用模型"
                : "连接成功（/models 端点可用）";
        return new TestResult(true, detail, availableModels);
    }

    /**
     * 测试结果：成功标志 + 详细消息 + 可用模型列表（可能为 null）。
     */
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
                                     double temperature, int maxTokens, boolean stream) throws Exception {
        List<Map<String, String>> msgList = new ArrayList<>();
        for (ChatMessage msg : messages) {
            Map<String, String> m = new HashMap<>();
            m.put("role", msg.getRole());
            m.put("content", msg.getContent());
            msgList.add(m);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", msgList);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        if (stream) body.put("stream", true);

        return objectMapper.writeValueAsString(body);
    }
}
