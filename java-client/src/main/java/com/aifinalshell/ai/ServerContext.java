package com.aifinalshell.ai;

import java.time.LocalDateTime;

/**
 * 服务器上下文信息模型。
 * 存储从服务器拉取的完整环境信息，供 AI 分析和部署决策使用。
 */
public class ServerContext {

    /** 服务器ID */
    private Long serverId;

    /** 系统信息：uname -a 输出 */
    private String systemInfo;

    /** OS发行版信息：cat /etc/os-release 输出 */
    private String osRelease;

    /** CPU核心数 */
    private String cpuCores;

    /** 内存信息：free -h 输出 */
    private String memoryInfo;

    /** 磁盘空间：df -h 输出 */
    private String diskInfo;

    /** IP地址列表：hostname -I 输出 */
    private String ipAddresses;

    /** 主机名 */
    private String hostname;

    /** 监听端口列表：ss -tlnp 输出 */
    private String listeningPorts;

    /** 运行中的服务：systemctl list-units 输出 */
    private String runningServices;

    /** Java环境信息：java -version 输出 */
    private String javaVersion;

    /** Python环境信息：python3 --version 输出 */
    private String pythonVersion;

    /** Node环境信息：node --version 输出 */
    private String nodeVersion;

    /** 关键目录文件索引：/home, /opt, /var/log 下的文件列表 */
    private String directoryIndex;

    /** Docker状态：docker ps 输出（未安装则为空） */
    private String dockerStatus;

    /** 是否已安装Docker */
    private boolean dockerInstalled;

    /** 上下文拉取时间 */
    private LocalDateTime fetchedAt;

    public ServerContext() {
        this.fetchedAt = LocalDateTime.now();
    }

    // ========== Getters & Setters ==========

    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }

    public String getSystemInfo() { return systemInfo; }
    public void setSystemInfo(String systemInfo) { this.systemInfo = systemInfo; }

    public String getOsRelease() { return osRelease; }
    public void setOsRelease(String osRelease) { this.osRelease = osRelease; }

    public String getCpuCores() { return cpuCores; }
    public void setCpuCores(String cpuCores) { this.cpuCores = cpuCores; }

    public String getMemoryInfo() { return memoryInfo; }
    public void setMemoryInfo(String memoryInfo) { this.memoryInfo = memoryInfo; }

    public String getDiskInfo() { return diskInfo; }
    public void setDiskInfo(String diskInfo) { this.diskInfo = diskInfo; }

    public String getIpAddresses() { return ipAddresses; }
    public void setIpAddresses(String ipAddresses) { this.ipAddresses = ipAddresses; }

    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }

    public String getListeningPorts() { return listeningPorts; }
    public void setListeningPorts(String listeningPorts) { this.listeningPorts = listeningPorts; }

    public String getRunningServices() { return runningServices; }
    public void setRunningServices(String runningServices) { this.runningServices = runningServices; }

    public String getJavaVersion() { return javaVersion; }
    public void setJavaVersion(String javaVersion) { this.javaVersion = javaVersion; }

    public String getPythonVersion() { return pythonVersion; }
    public void setPythonVersion(String pythonVersion) { this.pythonVersion = pythonVersion; }

    public String getNodeVersion() { return nodeVersion; }
    public void setNodeVersion(String nodeVersion) { this.nodeVersion = nodeVersion; }

    public String getDirectoryIndex() { return directoryIndex; }
    public void setDirectoryIndex(String directoryIndex) { this.directoryIndex = directoryIndex; }

    public String getDockerStatus() { return dockerStatus; }
    public void setDockerStatus(String dockerStatus) { this.dockerStatus = dockerStatus; }

    public boolean isDockerInstalled() { return dockerInstalled; }
    public void setDockerInstalled(boolean dockerInstalled) { this.dockerInstalled = dockerInstalled; }

    public LocalDateTime getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(LocalDateTime fetchedAt) { this.fetchedAt = fetchedAt; }

    /**
     * 判断服务器是否安装了Java环境
     */
    public boolean hasJava() {
        return javaVersion != null && !javaVersion.contains("not found") && !javaVersion.contains("No such file");
    }

    /**
     * 判断服务器是否安装了Python环境
     */
    public boolean hasPython() {
        return pythonVersion != null && !pythonVersion.contains("not found") && !pythonVersion.contains("No such file");
    }

    /**
     * 判断服务器是否安装了Node环境
     */
    public boolean hasNode() {
        return nodeVersion != null && !nodeVersion.contains("not found") && !nodeVersion.contains("No such file");
    }
}
