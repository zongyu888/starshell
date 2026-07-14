package com.aifinalshell.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * Chat message with function calling (tool use) support.
 * Supports OpenAI-style tool_calls and Anthropic-style tool_use content blocks.
 */
public class ChatMessage {
    private static final ObjectMapper mapper = new ObjectMapper();

    private String role;
    private String content;
    // Function calling fields
    private List<ToolCall> toolCalls;      // assistant messages: tool calls made by AI
    private String toolCallId;             // tool role messages: ID of the tool call being answered
    private String name;                    // tool role messages: name of the tool

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public ChatMessage(String role, String content, List<ToolCall> toolCalls) {
        this.role = role;
        this.content = content;
        this.toolCalls = toolCalls;
    }

    // ========== Factory Methods ==========

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ChatMessage("assistant", content, toolCalls);
    }

    /**
     * Create a tool response message (answering a tool call).
     */
    public static ChatMessage tool(String toolCallId, String name, String content) {
        ChatMessage msg = new ChatMessage("tool", content);
        msg.toolCallId = toolCallId;
        msg.name = name;
        return msg;
    }

    // ========== Getters ==========

    public String getRole() { return role; }
    public String getContent() { return content; }
    public List<ToolCall> getToolCalls() { return toolCalls; }
    public String getToolCallId() { return toolCallId; }
    public String getName() { return name; }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /**
     * Convert to OpenAI API JSON format.
     */
    public JsonNode toOpenAiJson() {
        var node = mapper.createObjectNode();
        node.put("role", role);

        if (content != null) {
            node.put("content", content);
        }

        if (toolCalls != null && !toolCalls.isEmpty()) {
            var callsNode = mapper.createArrayNode();
            for (ToolCall tc : toolCalls) {
                var callNode = mapper.createObjectNode();
                callNode.put("id", tc.getId());
                callNode.put("type", "function");
                var fnNode = mapper.createObjectNode();
                fnNode.put("name", tc.getName());
                fnNode.put("arguments", tc.getArguments());
                callNode.set("function", fnNode);
                callsNode.add(callNode);
            }
            node.set("tool_calls", callsNode);
        }

        if (toolCallId != null) {
            node.put("tool_call_id", toolCallId);
        }

        if (name != null) {
            node.put("name", name);
        }

        return node;
    }

    /**
     * Convert to Anthropic API format (content blocks).
     */
    public JsonNode toAnthropicJson() {
        var node = mapper.createObjectNode();
        node.put("role", "user".equals(role) ? "user" : "assistant");

        var contentArray = mapper.createArrayNode();

        if (content != null && !content.isEmpty()) {
            var textBlock = mapper.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", content);
            contentArray.add(textBlock);
        }

        if (toolCalls != null) {
            for (ToolCall tc : toolCalls) {
                var toolBlock = mapper.createObjectNode();
                toolBlock.put("type", "tool_use");
                toolBlock.put("id", tc.getId());
                toolBlock.put("name", tc.getName());
                try {
                    toolBlock.set("input", mapper.readTree(tc.getArguments()));
                } catch (Exception e) {
                    var input = mapper.createObjectNode();
                    input.put("value", tc.getArguments());
                    toolBlock.set("input", input);
                }
                contentArray.add(toolBlock);
            }
        }

        if ("tool".equals(role) && toolCallId != null) {
            var resultBlock = mapper.createObjectNode();
            resultBlock.put("type", "tool_result");
            resultBlock.put("tool_use_id", toolCallId);
            var resultContent = mapper.createObjectNode();
            resultContent.put("type", "text");
            resultContent.put("text", content != null ? content : "");
            var resultContentArray = mapper.createArrayNode();
            resultContentArray.add(resultContent);
            resultBlock.set("content", resultContentArray);
            contentArray.add(resultBlock);
            node.put("role", "user"); // Anthropic uses user role for tool results
        }

        node.set("content", contentArray);
        return node;
    }

    // ========== Nested ToolCall class ==========

    public static class ToolCall {
        private final String id;
        private final String name;
        private final String arguments; // JSON string of arguments

        public ToolCall(String id, String name, String arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getArguments() { return arguments; }

        /**
         * Parse arguments as a JsonNode.
         */
        public JsonNode getArgumentsAsJson() {
            try {
                return mapper.readTree(arguments);
            } catch (Exception e) {
                return mapper.createObjectNode();
            }
        }
    }
}
