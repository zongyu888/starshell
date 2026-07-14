package com.aifinalshell.provider;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 模型列表缓存 - TTL机制避免频繁请求API。
 * <p>
 * 每个 provider 的模型列表缓存 5 分钟，过期后自动重新获取。
 * 使用 ConcurrentHashMap 保证线程安全。
 * </p>
 */
public class ModelCache {
    private static ModelCache instance;

    /** 缓存有效期：5分钟（毫秒） */
    private static final long TTL = 300_000;

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private ModelCache() {
    }

    public static synchronized ModelCache getInstance() {
        if (instance == null) {
            instance = new ModelCache();
        }
        return instance;
    }

    /**
     * 缓存条目 - 存储模型列表和获取时间
     */
    private static class CacheEntry {
        final List<ModelInfo> models;
        final long fetchTime;

        CacheEntry(List<ModelInfo> models, long fetchTime) {
            this.models = models;
            this.fetchTime = fetchTime;
        }

        /** 判断缓存是否仍在有效期内 */
        boolean isValid() {
            return System.currentTimeMillis() - fetchTime < TTL;
        }
    }

    /**
     * 获取模型列表 - 缓存有效时返回缓存，否则调用 fetcher 获取并缓存。
     *
     * @param provider provider 名称
     * @param fetcher  模型列表获取函数（缓存未命中时调用）
     * @return 模型列表
     */
    public List<ModelInfo> getModels(String provider, Supplier<List<ModelInfo>> fetcher) {
        CacheEntry entry = cache.get(provider);
        if (entry != null && entry.isValid()) {
            return entry.models;
        }
        // 缓存未命中或已过期，重新获取
        List<ModelInfo> models = fetcher.get();
        cache.put(provider, new CacheEntry(models, System.currentTimeMillis()));
        return models;
    }

    /**
     * 使指定 provider 的缓存失效
     *
     * @param provider provider 名称
     */
    public void invalidate(String provider) {
        cache.remove(provider);
    }

    /**
     * 使所有缓存失效
     */
    public void invalidateAll() {
        cache.clear();
    }
}
