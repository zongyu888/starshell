package com.aifinalshell.agent.tool;

import java.util.Map;

/**
 * Interface for executing an AI-callable tool.
 */
public interface ToolExecutor {
    /**
     * Execute the tool with given arguments.
     *
     * @param args    tool arguments from AI
     * @param context execution context (SSH session, etc.)
     * @return tool output string
     * @throws Exception on execution failure
     */
    String execute(Map<String, Object> args, ToolContext context) throws Exception;
}
