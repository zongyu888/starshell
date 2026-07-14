package com.aifinalshell.provider;

import com.aifinalshell.config.AiConfigManager;
import com.aifinalshell.provider.openai.OpenAiProvider;
import com.aifinalshell.provider.anthropic.AnthropicProvider;
import com.aifinalshell.provider.free.FreeModelProvider;
import com.aifinalshell.provider.custom.CustomProvider;
import com.aifinalshell.provider.ollama.OllamaProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for all AI providers
 */
public class ProviderRegistry {
    private static final Logger logger = LoggerFactory.getLogger(ProviderRegistry.class);
    private static ProviderRegistry instance;
    private final Map<String, AiProvider> providers = new ConcurrentHashMap<>();

    private ProviderRegistry() {
        // Register built-in providers
        register(new OpenAiProvider());
        register(new AnthropicProvider());
        register(new FreeModelProvider());
        register(new CustomProvider());
        register(new OllamaProvider());
    }

    public static synchronized ProviderRegistry getInstance() {
        if (instance == null) {
            instance = new ProviderRegistry();
        }
        return instance;
    }

    public void register(AiProvider provider) {
        providers.put(provider.getName().toLowerCase(), provider);
        logger.info("Registered AI provider: {}", provider.getName());
    }

    public AiProvider getProvider(String name) {
        return providers.get(name.toLowerCase());
    }

    public List<AiProvider> getAllProviders() {
        return new ArrayList<>(providers.values());
    }

    public List<String> getProviderNames() {
        return new ArrayList<>(providers.keySet());
    }

    /**
     * Get provider by model string (e.g. "openai/gpt-4o" or just "gpt-4o")
     */
    public AiProvider getProviderForModel(String model) {
        if (model == null || model.isEmpty()) {
            return providers.get("openai"); // Default
        }
        if (model.contains("/")) {
            String providerName = model.split("/")[0];
            return providers.get(providerName.toLowerCase());
        }
        // Try to find provider by model name - use configured API keys
        AiConfigManager config = AiConfigManager.getInstance();
        for (AiProvider provider : providers.values()) {
            try {
                String apiKey = config.getApiKey(provider.getName());
                String baseUrl = config.getBaseUrl(provider.getName());
                for (ModelInfo m : provider.listModels(apiKey, baseUrl)) {
                    if (m.getId().equals(model)) {
                        return provider;
                    }
                }
            } catch (Exception e) {
                logger.debug("Failed to list models from {}: {}", provider.getName(), e.getMessage());
            }
        }
        return providers.get("openai"); // Default to OpenAI
    }

    /**
     * List all available models from all providers, each using its own credentials.
     * 使用 ModelCache 缓存模型列表，避免频繁请求API。
     *
     * @deprecated 使用无参重载 {@link #listAllModels()}，每个 provider 自动从
     *             {@link AiConfigManager} 获取各自凭证。保留本方法仅为向后兼容，
     *             传入的 apiKey/baseUrl 会被忽略。
     */
    @Deprecated
    public List<ModelInfo> listAllModels(String apiKey, String baseUrl) {
        return listAllModels();
    }

    /**
     * List all available models from all providers, each using its own credentials.
     * 遍历所有 provider，分别从 AiConfigManager 取该 provider 的 apiKey/baseUrl 拉取，
     * 避免误用统一凭证导致除当前 provider 外其余都返回空列表。
     * 使用 ModelCache 缓存模型列表，避免频繁请求API。
     */
    public List<ModelInfo> listAllModels() {
        List<ModelInfo> allModels = new ArrayList<>();
        AiConfigManager config = AiConfigManager.getInstance();
        for (AiProvider provider : providers.values()) {
            try {
                String providerApiKey = config.getApiKey(provider.getName());
                String providerBaseUrl = config.getBaseUrl(provider.getName());
                // 使用ModelCache缓存模型列表，避免频繁请求API
                List<ModelInfo> models = ModelCache.getInstance().getModels(
                        provider.getName(),
                        () -> provider.listModels(providerApiKey, providerBaseUrl)
                );
                allModels.addAll(models);
            } catch (Exception e) {
                logger.debug("Failed to list models from {}: {}", provider.getName(), e.getMessage());
            }
        }
        return allModels;
    }
}
