package com.aifinalshell.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 模型过滤器 - 按免费/能力过滤模型列表。
 * <p>
 * 支持链式过滤：依次应用所有选中的过滤条件，逐步缩小模型列表范围。
 * </p>
 */
public class ModelFilter {

    /**
     * 过滤类型枚举
     */
    public enum FilterType {
        /** 仅免费模型 */
        FREE_ONLY,
        /** 支持工具调用（function calling） */
        SUPPORTS_TOOLS,
        /** 支持视觉输入（image input） */
        SUPPORTS_VISION,
        /** 支持流式响应（streaming） */
        SUPPORTS_STREAMING
    }

    /**
     * 链式过滤模型列表。
     * <p>
     * 根据 filters 集合中的过滤类型，依次过滤模型列表。
     * 如果 filters 为空或 null，返回原始列表的副本。
     * </p>
     *
     * @param models  待过滤的模型列表
     * @param filters 过滤条件集合
     * @return 过滤后的模型列表
     */
    public static List<ModelInfo> filter(List<ModelInfo> models, Set<FilterType> filters) {
        if (filters == null || filters.isEmpty()) {
            return new ArrayList<>(models);
        }

        List<ModelInfo> result = new ArrayList<>(models);

        if (filters.contains(FilterType.FREE_ONLY)) {
            result.removeIf(m -> !m.isFree());
        }
        if (filters.contains(FilterType.SUPPORTS_TOOLS)) {
            result.removeIf(m -> !m.supportsTools());
        }
        if (filters.contains(FilterType.SUPPORTS_VISION)) {
            result.removeIf(m -> !m.supportsVision());
        }
        if (filters.contains(FilterType.SUPPORTS_STREAMING)) {
            result.removeIf(m -> !m.supportsStreaming());
        }

        return result;
    }
}
