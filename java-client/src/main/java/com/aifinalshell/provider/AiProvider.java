package com.aifinalshell.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.function.Consumer;

public interface AiProvider {
    String getName();
    List<ModelInfo> listModels(String apiKey, String baseUrl);
    String chat(String model, List<ChatMessage> messages, String apiKey, String baseUrl, double temperature, int maxTokens);
    boolean isAvailable(String apiKey);

    default void chatStream(String model, List<ChatMessage> messages, String apiKey,
                            String baseUrl, double temperature, int maxTokens,
                            Consumer<String> onToken, Runnable onComplete,
                            Consumer<Exception> onError) {
        try {
            String result = chat(model, messages, apiKey, baseUrl, temperature, maxTokens);
            onToken.accept(result);
            onComplete.run();
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    default ChatResult chatWithTools(String model, List<ChatMessage> messages,
                                      JsonNode tools, String apiKey, String baseUrl,
                                      double temperature, int maxTokens) {
        String response = chat(model, messages, apiKey, baseUrl, temperature, maxTokens);
        return new ChatResult(response, null);
    }

    /**
     * Streaming version of chatWithTools.
     * Accumulates content tokens (streamed via onToken for real-time display) and
     * incremental tool_calls, then calls onComplete with the assembled ChatResult.
     *
     * Default implementation falls back to synchronous chatWithTools.
     *
     * @param onToken    called for each content token as it arrives (for UI streaming)
     * @param onComplete called with the final ChatResult (content + tool calls) when done
     * @param onError    called on error
     */
    default void chatWithToolsStream(String model, List<ChatMessage> messages,
                                      JsonNode tools, String apiKey, String baseUrl,
                                      double temperature, int maxTokens,
                                      Consumer<String> onToken,
                                      Consumer<ChatResult> onComplete,
                                      Consumer<Exception> onError) {
        try {
            ChatResult result = chatWithTools(model, messages, tools, apiKey, baseUrl, temperature, maxTokens);
            if (result.getContent() != null && onToken != null) {
                onToken.accept(result.getContent());
            }
            onComplete.accept(result);
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    default boolean supportsFunctionCalling() {
        return false;
    }
}
