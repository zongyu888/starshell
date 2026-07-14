package com.aifinalshell.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * SSE (Server-Sent Events) stream parser for OpenAI-compatible APIs.
 * Parses "data: {...}" lines from text/event-stream responses.
 */
public class SseStreamParser {
    private static final Logger logger = LoggerFactory.getLogger(SseStreamParser.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String DATA_PREFIX = "data: ";
    private static final String DONE_MARKER = "[DONE]";

    /**
     * Parse SSE stream and invoke callback for each content token.
     *
     * @param inputStream the SSE stream
     * @param onToken     callback for each content token
     * @param onComplete  callback when stream ends normally
     * @param onError     callback on error
     */
    public static void parse(InputStream inputStream, Consumer<String> onToken,
                             Runnable onComplete, Consumer<Exception> onError) {
        // B11: 幂等保护，确保 onComplete/onError 各最多触发一次，避免重复 finalize
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
     * Parse Anthropic SSE stream format (event: message_start / content_block_delta / message_stop).
     */
    public static void parseAnthropic(InputStream inputStream, Consumer<String> onToken,
                                       Runnable onComplete, Consumer<Exception> onError) {
        // B11: 幂等保护
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

    /**
     * Parse Ollama NDJSON stream format (each line is a JSON object).
     */
    public static void parseOllama(InputStream inputStream, Consumer<String> onToken,
                                    Runnable onComplete, Consumer<Exception> onError) {
        // B11: 幂等保护
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

                    // Ollama signals end with "done": true
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
