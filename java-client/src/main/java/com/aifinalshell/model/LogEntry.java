package com.aifinalshell.model;

import java.time.LocalDateTime;

public class LogEntry {
    private Long id;
    private Long serverId;
    private String source;
    private String level;
    private String message;
    private String rawLog;
    private LocalDateTime timestamp;
    private boolean analyzed;
    private String aiAnalysis;

    public LogEntry() {}

    public LogEntry(Long serverId, String source, String level, String message, String rawLog) {
        this.serverId = serverId;
        this.source = source;
        this.level = level;
        this.message = message;
        this.rawLog = rawLog;
        this.timestamp = LocalDateTime.now();
        this.analyzed = false;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getRawLog() { return rawLog; }
    public void setRawLog(String rawLog) { this.rawLog = rawLog; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public boolean isAnalyzed() { return analyzed; }
    public void setAnalyzed(boolean analyzed) { this.analyzed = analyzed; }

    public String getAiAnalysis() { return aiAnalysis; }
    public void setAiAnalysis(String aiAnalysis) { this.aiAnalysis = aiAnalysis; }
}
