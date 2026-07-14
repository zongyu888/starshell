package com.aifinalshell.ai;

import com.aifinalshell.provider.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Token estimation and context truncation for chat messages.
 * Uses heuristic ~4 chars per token for English, ~2 chars per token for CJK.
 */
public class TokenCounter {
    private static final Pattern CJK_PATTERN = Pattern.compile("[\\u4e00-\\u9fff\\u3040-\\u309f\\u30a0-\\u30ff]");
    private static final int CHARS_PER_TOKEN_EN = 4;
    private static final int CHARS_PER_TOKEN_CJK = 2;

    /**
     * Estimate token count for a string.
     */
    public static int countTokens(String text) {
        if (text == null || text.isEmpty()) return 0;

        int cjkCount = 0;
        Matcher matcher = CJK_PATTERN.matcher(text);
        while (matcher.find()) {
            cjkCount++;
        }

        int englishChars = text.length() - cjkCount;
        return (int) Math.ceil((double) englishChars / CHARS_PER_TOKEN_EN)
                + (int) Math.ceil((double) cjkCount / CHARS_PER_TOKEN_CJK);
    }

    /**
     * Estimate total token count for a list of messages (including message overhead).
     */
    public static int countMessageTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage msg : messages) {
            total += 4; // message overhead: role + separators
            total += countTokens(msg.getRole());
            total += countTokens(msg.getContent());
        }
        total += 2; // reply priming
        return total;
    }

    /**
     * Truncate conversation history to fit within maxTokens, preserving system prompt and recent messages.
     *
     * @param messages  full message list (system + history + user)
     * @param maxTokens model's context window
     * @return truncated message list
     */
    public static List<ChatMessage> truncateToLimit(List<ChatMessage> messages, int maxTokens) {
        if (messages.isEmpty()) return messages;

        int totalTokens = countMessageTokens(messages);
        if (totalTokens <= maxTokens) return messages;

        List<ChatMessage> result = new ArrayList<>();
        int remaining = maxTokens;

        // Always keep system prompt (first message if it's system role)
        int startIdx = 0;
        if (!messages.isEmpty() && "system".equals(messages.get(0).getRole())) {
            ChatMessage systemMsg = messages.get(0);
            int systemTokens = countMessageTokens(List.of(systemMsg));
            result.add(systemMsg);
            remaining -= systemTokens;
            startIdx = 1;
        }

        // Keep the last N messages that fit within remaining tokens
        // Reserve 20% for AI response
        int availableForHistory = (int) (remaining * 0.8);

        List<ChatMessage> candidates = messages.subList(startIdx, messages.size());
        List<ChatMessage> kept = new ArrayList<>();
        int usedTokens = 0;

        // Scan from newest to oldest
        for (int i = candidates.size() - 1; i >= 0; i--) {
            ChatMessage msg = candidates.get(i);
            int msgTokens = countMessageTokens(List.of(msg));
            if (usedTokens + msgTokens > availableForHistory) break;
            kept.add(0, msg);
            usedTokens += msgTokens;
        }

        result.addAll(kept);
        return result;
    }
}
