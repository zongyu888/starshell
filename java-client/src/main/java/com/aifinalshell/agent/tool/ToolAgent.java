package com.aifinalshell.agent.tool;

import com.aifinalshell.ai.AiServiceClient;
import com.aifinalshell.ai.ContextCompactor;
import com.aifinalshell.config.AiConfigManager;
import com.aifinalshell.provider.AiProvider;
import com.aifinalshell.provider.ChatMessage;
import com.aifinalshell.provider.ChatResult;
import com.aifinalshell.provider.ProviderRegistry;
import com.aifinalshell.ssh.SshConnectionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;

/**
 * AI Agent that can call tools in a multi-step loop.
 *
 * Supports two execution paths:
 * 1. Native function calling: when provider.supportsFunctionCalling() is true,
 *    uses provider.chatWithTools() with OpenAI-format tools JSON.
 * 2. Text parsing fallback: when the provider does not support function calling,
 *    injects tool descriptions into the system prompt and parses JSON tool calls
 *    from the AI's text response.
 *
 * Tool results are fed back as proper tool messages (ChatMessage.tool()) for
 * native function calling, or as user messages for the fallback path.
 *
 * Supports streaming token output via executeStream() with simulated streaming
 * for intermediate and final responses.
 */
public class ToolAgent {
    private static final Logger logger = LoggerFactory.getLogger(ToolAgent.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 构造"已达最大工具步数"提示，注入最后一步让 AI 收尾总结。
     * 步数上限从 AiConfigManager.getMaxToolSteps() 读取（默认 50，可调大近似不限制）。
     */
    private static String buildMaxStepsPrompt(int maxSteps) {
        return "\n\n[SYSTEM NOTICE] You have reached the maximum number of tool call steps (" +
                maxSteps + "). Please summarize the current progress and provide a final answer " +
                "based on the information gathered so far. Do not attempt any more tool calls.";
    }

    /**
     * 连续工具调用硬失败次数阈值。超过即判定 AI 卡住，强制停止工具调用并向用户求助。
     * 硬失败定义见 {@link #isHardToolFailure(String)}：明确的错误/权限拒绝/参数缺失等。
     * 注意：空输出（如 cd/mkdir 等正常无输出命令）不计入失败，避免误判。
     */
    private static final int STUCK_THRESHOLD = 3;

    /**
     * 构造"AI 已卡住"提示，注入对话让 AI 停止调用工具、向用户说明问题并求助。
     */
    private static String buildStuckPrompt(int failures, List<String> recentErrors) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n[SYSTEM NOTICE - STUCK DETECTED] ");
        sb.append("You have failed ").append(failures).append(" consecutive tool calls. ");
        sb.append("This indicates you do not have enough information or capability to complete the task autonomously. ");
        sb.append("You MUST NOT call any more tools. Instead, respond directly to the user with:\n");
        sb.append("1. A clear explanation of what you were trying to do\n");
        sb.append("2. The specific problem or missing information that blocked you\n");
        sb.append("3. A concrete question or request for the user to provide what you need\n\n");
        sb.append("Recent failures:\n");
        int limit = Math.min(recentErrors.size(), STUCK_THRESHOLD);
        for (int i = recentErrors.size() - limit; i < recentErrors.size(); i++) {
            sb.append("- ").append(recentErrors.get(i)).append("\n");
        }
        sb.append("\nDo NOT attempt any more tool calls. Respond to the user now.");
        return sb.toString();
    }

    /**
     * 判断工具执行结果是否为"硬失败"——明确的无产出错误，应计入卡住检测。
     * 空输出不计入失败（cd/mkdir/sleep 等命令正常无输出），避免误判。
     */
    private static boolean isHardToolFailure(String result) {
        if (result == null) return true;
        String trimmed = result.trim();
        if (trimmed.isEmpty()) return false;
        return trimmed.startsWith("Error:")
                || trimmed.startsWith("Permission denied:")
                || trimmed.startsWith("Error executing tool")
                || trimmed.startsWith("Error: Unknown tool")
                || trimmed.startsWith("Error: Missing required parameter");
    }

    /**
     * 强制 AI 停止工具调用并向用户求助。当连续失败达到 {@link #STUCK_THRESHOLD} 时触发：
     * 1. 通过 onToken 向用户发出醒目的 ⚠️ 通知
     * 2. 注入 buildStuckPrompt 让 AI 明确停止工具调用
     * 3. 用不带 tools 的 provider.chat() 做最终调用（AI 无法再调用工具）
     * 4. 流式输出最终答复并返回；调用失败时返回兜底说明
     */
    private String forceFinalAnswer(AiProvider provider, String model,
                                    List<ChatMessage> messages,
                                    String apiKey, String baseUrl,
                                    double temperature, int maxTokens,
                                    Consumer<String> onToken,
                                    int failures, List<String> recentErrors,
                                    Consumer<String> onPersistedText) {
        if (onToken != null) {
            onToken.accept("\n\n⚠️ **AI 已连续失败 " + failures + " 次，可能无法自主完成任务。**\n");
            onToken.accept("正在停止工具调用并向你说明问题，请查看下方说明并提供所需信息：\n\n");
        }
        logger.warn("ToolAgent 触发卡住检测：连续失败 {} 次。最近错误：{}", failures, recentErrors);

        messages.add(ChatMessage.user(buildStuckPrompt(failures, recentErrors)));

        try {
            String finalResponse = provider.chat(model, messages, apiKey, baseUrl,
                    temperature, maxTokens);
            if (onToken != null && finalResponse != null) {
                streamText(finalResponse, onToken);
            }
            String reply = finalResponse != null ? finalResponse : "";
            if (onPersistedText != null && !reply.isEmpty()) {
                onPersistedText.accept(reply);
            }
            return reply;
        } catch (Exception e) {
            logger.error("forceFinalAnswer 调用失败", e);
            String fallback = "AI 在尝试完成任务时遇到持续性问题（连续失败 " + failures
                    + " 次）并已停止工具调用。请查看上方的工具调用记录，或提供更多信息后重试。";
            if (onToken != null) onToken.accept(fallback);
            if (onPersistedText != null) {
                onPersistedText.accept(fallback);
            }
            return fallback;
        }
    }

    private final ToolRegistry toolRegistry = ToolRegistry.getInstance();

    // ========== Public API: Synchronous Execution ==========

    /**
     * Execute a user request with tool calling capability.
     * Auto-approves ASK permissions (no UI callback).
     *
     * @return the final AI response after all tool calls are resolved
     */
    public String execute(String userMessage, String systemPrompt, ToolContext context) {
        return execute(userMessage, systemPrompt, context, null, null);
    }

    /**
     * Execute a user request with tool calling capability and optional permission callback.
     *
     * @param askCallback callback for ASK permission requests; if null, ASK permissions
     *                    are auto-approved (non-BLOCK results proceed)
     * @return the final AI response after all tool calls are resolved
     */
    public String execute(String userMessage, String systemPrompt, ToolContext context,
                          Consumer<ToolPermission.PermissionRequest> askCallback) {
        return execute(userMessage, systemPrompt, context, askCallback, null);
    }

    /**
     * Execute with conversation history (prior turns) for multi-turn memory.
     * History is inserted between the system prompt and the current user message,
     * giving the AI recall of previous conversation turns.
     *
     * @param history prior conversation messages (user/assistant turns, excluding system
     *                and the current userMessage); null/empty for single-turn
     */
    public String execute(String userMessage, String systemPrompt, ToolContext context,
                          Consumer<ToolPermission.PermissionRequest> askCallback,
                          List<ChatMessage> history) {
        return executeInternal(userMessage, systemPrompt, context, null, askCallback, history, null);
    }

    // ========== Public API: Streaming Execution ==========

    /**
     * Streaming version with tool support.
     * Runs the tool-calling loop in a background thread and streams the final
     * response via onToken callback.
     */
    public void executeStream(String userMessage, String systemPrompt, ToolContext context,
                               Consumer<String> onToken, Runnable onComplete,
                               Consumer<Exception> onError) {
        executeStream(userMessage, systemPrompt, context, onToken, onComplete, onError, null, null, null);
    }

    /**
     * Streaming version with tool support and permission callback.
     *
     * @param askCallback callback for ASK permission requests
     */
    public void executeStream(String userMessage, String systemPrompt, ToolContext context,
                               Consumer<String> onToken, Runnable onComplete,
                               Consumer<Exception> onError,
                               Consumer<ToolPermission.PermissionRequest> askCallback) {
        executeStream(userMessage, systemPrompt, context, onToken, onComplete, onError, askCallback, null, null);
    }

    /**
     * Streaming version with tool support, permission callback, conversation history,
     * and a separate persisted-text callback.
     * History gives the AI multi-turn memory of the conversation.
     *
     * @param history          prior conversation messages (user/assistant turns); null/empty for single-turn
     * @param onPersistedText  receives ONLY the final natural-language reply (no tool/status logs)
     *                         so the caller can persist it as the assistant message; null to ignore
     */
    public void executeStream(String userMessage, String systemPrompt, ToolContext context,
                               Consumer<String> onToken, Runnable onComplete,
                               Consumer<Exception> onError,
                               Consumer<ToolPermission.PermissionRequest> askCallback,
                               List<ChatMessage> history,
                               Consumer<String> onPersistedText) {
        Thread thread = new Thread(() -> {
            try {
                executeInternal(userMessage, systemPrompt, context, onToken, askCallback, history, onPersistedText);
                onComplete.run();
            } catch (Exception e) {
                logger.error("Streaming execution failed", e);
                onError.accept(e);
            }
        }, "ToolAgent-Stream");
        thread.setDaemon(true);
        thread.start();
    }

    // ========== Core Execution Logic ==========

    /**
     * Internal execution method shared by execute() and executeStream().
     *
     * @param onToken     optional streaming callback; if non-null, intermediate and
     *                    final responses are streamed via this callback
     * @param askCallback optional permission callback for ASK requests
     * @return the final AI response
     */
    private String executeInternal(String userMessage, String systemPrompt, ToolContext context,
                                    Consumer<String> onToken,
                                    Consumer<ToolPermission.PermissionRequest> askCallback,
                                    List<ChatMessage> history,
                                    Consumer<String> onPersistedText) {
        AiConfigManager config = AiConfigManager.getInstance();
        String providerName = config.getActiveProvider();
        String model = config.getActiveModel();

        AiProvider provider = ProviderRegistry.getInstance().getProvider(providerName);
        if (provider == null) {
            String error = "Error: Provider '" + providerName + "' not found.";
            if (onToken != null) onToken.accept(error);
            return error;
        }

        String apiKey = config.getApiKey(providerName);
        String baseUrl = config.getBaseUrl(providerName);
        double temperature = config.getTemperature();
        int maxTokens = config.getMaxTokens();
        // 工具调用最大步数（可配置，默认 50；调大即近似不限制，让 AI 自主完成多步任务）
        int maxSteps = config.getMaxToolSteps();

        // Choose execution path based on provider capability
        if (provider.supportsFunctionCalling()) {
            return executeWithFunctionCalling(provider, model, systemPrompt, userMessage,
                    context, apiKey, baseUrl, temperature, maxTokens, maxSteps, onToken, askCallback, history, onPersistedText);
        } else {
            return executeWithTextParsing(provider, model, systemPrompt, userMessage,
                    context, apiKey, baseUrl, temperature, maxTokens, maxSteps, onToken, askCallback, history, onPersistedText);
        }
    }

    // ========== Native Function Calling Path ==========

    /**
     * Execute using native function calling (OpenAI tool_use API).
     * Tools are passed as a JSON array, and the API returns structured tool calls.
     */
    private String executeWithFunctionCalling(AiProvider provider, String model,
                                               String systemPrompt, String userMessage,
                                               ToolContext context,
                                               String apiKey, String baseUrl,
                                               double temperature, int maxTokens, int maxSteps,
                                               Consumer<String> onToken,
                                               Consumer<ToolPermission.PermissionRequest> askCallback,
                                               List<ChatMessage> history,
                                               Consumer<String> onPersistedText) {
        // Build initial messages (system prompt does NOT include tool descriptions
        // since tools are passed natively via the API)
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        // 对话记忆：在 system 之后、当前用户消息之前插入历史轮次，使 AI 能回忆前文
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }
        messages.add(ChatMessage.user(userMessage));

        // Task 6: 长对话压缩——在进入工具调用循环前，若 token 用量超过上下文窗口 70% 则压缩
        int contextWindow = AiServiceClient.getInstance().getModelContextWindow(model);
        if (ContextCompactor.needsCompaction(messages, contextWindow)) {
            messages = ContextCompactor.compact(messages, provider, model, apiKey, baseUrl, contextWindow);
        }

        // Get tools JSON in OpenAI format
        JsonNode toolsJson = toolRegistry.generateToolsJson();

        // Stuck detection: track consecutive hard failures across all steps
        int consecutiveFailures = 0;
        List<String> recentErrors = new ArrayList<>();

        for (int step = 0; step < maxSteps; step++) {
            try {
                // Inject max-steps notice on the last step
                if (step == maxSteps - 1) {
                    messages.add(ChatMessage.user(buildMaxStepsPrompt(maxSteps)));
                }

                // Call API with tools
                ChatResult result = provider.chatWithTools(model, messages, toolsJson,
                        apiKey, baseUrl, temperature, maxTokens);

                String content = result.getContent();
                boolean hasToolCalls = result.hasToolCalls();

                // If no tool calls, this is the final response
                if (!hasToolCalls) {
                    if (onToken != null && content != null) {
                        streamText(content, onToken);
                    }
                    String reply = content != null ? content : "";
                    if (onPersistedText != null && !reply.isEmpty()) {
                        onPersistedText.accept(reply);
                    }
                    return reply;
                }

                // Stream any content the AI provided alongside tool calls
                if (onToken != null && content != null && !content.isEmpty()) {
                    streamText(content, onToken);
                }

                // Add assistant message with tool calls
                messages.add(ChatMessage.assistant(content, result.getToolCalls()));

                // Process each tool call
                for (ChatMessage.ToolCall toolCall : result.getToolCalls()) {
                    String toolName = toolCall.getName();
                    Map<String, Object> args = parseArgsFromJson(toolCall.getArgumentsAsJson());

                    // Emit status message for streaming
                    if (onToken != null) {
                        onToken.accept("\n\n[Executing tool: " + toolName + "]\n");
                    }

                    // Execute tool with permission check
                    String toolResult = executeToolWithPermission(
                            toolName, args, context, askCallback);

                    // Add tool result as a proper tool message
                    messages.add(ChatMessage.tool(toolCall.getId(), toolName, toolResult));

                    // Emit result summary for streaming
                    if (onToken != null) {
                        String summary = toolResult.length() > 200
                                ? toolResult.substring(0, 200) + "...\n"
                                : toolResult + "\n";
                        onToken.accept("[Result]: " + summary);
                    }

                    // Stuck detection: count consecutive hard failures; force final
                    // answer (stop tools + notify user) when threshold is reached.
                    if (isHardToolFailure(toolResult)) {
                        consecutiveFailures++;
                        recentErrors.add(toolName + " -> " + toolResult.trim());
                        if (consecutiveFailures >= STUCK_THRESHOLD) {
                            return forceFinalAnswer(provider, model, messages, apiKey, baseUrl,
                                    temperature, maxTokens, onToken, consecutiveFailures, recentErrors, onPersistedText);
                        }
                    } else {
                        consecutiveFailures = 0;
                    }
                }

            } catch (Exception e) {
                logger.error("Function calling step {} failed", step, e);
                String error = "Error during tool execution: " + e.getMessage();
                if (onToken != null) onToken.accept(error);
                return error;
            }
        }

        String limitMsg = "Reached maximum tool call steps (" + maxSteps + "). " +
                "The task may require more steps to complete fully.";
        if (onToken != null) onToken.accept(limitMsg);
        if (onPersistedText != null) onPersistedText.accept(limitMsg);
        return limitMsg;
    }

    // ========== Text Parsing Fallback Path ==========

    /**
     * Execute using text-based tool call parsing (fallback for providers without
     * native function calling support).
     * Tool descriptions are injected into the system prompt, and tool calls are
     * parsed from JSON in the AI's text response.
     */
    private String executeWithTextParsing(AiProvider provider, String model,
                                           String systemPrompt, String userMessage,
                                           ToolContext context,
                                           String apiKey, String baseUrl,
                                           double temperature, int maxTokens, int maxSteps,
                                           Consumer<String> onToken,
                                           Consumer<ToolPermission.PermissionRequest> askCallback,
                                           List<ChatMessage> history,
                                           Consumer<String> onPersistedText) {
        // Build system prompt with tool descriptions
        String fullSystemPrompt = systemPrompt + "\n\n" + toolRegistry.generateToolsPrompt();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(fullSystemPrompt));
        // 对话记忆：在 system 之后、当前用户消息之前插入历史轮次
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }
        messages.add(ChatMessage.user(userMessage));

        // Task 6: 长对话压缩——在进入工具调用循环前，若 token 用量超过上下文窗口 70% 则压缩
        int contextWindow = AiServiceClient.getInstance().getModelContextWindow(model);
        if (ContextCompactor.needsCompaction(messages, contextWindow)) {
            messages = ContextCompactor.compact(messages, provider, model, apiKey, baseUrl, contextWindow);
        }

        // Stuck detection: track consecutive hard failures across all steps
        int consecutiveFailures = 0;
        List<String> recentErrors = new ArrayList<>();

        for (int step = 0; step < maxSteps; step++) {
            try {
                // Inject max-steps notice on the last step
                if (step == maxSteps - 1) {
                    messages.add(ChatMessage.user(buildMaxStepsPrompt(maxSteps)));
                }

                String response = provider.chat(model, messages, apiKey, baseUrl,
                        temperature, maxTokens);

                // Check for tool call in response
                ParsedToolCall toolCall = parseToolCall(response);

                if (toolCall == null) {
                    // No tool call - stream and return final response
                    if (onToken != null) {
                        streamText(response, onToken);
                    }
                    if (onPersistedText != null && response != null) {
                        onPersistedText.accept(response);
                    }
                    return response;
                }

                // Add assistant response to message history
                messages.add(ChatMessage.assistant(response));

                // Emit status message for streaming
                if (onToken != null) {
                    onToken.accept("\n\n[Executing tool: " + toolCall.toolName + "]\n");
                }

                // Execute tool with permission check
                String toolResult = executeToolWithPermission(
                        toolCall.toolName, toolCall.args, context, askCallback);

                // Feed result back to AI as user message (fallback mode)
                messages.add(ChatMessage.user(
                        "Tool '" + toolCall.toolName + "' returned:\n" + toolResult +
                        "\n\nPlease analyze this result and continue, or provide final answer."));

                // Emit result summary for streaming
                if (onToken != null) {
                    String summary = toolResult.length() > 200
                            ? toolResult.substring(0, 200) + "...\n"
                            : toolResult + "\n";
                    onToken.accept("[Result]: " + summary);
                }

                // Stuck detection: count consecutive hard failures; force final
                // answer (stop tools + notify user) when threshold is reached.
                if (isHardToolFailure(toolResult)) {
                    consecutiveFailures++;
                    recentErrors.add(toolCall.toolName + " -> " + toolResult.trim());
                    if (consecutiveFailures >= STUCK_THRESHOLD) {
                        return forceFinalAnswer(provider, model, messages, apiKey, baseUrl,
                                temperature, maxTokens, onToken, consecutiveFailures, recentErrors, onPersistedText);
                    }
                } else {
                    consecutiveFailures = 0;
                }

            } catch (Exception e) {
                logger.error("Text parsing step {} failed", step, e);
                String error = "Error during tool execution: " + e.getMessage();
                if (onToken != null) onToken.accept(error);
                return error;
            }
        }

        String limitMsg = "Reached maximum tool call steps (" + maxSteps + "). " +
                "The task may require more steps to complete fully.";
        if (onToken != null) onToken.accept(limitMsg);
        if (onPersistedText != null) onPersistedText.accept(limitMsg);
        return limitMsg;
    }

    // ========== Tool Execution with Permission Check ==========

    /**
     * Execute a single tool call with permission checking.
     * Uses checkWithCallback when askCallback is provided, otherwise uses
     * the non-async check() method (auto-approves ASK results).
     */
    private String executeToolWithPermission(String toolName, Map<String, Object> args,
                                              ToolContext context,
                                              Consumer<ToolPermission.PermissionRequest> askCallback) {
        // Check permission
        boolean allowed;
        if (askCallback != null) {
            // Use async permission check with callback
            allowed = ToolPermission.checkWithCallback(toolName, args, askCallback);
        } else {
            // Non-async: auto-approve ASK, only block on BLOCK
            ToolPermission.PermissionResult perm = ToolPermission.check(toolName, args);
            allowed = (perm != ToolPermission.PermissionResult.BLOCK);
        }

        if (!allowed) {
            String warning = ToolPermission.getWarning(toolName, args,
                    ToolPermission.PermissionResult.BLOCK);
            return "Permission denied: " + (warning != null ? warning : "Action blocked by security policy.");
        }

        // Execute the tool
        ToolDefinition tool = toolRegistry.getTool(toolName);
        if (tool == null) {
            return "Error: Unknown tool '" + toolName + "'";
        }

        // Required-argument validation: prevents AI from calling tools with missing
        // required params (e.g. read_file without filePath), which previously caused
        // silent failures and tool-loop spinning. Guides the AI to ask the user.
        JsonNode params = tool.getParameters();
        if (params != null && params.has("required") && params.get("required").isArray()) {
            JsonNode requiredArr = params.get("required");
            List<String> missing = new ArrayList<>();
            for (JsonNode req : requiredArr) {
                String reqName = req.asText();
                Object val = args.get(reqName);
                if (val == null || (val instanceof String && ((String) val).isEmpty())) {
                    missing.add(reqName);
                }
            }
            if (!missing.isEmpty()) {
                return "Error: Missing required parameter(s) " + missing
                        + " for tool '" + toolName + "'. If you do not have this information, "
                        + "ask the user to provide it before retrying.";
            }
        }

        // SSH 连接预检：所有内置工具都依赖远程 SSH 会话。若未连接则返回友好提示，
        // 引导 AI 告知用户先连接服务器，而非抛 "SSH会话未连接: 1_1" 这类技术性错误。
        String sshKey = context.getSshKey();
        if (!SshConnectionManager.getInstance().isConnected(sshKey)) {
            return "Error: SSH session is not connected. The server is offline or has not been connected yet. "
                    + "Please tell the user to click the 'Connect' button in the toolbar to connect to the server first, "
                    + "then retry. Do NOT keep calling tools that require an SSH connection.";
        }

        try {
            return tool.getExecutor().execute(args, context);
        } catch (Exception e) {
            logger.error("Tool execution failed: {}", toolName, e);
            return "Error executing tool '" + toolName + "': " + e.getMessage();
        }
    }

    // ========== Streaming Helper ==========

    /**
     * Simulate streaming by emitting text in word-sized chunks.
     */
    private void streamText(String text, Consumer<String> onToken) {
        if (text == null || text.isEmpty()) return;
        // Split on whitespace boundaries, keeping the whitespace
        String[] chunks = text.split("(?<=\\s)");
        for (String chunk : chunks) {
            onToken.accept(chunk);
        }
    }

    // ========== JSON Parsing Helpers ==========

    /**
     * Parse a JsonNode (from native function calling arguments) into a Map.
     */
    private Map<String, Object> parseArgsFromJson(JsonNode argsNode) {
        Map<String, Object> args = new HashMap<>();
        if (argsNode == null || !argsNode.isObject()) {
            return args;
        }
        argsNode.fields().forEachRemaining(entry -> {
            JsonNode val = entry.getValue();
            if (val == null || val.isNull()) {
                args.put(entry.getKey(), null);
            } else if (val.isInt()) {
                args.put(entry.getKey(), val.asInt());
            } else if (val.isLong()) {
                args.put(entry.getKey(), val.asLong());
            } else if (val.isDouble()) {
                args.put(entry.getKey(), val.asDouble());
            } else if (val.isBoolean()) {
                args.put(entry.getKey(), val.asBoolean());
            } else {
                args.put(entry.getKey(), val.asText());
            }
        });
        return args;
    }

    /**
     * Parse tool call from AI text response (fallback path).
     * Looks for JSON like: {"tool": "execute_shell", "args": {"command": "free -h"}}
     */
    private ParsedToolCall parseToolCall(String response) {
        try {
            String json = response;
            // Try to extract JSON from code blocks
            if (response.contains("```json")) {
                json = response.split("```json")[1].split("```")[0].trim();
            } else if (response.contains("```")) {
                json = response.split("```")[1].split("```")[0].trim();
            }

            // Try parsing as JSON
            JsonNode root;
            try {
                root = objectMapper.readTree(json);
            } catch (Exception e) {
                // Try finding JSON object in the response
                int start = response.indexOf('{');
                int end = response.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    root = objectMapper.readTree(response.substring(start, end + 1));
                } else {
                    return null;
                }
            }

            if (root.has("tool") && root.has("args")) {
                String toolName = root.get("tool").asText();
                JsonNode argsNode = root.get("args");

                Map<String, Object> args = new HashMap<>();
                if (argsNode.isObject()) {
                    argsNode.fields().forEachRemaining(entry -> {
                        JsonNode val = entry.getValue();
                        if (val == null || val.isNull()) {
                            args.put(entry.getKey(), null);
                        } else if (val.isInt()) {
                            args.put(entry.getKey(), val.asInt());
                        } else if (val.isLong()) {
                            args.put(entry.getKey(), val.asLong());
                        } else if (val.isDouble()) {
                            args.put(entry.getKey(), val.asDouble());
                        } else if (val.isBoolean()) {
                            args.put(entry.getKey(), val.asBoolean());
                        } else {
                            args.put(entry.getKey(), val.asText());
                        }
                    });
                }

                return new ParsedToolCall(toolName, args);
            }
        } catch (Exception e) {
            logger.debug("Failed to parse tool call: {}", e.getMessage());
        }
        return null;
    }

    // ========== Inner Classes ==========

    /**
     * Parsed tool call from text response (fallback path).
     */
    private static class ParsedToolCall {
        final String toolName;
        final Map<String, Object> args;

        ParsedToolCall(String toolName, Map<String, Object> args) {
            this.toolName = toolName;
            this.args = args;
        }
    }
}
