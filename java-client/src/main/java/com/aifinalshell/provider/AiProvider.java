package com.aifinalshell.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.function.Consumer;

/**
 * AI Provider interface - all LLM providers implement this.
 * Supports synchronous chat, streaming (SSE), and native function calling (tool use).
 *
 * Design inspired by opencode-dev's Protocol/Route architecture:
 * - Protocol: OpenAI Chat / Anthropic Messages / Ollama
 * - Auth: bearer token / header injection
 * - Framing: SSE stream parsing
 */
public interface AiProvider {
    String getName();
    List<ModelInfo> listModels(String apiKey, String baseUrl);
    String chat(String model, List<ChatMessage> messages, String apiKey, String baseUrl, double temperature, int maxTokens);
    boolean isAvailable(String apiKey);

    /**
     * Streaming chat with real-time token callback.
     * Default implementation falls back to synchronous call.
     */
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

    /**
     * Chat with tool/function calling support.
     * Returns a ChatResult containing both text response and any tool calls.
     * Default implementation falls back to text-based tool parsing.
     *
     * @param tools JSON array of tool definitions (OpenAI format)
     */
    default ChatResult chatWithTools(String model, List<ChatMessage> messages,
                                      JsonNode tools, String apiKey, String baseUrl,
                                      double temperature, int maxTokens) {
        // Default: fall back to regular chat (no native function calling)
        String response = chat(model, messages, apiKey, baseUrl, temperature, maxTokens);
        return new ChatResult(response, null);
    }

    /**
     * Check if this provider supports native function calling.
     */
    default boolean supportsFunctionCalling() {
        return false;
    }
}
