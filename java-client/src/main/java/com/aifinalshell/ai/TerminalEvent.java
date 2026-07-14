package com.aifinalshell.ai;

import java.time.LocalDateTime;

/**
 * 终端事件模型。
 * 当终端输出中出现特定模式（错误、需要输入、服务启动等）时生成此事件，
 * 通知 AI 进行进一步处理。
 */
public class TerminalEvent {

    /**
     * 终端事件类型枚举。
     */
    public enum EventType {
        /** 检测到错误关键词（error, exception, failed, refused, denied） */
        ERROR_DETECTED,
        /** 检测到需要用户输入的提示（password:, yes/no, continue?） */
        INPUT_REQUIRED,
        /** 检测到服务启动成功（Started, Active: active (running)） */
        SERVICE_STARTED,
        /** 检测到端口开始监听 */
        PORT_LISTENING,
        /** 检测到部署完成标志 */
        DEPLOYMENT_COMPLETE
    }

    /** 事件类型 */
    private EventType eventType;

    /** 触发事件的原始终端输出 */
    private String rawOutput;

    /** 匹配到的关键词/模式 */
    private String detectedPattern;

    /** AI建议的操作（如修复命令、自动输入响应） */
    private String suggestedAction;

    /** 事件发生时间 */
    private LocalDateTime timestamp;

    /** 关联的SSH连接键 */
    private String sshKey;

    public TerminalEvent() {
        this.timestamp = LocalDateTime.now();
    }

    public TerminalEvent(EventType eventType, String rawOutput, String detectedPattern) {
        this.eventType = eventType;
        this.rawOutput = rawOutput;
        this.detectedPattern = detectedPattern;
        this.timestamp = LocalDateTime.now();
    }

    // ========== Getters & Setters ==========

    public EventType getEventType() { return eventType; }
    public void setEventType(EventType eventType) { this.eventType = eventType; }

    public String getRawOutput() { return rawOutput; }
    public void setRawOutput(String rawOutput) { this.rawOutput = rawOutput; }

    public String getDetectedPattern() { return detectedPattern; }
    public void setDetectedPattern(String detectedPattern) { this.detectedPattern = detectedPattern; }

    public String getSuggestedAction() { return suggestedAction; }
    public void setSuggestedAction(String suggestedAction) { this.suggestedAction = suggestedAction; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public String getSshKey() { return sshKey; }
    public void setSshKey(String sshKey) { this.sshKey = sshKey; }

    /**
     * 判断事件是否为错误类型
     */
    public boolean isError() {
        return eventType == EventType.ERROR_DETECTED;
    }

    /**
     * 判断事件是否需要用户/AI介入
     */
    public boolean requiresAction() {
        return eventType == EventType.ERROR_DETECTED || eventType == EventType.INPUT_REQUIRED;
    }

    @Override
    public String toString() {
        return "TerminalEvent{type=" + eventType +
                ", pattern='" + detectedPattern + '\'' +
                ", time=" + timestamp + '}';
    }
}
