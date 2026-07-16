package com.aifinalshell.controller;

import com.aifinalshell.model.ServerConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session terminal/SFTP/SSH state. Session ID is the only key space; server IDs are metadata,
 * never map keys, which prevents two tabs on the same server from overwriting each other's cwd.
 */
final class SessionWorkspaceState {
    static final class Data {
        String terminalText = "";
        String remotePath = "/";
        Long serverId;
        ServerConfig serverConfig;
        String connectionKey;
    }

    private final Map<Long, Data> sessions = new ConcurrentHashMap<>();

    Data get(Long sessionId) {
        if (sessionId == null) throw new IllegalArgumentException("sessionId is required");
        return sessions.computeIfAbsent(sessionId, ignored -> new Data());
    }

    Data remove(Long sessionId) {
        return sessionId == null ? null : sessions.remove(sessionId);
    }

    String remotePath(Long sessionId) {
        return sessionId == null ? "/" : get(sessionId).remotePath;
    }

    void setRemotePath(Long sessionId, String path) {
        if (sessionId != null) get(sessionId).remotePath = normalizePath(path);
    }

    private String normalizePath(String path) {
        return path == null || path.isBlank() ? "/" : path;
    }
}
