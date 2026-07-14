package com.aifinalshell.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Defines an AI-callable tool with its schema and executor.
 */
public class ToolDefinition {
    private final String id;
    private final String name;
    private final String description;
    private final JsonNode parameters; // JSON Schema
    private final ToolExecutor executor;

    public ToolDefinition(String id, String name, String description,
                          JsonNode parameters, ToolExecutor executor) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.executor = executor;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public JsonNode getParameters() { return parameters; }
    public ToolExecutor getExecutor() { return executor; }

    /**
     * Generate tool definition for inclusion in system prompt.
     */
    public String toPromptString() {
        return String.format("- %s: %s\n  Parameters: %s",
                name, description, parameters != null ? parameters.toString() : "{}");
    }
}
