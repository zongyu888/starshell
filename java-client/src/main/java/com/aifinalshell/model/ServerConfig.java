package com.aifinalshell.model;

import java.time.LocalDateTime;

public class ServerConfig {
    private Long id;
    private String name;
    private String host;
    private int port;
    private String username;
    private String password;
    private String privateKeyPath;
    private String logPath;
    private int monitorInterval;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public ServerConfig() {}

    public ServerConfig(String name, String host, int port, String username, String password) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.logPath = "/var/log";
        this.monitorInterval = 5;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getPrivateKeyPath() { return privateKeyPath; }
    public void setPrivateKeyPath(String privateKeyPath) { this.privateKeyPath = privateKeyPath; }

    public String getLogPath() { return logPath; }
    public void setLogPath(String logPath) { this.logPath = logPath; }

    public int getMonitorInterval() { return monitorInterval; }
    public void setMonitorInterval(int monitorInterval) { this.monitorInterval = monitorInterval; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return name + " (" + username + "@" + host + ":" + port + ")";
    }
}
