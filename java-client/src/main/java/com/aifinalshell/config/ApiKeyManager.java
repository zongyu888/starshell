package com.aifinalshell.config;

import com.aifinalshell.util.SecurityUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * API密钥管理器 - 多密钥管理、AES-GCM加密存储、轮询负载均衡。
 * <p>
 * 密钥以加密形式存储在 config.json 的 ai.providers.{provider}.api_keys 数组中，
 * 内存中使用时通过 SecurityUtils.decrypt 解密为明文。
 * 轮询负载均衡通过 AtomicInteger 计数器实现，每个 provider 独立计数。
 * </p>
 */
public class ApiKeyManager {
    private static final Logger logger = LoggerFactory.getLogger(ApiKeyManager.class);
    private static ApiKeyManager instance;

    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 每个 provider 的轮询计数器，用于负载均衡 */
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    /** 最大失败次数，达到后标记密钥为 invalid */
    private static final int MAX_FAIL_COUNT = 3;
    /** C5: lastUsed 脏标记集合——内存更新后标记，定时刷盘避免每次轮询写磁盘 */
    private final Set<String> dirtyProviders = ConcurrentHashMap.newKeySet();
    /** C5: 定时刷盘调度器（daemon 单线程，每 30 秒 flush 一次脏密钥） */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ApiKeyManager-Flusher");
        t.setDaemon(true);
        return t;
    });

    private ApiKeyManager() {
        // C5: 启动定时刷盘任务，每 30 秒持久化 lastUsed 脏数据
        scheduler.scheduleAtFixedRate(this::flushDirty, 30, 30, TimeUnit.SECONDS);
    }

    public static synchronized ApiKeyManager getInstance() {
        if (instance == null) {
            instance = new ApiKeyManager();
        }
        return instance;
    }

    // ========== 内部类：密钥条目 ==========

    /**
     * API密钥条目 - 内存中 key 为解密后的明文，配置文件中存储为加密形式。
     */
    public static class ApiKeyEntry {
        /** 解密后的密钥明文 */
        private String key;
        /** 标签（如 "Key 1"） */
        private String label;
        /** 状态：valid / invalid */
        private String status;
        /** 连续失败次数 */
        private int failCount;
        /** 最后使用时间戳（毫秒） */
        private long lastUsed;

        public ApiKeyEntry() {
        }

        public ApiKeyEntry(String key, String label, String status, int failCount, long lastUsed) {
            this.key = key;
            this.label = label;
            this.status = status;
            this.failCount = failCount;
            this.lastUsed = lastUsed;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public int getFailCount() {
            return failCount;
        }

        public void setFailCount(int failCount) {
            this.failCount = failCount;
        }

        public long getLastUsed() {
            return lastUsed;
        }

        public void setLastUsed(long lastUsed) {
            this.lastUsed = lastUsed;
        }

        /** 是否为有效状态 */
        public boolean isValid() {
            return "valid".equalsIgnoreCase(status);
        }
    }

    // ========== 配置读写辅助方法 ==========

    /**
     * 获取 AiConfigManager 的配置根节点
     */
    private ObjectNode getRootConfig() {
        return AiConfigManager.getInstance().getConfig();
    }

    /**
     * 通过 AiConfigManager 保存配置
     */
    private void saveConfig() {
        AiConfigManager.getInstance().saveConfig();
    }

    /**
     * 获取指定 provider 的 api_keys 数组节点。
     *
     * @param provider provider 名称
     * @param create   为 true 时如果节点不存在则创建，为 false 时返回 null
     */
    private ArrayNode getApiKeysArray(String provider, boolean create) {
        ObjectNode root = getRootConfig();
        JsonNode aiNode = root.get("ai");
        ObjectNode ai;
        if (aiNode instanceof ObjectNode existingAi) {
            ai = existingAi;
        } else {
            if (!create) return null;
            ai = objectMapper.createObjectNode();
            root.set("ai", ai);
        }
        JsonNode providersNode = ai.get("providers");
        ObjectNode providers;
        if (providersNode instanceof ObjectNode existingProviders) {
            providers = existingProviders;
        } else {
            if (!create) return null;
            providers = objectMapper.createObjectNode();
            ai.set("providers", providers);
        }
        JsonNode providerNode = providers.get(provider);
        ObjectNode p;
        if (providerNode instanceof ObjectNode existingProvider) {
            p = existingProvider;
        } else {
            if (!create) return null;
            p = objectMapper.createObjectNode();
            providers.set(provider, p);
        }
        JsonNode keysNode = p.path("api_keys");
        if (!keysNode.isArray()) {
            if (!create) return null;
            ArrayNode arr = objectMapper.createArrayNode();
            p.set("api_keys", arr);
            return arr;
        }
        return (ArrayNode) keysNode;
    }

    // ========== 公共 API ==========

    /**
     * 获取指定 provider 的所有密钥列表（解密后的明文）。
     *
     * @param provider provider 名称
     * @return 密钥条目列表，密钥字段为解密后的明文
     */
    public List<ApiKeyEntry> getKeys(String provider) {
        List<ApiKeyEntry> result = new ArrayList<>();
        ArrayNode arr = getApiKeysArray(provider, false);
        if (arr == null) {
            return result;
        }
        for (JsonNode node : arr) {
            String encKey = node.path("key").asText("");
            String decKey = SecurityUtils.decrypt(encKey);
            ApiKeyEntry entry = new ApiKeyEntry(
                    decKey,
                    node.path("label").asText(""),
                    node.path("status").asText("valid"),
                    node.path("failCount").asInt(0),
                    node.path("lastUsed").asLong(0)
            );
            result.add(entry);
        }
        return result;
    }

    /**
     * 添加密钥 - 调用 SecurityUtils.encrypt 加密后存储到 config.json 的 api_keys 数组。
     * 如果密钥已存在则跳过。
     *
     * @param provider      provider 名称
     * @param plaintextKey  明文密钥
     */
    public void addKey(String provider, String plaintextKey) {
        if (plaintextKey == null || plaintextKey.isEmpty()) {
            return;
        }
        String encrypted = SecurityUtils.encrypt(plaintextKey);
        ArrayNode arr = getApiKeysArray(provider, true);

        // 检查是否已存在相同密钥（解密后比较）
        for (JsonNode node : arr) {
            String existingKey = SecurityUtils.decrypt(node.path("key").asText(""));
            if (plaintextKey.equals(existingKey)) {
                logger.info("密钥已存在，跳过添加: provider={}", provider);
                return;
            }
        }

        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("key", encrypted);
        entry.put("label", "Key " + (arr.size() + 1));
        entry.put("status", "valid");
        entry.put("failCount", 0);
        entry.put("lastUsed", 0);
        arr.add(entry);

        saveConfig();
        logger.info("已添加API密钥: provider={}", provider);
    }

    /**
     * 删除指定索引的密钥。
     *
     * @param provider provider 名称
     * @param index    密钥在数组中的索引
     */
    public void removeKey(String provider, int index) {
        ArrayNode arr = getApiKeysArray(provider, false);
        if (arr == null) {
            return;
        }
        if (index >= 0 && index < arr.size()) {
            arr.remove(index);
            saveConfig();
            logger.info("已删除API密钥: provider={}, index={}", provider, index);
        }
    }

    /**
     * 清空指定 provider 的所有密钥（api_keys 数组）。
     * 用于"换平台/换账号"场景下重置密钥列表，避免旧 key 残留参与轮询导致 401。
     * <p>
     * 注意：仅清空 api_keys 数组，不会动 provider 节点上的 api_key 旧字段
     * （该字段由调用方在写入新 key 时一并覆盖）。
     *
     * @param provider provider 名称
     */
    public void clearKeys(String provider) {
        ArrayNode arr = getApiKeysArray(provider, false);
        if (arr == null) {
            return;
        }
        if (arr.size() > 0) {
            arr.removeAll();
            saveConfig();
            logger.info("已清空所有API密钥: provider={}", provider);
        }
    }

    /**
     * 原子替换语义：清空 provider 原有所有密钥，然后只保留传入的这一个 key。
     * <p>
     * 适用场景：用户在"自定义大模型配置"对话框里保存时，期望"我填的就是当前要用的全部"，
     * 而不是把新 key 追加到旧 key 后面参与轮询。多 key 轮询应交给专门的 Key 管理界面
     * （如 AiSettingsDialog 的多 key 列表）维护。
     * </p>
     *
     * @param provider      provider 名称
     * @param plaintextKey  明文密钥；为空则等价于 clearKeys
     */
    public void setSingleKey(String provider, String plaintextKey) {
        if (plaintextKey == null || plaintextKey.isEmpty()) {
            clearKeys(provider);
            return;
        }
        ArrayNode arr = getApiKeysArray(provider, true);
        arr.removeAll();

        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("key", SecurityUtils.encrypt(plaintextKey));
        entry.put("label", "Key 1");
        entry.put("status", "valid");
        entry.put("failCount", 0);
        entry.put("lastUsed", 0);
        arr.add(entry);

        // 重置轮询计数器，避免索引越界
        AtomicInteger counter = counters.get(provider);
        if (counter != null) {
            counter.set(0);
        }

        saveConfig();
        logger.info("已替换为单一API密钥: provider={}", provider);
    }

    /**
     * 获取活跃密钥 - 轮询负载均衡。
     * <p>
     * 使用 AtomicInteger 计数器，取 counter++ % validKeys.size()，
     * 返回 SecurityUtils.decrypt 后的明文密钥。
     * </p>
     *
     * @param provider provider 名称
     * @return 解密后的明文密钥，无可用密钥时返回空字符串
     */
    public String getActiveKey(String provider) {
        List<ApiKeyEntry> allKeys = getKeys(provider);
        if (allKeys.isEmpty()) {
            return "";
        }

        // 筛选有效密钥
        List<ApiKeyEntry> validKeys = new ArrayList<>();
        for (ApiKeyEntry entry : allKeys) {
            if (entry.isValid()) {
                validKeys.add(entry);
            }
        }

        if (validKeys.isEmpty()) {
            // B3: 无有效密钥时返回空字符串，由调用方提示用户；
            // 不再回退到已失效密钥，避免持续用坏 key 重试加剧限流
            logger.warn("无有效密钥: provider={}（共 {} 个密钥均已失效）", provider, allKeys.size());
            return "";
        }

        // 轮询选择
        AtomicInteger counter = counters.computeIfAbsent(provider, k -> new AtomicInteger(0));
        int idx = Math.abs(counter.getAndIncrement()) % validKeys.size();
        ApiKeyEntry selected = validKeys.get(idx);

        // 更新 lastUsed 时间戳
        updateLastUsed(provider, selected.getKey());

        return selected.getKey();
    }

    /**
     * 更新密钥的 lastUsed 时间戳
     * <p>C5: 仅更新内存中的配置节点并标记脏，不立即写盘；由定时任务或退出时 flushDirty 持久化，
     * 避免每次轮询取 key 都触发磁盘 IO。</p>
     */
    private void updateLastUsed(String provider, String plaintextKey) {
        ArrayNode arr = getApiKeysArray(provider, false);
        if (arr == null) {
            return;
        }
        for (JsonNode node : arr) {
            if (node instanceof ObjectNode objNode) {
                String decKey = SecurityUtils.decrypt(objNode.path("key").asText(""));
                if (plaintextKey.equals(decKey)) {
                    objNode.put("lastUsed", System.currentTimeMillis());
                    dirtyProviders.add(provider);
                    break;
                }
            }
        }
    }

    /**
     * C5: 刷盘——若有脏 provider 则持久化配置并清空脏标记。
     * 由定时调度器周期调用，亦在退出时手动调用。
     */
    public synchronized void flushDirty() {
        if (dirtyProviders.isEmpty()) {
            return;
        }
        try {
            saveConfig();
            dirtyProviders.clear();
            logger.debug("lastUsed 脏数据已刷盘");
        } catch (Exception e) {
            logger.warn("lastUsed 刷盘失败: {}", e.getMessage());
        }
    }

    /**
     * C5: 退出刷盘——取消定时调度器并最后 flush 一次，确保不丢 lastUsed 数据。
     * 应在应用优雅关闭流程中调用（AiFinalShellApp.performClose）。
     */
    public void flushAndShutdown() {
        try {
            scheduler.shutdownNow();
        } catch (Exception ignored) {
        }
        flushDirty();
    }

    /**
     * 标记密钥失败 - failCount++，达到 MAX_FAIL_COUNT 次后设 status="invalid"。
     *
     * @param provider provider 名称
     * @param key      明文密钥（用于匹配）
     */
    public void markKeyFailed(String provider, String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        ArrayNode arr = getApiKeysArray(provider, false);
        if (arr == null) {
            return;
        }
        boolean changed = false;
        for (JsonNode node : arr) {
            if (node instanceof ObjectNode objNode) {
                String decKey = SecurityUtils.decrypt(objNode.path("key").asText(""));
                if (key.equals(decKey)) {
                    int failCount = objNode.path("failCount").asInt(0) + 1;
                    objNode.put("failCount", failCount);
                    if (failCount >= MAX_FAIL_COUNT) {
                        objNode.put("status", "invalid");
                        logger.warn("密钥已标记为无效: provider={}, failCount={}", provider, failCount);
                    }
                    changed = true;
                    break;
                }
            }
        }
        if (changed) {
            saveConfig();
        }
    }

    /**
     * 标记密钥有效 - 重置 failCount=0, status="valid"。
     *
     * @param provider provider 名称
     * @param key      明文密钥（用于匹配）
     */
    public void markKeyValid(String provider, String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        ArrayNode arr = getApiKeysArray(provider, false);
        if (arr == null) {
            return;
        }
        boolean changed = false;
        for (JsonNode node : arr) {
            if (node instanceof ObjectNode objNode) {
                String decKey = SecurityUtils.decrypt(objNode.path("key").asText(""));
                if (key.equals(decKey)) {
                    objNode.put("failCount", 0);
                    objNode.put("status", "valid");
                    changed = true;
                    break;
                }
            }
        }
        if (changed) {
            saveConfig();
        }
    }

    /**
     * 迁移明文密钥 - 检测 api_key 字段是否以 ENC: 开头，
     * 未加密则加密后存入 api_keys 数组，并更新 api_key 为加密值。
     * <p>
     * 此方法接收 AiConfigManager 实例作为参数，直接操作其配置对象，
     * 避免在初始化阶段引发循环依赖。
     * </p>
     *
     * @param config AiConfigManager 实例
     */
    public void migratePlaintextKeys(AiConfigManager config) {
        ObjectNode root = config.getConfig();
        JsonNode providersNode = root.path("ai").path("providers");
        if (!providersNode.isObject()) {
            return;
        }

        boolean changed = false;
        var it = providersNode.fields();
        while (it.hasNext()) {
            var entry = it.next();
            String providerName = entry.getKey();
            if (!(entry.getValue() instanceof ObjectNode providerNode)) {
                continue;
            }

            String apiKey = providerNode.path("api_key").asText("");
            if (apiKey.isEmpty() || SecurityUtils.isEncrypted(apiKey)) {
                continue;
            }

            // 明文密钥需要迁移
            logger.info("迁移明文密钥: provider={}", providerName);
            String encrypted = SecurityUtils.encrypt(apiKey);

            // 添加到 api_keys 数组
            JsonNode existingArr = providerNode.path("api_keys");
            ArrayNode keysArr;
            if (existingArr.isArray()) {
                keysArr = (ArrayNode) existingArr;
            } else {
                keysArr = objectMapper.createArrayNode();
                providerNode.set("api_keys", keysArr);
            }

            // 检查是否已存在相同密钥
            boolean exists = false;
            for (JsonNode kn : keysArr) {
                String decKey = SecurityUtils.decrypt(kn.path("key").asText(""));
                if (apiKey.equals(decKey)) {
                    exists = true;
                    break;
                }
            }

            if (!exists) {
                ObjectNode keyEntry = objectMapper.createObjectNode();
                keyEntry.put("key", encrypted);
                keyEntry.put("label", "migrated");
                keyEntry.put("status", "valid");
                keyEntry.put("failCount", 0);
                keyEntry.put("lastUsed", 0);
                keysArr.add(keyEntry);
            }

            // 更新 api_key 字段为加密值
            providerNode.put("api_key", encrypted);
            changed = true;
        }

        if (changed) {
            config.saveConfig();
            logger.info("明文密钥迁移完成");
        }
    }
}
