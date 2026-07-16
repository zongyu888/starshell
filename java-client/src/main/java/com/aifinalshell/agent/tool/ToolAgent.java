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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ToolAgent {
    private static final Logger logger = LoggerFactory.getLogger(ToolAgent.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static String buildMaxStepsPrompt(int maxSteps) {
        return "\n\n[SYSTEM NOTICE] You have reached the maximum number of tool call steps (" +
                maxSteps + "). Please summarize the current progress and provide a final answer " +
                "based on the information gathered so far. Do not attempt any more tool calls.";
    }

    private static final int STUCK_THRESHOLD = 3;

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

    private static boolean isHardToolFailure(String result) {
        if (result == null) return true;
        String trimmed = result.trim();
        if (trimmed.isEmpty()) return false;
        return trimmed.startsWith("Error:")
                || trimmed.startsWith("Permission denied:")
                || trimmed.startsWith("Error executing tool")
                || trimmed.startsWith("Error: Unknown tool")
                || trimmed.startsWith("Error: Missing required parameter")
                || trimmed.contains("AUTH_FAILED")
                || trimmed.contains("SSH session is not connected")
                || trimmed.matches("(?s).*\\[exit code: (?!0\\])\\d+\\].*");
    }

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

    public String execute(String userMessage, String systemPrompt, ToolContext context) {
        return execute(userMessage, systemPrompt, context, null, null);
    }

    public String execute(String userMessage, String systemPrompt, ToolContext context,
                          Consumer<ToolPermission.PermissionRequest> askCallback) {
        return execute(userMessage, systemPrompt, context, askCallback, null);
    }

    public String execute(String userMessage, String systemPrompt, ToolContext context,
                          Consumer<ToolPermission.PermissionRequest> askCallback,
                          List<ChatMessage> history) {
        return executeInternal(userMessage, systemPrompt, context, null, askCallback, history, null);
    }

    public Thread executeStream(String userMessage, String systemPrompt, ToolContext context,
                               Consumer<String> onToken, Runnable onComplete,
                               Consumer<Exception> onError) {
        return executeStream(userMessage, systemPrompt, context, onToken, onComplete, onError, null, null, null);
    }

    public Thread executeStream(String userMessage, String systemPrompt, ToolContext context,
                               Consumer<String> onToken, Runnable onComplete,
                               Consumer<Exception> onError,
                               Consumer<ToolPermission.PermissionRequest> askCallback) {
        return executeStream(userMessage, systemPrompt, context, onToken, onComplete, onError, askCallback, null, null);
    }

    /** 启动可取消的流式 Agent 任务并返回真正执行模型/工具调用的工作线程。 */
    public Thread executeStream(String userMessage, String systemPrompt, ToolContext context,
                               Consumer<String> onToken, Runnable onComplete,
                               Consumer<Exception> onError,
                               Consumer<ToolPermission.PermissionRequest> askCallback,
                               List<ChatMessage> history,
                               Consumer<String> onPersistedText) {
        Thread thread = new Thread(() -> {
            try {
                executeInternal(userMessage, systemPrompt, context, onToken, askCallback, history, onPersistedText);
                if (!Thread.currentThread().isInterrupted()) {
                    onComplete.run();
                }
            } catch (java.util.concurrent.CancellationException e) {
                logger.debug("ToolAgent stream cancelled");
            } catch (Exception e) {
                logger.error("Streaming execution failed", e);
                onError.accept(e);
            }
        }, "ToolAgent-Stream");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void throwIfCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("AI generation cancelled");
        }
    }

    private static void rethrowIfCancelled(Exception e) {
        if (e instanceof InterruptedException
                || e instanceof java.util.concurrent.CancellationException
                || Thread.currentThread().isInterrupted()) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("AI generation cancelled");
        }
    }

    private String executeInternal(String userMessage, String systemPrompt, ToolContext context,
                                    Consumer<String> onToken,
                                    Consumer<ToolPermission.PermissionRequest> askCallback,
                                    List<ChatMessage> history,
                                    Consumer<String> onPersistedText) {
        throwIfCancelled();
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
        int maxSteps = config.getMaxToolSteps();

        if (provider.supportsFunctionCalling()) {
            return executeWithFunctionCalling(provider, model, systemPrompt, userMessage,
                    context, apiKey, baseUrl, temperature, maxTokens, maxSteps, onToken, askCallback, history, onPersistedText);
        } else {
            return executeWithTextParsing(provider, model, systemPrompt, userMessage,
                    context, apiKey, baseUrl, temperature, maxTokens, maxSteps, onToken, askCallback, history, onPersistedText);
        }
    }

    private String executeWithFunctionCalling(AiProvider provider, String model,
                                               String systemPrompt, String userMessage,
                                               ToolContext context,
                                               String apiKey, String baseUrl,
                                               double temperature, int maxTokens, int maxSteps,
                                               Consumer<String> onToken,
                                               Consumer<ToolPermission.PermissionRequest> askCallback,
                                               List<ChatMessage> history,
                                               Consumer<String> onPersistedText) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }
        messages.add(ChatMessage.user(userMessage));

        int contextWindow = AiServiceClient.getInstance().getModelContextWindow(model);
        if (ContextCompactor.needsCompaction(messages, contextWindow)) {
            messages = ContextCompactor.compact(messages, provider, model, apiKey, baseUrl, contextWindow);
        }

        JsonNode toolsJson = toolRegistry.generateToolsJson();

        int consecutiveFailures = 0;
        List<String> recentErrors = new ArrayList<>();

        for (int step = 0; step < maxSteps; step++) {
            throwIfCancelled();
            try {
                if (step == maxSteps - 1) {
                    messages.add(ChatMessage.user(buildMaxStepsPrompt(maxSteps)));
                }

                if (onToken != null) {
                    onToken.accept("\n\n🤔 ");
                }

                ChatResult result = callWithToolsStream(provider, model, messages, toolsJson,
                        apiKey, baseUrl, temperature, maxTokens, onToken);

                if (result == null) {
                    String err = "Error: AI request failed (no response)";
                    if (onToken != null) onToken.accept("\n❌ " + err + "\n");
                    return err;
                }

                String content = result.getContent();
                boolean hasToolCalls = result.hasToolCalls();

                if (!hasToolCalls) {
                    String textContent = content != null ? content : "";
                    List<ParsedToolCall> textToolCalls = parseAllToolCalls(textContent);
                    if (!textToolCalls.isEmpty()) {
                        hasToolCalls = true;
                        messages.add(ChatMessage.assistant(textContent));

                        StringBuilder toolResultsSummary = new StringBuilder();
                        for (ParsedToolCall tc : textToolCalls) {
                            if (onToken != null) {
                                onToken.accept("\n\n🔧 **执行工具**: `" + tc.toolName + "`\n");
                                if (tc.args.containsKey("command")) {
                                    onToken.accept("```bash\n" + tc.args.get("command") + "\n```\n");
                                }
                            }

                            String toolResult = executeToolWithPermission(
                                    tc.toolName, tc.args, context, askCallback);
                            throwIfCancelled();

                            toolResultsSummary.append("Tool '").append(tc.toolName).append("' returned:\n")
                                    .append(toolResult).append("\n\n");

                            if (onToken != null) {
                                String displayResult;
                                if (toolResult.length() > 500) {
                                    displayResult = toolResult.substring(0, 500) + "\n... (输出过长，已截断，完整输出见终端)\n";
                                } else {
                                    displayResult = toolResult;
                                }
                                if (!displayResult.trim().isEmpty() && !displayResult.trim().equals("(no output)")) {
                                    onToken.accept("📤 **结果**:\n```\n" + displayResult + "\n```\n");
                                } else {
                                    onToken.accept("✅ 命令已执行（无输出或输出已在终端显示）\n");
                                }
                            }

                            if (isHardToolFailure(toolResult)) {
                                consecutiveFailures++;
                                recentErrors.add(tc.toolName + " -> " + toolResult.trim());
                                if (consecutiveFailures >= STUCK_THRESHOLD) {
                                    messages.add(ChatMessage.user(toolResultsSummary.toString()
                                            + "Please analyze these results and continue, or provide final answer."));
                                    return forceFinalAnswer(provider, model, messages, apiKey, baseUrl,
                                            temperature, maxTokens, onToken, consecutiveFailures, recentErrors, onPersistedText);
                                }
                            } else {
                                consecutiveFailures = 0;
                            }
                        }

                        messages.add(ChatMessage.user(
                                toolResultsSummary.toString()
                                        + "Please analyze these results and continue with more tool calls if needed, "
                                        + "or provide a final answer to the user."));
                        continue;
                    }

                    String text = textContent;
                    if (onToken != null && !text.isEmpty()) {
                        if (text.startsWith("\n\n🤔")) {
                            text = text.substring("\n\n🤔".length());
                        }
                    }
                    String reply = textContent.trim();
                    if (onPersistedText != null && !reply.isEmpty()) {
                        onPersistedText.accept(reply);
                    }
                    return reply;
                }

                messages.add(ChatMessage.assistant(content, result.getToolCalls()));

                for (ChatMessage.ToolCall toolCall : result.getToolCalls()) {
                    String toolName = toolCall.getName();
                    Map<String, Object> args = parseArgsFromJson(toolCall.getArgumentsAsJson());

                    if (onToken != null) {
                        onToken.accept("\n\n🔧 **执行工具**: `" + toolName + "`\n");
                        if (args.containsKey("command")) {
                            onToken.accept("```bash\n" + args.get("command") + "\n```\n");
                        }
                    }

                    String toolResult = executeToolWithPermission(
                            toolName, args, context, askCallback);
                    throwIfCancelled();

                    messages.add(ChatMessage.tool(toolCall.getId(), toolName, toolResult));

                    if (onToken != null) {
                        String displayResult;
                        if (toolResult.length() > 500) {
                            String preview = toolResult.substring(0, 500);
                            displayResult = preview + "\n... (输出过长，已截断，完整输出见终端)\n";
                        } else {
                            displayResult = toolResult;
                        }
                        if (!displayResult.trim().isEmpty() && !displayResult.trim().equals("(no output)")) {
                            onToken.accept("📤 **结果**:\n```\n" + displayResult + "\n```\n");
                        } else {
                            onToken.accept("✅ 命令已执行（无输出或输出已在终端显示）\n");
                        }
                    }

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
                rethrowIfCancelled(e);
                logger.error("Function calling step {} failed", step, e);
                String error = "Error during tool execution: " + e.getMessage();
                if (onToken != null) onToken.accept("\n❌ " + error + "\n");
                return error;
            }
        }

        String limitMsg = "Reached maximum tool call steps (" + maxSteps + "). " +
                "The task may require more steps to complete fully.";
        if (onToken != null) onToken.accept("\n⚠️ " + limitMsg + "\n");
        if (onPersistedText != null) onPersistedText.accept(limitMsg);
        return limitMsg;
    }

    private String executeWithTextParsing(AiProvider provider, String model,
                                           String systemPrompt, String userMessage,
                                           ToolContext context,
                                           String apiKey, String baseUrl,
                                           double temperature, int maxTokens, int maxSteps,
                                           Consumer<String> onToken,
                                           Consumer<ToolPermission.PermissionRequest> askCallback,
                                           List<ChatMessage> history,
                                           Consumer<String> onPersistedText) {
        String fullSystemPrompt = systemPrompt + "\n\n" + toolRegistry.generateToolsPrompt();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(fullSystemPrompt));
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }
        messages.add(ChatMessage.user(userMessage));

        int contextWindow = AiServiceClient.getInstance().getModelContextWindow(model);
        if (ContextCompactor.needsCompaction(messages, contextWindow)) {
            messages = ContextCompactor.compact(messages, provider, model, apiKey, baseUrl, contextWindow);
        }

        int consecutiveFailures = 0;
        List<String> recentErrors = new ArrayList<>();

        for (int step = 0; step < maxSteps; step++) {
            throwIfCancelled();
            try {
                if (step == maxSteps - 1) {
                    messages.add(ChatMessage.user(buildMaxStepsPrompt(maxSteps)));
                }

                if (onToken != null) {
                    onToken.accept("\n\n🤔 ");
                }

                // 文本工具协议必须先缓冲再解析：避免把 <tool_call> 泄漏到聊天区，
                // 也避免无工具回复在 provider 流式回调后又被 streamText 重复渲染。
                String response = callStream(provider, model, messages, apiKey, baseUrl,
                        temperature, maxTokens, null);

                if (response == null) {
                    String err = "Error: AI request failed (no response)";
                    if (onToken != null) onToken.accept("\n❌ " + err + "\n");
                    return err;
                }

                List<ParsedToolCall> toolCalls = parseAllToolCalls(response);

                if (toolCalls.isEmpty()) {
                    String cleaned = response;
                    if (cleaned.startsWith("\n\n🤔")) {
                        cleaned = cleaned.substring("\n\n🤔".length());
                    }
                    cleaned = cleaned.trim();
                    if (onToken != null) {
                        streamText(cleaned, onToken);
                    }
                    if (onPersistedText != null && !cleaned.isEmpty()) {
                        onPersistedText.accept(cleaned);
                    }
                    return cleaned;
                }

                messages.add(ChatMessage.assistant(response));

                String narrative = stripToolCallMarkup(response).trim();
                if (onToken != null && !narrative.isEmpty()) {
                    streamText(narrative + "\n", onToken);
                }

                StringBuilder toolResultsSummary = new StringBuilder();
                for (ParsedToolCall tc : toolCalls) {
                    if (onToken != null) {
                        onToken.accept("\n\n🔧 **执行工具**: `" + tc.toolName + "`\n");
                        if (tc.args.containsKey("command")) {
                            onToken.accept("```bash\n" + tc.args.get("command") + "\n```\n");
                        }
                    }

                    String toolResult = executeToolWithPermission(
                            tc.toolName, tc.args, context, askCallback);
                    throwIfCancelled();

                    toolResultsSummary.append("Tool '").append(tc.toolName).append("' returned:\n")
                            .append(toolResult).append("\n\n");

                    if (onToken != null) {
                        String displayResult;
                        if (toolResult.length() > 500) {
                            displayResult = toolResult.substring(0, 500) + "\n... (输出过长，已截断)\n";
                        } else {
                            displayResult = toolResult;
                        }
                        if (!displayResult.trim().isEmpty() && !displayResult.trim().equals("(no output)")) {
                            onToken.accept("📤 **结果**:\n```\n" + displayResult + "\n```\n");
                        } else {
                            onToken.accept("✅ 命令已执行\n");
                        }
                    }

                    if (isHardToolFailure(toolResult)) {
                        consecutiveFailures++;
                        recentErrors.add(tc.toolName + " -> " + toolResult.trim());
                        if (consecutiveFailures >= STUCK_THRESHOLD) {
                            messages.add(ChatMessage.user(toolResultsSummary.toString()
                                    + "Please analyze these results and continue, or provide final answer."));
                            return forceFinalAnswer(provider, model, messages, apiKey, baseUrl,
                                    temperature, maxTokens, onToken, consecutiveFailures, recentErrors, onPersistedText);
                        }
                    } else {
                        consecutiveFailures = 0;
                    }
                }

                messages.add(ChatMessage.user(
                        toolResultsSummary.toString()
                                + "Please analyze these results and continue with more tool calls if needed, "
                                + "or provide a final answer to the user."));

            } catch (Exception e) {
                rethrowIfCancelled(e);
                logger.error("Text parsing step {} failed", step, e);
                String error = "Error during tool execution: " + e.getMessage();
                if (onToken != null) onToken.accept("\n❌ " + error + "\n");
                return error;
            }
        }

        String limitMsg = "Reached maximum tool call steps (" + maxSteps + "). " +
                "The task may require more steps to complete fully.";
        if (onToken != null) onToken.accept("\n⚠️ " + limitMsg + "\n");
        if (onPersistedText != null) onPersistedText.accept(limitMsg);
        return limitMsg;
    }

    private ChatResult callWithToolsStream(AiProvider provider, String model,
                                            List<ChatMessage> messages, JsonNode tools,
                                            String apiKey, String baseUrl,
                                            double temperature, int maxTokens,
                                            Consumer<String> onToken) throws Exception {
        throwIfCancelled();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ChatResult> resultRef = new AtomicReference<>();
        AtomicReference<Exception> errorRef = new AtomicReference<>();
        Thread ownerThread = Thread.currentThread();

        provider.chatWithToolsStream(model, messages, tools, apiKey, baseUrl, temperature, maxTokens,
                token -> {
                    if (!ownerThread.isInterrupted() && onToken != null) onToken.accept(token);
                },
                result -> {
                    resultRef.set(result);
                    latch.countDown();
                },
                error -> {
                    errorRef.set(error);
                    latch.countDown();
                });

        boolean completed = latch.await(300, TimeUnit.SECONDS);
        if (!completed) {
            throw new Exception("AI request timed out after 300 seconds");
        }
        if (errorRef.get() != null) {
            throw errorRef.get();
        }
        return resultRef.get();
    }

    private String callStream(AiProvider provider, String model,
                               List<ChatMessage> messages,
                               String apiKey, String baseUrl,
                               double temperature, int maxTokens,
                               Consumer<String> onToken) throws Exception {
        throwIfCancelled();
        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder sb = new StringBuilder();
        AtomicReference<Exception> errorRef = new AtomicReference<>();
        Thread ownerThread = Thread.currentThread();

        provider.chatStream(model, messages, apiKey, baseUrl, temperature, maxTokens,
                token -> {
                    sb.append(token);
                    if (!ownerThread.isInterrupted() && onToken != null) onToken.accept(token);
                },
                () -> latch.countDown(),
                error -> {
                    errorRef.set(error);
                    latch.countDown();
                });

        boolean completed = latch.await(300, TimeUnit.SECONDS);
        if (!completed) {
            throw new Exception("AI request timed out after 300 seconds");
        }
        if (errorRef.get() != null) {
            throw errorRef.get();
        }
        return sb.toString();
    }

    private String executeToolWithPermission(String toolName, Map<String, Object> args,
                                              ToolContext context,
                                              Consumer<ToolPermission.PermissionRequest> askCallback) {
        throwIfCancelled();
        boolean allowed;
        if (askCallback != null) {
            allowed = ToolPermission.checkWithCallback(toolName, args, askCallback);
        } else {
            ToolPermission.PermissionResult perm = ToolPermission.check(toolName, args);
            // Without a UI confirmation callback an ASK decision cannot be
            // satisfied safely.  Fail closed instead of silently treating it as
            // approval (the main JavaFX chat path always supplies the callback).
            allowed = (perm == ToolPermission.PermissionResult.ALLOW);
        }

        if (!allowed) {
            String warning = ToolPermission.getWarning(toolName, args,
                    ToolPermission.PermissionResult.BLOCK);
            return "Permission denied: " + (warning != null ? warning : "Action blocked by security policy.");
        }

        ToolDefinition tool = toolRegistry.getTool(toolName);
        if (tool == null) {
            return "Error: Unknown tool '" + toolName + "'";
        }

        JsonNode params = tool.getParameters();
        if (params != null && params.has("required") && params.get("required").isArray()) {
            JsonNode requiredArr = params.get("required");
            List<String> missing = new ArrayList<>();
            for (JsonNode req : requiredArr) {
                String reqName = req.asText();
                if ("dummy".equals(reqName)) continue;
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

        String sshKey = context.getSshKey();
        if (sshKey != null && !sshKey.isEmpty() && !sshKey.equals("0")
                && requiresSsh(toolName)) {
            if (!SshConnectionManager.getInstance().isConnected(sshKey)) {
                return "Error: SSH session is not connected. The server is offline or has not been connected yet. "
                        + "Please tell the user to click the 'Connect' button in the toolbar to connect to the server first, "
                        + "then retry. Do NOT keep calling tools that require an SSH connection.";
            }
        }

        try {
            String result = tool.getExecutor().execute(args, context);
            throwIfCancelled();
            return result;
        } catch (Exception e) {
            rethrowIfCancelled(e);
            logger.error("Tool execution failed: {}", toolName, e);
            return "Error executing tool '" + toolName + "': " + e.getMessage();
        }
    }

    private boolean requiresSsh(String toolName) {
        Set<String> localTools = Set.of();
        return !localTools.contains(toolName);
    }

    private void streamText(String text, Consumer<String> onToken) {
        if (text == null || text.isEmpty()) return;
        String[] chunks = text.split("(?<=\\s)");
        for (String chunk : chunks) {
            onToken.accept(chunk);
        }
    }

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
            } else if (val.isArray()) {
                args.put(entry.getKey(), val.toString());
            } else if (val.isObject()) {
                args.put(entry.getKey(), val.toString());
            } else {
                args.put(entry.getKey(), val.asText());
            }
        });
        return args;
    }

    private List<ParsedToolCall> parseAllToolCalls(String response) {
        List<ParsedToolCall> calls = new ArrayList<>();
        if (response == null) return calls;

        calls.addAll(parseXmlToolCalls(response));

        if (calls.isEmpty()) {
            ParsedToolCall jsonCall = parseJsonToolCall(response);
            if (jsonCall != null) calls.add(jsonCall);
        }

        calls.addAll(parseBareToolCalls(response, calls));

        return calls;
    }

    /** 移除文本模型输出中的内部工具调用协议，只保留用户可读的说明文字。 */
    private String stripToolCallMarkup(String response) {
        if (response == null || response.isEmpty()) return "";
        String cleaned = TOOL_CALL_CLOSED.matcher(response).replaceAll("");
        cleaned = TOOL_CALL_OPEN.matcher(cleaned).replaceAll("");
        cleaned = TOOL_CALL_SIMPLE_CLOSED.matcher(cleaned).replaceAll("");
        cleaned = TOOL_CALL_SIMPLE_OPEN.matcher(cleaned).replaceAll("");
        if (parseJsonToolCall(response) != null) {
            int start = cleaned.indexOf('{');
            if (start >= 0) {
                String json = extractBalancedJson(cleaned, start);
                cleaned = cleaned.substring(0, start)
                        + cleaned.substring(Math.min(cleaned.length(), start + json.length()));
            }
            cleaned = cleaned.replace("```json", "").replace("```JSON", "").replace("```", "");
        }
        return cleaned;
    }

    private static final Pattern TOOL_CALL_CLOSED = Pattern.compile(
            "<tool_call>\\s*(\\w+)\\s*\\(\\s*(\\{.*?\\})\\s*\\)\\s*</tool_call>", Pattern.DOTALL);

    private static final Pattern TOOL_CALL_OPEN = Pattern.compile(
            "<tool_call>\\s*(\\w+)\\s*\\(\\s*(\\{[^}]*\\})\\s*\\)", Pattern.DOTALL);

    private static final Pattern TOOL_CALL_SIMPLE_CLOSED = Pattern.compile(
            "<tool_call>\\s*(\\w+)\\s*\\(\\s*(.*?)\\s*\\)\\s*</tool_call>", Pattern.DOTALL);

    private static final Pattern TOOL_CALL_SIMPLE_OPEN = Pattern.compile(
            "<tool_call>\\s*(\\w+)\\s*\\(\\s*([^)]*)\\s*\\)", Pattern.DOTALL);

    private List<ParsedToolCall> parseXmlToolCalls(String response) {
        List<ParsedToolCall> calls = new ArrayList<>();

        Matcher m = TOOL_CALL_CLOSED.matcher(response);
        while (m.find()) {
            String toolName = m.group(1);
            String argsJson = m.group(2);
            Map<String, Object> args = parseJsonArgs(argsJson);
            calls.add(new ParsedToolCall(toolName, args));
        }

        if (calls.isEmpty()) {
            m = TOOL_CALL_OPEN.matcher(response);
            while (m.find()) {
                String toolName = m.group(1);
                String argsJson = m.group(2);
                Map<String, Object> args = parseJsonArgs(argsJson);
                calls.add(new ParsedToolCall(toolName, args));
            }
        }

        if (calls.isEmpty()) {
            m = TOOL_CALL_SIMPLE_CLOSED.matcher(response);
            while (m.find()) {
                String toolName = m.group(1);
                String argsStr = m.group(2);
                Map<String, Object> args = parseSimpleArgs(argsStr);
                if (!args.isEmpty() || isNoArgTool(toolName)) {
                    calls.add(new ParsedToolCall(toolName, args));
                }
            }
        }

        if (calls.isEmpty()) {
            m = TOOL_CALL_SIMPLE_OPEN.matcher(response);
            while (m.find()) {
                String toolName = m.group(1);
                String argsStr = m.group(2);
                Map<String, Object> args = parseSimpleArgs(argsStr);
                if (!args.isEmpty() || isNoArgTool(toolName)) {
                    calls.add(new ParsedToolCall(toolName, args));
                }
            }
        }

        return calls;
    }

    private List<ParsedToolCall> parseBareToolCalls(String response, List<ParsedToolCall> alreadyFound) {
        List<ParsedToolCall> calls = new ArrayList<>();
        if (!alreadyFound.isEmpty()) return calls;

        Pattern barePattern = Pattern.compile(
                "(?i)(?:execute_shell|run_in_terminal|read_log|list_processes|check_port|check_disk|" +
                "check_memory|check_cpu|upload_file|list_services|manage_service|check_network|" +
                "list_files|install_package|read_file|write_file|delete_file|download_file)\\s*\\(");
        Matcher m = barePattern.matcher(response);
        int pos = 0;
        while (m.find(pos)) {
            String matched = m.group();
            String toolName = matched.substring(0, matched.indexOf('(')).trim().toLowerCase();
            if (!toolRegistry.getAllTools().stream().anyMatch(t -> t.getName().equals(toolName))) {
                pos = m.end();
                continue;
            }
            int argsStart = m.end();
            int argsEnd = findMatchingParen(response, argsStart - 1);
            if (argsEnd < 0) {
                pos = m.end();
                continue;
            }
            String argsStr = response.substring(argsStart, argsEnd);
            Map<String, Object> args = parseSimpleArgs(argsStr);
            if (!args.isEmpty() || isNoArgTool(toolName)) {
                calls.add(new ParsedToolCall(toolName, args));
            }
            pos = argsEnd + 1;
        }
        return calls;
    }

    private int findMatchingParen(String s, int openPos) {
        int depth = 0;
        boolean inString = false;
        char stringChar = 0;
        for (int i = openPos; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\' && i + 1 < s.length()) { i++; continue; }
                if (c == stringChar) inString = false;
            } else {
                if (c == '"' || c == '\'') { inString = true; stringChar = c; }
                else if (c == '(') depth++;
                else if (c == ')') { depth--; if (depth == 0) return i; }
            }
        }
        return -1;
    }

    private ParsedToolCall parseJsonToolCall(String response) {
        try {
            String json = response;
            if (response.contains("```json")) {
                json = response.split("```json")[1].split("```")[0].trim();
            } else if (response.contains("```")) {
                String[] parts = response.split("```");
                if (parts.length >= 2) {
                    json = parts[1];
                    if (json.startsWith("json") || json.startsWith("JSON")) {
                        json = json.substring(4).trim();
                    }
                }
            }

            JsonNode root;
            try {
                root = objectMapper.readTree(json);
            } catch (Exception e) {
                int start = response.indexOf('{');
                int end = response.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    try {
                        root = objectMapper.readTree(extractBalancedJson(response, start));
                    } catch (Exception e2) {
                        return null;
                    }
                } else {
                    return null;
                }
            }

            if (root == null || !root.isObject()) return null;

            if (root.has("tool") && root.has("args")) {
                String toolName = root.get("tool").asText();
                JsonNode argsNode = root.get("args");
                Map<String, Object> args = new HashMap<>();
                if (argsNode.isObject()) {
                    argsNode.fields().forEachRemaining(entry -> {
                        JsonNode val = entry.getValue();
                        args.put(entry.getKey(), jsonNodeToValue(val));
                    });
                }
                return new ParsedToolCall(toolName, args);
            }

            if (root.has("name") && (root.has("parameters") || root.has("arguments"))) {
                String toolName = root.get("name").asText();
                JsonNode argsNode = root.has("parameters") ? root.get("parameters") : root.get("arguments");
                Map<String, Object> args = new HashMap<>();
                if (argsNode != null && argsNode.isObject()) {
                    argsNode.fields().forEachRemaining(entry -> {
                        JsonNode val = entry.getValue();
                        args.put(entry.getKey(), jsonNodeToValue(val));
                    });
                }
                return new ParsedToolCall(toolName, args);
            }
        } catch (Exception e) {
            logger.debug("Failed to parse JSON tool call: {}", e.getMessage());
        }
        return null;
    }

    private String extractBalancedJson(String s, int start) {
        int depth = 0;
        boolean inString = false;
        char stringChar = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\' && i + 1 < s.length()) { i++; continue; }
                if (c == stringChar) inString = false;
            } else {
                if (c == '"' || c == '\'') { inString = true; stringChar = c; }
                else if (c == '{') depth++;
                else if (c == '}') { depth--; if (depth == 0) return s.substring(start, i + 1); }
            }
        }
        return s.substring(start);
    }

    private Object jsonNodeToValue(JsonNode val) {
        if (val == null || val.isNull()) return null;
        if (val.isInt()) return val.asInt();
        if (val.isLong()) return val.asLong();
        if (val.isDouble() || val.isFloat()) return val.asDouble();
        if (val.isBoolean()) return val.asBoolean();
        if (val.isArray() || val.isObject()) return val.toString();
        return val.asText();
    }

    private Map<String, Object> parseJsonArgs(String argsJson) {
        Map<String, Object> args = new HashMap<>();
        if (argsJson == null || argsJson.isBlank()) return args;
        try {
            JsonNode argsNode = objectMapper.readTree(argsJson);
            if (argsNode.isObject()) {
                argsNode.fields().forEachRemaining(entry -> {
                    args.put(entry.getKey(), jsonNodeToValue(entry.getValue()));
                });
            }
        } catch (Exception e) {
            args.putAll(parseSimpleArgs(argsJson));
        }
        return args;
    }

    private boolean isNoArgTool(String toolName) {
        return toolName.equals("list_services") || toolName.equals("check_cpu")
                || toolName.equals("check_memory") || toolName.equals("check_disk");
    }

    private Map<String, Object> parseSimpleArgs(String argsStr) {
        Map<String, Object> args = new HashMap<>();
        if (argsStr == null || argsStr.trim().isEmpty()) return args;

        argsStr = argsStr.trim();

        if (argsStr.startsWith("{") && argsStr.endsWith("}")) {
            try {
                JsonNode node = objectMapper.readTree(argsStr);
                if (node.isObject()) {
                    node.fields().forEachRemaining(e -> {
                        JsonNode v = e.getValue();
                        if (v.isInt() || v.isLong()) args.put(e.getKey(), v.asInt());
                        else if (v.isBoolean()) args.put(e.getKey(), v.asBoolean());
                        else if (v.isDouble() || v.isFloat()) args.put(e.getKey(), v.asDouble());
                        else args.put(e.getKey(), v.asText());
                    });
                    return args;
                }
            } catch (Exception ignored) {}
        }

        Matcher m = Pattern.compile(
                "(\\w+)\\s*[=:]\\s*\"([^\"]*)\"|(\\w+)\\s*[=:]\\s*'([^']*)'|(\\w+)\\s*[=:]\\s*(\\d+)"
        ).matcher(argsStr);
        while (m.find()) {
            if (m.group(1) != null) {
                args.put(m.group(1), m.group(2));
            } else if (m.group(3) != null) {
                args.put(m.group(3), m.group(4));
            } else if (m.group(5) != null) {
                args.put(m.group(5), Integer.parseInt(m.group(6)));
            }
        }

        if (args.isEmpty() && !argsStr.startsWith("{")) {
            String trimmed = argsStr.trim().replaceAll("^[\"']|[\"']$", "");
            if (trimmed.matches("^\\d+$")) {
                args.put("port", Integer.parseInt(trimmed));
            } else if (trimmed.matches("^[a-zA-Z0-9_/.-]+$") && !trimmed.isEmpty()) {
                args.put("path", trimmed);
                args.put("command", trimmed);
                args.put("filePath", trimmed);
                args.put("logPath", trimmed);
            }
        }

        return args;
    }

    private static class ParsedToolCall {
        final String toolName;
        final Map<String, Object> args;

        ParsedToolCall(String toolName, Map<String, Object> args) {
            this.toolName = toolName;
            this.args = args;
        }
    }
}
