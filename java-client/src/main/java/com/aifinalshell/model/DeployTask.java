package com.aifinalshell.model;

import java.time.LocalDateTime;

public class DeployTask {
    private Long id;
    private Long serverId;
    private String name;
    private String description;
    private String script;
    private String status;
    private String output;
    private String rollbackScript;
    private boolean rollbackAvailable;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    public DeployTask() {}

    public DeployTask(Long serverId, String name, String description, String script) {
        this.serverId = serverId;
        this.name = name;
        this.description = description;
        this.script = script;
        this.status = "PENDING";
        this.rollbackAvailable = false;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getScript() { return script; }
    public void setScript(String script) { this.script = script; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }

    public String getRollbackScript() { return rollbackScript; }
    public void setRollbackScript(String rollbackScript) { this.rollbackScript = rollbackScript; }

    public boolean isRollbackAvailable() { return rollbackAvailable; }
    public void setRollbackAvailable(boolean rollbackAvailable) { this.rollbackAvailable = rollbackAvailable; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
