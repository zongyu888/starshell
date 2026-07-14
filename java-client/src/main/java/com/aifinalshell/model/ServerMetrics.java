package com.aifinalshell.model;

import java.util.ArrayList;
import java.util.List;

public class ServerMetrics {
    private Long serverId;
    private double cpuUsage;
    private double memoryUsage;
    private int memoryUsedMB;
    private int memoryTotalMB;
    private double diskUsage;
    private double loadAverage;
    private long processCount;
    private long networkIn;
    private long networkOut;

    // New fields for FinalShell-style left panel
    private String uptime;
    private double load1, load5, load15;
    private long networkInSpeed;
    private long networkOutSpeed;
    private long diskTotalKB;
    private long diskFreeKB;
    private String logPath;
    private int recentErrorCount;
    private List<String> topProcesses;
    private long pingMs;

    public ServerMetrics() {
        this.topProcesses = new ArrayList<>();
    }

    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }

    public double getCpuUsage() { return cpuUsage; }
    public void setCpuUsage(double cpuUsage) { this.cpuUsage = cpuUsage; }

    public double getMemoryUsage() { return memoryUsage; }
    public void setMemoryUsage(double memoryUsage) { this.memoryUsage = memoryUsage; }

    public int getMemoryUsedMB() { return memoryUsedMB; }
    public void setMemoryUsedMB(int memoryUsedMB) { this.memoryUsedMB = memoryUsedMB; }

    public int getMemoryTotalMB() { return memoryTotalMB; }
    public void setMemoryTotalMB(int memoryTotalMB) { this.memoryTotalMB = memoryTotalMB; }

    public double getDiskUsage() { return diskUsage; }
    public void setDiskUsage(double diskUsage) { this.diskUsage = diskUsage; }

    public double getLoadAverage() { return loadAverage; }
    public void setLoadAverage(double loadAverage) { this.loadAverage = loadAverage; }

    public long getProcessCount() { return processCount; }
    public void setProcessCount(long processCount) { this.processCount = processCount; }

    public long getNetworkIn() { return networkIn; }
    public void setNetworkIn(long networkIn) { this.networkIn = networkIn; }

    public long getNetworkOut() { return networkOut; }
    public void setNetworkOut(long networkOut) { this.networkOut = networkOut; }

    public String getUptime() { return uptime; }
    public void setUptime(String uptime) { this.uptime = uptime; }

    public double getLoad1() { return load1; }
    public void setLoad1(double load1) { this.load1 = load1; }

    public double getLoad5() { return load5; }
    public void setLoad5(double load5) { this.load5 = load5; }

    public double getLoad15() { return load15; }
    public void setLoad15(double load15) { this.load15 = load15; }

    public long getNetworkInSpeed() { return networkInSpeed; }
    public void setNetworkInSpeed(long networkInSpeed) { this.networkInSpeed = networkInSpeed; }

    public long getNetworkOutSpeed() { return networkOutSpeed; }
    public void setNetworkOutSpeed(long networkOutSpeed) { this.networkOutSpeed = networkOutSpeed; }

    public long getDiskTotalKB() { return diskTotalKB; }
    public void setDiskTotalKB(long diskTotalKB) { this.diskTotalKB = diskTotalKB; }

    public long getDiskFreeKB() { return diskFreeKB; }
    public void setDiskFreeKB(long diskFreeKB) { this.diskFreeKB = diskFreeKB; }

    public String getLogPath() { return logPath; }
    public void setLogPath(String logPath) { this.logPath = logPath; }

    public int getRecentErrorCount() { return recentErrorCount; }
    public void setRecentErrorCount(int recentErrorCount) { this.recentErrorCount = recentErrorCount; }

    public List<String> getTopProcesses() { return topProcesses; }
    public void setTopProcesses(List<String> topProcesses) { this.topProcesses = topProcesses; }

    public long getPingMs() { return pingMs; }
    public void setPingMs(long pingMs) { this.pingMs = pingMs; }
}
