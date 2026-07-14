package com.aifinalshell.session;

import java.time.LocalDateTime;

/**
 * Chat message model
 */
public class Message {
    private Long id;
    private Long sessionId;
    private String role; // user, assistant, system
    private String content;
    private String model;
    private int tokensUsed;
    private LocalDateTime createdAt;

    public Message() {
        this.createdAt = LocalDateTime.now();
    }

    public Message(String role, String content) {
        this();
        this.role = role;
        this.content = content;
    }

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public int getTokensUsed() { return tokensUsed; }
    public void setTokensUsed(int tokensUsed) { this.tokensUsed = tokensUsed; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
