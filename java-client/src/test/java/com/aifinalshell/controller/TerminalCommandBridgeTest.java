package com.aifinalshell.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TerminalCommandBridge} 单元测试（修复项5 回归防护）。
 *
 * <p>覆盖范围：</p>
 * <ul>
 *   <li>{@code decide()} 分支判定纯函数的全部 4 种结果及优先级（无终端 > 重连）</li>
 *   <li>错误消息常量非空且明确要求恢复可见终端</li>
 *   <li>{@code execute()} 在未注册终端时的错误返回路径</li>
 *   <li>重连策略注入后，无终端场景仍优先返回"无终端"（验证优先级在集成路径也成立）</li>
 * </ul>
 *
 * <p><b>说明</b>：{@code EXECUTE} 与 {@code RECONNECT} 成功重试路径依赖 {@link TerminalController}
 * （构造需 JavaFX TextArea，无法在无头单测中实例化），由修复计划验证矩阵 V10/V11/V12 手动覆盖；
 * 本类聚焦可自动化的分支判定与错误返回逻辑。</p>
 */
class TerminalCommandBridgeTest {

    private final TerminalCommandBridge bridge = TerminalCommandBridge.getInstance();

    @AfterEach
    void cleanup() {
        // 恢复单例状态，避免测试间互相干扰
        bridge.setReconnectStrategy(null);
        bridge.unregister("test_key");
        bridge.unregister("unregistered_key");
        bridge.unregister(null);
    }

    // ==================== decide() 纯函数分支测试 ====================

    @Nested
    @DisplayName("decide() 分支判定")
    class DecideBranching {

        @Test
        @DisplayName("无终端 → ERROR_NO_TERMINAL（优先级最高，即使可重连也返回无终端）")
        void noTerminalTakesPriority() {
            assertEquals(TerminalCommandBridge.ExecuteOutcome.ERROR_NO_TERMINAL,
                    TerminalCommandBridge.decide(false, false, false));
            // 即使注入了重连策略，没有终端时也不应进入重连分支
            assertEquals(TerminalCommandBridge.ExecuteOutcome.ERROR_NO_TERMINAL,
                    TerminalCommandBridge.decide(false, false, true));
            // 边界：无终端但 connected=true（逻辑上不应发生）仍按"无终端"处理，防止 NPE
            assertEquals(TerminalCommandBridge.ExecuteOutcome.ERROR_NO_TERMINAL,
                    TerminalCommandBridge.decide(false, true, true));
        }

        @Test
        @DisplayName("终端已连接 → EXECUTE")
        void connectedExecutes() {
            assertEquals(TerminalCommandBridge.ExecuteOutcome.EXECUTE,
                    TerminalCommandBridge.decide(true, true, false));
            assertEquals(TerminalCommandBridge.ExecuteOutcome.EXECUTE,
                    TerminalCommandBridge.decide(true, true, true));
        }

        @Test
        @DisplayName("终端断连且有重连策略 → RECONNECT")
        void disconnectedWithStrategyReconnects() {
            assertEquals(TerminalCommandBridge.ExecuteOutcome.RECONNECT,
                    TerminalCommandBridge.decide(true, false, true));
        }

        @Test
        @DisplayName("终端断连且无重连策略 → ERROR_DISCONNECTED")
        void disconnectedWithoutStrategyErrors() {
            assertEquals(TerminalCommandBridge.ExecuteOutcome.ERROR_DISCONNECTED,
                    TerminalCommandBridge.decide(true, false, false));
        }
    }

    // ==================== 错误消息常量测试 ====================

    @Nested
    @DisplayName("错误消息引导")
    class ErrorMessages {

        @Test
        @DisplayName("无终端错误要求先连接可见终端")
        void noTerminalErrorRequiresVisibleConnection() {
            assertNotNull(TerminalCommandBridge.ERR_NO_TERMINAL);
            assertTrue(TerminalCommandBridge.ERR_NO_TERMINAL.contains("visible"),
                    "无终端错误应说明命令必须保持用户可见");
            assertTrue(TerminalCommandBridge.ERR_NO_TERMINAL.contains("Connect"),
                    "无终端错误应引导用户连接服务器");
            assertTrue(TerminalCommandBridge.ERR_NO_TERMINAL.toLowerCase().contains("error"),
                    "错误消息应以 Error 标识");
        }

        @Test
        @DisplayName("断连错误要求手动重连")
        void disconnectedErrorRequiresReconnect() {
            assertNotNull(TerminalCommandBridge.ERR_DISCONNECTED);
            assertTrue(TerminalCommandBridge.ERR_DISCONNECTED.contains("Reconnect"),
                    "断连错误应引导用户恢复可见终端");
            assertTrue(TerminalCommandBridge.ERR_DISCONNECTED.toLowerCase().contains("disconnected"),
                    "断连错误应说明是断连");
        }
    }

    // ==================== execute() 集成路径（无 JavaFX 依赖部分） ====================

    @Nested
    @DisplayName("execute() 错误返回路径")
    class ExecuteErrorPaths {

        @Test
        @DisplayName("null sshKey → 返回无终端错误（不抛异常）")
        void nullKeyReturnsNoTerminalError() {
            String result = bridge.execute(null, "ls", 1000);
            assertEquals(TerminalCommandBridge.ERR_NO_TERMINAL, result);
        }

        @Test
        @DisplayName("未注册的 sshKey → 返回无终端错误")
        void unregisteredKeyReturnsNoTerminalError() {
            String result = bridge.execute("unregistered_key", "ls", 1000);
            assertEquals(TerminalCommandBridge.ERR_NO_TERMINAL, result);
        }

        @Test
        @DisplayName("注入重连策略后，无终端场景仍返回无终端错误（优先级在集成路径成立）")
        void strategyDoesNotFireWithoutTerminal() {
            boolean[] strategyInvoked = {false};
            bridge.setReconnectStrategy(sshKey -> {
                strategyInvoked[0] = true;
                return false;
            });
            String result = bridge.execute("unregistered_key", "ls", 1000);
            assertEquals(TerminalCommandBridge.ERR_NO_TERMINAL, result,
                    "无终端时应优先返回无终端错误，不应调用重连策略");
            assertEquals(false, strategyInvoked[0],
                    "无终端时重连策略不应被调用");
        }

        @Test
        @DisplayName("单例实例非空")
        void singletonNotNull() {
            assertNotNull(TerminalCommandBridge.getInstance());
            // 同一 JVM 内多次获取应为同一实例
            assertEquals(TerminalCommandBridge.getInstance(), TerminalCommandBridge.getInstance());
        }
    }
}
