package com.aifinalshell.provider;

import java.util.ArrayList;
import java.util.List;

public class StreamChatResult {
    private final StringBuilder contentBuilder = new StringBuilder();
    private final List<ToolCallAccumulator> toolCalls = new ArrayList<>();
    private boolean finished = false;
    private String finishReason;

    public String getContent() {
        return contentBuilder.toString();
    }

    public void appendContent(String text) {
        if (text != null) {
            contentBuilder.append(text);
        }
    }

    public List<ChatMessage.ToolCall> getToolCalls() {
        List<ChatMessage.ToolCall> result = new ArrayList<>();
        for (ToolCallAccumulator acc : toolCalls) {
            result.add(new ChatMessage.ToolCall(acc.id, acc.name, acc.argsBuilder.toString()));
        }
        return result;
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public boolean isEmpty() {
        return contentBuilder.length() == 0 && toolCalls.isEmpty();
    }

    public boolean isFinished() {
        return finished;
    }

    public void setFinished(String reason) {
        this.finished = true;
        this.finishReason = reason;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public void accumulateToolCall(int index, String id, String name, String argsDelta) {
        while (toolCalls.size() <= index) {
            toolCalls.add(new ToolCallAccumulator());
        }
        ToolCallAccumulator acc = toolCalls.get(index);
        if (id != null && !id.isEmpty()) acc.id = id;
        if (name != null && !name.isEmpty()) acc.name = name;
        if (argsDelta != null) acc.argsBuilder.append(argsDelta);
    }

    public ChatResult toChatResult() {
        String content = contentBuilder.toString();
        List<ChatMessage.ToolCall> calls = getToolCalls();
        return new ChatResult(content, calls.isEmpty() ? null : calls);
    }

    private static class ToolCallAccumulator {
        String id = "";
        String name = "";
        StringBuilder argsBuilder = new StringBuilder();
    }
}
