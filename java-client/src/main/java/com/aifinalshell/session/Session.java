package com.aifinalshell.session;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Chat session model
 */
public class Session {
    private Long id;
    private String name;
    private Long serverId;
    private String model;
    private String agent;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<Message> messages;

    public Session() {
        this.messages = new ArrayList<>();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Session(String name) {
        this();
        this.name = name;
    }

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getAgent() { return agent; }
    public void setAgent(String agent) { this.agent = agent; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public List<Message> getMessages() { return messages; }
    public void setMessages(List<Message> messages) { this.messages = messages; }

    public void addMessage(Message msg) {
        msg.setSessionId(this.id);
        this.messages.add(msg);
        this.updatedAt = LocalDateTime.now();
    }
}
