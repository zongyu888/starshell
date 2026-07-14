package com.aifinalshell.ai;

import com.aifinalshell.ssh.SshConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务器上下文拉取器。
 * 当用户连接服务器后，自动拉取服务器完整环境信息，供 AI 分析和部署决策使用。
 */
public class ServerContextFetcher {
    private static final Logger logger = LoggerFactory.getLogger(ServerContextFetcher.class);
    private static ServerContextFetcher instance;

    private ServerContextFetcher() {}

    public static synchronized ServerContextFetcher getInstance() {
        if (instance == null) {
            instance = new ServerContextFetcher();
        }
        return instance;
    }

    /**
     * 拉取服务器完整上下文信息。
     * 包含系统信息、资源概况、网络、端口、服务、运行环境、目录索引、Docker状态。
     *
     * @param sshKey   SSH连接键（如 "sessionId_serverId"）
     * @param serverId 服务器ID
     * @return 填充完毕的 ServerContext 对象
     */
    public ServerContext fetch(String sshKey, Long serverId) {
        ServerContext ctx = new ServerContext();
        ctx.setServerId(serverId);

        logger.info("开始拉取服务器完整上下文: sshKey={}, serverId={}", sshKey, serverId);

        // 系统信息
        ctx.setSystemInfo(safeExecute(sshKey, "uname -a"));
        ctx.setOsRelease(safeExecute(sshKey, "cat /etc/os-release 2>/dev/null"));

        // 资源概况
        ctx.setCpuCores(safeExecute(sshKey, "nproc"));
        ctx.setMemoryInfo(safeExecute(sshKey, "free -h"));
        ctx.setDiskInfo(safeExecute(sshKey, "df -h"));

        // 网络信息
        ctx.setIpAddresses(safeExecute(sshKey, "hostname -I 2>/dev/null || hostname 2>/dev/null"));
        ctx.setHostname(safeExecute(sshKey, "hostname"));

        // 端口状态
        ctx.setListeningPorts(safeExecute(sshKey, "ss -tlnp 2>/dev/null || netstat -tlnp 2>/dev/null"));

        // 运行中的服务
        ctx.setRunningServices(safeExecute(sshKey,
                "systemctl list-units --type=service --state=running 2>/dev/null | head -30"));

        // 运行环境检测
        ctx.setJavaVersion(safeExecute(sshKey, "java -version 2>&1"));
        ctx.setPythonVersion(safeExecute(sshKey, "python3 --version 2>&1"));
        ctx.setNodeVersion(safeExecute(sshKey, "node --version 2>&1"));

        // 关键目录文件索引（限制深度和数量，避免输出过大）
        ctx.setDirectoryIndex(safeExecute(sshKey,
                "echo '=== /home ===' && find /home -maxdepth 2 -type f 2>/dev/null | head -20 && " +
                "echo '=== /opt ===' && find /opt -maxdepth 2 -type f 2>/dev/null | head -20 && " +
                "echo '=== /var/log ===' && find /var/log -maxdepth 1 -type f 2>/dev/null | head -20"));

        // Docker状态（可能未安装，单独处理）
        fetchDockerInfo(sshKey, ctx);

        logger.info("服务器上下文拉取完成: serverId={}", serverId);
        return ctx;
    }

    /**
     * 快速拉取服务器上下文（用于连接时自动触发）。
     * 只拉取最核心的信息：系统信息、CPU、内存、磁盘、网络、端口。
     *
     * @param sshKey   SSH连接键
     * @param serverId 服务器ID
     * @return 填充了核心信息的 ServerContext 对象
     */
    public ServerContext fetchQuick(String sshKey, Long serverId) {
        ServerContext ctx = new ServerContext();
        ctx.setServerId(serverId);

        logger.info("快速拉取服务器上下文: sshKey={}, serverId={}", sshKey, serverId);

        // 核心系统信息
        ctx.setSystemInfo(safeExecute(sshKey, "uname -a"));
        ctx.setHostname(safeExecute(sshKey, "hostname"));
        ctx.setIpAddresses(safeExecute(sshKey, "hostname -I 2>/dev/null || hostname 2>/dev/null"));

        // 核心资源信息
        ctx.setCpuCores(safeExecute(sshKey, "nproc"));
        ctx.setMemoryInfo(safeExecute(sshKey, "free -h"));
        ctx.setDiskInfo(safeExecute(sshKey, "df -h"));

        // 端口状态
        ctx.setListeningPorts(safeExecute(sshKey, "ss -tlnp 2>/dev/null || netstat -tlnp 2>/dev/null"));

        // Java环境（快速检测）
        ctx.setJavaVersion(safeExecute(sshKey, "java -version 2>&1"));

        logger.info("快速上下文拉取完成: serverId={}", serverId);
        return ctx;
    }

    /**
     * 将服务器上下文格式化为 AI 系统提示词。
     * AI 可据此了解服务器环境，做出更精准的运维决策。
     *
     * @param context 服务器上下文
     * @return 格式化的提示词字符串
     */
    public String toPromptContext(ServerContext context) {
        if (context == null) {
            return "（暂无服务器上下文信息）";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 当前服务器上下文\n\n");

        // 基本信息
        sb.append("### 基本信息\n");
        sb.append("- 主机名: ").append(nullToNa(context.getHostname())).append("\n");
        sb.append("- IP地址: ").append(nullToNa(context.getIpAddresses())).append("\n");
        sb.append("- 服务器ID: ").append(context.getServerId()).append("\n\n");

        // 系统信息
        sb.append("### 系统信息\n");
        sb.append("```\n").append(nullToNa(context.getSystemInfo())).append("\n```\n");
        if (context.getOsRelease() != null && !context.getOsRelease().isEmpty()) {
            sb.append("```\n").append(context.getOsRelease()).append("\n```\n");
        }
        sb.append("\n");

        // 资源概况
        sb.append("### 资源概况\n");
        sb.append("- CPU核心数: ").append(nullToNa(context.getCpuCores())).append("\n");
        sb.append("- 内存:\n```\n").append(nullToNa(context.getMemoryInfo())).append("\n```\n");
        sb.append("- 磁盘:\n```\n").append(nullToNa(context.getDiskInfo())).append("\n```\n\n");

        // 网络与端口
        sb.append("### 网络与端口\n");
        sb.append("监听端口:\n```\n").append(nullToNa(context.getListeningPorts())).append("\n```\n\n");

        // 运行中的服务
        if (context.getRunningServices() != null && !context.getRunningServices().isEmpty()) {
            sb.append("### 运行中的服务\n");
            sb.append("```\n").append(context.getRunningServices()).append("\n```\n\n");
        }

        // 运行环境
        sb.append("### 运行环境\n");
        sb.append("- Java: ").append(context.hasJava() ? context.getJavaVersion() : "未安装").append("\n");
        sb.append("- Python: ").append(context.hasPython() ? context.getPythonVersion() : "未安装").append("\n");
        sb.append("- Node: ").append(context.hasNode() ? context.getNodeVersion() : "未安装").append("\n");
        sb.append("- Docker: ").append(context.isDockerInstalled() ? "已安装" : "未安装").append("\n\n");

        // Docker状态
        if (context.isDockerInstalled() && context.getDockerStatus() != null) {
            sb.append("### Docker容器\n");
            sb.append("```\n").append(context.getDockerStatus()).append("\n```\n\n");
        }

        // 目录索引
        if (context.getDirectoryIndex() != null && !context.getDirectoryIndex().isEmpty()) {
            sb.append("### 关键目录索引\n");
            sb.append("```\n").append(context.getDirectoryIndex()).append("\n```\n\n");
        }

        sb.append("---\n");
        sb.append("请基于以上服务器上下文信息进行运维操作和决策。");

        return sb.toString();
    }

    // ========== 内部辅助方法 ==========

    /**
     * 安全执行SSH命令，捕获异常并返回输出。
     * 命令失败时返回空字符串而非抛出异常，保证上下文拉取流程不被中断。
     */
    private String safeExecute(String sshKey, String command) {
        try {
            String result = SshConnectionManager.getInstance().executeCommand(sshKey, command);
            return result != null ? result.trim() : "";
        } catch (Exception e) {
            logger.debug("命令执行失败（已忽略）: cmd={}, error={}", command, e.getMessage());
            return "";
        }
    }

    /**
     * 拉取Docker信息，检测是否安装Docker并获取容器状态。
     */
    private void fetchDockerInfo(String sshKey, ServerContext ctx) {
        String dockerCheck = safeExecute(sshKey, "which docker 2>/dev/null");
        if (dockerCheck != null && !dockerCheck.isEmpty() && !dockerCheck.contains("not found")) {
            ctx.setDockerInstalled(true);
            ctx.setDockerStatus(safeExecute(sshKey, "docker ps 2>/dev/null"));
        } else {
            ctx.setDockerInstalled(false);
            ctx.setDockerStatus("");
        }
    }

    /**
     * 空值转换为 "N/A"。
     */
    private String nullToNa(String value) {
        if (value == null || value.isEmpty()) {
            return "N/A";
        }
        return value;
    }
}
