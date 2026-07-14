package com.aifinalshell.service;

import com.aifinalshell.model.LogEntry;
import com.aifinalshell.model.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogCollectorService {
    private static final Logger logger = LoggerFactory.getLogger(LogCollectorService.class);
    private static LogCollectorService instance;
    private final Map<Long, Deque<String>> logBuffers = new ConcurrentHashMap<>();
    private final Pattern logPattern = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}[.,]?\\d*)\\s+\\[(\\w+)\\]\\s+(.*)");
    private final Pattern errorPattern = Pattern.compile(
            "(ERROR|FATAL|Exception|OOM|OutOfMemory|Connection refused|timeout)", Pattern.CASE_INSENSITIVE);

    private LogCollectorService() {}

    public static synchronized LogCollectorService getInstance() {
        if (instance == null) {
            instance = new LogCollectorService();
        }
        return instance;
    }

    public void processLogLine(Long serverId, String line) {
        Deque<String> buffer = logBuffers.computeIfAbsent(serverId, k -> new ConcurrentLinkedDeque<>());
        buffer.addLast(line);

        while (buffer.size() > 1000) {
            buffer.removeFirst();
        }

        LogEntry entry = parseLogLine(serverId, line);
        if (entry != null) {
            try {
                DatabaseManager.getInstance().saveLogEntry(entry);
            } catch (Exception e) {
                logger.error("保存日志条目失败", e);
            }
        }
    }

    private LogEntry parseLogLine(Long serverId, String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }

        String level = "INFO";
        String source = "unknown";
        String message = line;

        Matcher logMatcher = logPattern.matcher(line);
        if (logMatcher.matches()) {
            source = logMatcher.group(2);
            message = logMatcher.group(3);
        }

        if (errorPattern.matcher(line).find()) {
            if (line.contains("ERROR") || line.contains("FATAL")) {
                level = "ERROR";
            } else if (line.contains("WARN")) {
                level = "WARN";
            } else {
                level = "ERROR";
            }
        } else if (line.contains("DEBUG")) {
            level = "DEBUG";
        } else if (line.contains("INFO")) {
            level = "INFO";
        }

        return new LogEntry(serverId, source, level, message, line);
    }

    public List<String> getRecentLogs(Long serverId, int count) {
        Deque<String> buffer = logBuffers.get(serverId);
        if (buffer == null) {
            return Collections.emptyList();
        }

        List<String> recent = new ArrayList<>(buffer);
        int start = Math.max(0, recent.size() - count);
        return recent.subList(start, recent.size());
    }

    public String getLogsAsText(Long serverId, int count) {
        return String.join("\n", getRecentLogs(serverId, count));
    }

    public boolean hasErrors(Long serverId) {
        Deque<String> buffer = logBuffers.get(serverId);
        if (buffer == null) {
            return false;
        }

        return buffer.stream().anyMatch(line -> errorPattern.matcher(line).find());
    }

    public Map<String, Integer> getLogStats(Long serverId) {
        Map<String, Integer> stats = new HashMap<>();
        stats.put("total", 0);
        stats.put("error", 0);
        stats.put("warn", 0);
        stats.put("info", 0);
        stats.put("debug", 0);

        Deque<String> buffer = logBuffers.get(serverId);
        if (buffer == null) {
            return stats;
        }

        for (String line : buffer) {
            stats.put("total", stats.get("total") + 1);
            if (line.contains("ERROR") || line.contains("FATAL")) {
                stats.put("error", stats.get("error") + 1);
            } else if (line.contains("WARN")) {
                stats.put("warn", stats.get("warn") + 1);
            } else if (line.contains("INFO")) {
                stats.put("info", stats.get("info") + 1);
            } else if (line.contains("DEBUG")) {
                stats.put("debug", stats.get("debug") + 1);
            }
        }

        return stats;
    }

    public void clearBuffer(Long serverId) {
        logBuffers.remove(serverId);
    }
}
