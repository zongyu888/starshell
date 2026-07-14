package com.aifinalshell.ai;

import com.aifinalshell.provider.AiProvider;
import com.aifinalshell.provider.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Compacts long conversation history by generating an AI summary,
 * preserving system prompt + summary + recent messages.
 */
public class ContextCompactor {
    private static final Logger logger = LoggerFactory.getLogger(ContextCompactor.class);
    private static final int RECENT_MESSAGES_TO_KEEP = 5;
    private static final double COMPACTION_RATIO = 0.7; // compact when usage > 70% of context

    /**
     * Check if compaction is needed based on token usage.
     */
    public static boolean needsCompaction(List<ChatMessage> messages, int contextWindow) {
        int tokens = TokenCounter.countMessageTokens(messages);
        return tokens > contextWindow * COMPACTION_RATIO;
    }

    /**
     * Compact conversation by generating a summary of older messages.
     *
     * @param messages      full conversation
     * @param provider      AI provider for summary generation
     * @param model         model to use for summary
     * @param apiKey        API key
     * @param baseUrl       base URL
     * @param contextWindow model's context window size
     * @return compacted message list
     */
    public static List<ChatMessage> compact(List<ChatMessage> messages, AiProvider provider,
                                            String model, String apiKey, String baseUrl,
                                            int contextWindow) {
        if (!needsCompaction(messages, contextWindow)) return messages;

        // Split: system prompt + old messages + recent messages
        int systemEnd = 0;
        if (!messages.isEmpty() && "system".equals(messages.get(0).getRole())) {
            systemEnd = 1;
        }

        ChatMessage systemPrompt = systemEnd > 0 ? messages.get(0) : null;
        List<ChatMessage> allChat = messages.subList(systemEnd, messages.size());

        if (allChat.size() <= RECENT_MESSAGES_TO_KEEP * 2) return messages;

        List<ChatMessage> oldMessages = allChat.subList(0, allChat.size() - RECENT_MESSAGES_TO_KEEP);
        List<ChatMessage> recentMessages = allChat.subList(allChat.size() - RECENT_MESSAGES_TO_KEEP, allChat.size());

        // Generate summary of old messages
        String summary = generateSummary(oldMessages, provider, model, apiKey, baseUrl);

        // Build compacted list
        List<ChatMessage> compacted = new ArrayList<>();
        if (systemPrompt != null) {
            compacted.add(systemPrompt);
        }
        compacted.add(ChatMessage.system(
                "[Conversation Summary]\n" + summary +
                "\n[End of Summary - Recent conversation follows]"
        ));
        compacted.addAll(recentMessages);

        logger.info("Compacted {} messages into summary ({} tokens -> {} tokens)",
                oldMessages.size(),
                TokenCounter.countMessageTokens(messages),
                TokenCounter.countMessageTokens(compacted));

        return compacted;
    }

    private static String generateSummary(List<ChatMessage> messages, AiProvider provider,
                                          String model, String apiKey, String baseUrl) {
        StringBuilder conversation = new StringBuilder();
        for (ChatMessage msg : messages) {
            conversation.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
        }

        String summaryPrompt = "Summarize the following conversation concisely, preserving key decisions, " +
                "server information, technical details, and ongoing tasks. Keep it under 500 words:\n\n" +
                conversation;

        try {
            List<ChatMessage> summaryMessages = List.of(
                    ChatMessage.system("You are a conversation summarizer. Be concise but preserve technical details."),
                    ChatMessage.user(summaryPrompt)
            );
            return provider.chat(model, summaryMessages, apiKey, baseUrl, 0.3, 1024);
        } catch (Exception e) {
            logger.error("Failed to generate summary", e);
            return "[Summary generation failed - keeping recent messages only]";
        }
    }
}
