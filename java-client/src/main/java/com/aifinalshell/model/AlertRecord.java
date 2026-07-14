package com.aifinalshell.model;

import java.time.LocalDateTime;

public class AlertRecord {
    private Long id;
    private Long serverId;
    private String alertType;
    private String severity;
    private String title;
    private String message;
    private String aiSuggestion;
    private boolean acknowledged;
    private LocalDateTime createdAt;
    private LocalDateTime acknowledgedAt;

    public AlertRecord() {}

    public AlertRecord(Long serverId, String alertType, String severity, String title, String message) {
        this.serverId = serverId;
        this.alertType = alertType;
        this.severity = severity;
        this.title = title;
        this.message = message;
        this.acknowledged = false;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }

    public String getAlertType() { return alertType; }
    public void setAlertType(String alertType) { this.alertType = alertType; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getAiSuggestion() { return aiSuggestion; }
    public void setAiSuggestion(String aiSuggestion) { this.aiSuggestion = aiSuggestion; }

    public boolean isAcknowledged() { return acknowledged; }
    public void setAcknowledged(boolean acknowledged) { this.acknowledged = acknowledged; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getAcknowledgedAt() { return acknowledgedAt; }
    public void setAcknowledgedAt(LocalDateTime acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }
}
