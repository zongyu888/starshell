package com.aifinalshell.controller;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 可见终端命令桥接器：按 sshKey 路由 AI 工具命令到用户可见的交互式终端。
 * 与 TerminalBridgeHelper 协作：连接时 register，断开时 unregister。
 *
 * 工作流程：
 * 1. 用户连接服务器 -> TerminalBridgeHelper.setupBridge -> register(sshKey, terminal)
 * 2. AI 调用 run_in_terminal 工具 -> execute(sshKey, command, timeout)
 *    -> TerminalController.executeCaptured 哨兵法捕获可见终端输出
 * 3. 用户断开服务器 -> TerminalBridgeHelper.teardownBridge -> unregister(sshKey)
 *
 * <p>修复项5（AI 与终端同步）：execute 在检测到终端断连时，先尝试一次自动重连
 * （通过 {@link ReconnectStrategy}，由 MainController 注入），重连成功则重试命令；
 * 失败则返回统一、明确的错误，引导 AI 改用 execute_shell，避免 AI 盲目重试卡死。</p>
 */
public class TerminalCommandBridge {
    private static final TerminalCommandBridge INSTANCE = new TerminalCommandBridge();
    private final ConcurrentHashMap<String, TerminalController> terminals = new ConcurrentHashMap<>();

    /** 自动重连策略（由 MainController 注入）；为 null 时不重连，直接返回断连错误 */
    private volatile ReconnectStrategy reconnectStrategy;

    private TerminalCommandBridge() {}

    public static TerminalCommandBridge getInstance() {
        return INSTANCE;
    }

    /**
     * 自动重连策略：终端断连时由 Bridge 调用一次，尝试恢复可见终端。
     * 实现应同步返回（Bridge.execute 在 AI 后台线程调用），true 表示重连成功且终端就绪。
     */
    @FunctionalInterface
    public interface ReconnectStrategy {
        boolean reconnect(String sshKey);
    }

    /**
     * 注入自动重连策略（通常在 MainController 初始化终端时调用一次）。
     *
     * @param strategy 重连策略；null 表示禁用自动重连
     */
    public void setReconnectStrategy(ReconnectStrategy strategy) {
        this.reconnectStrategy = strategy;
    }

    /**
     * 注册可见终端，供 run_in_terminal 工具按 sshKey 路由命令。
     *
     * @param sshKey   SSH 连接键（sessionId + "_" + serverId）
     * @param terminal 终端控制器
     */
    public void register(String sshKey, TerminalController terminal) {
        if (sshKey != null && terminal != null) {
            terminals.put(sshKey, terminal);
        }
    }

    /**
     * 注销可见终端（断开连接时调用）。
     *
     * @param sshKey SSH 连接键
     */
    public void unregister(String sshKey) {
        if (sshKey != null) {
            terminals.remove(sshKey);
        }
    }

    /** execute 的判定结果（抽出为纯函数，便于单元测试覆盖分支逻辑） */
    enum ExecuteOutcome {
        /** 终端已连接，直接执行 */
        EXECUTE,
        /** 终端断连但可尝试重连 */
        RECONNECT,
        /** 未注册可见终端 */
        ERROR_NO_TERMINAL,
        /** 终端断连且（重连后仍）不可用 */
        ERROR_DISCONNECTED
    }

    /**
     * 纯函数判定 execute 应采取的动作，无副作用、不依赖 JavaFX，便于单元测试。
     *
     * @param terminalPresent     是否存在已注册的可见终端
     * @param connected           该终端当前是否已连接
     * @param reconnectAvailable  是否注入了重连策略
     * @return 对应的执行结果
     */
    static ExecuteOutcome decide(boolean terminalPresent, boolean connected, boolean reconnectAvailable) {
        if (!terminalPresent) return ExecuteOutcome.ERROR_NO_TERMINAL;
        if (connected) return ExecuteOutcome.EXECUTE;
        if (reconnectAvailable) return ExecuteOutcome.RECONNECT;
        return ExecuteOutcome.ERROR_DISCONNECTED;
    }

    /** 未注册可见终端时的统一错误（引导 AI 改用 execute_shell） */
    static final String ERR_NO_TERMINAL =
            "Error: no visible terminal registered for this server. "
                    + "Connect to the server first, or use execute_shell for non-interactive commands.";

    /** 终端断连且重连失败时的统一错误（引导 AI 改用 execute_shell） */
    static final String ERR_DISCONNECTED =
            "Error: terminal disconnected and auto-reconnect failed. "
                    + "Reconnect the server manually, or use execute_shell for non-interactive commands.";

    /**
     * 在可见终端里执行命令并返回捕获的输出。
     *
     * @param sshKey    SSH 连接键
     * @param command   要执行的 shell 命令
     * @param timeoutMs 等待输出的超时时间（毫秒）
     * @return 命令输出文本；未注册/未连接（且重连失败）时返回错误说明（提示改用 execute_shell）
     */
    public String execute(String sshKey, String command, long timeoutMs) {
        TerminalController t = (sshKey == null) ? null : terminals.get(sshKey);
        boolean present = (t != null);
        boolean connected = present && t.isConnected();
        boolean reconnectAvailable = (reconnectStrategy != null);

        switch (decide(present, connected, reconnectAvailable)) {
            case ERROR_NO_TERMINAL:
                return ERR_NO_TERMINAL;
            case EXECUTE:
                return t.executeCaptured(command, timeoutMs);
            case RECONNECT:
                // 尝试一次自动重连；成功则重新取终端并执行命令
                if (tryReconnect(sshKey)) {
                    TerminalController t2 = terminals.get(sshKey);
                    if (t2 != null && t2.isConnected()) {
                        return t2.executeCaptured(command, timeoutMs);
                    }
                }
                return ERR_DISCONNECTED;
            case ERROR_DISCONNECTED:
            default:
                return ERR_DISCONNECTED;
        }
    }

    /**
     * 调用注入的重连策略（一次）。任何异常都吞掉并视为失败，保证不阻断 AI 工具主流程。
     *
     * @param sshKey SSH 连接键
     * @return true 表示重连策略返回成功
     */
    private boolean tryReconnect(String sshKey) {
        ReconnectStrategy s = this.reconnectStrategy;
        if (s == null) return false;
        try {
            return s.reconnect(sshKey);
        } catch (Exception e) {
            // 重连异常降级：返回失败，由 execute 统一返回 ERR_DISCONNECTED
            return false;
        }
    }
}
