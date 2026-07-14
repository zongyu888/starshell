package com.aifinalshell.plan;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Execution plan for complex tasks
 */
public class Plan {
    private Long id;
    private Long sessionId;
    private String title;
    private String content;
    private String status; // draft, confirmed, executing, done
    private List<String> steps;
    private LocalDateTime createdAt;

    public Plan() {
        this.steps = new ArrayList<>();
        this.status = "draft";
        this.createdAt = LocalDateTime.now();
    }

    public Plan(String title, String content) {
        this();
        this.title = title;
        this.content = content;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public List<String> getSteps() { return steps; }
    public void setSteps(List<String> steps) { this.steps = steps; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
