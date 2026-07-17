package com.aifinalshell.config;

import com.aifinalshell.util.SecurityUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * AI Configuration Manager - loads config from JSON files
 */
public class AiConfigManager {
    private static final Logger logger = LoggerFactory.getLogger(AiConfigManager.class);
    private static AiConfigManager instance;
    private final ObjectMapper objectMapper;
    private ObjectNode config;
    private String configPath;

    // Default config paths
    private static final String USER_HOME = System.getProperty("user.home");
    private static final String GLOBAL_CONFIG = USER_HOME + File.separator + ".aifinalshell" + File.separator + "config.json";
    private static final String LOCAL_CONFIG = "config.json";

    private AiConfigManager() {
        this.objectMapper = new ObjectMapper();
        loadConfig();
    }

    public static synchronized AiConfigManager getInstance() {
        if (instance == null) {
            instance = new AiConfigManager();
        }
        return instance;
    }

    private void loadConfig() {
        // Try environment variable
        String envConfig = System.getenv("AIFINALSHELL_CONFIG");
        if (envConfig != null && Files.exists(Paths.get(envConfig))) {
            loadFromFile(envConfig);
            return;
        }

        // Try global config
        if (Files.exists(Paths.get(GLOBAL_CONFIG))) {
            loadFromFile(GLOBAL_CONFIG);
            return;
        }

        // Try local config
        if (Files.exists(Paths.get(LOCAL_CONFIG))) {
            loadFromFile(LOCAL_CONFIG);
            return;
        }

        // Create default config
        createDefaultConfig();
    }

    private void loadFromFile(String path) {
        try {
            JsonNode loaded = objectMapper.readTree(new File(path));
            if (!(loaded instanceof ObjectNode loadedObject)) {
                throw new IOException("AI config root must be a JSON object");
            }
            config = loadedObject;
            configPath = path;
            logger.info("Loaded AI config from: {}", path);
            // 加载后迁移明文密钥为加密存储
            migratePlaintextKeysSafe();
        } catch (Exception e) {
            logger.error("Failed to load config from {}: {}", path, e.getMessage());
            createDefaultConfig();
        }
    }

    /**
     * 安全地迁移明文密钥（避免循环初始化）
     */
    private void migratePlaintextKeysSafe() {
        try {
            ApiKeyManager.getInstance().migratePlaintextKeys(this);
        } catch (Exception e) {
            logger.warn("密钥迁移失败: {}", e.getMessage());
        }
    }

    private void createDefaultConfig() {
        try {
            config = objectMapper.createObjectNode();
            configPath = GLOBAL_CONFIG;

            ObjectNode ai = objectMapper.createObjectNode();
            ai.put("active_provider", "openai");
            ai.put("active_model", "");
            ai.put("temperature", 0.7);
            ai.put("max_tokens", 4096);
            // Agent 工具调用最大步数（防无限循环的安全上限；调大即近似不限制）
            ai.put("max_tool_steps", 50);

            ObjectNode providers = objectMapper.createObjectNode();

            ObjectNode openai = objectMapper.createObjectNode();
            openai.put("api_key", "");
            openai.put("base_url", "https://api.openai.com/v1");
            providers.set("openai", openai);

            ObjectNode anthropic = objectMapper.createObjectNode();
            anthropic.put("api_key", "");
            providers.set("anthropic", anthropic);

            ObjectNode custom = objectMapper.createObjectNode();
            custom.put("api_key", "");
            custom.put("base_url", "");
            providers.set("custom", custom);

            ai.set("providers", providers);
            config.set("ai", ai);

            saveConfig();
            logger.info("Created default AI config at: {}", configPath);
        } catch (Exception e) {
            logger.error("Failed to create default config", e);
        }
    }

    public void saveConfig() {
        try {
            File file = new File(configPath);
            file.getParentFile().mkdirs();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, config);
            logger.info("Saved AI config to: {}", configPath);
        } catch (Exception e) {
            logger.error("Failed to save config", e);
        }
    }

    // ========== AI Config ==========

    private ObjectNode getOrCreateObject(ObjectNode parent, String field) {
        JsonNode existing = parent.get(field);
        if (existing instanceof ObjectNode object) {
            return object;
        }
        ObjectNode created = objectMapper.createObjectNode();
        parent.set(field, created);
        return created;
    }

    private ObjectNode getAiNode() {
        return getOrCreateObject(config, "ai");
    }

    private ObjectNode getProvidersNode() {
        return getOrCreateObject(getAiNode(), "providers");
    }

    private ObjectNode getProviderNode(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        return getOrCreateObject(getProvidersNode(), provider);
    }

    public String getActiveProvider() {
        return config.path("ai").path("active_provider").asText("free");
    }

    public void setActiveProvider(String provider) {
        getAiNode().put("active_provider", provider);
        saveConfig();
    }

    public String getActiveModel() {
        return config.path("ai").path("active_model").asText("");
    }

    public void setActiveModel(String model) {
        getAiNode().put("active_model", model);
        saveConfig();
    }

    public double getTemperature() {
        return config.path("ai").path("temperature").asDouble(0.7);
    }

    public int getMaxTokens() {
        return config.path("ai").path("max_tokens").asInt(4096);
    }

    /**
     * Agent 工具调用最大步数（多步工具循环的安全上限）。
     * 默认 50，调大可近似不限制（用户想"完全让 AI 自己搞完"时设很大即可）。
     */
    public int getMaxToolSteps() {
        int v = config.path("ai").path("max_tool_steps").asInt(50);
        return v > 0 ? v : 50;
    }

    public void setMaxToolSteps(int maxSteps) {
        getAiNode().put("max_tool_steps", maxSteps);
        saveConfig();
    }

    // ========== Provider Config ==========

    public String getApiKey(String provider) {
        // 优先从ApiKeyManager获取活跃密钥（轮询负载均衡）
        try {
            String activeKey = ApiKeyManager.getInstance().getActiveKey(provider);
            if (activeKey != null && !activeKey.isEmpty()) {
                return activeKey;
            }
        } catch (Exception e) {
            logger.debug("从ApiKeyManager获取密钥失败，回退到旧字段: {}", e.getMessage());
        }
        // 回退到旧字段（解密后返回明文）
        String storedKey = config.path("ai").path("providers").path(provider).path("api_key").asText("");
        return SecurityUtils.decrypt(storedKey);
    }

    public void setApiKey(String provider, String apiKey) {
        // 加密后委托ApiKeyManager添加到密钥列表
        if (apiKey != null && !apiKey.isEmpty()) {
            ApiKeyManager.getInstance().addKey(provider, apiKey);
        }
        // 同步更新旧字段为加密值
        String encrypted = SecurityUtils.encrypt(apiKey);
        ObjectNode p = getProviderNode(provider);
        p.put("api_key", encrypted);
        saveConfig();
    }

    /**
     * 原子替换语义：清空 provider 原有所有密钥，只保留传入的这一个 key。
     * <p>
     * 与 {@link #setApiKey} 的"追加"语义不同，本方法适用于"一站式快速配置"场景
     * （如 CustomModelConfigDialog），用户期望"我填的就是当前要用的全部"，
     * 而不是把新 key 追加到旧 key 后面参与轮询。
     * </p>
     * <p>
     * 同时更新 api_keys 数组（轮询源）和 api_key 旧字段（回退源），保证两条读取路径一致。
     * </p>
     *
     * @param provider provider 名称
     * @param apiKey   明文密钥；为空或 null 则仅清空密钥列表
     */
    public void replaceApiKey(String provider, String apiKey) {
        // 委托 setSingleKey：清空 api_keys 数组，只保留这一个 key（加密存储）
        ApiKeyManager.getInstance().setSingleKey(provider, apiKey);
        // 同步更新 api_key 旧字段（加密），保证 getApiKey 回退路径一致
        String encrypted = (apiKey == null || apiKey.isEmpty())
                ? "" : SecurityUtils.encrypt(apiKey);
        ObjectNode p = getProviderNode(provider);
        p.put("api_key", encrypted);
        saveConfig();
    }

    /**
     * 获取指定provider的所有API密钥列表（委托ApiKeyManager）
     */
    public List<ApiKeyManager.ApiKeyEntry> getApiKeys(String provider) {
        return ApiKeyManager.getInstance().getKeys(provider);
    }

    /**
     * 添加API密钥（委托ApiKeyManager）
     */
    public void addApiKey(String provider, String apiKey) {
        ApiKeyManager.getInstance().addKey(provider, apiKey);
    }

    /**
     * 删除指定索引的API密钥（委托ApiKeyManager）
     */
    public void removeApiKey(String provider, int index) {
        ApiKeyManager.getInstance().removeKey(provider, index);
    }

    public String getBaseUrl(String provider) {
        return config.path("ai").path("providers").path(provider).path("base_url").asText("");
    }

    public void setBaseUrl(String provider, String baseUrl) {
        ObjectNode p = getProviderNode(provider);
        p.put("base_url", baseUrl);
        saveConfig();
    }

    public List<String> getCustomModels(String provider) {
        List<String> models = new ArrayList<>();
        JsonNode arr = config.path("ai").path("providers").path(provider).path("models");
        if (arr != null && arr.isArray()) {
            for (JsonNode node : arr) {
                models.add(node.asText());
            }
        }
        return models;
    }

    public void setCustomModels(String provider, List<String> models) {
        ObjectNode p = getProviderNode(provider);
        p.set("models", objectMapper.valueToTree(models));
        saveConfig();
    }

    public JsonNode getFullConfig() {
        return config;
    }

    /**
     * 获取配置根节点（ObjectNode），供ApiKeyManager等组件使用
     */
    public ObjectNode getConfig() {
        return config;
    }
}
