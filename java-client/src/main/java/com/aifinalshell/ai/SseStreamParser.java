package com.aifinalshell.ai;

import com.aifinalshell.provider.ChatResult;
import com.aifinalshell.provider.StreamChatResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class SseStreamParser {
    private static final Logger logger = LoggerFactory.getLogger(SseStreamParser.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String DATA_PREFIX = "data: ";
    private static final String DONE_MARKER = "[DONE]";

    public static void parse(InputStream inputStream, Consumer<String> onToken,
                             Runnable onComplete, Consumer<Exception> onError) {
        java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean(false);
        Runnable safeComplete = () -> { if (finished.compareAndSet(false, true)) onComplete.run(); };
        Consumer<Exception> safeError = e -> { if (finished.compareAndSet(false, true)) onError.accept(e); };
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;

                if (line.startsWith(DATA_PREFIX)) {
                    String data = line.substring(DATA_PREFIX.length()).trim();

                    if (DONE_MARKER.equals(data)) {
                        safeComplete.run();
                        return;
                    }

                    try {
                        JsonNode root = objectMapper.readTree(data);
                        JsonNode choices = root.get("choices");
                        if (choices != null && choices.size() > 0) {
                            JsonNode delta = choices.get(0).get("delta");
                            if (delta != null) {
                                JsonNode content = delta.get("content");
                                if (content != null && !content.isNull()) {
                                    onToken.accept(content.asText());
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Failed to parse SSE data line: {}", data, e);
                    }
                }
            }

            safeComplete.run();
        } catch (Exception e) {
            safeError.accept(e);
        }
    }

    /**
     * Parse SSE stream with full tool_calls support.
     * Accumulates both content tokens and incremental tool_calls, then invokes
     * onComplete with the assembled ChatResult (content + parsed tool calls).
     *
     * @param onToken    callback for each content token (for streaming display)
     * @param onComplete callback with the final assembled ChatResult
     * @param onError    callback on error
     */
    public static void parseWithTools(InputStream inputStream,
                                      Consumer<String> onToken,
                                      Consumer<ChatResult> onComplete,
                                      Consumer<Exception> onError) {
        java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean(false);
        Consumer<ChatResult> safeComplete = r -> { if (finished.compareAndSet(false, true)) onComplete.accept(r); };
        Consumer<Exception> safeError = e -> { if (finished.compareAndSet(false, true)) onError.accept(e); };

        StreamChatResult accumulator = new StreamChatResult();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;

                if (line.startsWith(DATA_PREFIX)) {
                    String data = line.substring(DATA_PREFIX.length()).trim();

                    if (DONE_MARKER.equals(data)) {
                        safeComplete.accept(accumulator.toChatResult());
                        return;
                    }

                    try {
                        JsonNode root = objectMapper.readTree(data);
                        JsonNode choices = root.get("choices");
                        if (choices != null && choices.size() > 0) {
                            JsonNode choice = choices.get(0);
                            JsonNode delta = choice.get("delta");
                            if (delta != null) {
                                JsonNode content = delta.get("content");
                                if (content != null && !content.isNull() && !content.asText().isEmpty()) {
                                    String text = content.asText();
                                    accumulator.appendContent(text);
                                    if (onToken != null) onToken.accept(text);
                                }

                                JsonNode toolCalls = delta.get("tool_calls");
                                if (toolCalls != null && toolCalls.isArray()) {
                                    for (JsonNode tc : toolCalls) {
                                        int index = tc.has("index") ? tc.get("index").asInt() : 0;
                                        String id = tc.has("id") && !tc.get("id").isNull() ? tc.get("id").asText() : null;
                                        String name = null;
                                        String argsDelta = null;

                                        JsonNode fn = tc.get("function");
                                        if (fn != null) {
                                            if (fn.has("name") && !fn.get("name").isNull()) {
                                                name = fn.get("name").asText();
                                            }
                                            if (fn.has("arguments") && !fn.get("arguments").isNull()) {
                                                argsDelta = fn.get("arguments").asText();
                                            }
                                        }

                                        accumulator.accumulateToolCall(index, id, name, argsDelta);
                                    }
                                }
                            }

                            if (choice.has("finish_reason") && !choice.get("finish_reason").isNull()) {
                                accumulator.setFinished(choice.get("finish_reason").asText());
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Failed to parse SSE data line (with tools): {}", data, e);
                    }
                }
            }

            safeComplete.accept(accumulator.toChatResult());
        } catch (Exception e) {
            safeError.accept(e);
        }
    }

    public static void parseAnthropic(InputStream inputStream, Consumer<String> onToken,
                                       Runnable onComplete, Consumer<Exception> onError) {
        java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean(false);
        Runnable safeComplete = () -> { if (finished.compareAndSet(false, true)) onComplete.run(); };
        Consumer<Exception> safeError = e -> { if (finished.compareAndSet(false, true)) onError.accept(e); };
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String line;
            String eventType = null;

            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    eventType = null;
                    continue;
                }

                if (line.startsWith("event: ")) {
                    eventType = line.substring(7).trim();
                    continue;
                }

                if (line.startsWith("data: ") && "content_block_delta".equals(eventType)) {
                    String data = line.substring(6).trim();
                    try {
                        JsonNode root = objectMapper.readTree(data);
                        JsonNode delta = root.get("delta");
                        if (delta != null) {
                            JsonNode text = delta.get("text");
                            if (text != null && !text.isNull()) {
                                onToken.accept(text.asText());
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Failed to parse Anthropic SSE data: {}", data, e);
                    }
                }

                if ("message_stop".equals(eventType)) {
                    safeComplete.run();
                    return;
                }
            }

            safeComplete.run();
        } catch (Exception e) {
            safeError.accept(e);
        }
    }

    public static void parseOllama(InputStream inputStream, Consumer<String> onToken,
                                    Runnable onComplete, Consumer<Exception> onError) {
        java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean(false);
        Runnable safeComplete = () -> { if (finished.compareAndSet(false, true)) onComplete.run(); };
        Consumer<Exception> safeError = e -> { if (finished.compareAndSet(false, true)) onError.accept(e); };
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;

                try {
                    JsonNode root = objectMapper.readTree(line);
                    JsonNode message = root.get("message");
                    if (message != null) {
                        JsonNode content = message.get("content");
                        if (content != null && !content.isNull()) {
                            onToken.accept(content.asText());
                        }
                    }

                    JsonNode done = root.get("done");
                    if (done != null && done.asBoolean()) {
                        safeComplete.run();
                        return;
                    }
                } catch (Exception e) {
                    logger.debug("Failed to parse Ollama line: {}", line, e);
                }
            }

            safeComplete.run();
        } catch (Exception e) {
            safeError.accept(e);
        }
    }
}
