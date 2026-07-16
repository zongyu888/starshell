package com.aifinalshell.controller;

import com.aifinalshell.ai.TerminalEvent;
import com.aifinalshell.ai.TerminalEventBridge;
import javafx.application.Platform;

/**
 * 终端事件桥接助手。
 * 将终端输出通过 TerminalEventBridge 进行事件检测，
 * 检测到错误、需要输入、服务启动等事件时通知 MainController 进行 UI 反馈。
 *
 * 工作流程：
 * 1. 终端控制器产生输出 -> TerminalEventBridge.feedOutput() 进行事件检测
 * 2. 检测到事件 -> 通过 watch 回调通知 -> handleEvent() 分发到 MainController
 */
public class TerminalBridgeHelper {

    /**
     * 注册终端事件监听，建立终端输出与AI事件检测的桥梁。
     *
     * 步骤：
     * 1. 设置终端数据回调：终端输出 -> TerminalEventBridge.feedOutput()
     * 2. 注册事件监听器：TerminalEventBridge.watch() -> handleEvent()
     *
     * @param sshKey    SSH连接键
     * @param terminal  终端控制器
     * @param controller 主控制器
     */
    public static void setupBridge(String sshKey, TerminalController terminal, MainController controller) {
        // 1. 设置终端数据回调：终端输出推送到事件桥接器进行检测
        terminal.setOnDataReceived(output -> {
            TerminalEventBridge.getInstance().feedOutput(sshKey, output);
        });

        // 2. 注册事件监听器：检测到事件时在JavaFX线程中处理UI反馈
        TerminalEventBridge.getInstance().watch(sshKey, event -> {
            // AI 命令的完整输出会直接回传 ToolAgent 继续分析；此时再插入独立“终端错误”气泡
            // 会重复打断对话。保留 INPUT_REQUIRED 提示，方便用户在密码/确认场景介入。
            if (terminal.isAiCommandActive() && event.isError()) {
                return;
            }
            Platform.runLater(() -> handleEvent(event, controller));
        });

        // 3. 终端+AI 联动：注册可见终端，供 run_in_terminal 工具按 sshKey 路由命令
        TerminalCommandBridge.getInstance().register(sshKey, terminal);

        // 4. AI 终端操作状态回调：AI 打字/执行/空闲时更新终端底部的 🤖 指示器
        terminal.setOnAiBusy((state, command) -> {
            // 此回调已在 FX 线程执行（executeCaptured 内部通过 Platform.runLater 调用）
            controller.updateAiTerminalStatus(state, command);
        });
    }

    /**
     * 注销终端事件监听，清理桥梁。
     *
     * @param sshKey SSH连接键
     */
    public static void teardownBridge(String sshKey) {
        TerminalEventBridge.getInstance().stopWatching(sshKey);
        // 终端+AI 联动：注销可见终端
        TerminalCommandBridge.getInstance().unregister(sshKey);
    }

    /**
     * 处理检测到的终端事件，分发到 MainController 的对应通知方法。
     *
     * @param event      终端事件
     * @param controller 主控制器
     */
    private static void handleEvent(TerminalEvent event, MainController controller) {
        switch (event.getEventType()) {
            case ERROR_DETECTED:
                // 终端检测到错误：显示错误通知，提供AI分析入口
                controller.showTerminalErrorNotification(event);
                break;
            case INPUT_REQUIRED:
                // 终端等待输入：在状态栏提示
                controller.showTerminalInputHint(event);
                break;
            case SERVICE_STARTED:
            case PORT_LISTENING:
            case DEPLOYMENT_COMPLETE:
                // 服务启动/端口监听/部署完成：显示成功通知
                controller.showTerminalSuccessNotification(event);
                break;
        }
    }
}
