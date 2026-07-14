package com.aifinalshell.provider.free;

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
 * Free model provider using OpenCode Zen API with streaming SSE support.
 */
public class FreeModelProvider implements AiProvider {
    private static final Logger logger = LoggerFactory.getLogger(FreeModelProvider.class);
    private final HttpClient httpClient;
    private final HttpClient streamHttpClient;
    private final ObjectMapper objectMapper;

    private static final String ZEN_BASE_URL = "https://opencode.ai/zen/v1";
    private static final String ZEN_API_KEY = "public";
    private static final String MODELS_URL = "https://models.dev/api.json";

    public FreeModelProvider() {
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
        return "opencode";
    }

    @Override
    public List<ModelInfo> listModels(String apiKey, String baseUrl) {
        // opencode zen API 免费模型（mimo/nemotron/ring 等）已废弃：用户无法实际使用，
        // 且启动时请求 models.dev 会拖慢初始化。这里返回空列表，选择器只展示用户已配置
        // API Key 的 provider（openai/custom 等）模型。保留 provider 注册以兼容旧配置。
        return new ArrayList<>();
    }

    @Override
    public String chat(String model, List<ChatMessage> messages, String apiKey, String baseUrl,
                       double temperature, int maxTokens) {
        try {
            String requestBody = buildRequestBody(model, messages, temperature, maxTokens, false);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ZEN_BASE_URL + "/chat/completions"))
                    .header("Authorization", "Bearer " + ZEN_API_KEY)
                    .header("Content-Type", "application/json")
                    .header("HTTP-Referer", "https://opencode.ai/")
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
                logger.error("OpenCode Zen API error {}: {}", response.statusCode(), response.body());
                return "API Error " + response.statusCode() + ": " + response.body();
            }
        } catch (Exception e) {
            logger.error("OpenCode Zen chat failed", e);
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
            String requestBody = buildRequestBody(model, messages, temperature, maxTokens, true);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ZEN_BASE_URL + "/chat/completions"))
                    .header("Authorization", "Bearer " + ZEN_API_KEY)
                    .header("Content-Type", "application/json")
                    .header("HTTP-Referer", "https://opencode.ai/")
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
        return true;
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
