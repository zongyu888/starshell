package com.aifinalshell.agent.tool;

/**
 * Context passed to tool executors, providing access to SSH and server info.
 *
 * sshKey 来源说明（连接键漂移修复）：
 * 优先使用 explicitSshKey —— 即 connectToServer 连接成功时设置、disconnectServer 清除的
 * currentConnectionKey（实际存储在 SshConnectionManager 中的键）。它不受 AI 发送时新建/切换
 * 会话导致的 sessionId 漂移影响。仅当未显式传入时才回退到 sessionId + "_" + serverId 重算。
 */
public class ToolContext {
    private final String sessionId;
    private final String serverId;
    private final String serverHost;
    private final String serverUser;
    private final String explicitSshKey;

    /** 向后兼容构造：不传显式连接键，getSshKey() 回退到 sessionId + "_" + serverId 重算。 */
    public ToolContext(String sessionId, String serverId, String serverHost, String serverUser) {
        this(sessionId, serverId, serverHost, serverUser, null);
    }

    /**
     * 推荐构造：显式传入连接时实际存储的 sshKey（即 MainController.currentConnectionKey），
     * 避免 AI 工具因会话 ID 漂移而命中错误的连接键。
     */
    public ToolContext(String sessionId, String serverId, String serverHost, String serverUser, String explicitSshKey) {
        this.sessionId = sessionId;
        this.serverId = serverId;
        this.serverHost = serverHost;
        this.serverUser = serverUser;
        this.explicitSshKey = explicitSshKey;
    }

    public String getSessionId() { return sessionId; }
    public String getServerId() { return serverId; }
    public String getServerHost() { return serverHost; }
    public String getServerUser() { return serverUser; }

    /**
     * 获取工具执行用的 SSH 连接键。
     * 优先返回显式传入的 explicitSshKey（连接时实际存储的键），避免会话 ID 漂移；
     * 未传入时回退到 sessionId + "_" + serverId（保留旧行为，向后兼容）。
     */
    public String getSshKey() {
        if (explicitSshKey != null && !explicitSshKey.isEmpty()) {
            return explicitSshKey;
        }
        return sessionId + "_" + serverId;
    }
}
