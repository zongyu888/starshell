package com.aifinalshell.ai;

import com.aifinalshell.config.AiConfigManager;
import com.aifinalshell.config.ApiKeyManager;
import com.aifinalshell.model.AiAnalysisResult;
import com.aifinalshell.provider.ModelInfo;
import com.aifinalshell.provider.*;
import com.aifinalshell.session.Message;
import com.aifinalshell.session.Session;
import com.aifinalshell.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * AI Service Client - unified interface for all AI operations.
 * Supports streaming SSE, token counting, retry, context compaction.
 */
public class AiServiceClient {
    private static final Logger logger = LoggerFactory.getLogger(AiServiceClient.class);
    private static AiServiceClient instance;

    /** 模型信息缓存（modelId -> ModelInfo），listAllModels 时填充，用于上下文窗口查询（B4） */
    private final Map<String, ModelInfo> modelCache = new java.util.concurrent.ConcurrentHashMap<>();

    private AiServiceClient() {}

    public static synchronized AiServiceClient getInstance() {
        if (instance == null) {
            instance = new AiServiceClient();
        }
        return instance;
    }

    /**
     * Synchronous chat with AI using configured provider and model.
     * Includes retry logic and token counting.
     */
    public String chat(String message, String context) {
        AiConfigManager config = AiConfigManager.getInstance();
        String providerName = config.getActiveProvider();
        String model = config.getActiveModel();

        AiProvider provider = ProviderRegistry.getInstance().getProvider(providerName);
        if (provider == null) {
            return "Error: Provider '" + providerName + "' not found. Please configure in settings.";
        }

        String baseUrl = config.getBaseUrl(providerName);
        // 首次取 key 用于 buildMessages（上下文压缩可能调用 AI）
        String apiKey0 = config.getApiKey(providerName);
        List<ChatMessage> messages = buildMessages(message, context, provider, model, apiKey0, baseUrl, config);

        // B1+B2: 每次重试重新取 key（getApiKey→getActiveKey 轮询），
        // 配合 markKeyFailed 在 429/鉴权失败时自动切换到下一个密钥，避免限流
        java.util.concurrent.atomic.AtomicReference<String> usedKey =
                new java.util.concurrent.atomic.AtomicReference<>(apiKey0);
        try {
            String response = RetryHandler.executeWithRetry(() -> {
                String apiKey = config.getApiKey(providerName);
                usedKey.set(apiKey);
                if (apiKey == null || apiKey.isEmpty()) {
                    throw new RuntimeException("无可用 API 密钥（provider=" + providerName
                            + "）。请在设置中配置或检查密钥有效性。");
                }
                return provider.chat(model, messages, apiKey, baseUrl,
                        config.getTemperature(), config.getMaxTokens());
            });

            // B1: 成功时重置该密钥失败计数，避免临时性失败累积导致密钥被误判无效
            ApiKeyManager.getInstance().markKeyValid(providerName, usedKey.get());
            saveToSession(message, response);
            return response;
        } catch (Exception e) {
            logger.error("AI chat failed after retries", e);
            // B1: 鉴权/限流类错误标记密钥失败，触发后续轮询切换
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("401") || msg.contains("403") || msg.contains("429")) {
                ApiKeyManager.getInstance().markKeyFailed(providerName, usedKey.get());
            }
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Streaming chat with real-time token output.
     * Returns immediately; tokens delivered via onToken callback.
     */
    public void chatStream(String message, String context,
                           Consumer<String> onToken, Runnable onComplete,
                           Consumer<Exception> onError) {
        AiConfigManager config = AiConfigManager.getInstance();
        String providerName = config.getActiveProvider();
        String model = config.getActiveModel();

        AiProvider provider = ProviderRegistry.getInstance().getProvider(providerName);
        if (provider == null) {
            onError.accept(new RuntimeException("Provider '" + providerName + "' not found"));
            return;
        }

        String apiKey = config.getApiKey(providerName);
        String baseUrl = config.getBaseUrl(providerName);
        if (apiKey == null || apiKey.isEmpty()) {
            onError.accept(new RuntimeException("无可用 API 密钥（provider=" + providerName
                    + "）。请在设置中配置或检查密钥有效性。"));
            return;
        }

        List<ChatMessage> messages = buildMessages(message, context, provider, model, apiKey, baseUrl, config);

        AtomicReference<StringBuilder> fullResponse = new AtomicReference<>(new StringBuilder());
        final String usedKey = apiKey;
        final String providerNameFinal = providerName;

        provider.chatStream(model, messages, apiKey, baseUrl,
                config.getTemperature(), config.getMaxTokens(),
                token -> {
                    fullResponse.get().append(token);
                    onToken.accept(token);
                },
                () -> {
                    // B1: 成功重置失败计数
                    ApiKeyManager.getInstance().markKeyValid(providerNameFinal, usedKey);
                    saveToSession(message, fullResponse.get().toString());
                    onComplete.run();
                },
                error -> {
                    // B1: 鉴权/限流错误标记密钥失败，触发后续轮询切换
                    String m = error.getMessage() != null ? error.getMessage() : "";
                    if (m.contains("401") || m.contains("403") || m.contains("429")) {
                        ApiKeyManager.getInstance().markKeyFailed(providerNameFinal, usedKey);
                    }
                    onError.accept(error);
                }
        );
    }

    private List<ChatMessage> buildMessages(String message, String context,
                                             AiProvider provider, String model,
                                             String apiKey, String baseUrl,
                                             AiConfigManager config) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(buildSystemPrompt(context)));

        // Add conversation history if session exists
        // NOTE: User message is NOT yet saved to session at this point.
        // It will be saved in saveToSession() after the AI response is received.
        // This prevents the duplicate-save bug.
        Session session = SessionManager.getInstance().getCurrentSession();
        if (session != null && !session.getMessages().isEmpty()) {
            List<ChatMessage> historyMessages = session.getMessages().stream()
                    .map(m -> new ChatMessage(m.getRole(), m.getContent()))
                    .collect(Collectors.toList());
            messages.addAll(historyMessages);
        }

        messages.add(ChatMessage.user(message));

        // B4: 优先使用 ModelInfo 的真实上下文窗口，缓存未命中时回退到 maxTokens*4 估算
        ModelInfo mi = modelCache.get(model);
        int contextWindow = (mi != null && mi.getContextWindow() > 0)
                ? mi.getContextWindow()
                : config.getMaxTokens() * 4;
        messages = TokenCounter.truncateToLimit(messages, contextWindow);

        // Auto-compact if conversation is very long
        if (ContextCompactor.needsCompaction(messages, contextWindow)) {
            messages = ContextCompactor.compact(messages, provider, model, apiKey, baseUrl,
                    contextWindow);
        }

        return messages;
    }

    private void saveToSession(String userMessage, String response) {
        Session session = SessionManager.getInstance().getCurrentSession();
        if (session != null) {
            // Save both user message and AI response to session
            // This is the ONLY place messages are saved - prevents duplicate saves
            SessionManager.getInstance().addMessage(session.getId(), new Message("user", userMessage));
            SessionManager.getInstance().addMessage(session.getId(), new Message("assistant", response));
        }
    }

    /**
     * Analyze log content
     */
    public AiAnalysisResult analyzeLog(String logContent, String serverInfo) {
        String prompt = "Analyze the following server logs and return a JSON result:\n" +
                "{\"summary\":\"one line summary\",\"severity\":\"CRITICAL/WARNING/INFO\"," +
                "\"rootCause\":\"root cause\",\"fixCommand\":\"fix command\",\"explanation\":\"details\"}\n\n" +
                "Server: " + serverInfo + "\n\nLogs:\n" + logContent;

        String response = chat(prompt, "Log analysis");

        try {
            // Try to parse JSON from response
            String json = response;
            if (response.contains("```json")) {
                json = response.split("```json")[1].split("```")[0];
            } else if (response.contains("```")) {
                json = response.split("```")[1].split("```")[0];
            }

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(json.trim());

            AiAnalysisResult result = new AiAnalysisResult();
            result.setSummary(node.has("summary") ? node.get("summary").asText() : "Analysis complete");
            result.setSeverity(node.has("severity") ? node.get("severity").asText() : "INFO");
            result.setRootCause(node.has("rootCause") ? node.get("rootCause").asText() : "");
            result.setFixCommand(node.has("fixCommand") ? node.get("fixCommand").asText() : "");
            result.setExplanation(node.has("explanation") ? node.get("explanation").asText() : response);
            return result;
        } catch (Exception e) {
            AiAnalysisResult result = new AiAnalysisResult();
            result.setSummary("Analysis complete");
            result.setSeverity("INFO");
            result.setExplanation(response);
            return result;
        }
    }

    /**
     * Generate deployment script
     */
    public String generateDeployScript(String requirement, String serverInfo) {
        String prompt = "Generate a complete deployment script for the following requirements:\n" +
                requirement + "\n\nServer: " + serverInfo + "\n\n" +
                "Include: environment check, backup, install dependencies, deploy, configure service, " +
                "start, health check, and rollback script. Use set -e for safety.";
        return chat(prompt, "Deployment script generation");
    }

    private String buildSystemPrompt(String context) {
        return OpsPromptTemplates.buildOpsAssistantPrompt(context);
    }

    /**
     * Check if AI service is available
     */
    public boolean isServiceAvailable() {
        AiConfigManager config = AiConfigManager.getInstance();
        String providerName = config.getActiveProvider();
        AiProvider provider = ProviderRegistry.getInstance().getProvider(providerName);
        if (provider == null) return false;
        String apiKey = config.getApiKey(providerName);
        return provider.isAvailable(apiKey);
    }

    /**
     * List all available models
     */
    public List<ModelInfo> listAllModels() {
        AiConfigManager config = AiConfigManager.getInstance();
        List<ModelInfo> models = new ArrayList<>();
        for (AiProvider provider : ProviderRegistry.getInstance().getAllProviders()) {
            String apiKey = config.getApiKey(provider.getName());
            String baseUrl = config.getBaseUrl(provider.getName());
            try {
                List<ModelInfo> pm = provider.listModels(apiKey, baseUrl);
                models.addAll(pm);
                // B4: 缓存模型信息供上下文窗口查询
                for (ModelInfo m : pm) {
                    // C4: 集中填充类型字段（任务7筛选系统），避免逐 provider 修改；
                    // 启发式按 id 推断，supportsVision 的 chat 模型升级为 vision
                    String t = ModelInfo.inferType(m.getId());
                    if ("chat".equals(t) && m.supportsVision()) t = "vision";
                    m.setType(t);
                    modelCache.put(m.getId(), m);
                }
            } catch (Exception e) {
                logger.debug("Failed to list models from {}: {}", provider.getName(), e.getMessage());
            }
        }
        return models;
    }

    /**
     * Get the context window size for a model (from cache, fallback to maxTokens*4).
     * Used by ToolAgent to decide when to compact conversation history.
     */
    public int getModelContextWindow(String modelId) {
        ModelInfo mi = modelCache.get(modelId);
        if (mi != null && mi.getContextWindow() > 0) {
            return mi.getContextWindow();
        }
        AiConfigManager config = AiConfigManager.getInstance();
        return config.getMaxTokens() * 4;
    }

    /**
     * Get list of available providers
     */
    public List<String> getProviderNames() {
        return ProviderRegistry.getInstance().getProviderNames();
    }
}
