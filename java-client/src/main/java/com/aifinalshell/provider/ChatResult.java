package com.aifinalshell.provider;

import com.aifinalshell.provider.ChatMessage.ToolCall;
import java.util.List;

/**
 * Result of a chat call with tools, containing both text and tool calls.
 */
public class ChatResult {
    private final String content;
    private final List<ToolCall> toolCalls;

    public ChatResult(String content, List<ToolCall> toolCalls) {
        this.content = content;
        this.toolCalls = toolCalls;
    }

    public String getContent() { return content; }
    public List<ToolCall> getToolCalls() { return toolCalls; }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
