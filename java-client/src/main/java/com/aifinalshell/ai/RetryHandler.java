package com.aifinalshell.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;

/**
 * Retry handler with exponential backoff for AI API calls.
 */
public class RetryHandler {
    private static final Logger logger = LoggerFactory.getLogger(RetryHandler.class);
    private static final int MAX_RETRIES = 3;
    private static final long BASE_DELAY_MS = 1000;
    private static final long MAX_DELAY_MS = 30000;

    /**
     * Execute with retry on transient failures.
     */
    public static <T> T executeWithRetry(Callable<T> task) throws Exception {
        Exception lastException = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return task.call();
            } catch (java.net.ConnectException | java.net.SocketTimeoutException e) {
                lastException = e;
                logger.warn("Attempt {}/{} failed (network): {}", attempt + 1, MAX_RETRIES + 1, e.getMessage());
            } catch (java.net.http.HttpTimeoutException e) {
                lastException = e;
                logger.warn("Attempt {}/{} failed (timeout): {}", attempt + 1, MAX_RETRIES + 1, e.getMessage());
            } catch (Exception e) {
                lastException = e;
                String msg = e.getMessage() != null ? e.getMessage() : "";
                // Check for HTTP status code in exception message
                boolean retryable = msg.contains("429") || msg.contains("500") || msg.contains("502") || msg.contains("503");
                if (retryable) {
                    long delay = calculateDelay(attempt);
                    logger.warn("Attempt {}/{} failed (retryable), retrying in {}ms",
                            attempt + 1, MAX_RETRIES + 1, delay);
                    Thread.sleep(delay);
                    continue;
                }
                throw e;
            }

            if (attempt < MAX_RETRIES) {
                long delay = calculateDelay(attempt);
                logger.warn("Attempt {}/{} failed, retrying in {}ms",
                        attempt + 1, MAX_RETRIES + 1, delay);
                Thread.sleep(delay);
            }
        }

        throw lastException;
    }

    /**
     * Execute with retry, returning a default on failure.
     */
    public static <T> T executeWithRetry(Callable<T> task, T defaultValue) {
        try {
            return executeWithRetry(task);
        } catch (Exception e) {
            logger.error("All retry attempts failed", e);
            return defaultValue;
        }
    }

    private static long calculateDelay(int attempt) {
        long delay = BASE_DELAY_MS * (1L << attempt);
        return Math.min(delay, MAX_DELAY_MS);
    }
}
