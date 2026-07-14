package com.aifinalshell.provider;

/**
 * Model information with capabilities.
 */
public class ModelInfo {
    private String id;
    private String name;
    private String provider;
    private boolean free;
    private int contextWindow;
    private double inputCostPer1k;
    private double outputCostPer1k;
    // Capabilities
    private boolean supportsTools;      // function calling / tool use
    private boolean supportsVision;     // image input
    private boolean supportsStreaming;  // streaming responses
    private int maxOutputTokens;        // max output tokens
    private String type = "chat";       // C4: chat / embedding / vision（任务7筛选系统）

    public ModelInfo(String id, String name, String provider, boolean free) {
        this.id = id;
        this.name = name;
        this.provider = provider;
        this.free = free;
        this.contextWindow = 128000;
        this.inputCostPer1k = free ? 0 : 0.01;
        this.outputCostPer1k = free ? 0 : 0.03;
        this.supportsTools = true;
        this.supportsVision = false;
        this.supportsStreaming = true;
        this.maxOutputTokens = 4096;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getProvider() { return provider; }
    public boolean isFree() { return free; }
    public int getContextWindow() { return contextWindow; }
    public double getInputCostPer1k() { return inputCostPer1k; }
    public double getOutputCostPer1k() { return outputCostPer1k; }
    public boolean supportsTools() { return supportsTools; }
    public boolean supportsVision() { return supportsVision; }
    public boolean supportsStreaming() { return supportsStreaming; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public String getType() { return type; }

    public void setContextWindow(int ctx) { this.contextWindow = ctx; }
    public void setInputCostPer1k(double cost) { this.inputCostPer1k = cost; }
    public void setOutputCostPer1k(double cost) { this.outputCostPer1k = cost; }
    public void setSupportsTools(boolean v) { this.supportsTools = v; }
    public void setSupportsVision(boolean v) { this.supportsVision = v; }
    public void setSupportsStreaming(boolean v) { this.supportsStreaming = v; }
    public void setMaxOutputTokens(int v) { this.maxOutputTokens = v; }
    public void setType(String type) { this.type = type; }

    /**
     * C4: 根据 model id 启发式推断类型（任务7筛选系统）。
     * - 含 embed/embedding → embedding
     * - 含 vision → vision
     * - 否则 → chat
     */
    public static String inferType(String id) {
        if (id == null) return "chat";
        String lower = id.toLowerCase();
        if (lower.contains("embed")) return "embedding";
        if (lower.contains("vision")) return "vision";
        return "chat";
    }

    @Override
    public String toString() {
        return provider + "/" + id + (free ? " [FREE]" : "");
    }
}
