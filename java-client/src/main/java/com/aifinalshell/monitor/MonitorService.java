package com.aifinalshell.monitor;

import com.aifinalshell.config.AppConfig;
import com.aifinalshell.model.*;
import com.aifinalshell.service.DatabaseManager;
import com.aifinalshell.ssh.SshConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class MonitorService {
    private static final Logger logger = LoggerFactory.getLogger(MonitorService.class);
    private static MonitorService instance;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final Map<Long, ScheduledFuture<?>> monitorTasks = new ConcurrentHashMap<>();
    private final Map<Long, List<Double>> cpuHistory = new ConcurrentHashMap<>();
    private final Map<Long, List<Double>> memoryHistory = new ConcurrentHashMap<>();
    private final Map<Long, long[]> prevNetBytes = new ConcurrentHashMap<>(); // [rx, tx, timestampMs]
    private final Map<Long, ServerMetrics> lastMetrics = new ConcurrentHashMap<>();
    private final Map<Long, Integer> failureCount = new ConcurrentHashMap<>();
    private final Map<Long, String> connectionKeys = new ConcurrentHashMap<>();
    private Consumer<AlertRecord> alertCallback;
    private Consumer<String> failureCallback;

    private MonitorService() {}

    public static synchronized MonitorService getInstance() {
        if (instance == null) {
            instance = new MonitorService();
        }
        return instance;
    }

    public void setAlertCallback(Consumer<AlertRecord> callback) {
        this.alertCallback = callback;
    }

    public void setFailureCallback(Consumer<String> cb) {
        this.failureCallback = cb;
    }

    /**
     * @deprecated 使用 {@link #startMonitoring(ServerConfig, String)} 以复用终端连接。
     */
    @Deprecated
    public void startMonitoring(ServerConfig config) {
        startMonitoring(config, "default_" + config.getId());
    }

    public void startMonitoring(ServerConfig config, String connectionKey) {
        stopMonitoring(config.getId());

        connectionKeys.put(config.getId(), connectionKey);

        // 使用 AppConfig 中配置的 UI 刷新间隔（秒），默认 3 秒
        int intervalSec = AppConfig.getInstance().getMonitorUiIntervalSeconds();
        if (intervalSec <= 0) intervalSec = 3;

        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(
                () -> monitorServer(config, connectionKey),
                0,
                intervalSec,
                TimeUnit.SECONDS
        );

        monitorTasks.put(config.getId(), task);
        logger.info("开始监控服务器: {} (间隔{}秒)", config.getName(), intervalSec);
    }

    public void stopMonitoring(Long serverId) {
        ScheduledFuture<?> task = monitorTasks.remove(serverId);
        if (task != null) {
            task.cancel(false);
        }
        cpuHistory.remove(serverId);
        memoryHistory.remove(serverId);
        prevNetBytes.remove(serverId);
        lastMetrics.remove(serverId);
        failureCount.remove(serverId);
        connectionKeys.remove(serverId);
    }

    public void stopAllMonitoring() {
        monitorTasks.keySet().forEach(this::stopMonitoring);
        scheduler.shutdown();
    }

    private void monitorServer(ServerConfig config, String connectionKey) {
        try {
            if (!SshConnectionManager.getInstance().isConnected(connectionKey)) {
                return;
            }

            ServerMetrics metrics = null;
            try {
                metrics = collectMetrics(connectionKey, config.getId(), config.getLogPath());
            } catch (Exception ex) {
                logger.error("采集指标异常: {}", config.getName(), ex);
            }

            if (metrics != null) {
                lastMetrics.put(config.getId(), metrics);
                failureCount.put(config.getId(), 0);
                checkThresholds(config, metrics);
                checkLogs(config, connectionKey);
            } else {
                int count = failureCount.merge(config.getId(), 1, Integer::sum);
                if (count == 2 && failureCallback != null) {
                    try {
                        failureCallback.accept(config.getName());
                    } catch (Exception cbEx) {
                        logger.error("失败回调执行异常", cbEx);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("监控服务器失败: {}", config.getName(), e);
        }
    }

    /**
     * 获取最近一次成功采集的指标，用于 UI 即时刷新（缓存）。
     */
    public ServerMetrics getLastMetrics(Long serverId) {
        return lastMetrics.get(serverId);
    }

    /**
     * @deprecated 使用 {@link #collectMetrics(String, Long, String)} 以复用终端连接。
     */
    @Deprecated
    public ServerMetrics collectMetrics(Long serverId, String logPath) {
        return collectMetrics("default_" + serverId, serverId, logPath);
    }

    /**
     * @deprecated monitorIntervalMinutes 参数不再使用，网络速率改用实际时间差计算。
     *             使用 {@link #collectMetrics(String, Long, String)} 以复用终端连接。
     */
    @Deprecated
    public ServerMetrics collectMetrics(Long serverId, String logPath, int monitorIntervalMinutes) {
        return collectMetrics("default_" + serverId, serverId, logPath);
    }

    /**
     * Collect all metrics via a single SSH command to minimize overhead.
     * 使用 connectionKey 复用终端 SSH 连接，避免重复建立会话（V-P2-9 双 SSH）。
     */
    public ServerMetrics collectMetrics(String connectionKey, Long serverId, String logPath) {
        try {
            ServerMetrics metrics = new ServerMetrics();
            metrics.setServerId(serverId);
            metrics.setLogPath(logPath);

            // Combined metrics command
            String cmd = "echo \"===UPTIME===\"; " +
                    "uptime -p 2>/dev/null || uptime; " +
                    "echo \"===LOAD===\"; " +
                    "cat /proc/loadavg 2>/dev/null || echo '0 0 0'; " +
                    "echo \"===CPU===\"; " +
                    "top -bn1 2>/dev/null | grep 'Cpu(s)' | awk '{print $2}' || echo '0'; " +
                    "echo \"===MEM===\"; " +
                    "free -m | awk 'NR==2{printf \"%d|%d\", $3, $2}'; " +
                    "echo \"===DISK===\"; " +
                    "df -k / | awk 'NR==2{print $2, $4, $5}'; " +
                    "echo \"===NET===\"; " +
                    "cat /proc/net/dev 2>/dev/null | grep -E 'eth|ens|eno|enp' | head -1 | awk '{print $2, $10}' || echo '0 0'; " +
                    "echo \"===TOP===\"; " +
                    "ps aux --sort=-%cpu 2>/dev/null | head -6 | tail -5 | awk '{printf \"%s|%s|%s|%s\\n\", $1, $3, $4, $11}' || echo ''; " +
                    "echo \"===PING===\"; " +
                    "echo done";

            long pingStart = System.currentTimeMillis();
            String result = SshConnectionManager.getInstance().executeCommand(connectionKey, cmd);
            long pingEnd = System.currentTimeMillis();
            metrics.setPingMs(pingEnd - pingStart);

            // Parse sections
            String[] sections = result.split("===");
            Map<String, String> sectionMap = new HashMap<>();
            for (int i = 1; i < sections.length; i += 2) {
                if (i + 1 < sections.length) {
                    String key = sections[i].trim();
                    String value = sections[i + 1].trim();
                    sectionMap.put(key, value);
                }
            }

            // Parse uptime
            String uptimeStr = sectionMap.getOrDefault("UPTIME", "");
            if (uptimeStr.isEmpty()) {
                metrics.setUptime("--");
            } else {
                metrics.setUptime(formatUptime(uptimeStr));
            }

            // Parse load average
            String loadStr = sectionMap.getOrDefault("LOAD", "0 0 0");
            String[] loadParts = loadStr.split("\\s+");
            if (loadParts.length >= 3) {
                metrics.setLoad1(parseDouble(loadParts[0]));
                metrics.setLoad5(parseDouble(loadParts[1]));
                metrics.setLoad15(parseDouble(loadParts[2]));
                metrics.setLoadAverage(metrics.getLoad1());
            }

            // Parse CPU
            String cpuStr = sectionMap.getOrDefault("CPU", "0");
            metrics.setCpuUsage(parseDouble(cpuStr.trim()));

            // Parse memory
            String memStr = sectionMap.getOrDefault("MEM", "0|0");
            String[] memParts = memStr.split("\\|");
            if (memParts.length >= 2) {
                int usedMB = parseInt(memParts[0]);
                int totalMB = parseInt(memParts[1]);
                metrics.setMemoryUsedMB(usedMB);
                metrics.setMemoryTotalMB(totalMB);
                metrics.setMemoryUsage(totalMB > 0 ? (usedMB * 100.0 / totalMB) : 0);
            }

            // Parse disk
            String diskStr = sectionMap.getOrDefault("DISK", "0 0 0%");
            String[] diskParts = diskStr.split("\\s+");
            if (diskParts.length >= 3) {
                metrics.setDiskTotalKB(parseLong(diskParts[0]));
                metrics.setDiskFreeKB(parseLong(diskParts[1]));
                String pctStr = diskParts[2].replace("%", "");
                metrics.setDiskUsage(parseDouble(pctStr));
            }

            // Parse network bytes & calculate speed using actual elapsed time
            String netStr = sectionMap.getOrDefault("NET", "0 0");
            String[] netParts = netStr.split("\\s+");
            long rxBytes = 0, txBytes = 0;
            if (netParts.length >= 2) {
                rxBytes = parseLong(netParts[0]);
                txBytes = parseLong(netParts[1]);
            }
            metrics.setNetworkIn(rxBytes);
            metrics.setNetworkOut(txBytes);

            long[] prev = prevNetBytes.get(serverId);
            if (prev != null) {
                double elapsedSec = (System.currentTimeMillis() - prev[2]) / 1000.0;
                if (elapsedSec <= 0) elapsedSec = 1; // 安全保护
                metrics.setNetworkInSpeed((long) Math.max(0, (rxBytes - prev[0]) / elapsedSec));
                metrics.setNetworkOutSpeed((long) Math.max(0, (txBytes - prev[1]) / elapsedSec));
            }
            prevNetBytes.put(serverId, new long[]{rxBytes, txBytes, System.currentTimeMillis()});

            // Parse top processes
            String topStr = sectionMap.getOrDefault("TOP", "");
            List<String> procs = new ArrayList<>();
            if (!topStr.isEmpty()) {
                String[] lines = topStr.split("\n");
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        procs.add(trimmed);
                    }
                }
            }
            metrics.setTopProcesses(procs);

            // Check recent error count
            try {
                String errCmd = "tail -100 " + (logPath != null ? logPath : "/var/log") + " 2>/dev/null | grep -i -E 'error|exception|fatal|oom' | wc -l";
                String errResult = SshConnectionManager.getInstance().executeCommand(connectionKey, errCmd);
                metrics.setRecentErrorCount(parseInt(errResult.trim()));
            } catch (Exception e) {
                metrics.setRecentErrorCount(0);
            }

            // Store in history for threshold checks
            List<Double> cpuHist = cpuHistory.computeIfAbsent(serverId, k -> new ArrayList<>());
            List<Double> memHist = memoryHistory.computeIfAbsent(serverId, k -> new ArrayList<>());
            cpuHist.add(metrics.getCpuUsage());
            memHist.add(metrics.getMemoryUsage());
            if (cpuHist.size() > 10) cpuHist.remove(0);
            if (memHist.size() > 10) memHist.remove(0);

            return metrics;
        } catch (Exception e) {
            logger.error("采集指标失败", e);
            return null;
        }
    }

    private String formatUptime(String raw) {
        // uptime -p gives "up 15 days, 3 hours, 42 minutes"
        // or fallback " 15:42:00 up 15 days,  3:42, ..."
        if (raw.startsWith("up ")) {
            return raw.substring(3);
        }
        if (raw.contains("up ")) {
            return raw.substring(raw.indexOf("up ") + 3).trim();
        }
        return raw;
    }

    private void checkThresholds(ServerConfig config, ServerMetrics metrics) {
        List<Double> cpuHist = cpuHistory.getOrDefault(config.getId(), new ArrayList<>());
        List<Double> memHist = memoryHistory.getOrDefault(config.getId(), new ArrayList<>());

        int cpuThreshold = AppConfig.getInstance().getCpuThreshold();
        int memThreshold = AppConfig.getInstance().getMemoryThreshold();
        int diskThreshold = AppConfig.getInstance().getDiskThreshold();

        if (cpuHist.size() >= 3 && cpuHist.stream().skip(cpuHist.size() - 3).allMatch(c -> c > cpuThreshold)) {
            createAlert(config, "CPU_HIGH", "CRITICAL",
                    "CPU持续高负载",
                    "CPU使用率连续3次超过" + cpuThreshold + "%，当前: " + metrics.getCpuUsage() + "%",
                    "建议检查高CPU进程: top -o %CPU");
        }

        if (memHist.size() >= 3 && memHist.stream().skip(memHist.size() - 3).allMatch(m -> m > memThreshold)) {
            createAlert(config, "MEMORY_HIGH", "CRITICAL",
                    "内存即将耗尽",
                    "内存使用率连续3次超过" + memThreshold + "%，当前: " + metrics.getMemoryUsage() + "%",
                    "建议清理内存或增加内存: free -m");
        }

        if (metrics.getDiskUsage() > diskThreshold) {
            createAlert(config, "DISK_HIGH", "WARNING",
                    "磁盘空间不足",
                    "磁盘使用率超过" + diskThreshold + "%，当前: " + metrics.getDiskUsage() + "%",
                    "建议清理磁盘: du -sh /* | sort -rh | head -10");
        }
    }

    private void checkLogs(ServerConfig config, String connectionKey) {
        try {
            String cmd = "tail -100 " + config.getLogPath() + " 2>/dev/null | grep -i -E 'error|exception|fatal|oom' | wc -l";
            String result = SshConnectionManager.getInstance().executeCommand(connectionKey, cmd);
            int errorCount = parseInt(result.trim());

            if (errorCount > 10) {
                createAlert(config, "LOG_ERROR_HIGH", "WARNING",
                        "日志异常频率过高",
                        "最近100行日志中发现" + errorCount + "条错误日志",
                        "建议查看详细日志分析异常原因");
            }
        } catch (Exception e) {
            logger.error("检查日志失败", e);
        }
    }

    private void createAlert(ServerConfig config, String alertType, String severity, String title, String message, String suggestion) {
        AlertRecord alert = new AlertRecord(config.getId(), alertType, severity, title, message);
        alert.setAiSuggestion(suggestion);

        try {
            DatabaseManager.getInstance().saveAlertRecord(alert);
        } catch (Exception e) {
            logger.error("保存告警记录失败", e);
        }

        if (alertCallback != null) {
            alertCallback.accept(alert);
        }

        logger.warn("告警触发: {} - {} - {}", config.getName(), severity, title);
    }

    public Map<String, Double> getCurrentMetrics(Long serverId) {
        Map<String, Double> metrics = new HashMap<>();
        List<Double> cpu = cpuHistory.get(serverId);
        List<Double> mem = memoryHistory.get(serverId);

        metrics.put("cpu", cpu != null && !cpu.isEmpty() ? cpu.get(cpu.size() - 1) : 0.0);
        metrics.put("memory", mem != null && !mem.isEmpty() ? mem.get(mem.size() - 1) : 0.0);

        return metrics;
    }

    private double parseDouble(String str) {
        try {
            return Double.parseDouble(str.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private int parseInt(String str) {
        try {
            return Integer.parseInt(str.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long parseLong(String str) {
        try {
            return Long.parseLong(str.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
