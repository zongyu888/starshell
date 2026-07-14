package com.aifinalshell.controller;

import com.aifinalshell.ai.DeployOrchestrator;
import com.aifinalshell.model.ServerConfig;
import javafx.application.Platform;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.StackPane;

import java.io.File;

/**
 * 部署UI助手。
 * 在AI聊天面板注册JAR文件拖拽事件，拖拽JAR包后自动触发部署编排流程。
 * 部署进度、执行的命令、错误信息实时显示在AI对话气泡中。
 */
public class DeployUiHelper {

    /**
     * 在AI面板注册JAR拖拽部署事件。
     *
     * @param aiChatStack AI聊天面板的StackPane容器
     * @param controller  主控制器
     */
    public static void setupJarDragDrop(StackPane aiChatStack, MainController controller) {
        // 拖拽悬停：检测是否包含JAR文件
        aiChatStack.setOnDragOver(event -> {
            Dragboard db = event.getDragboard();
            if (db.hasFiles()) {
                boolean hasJar = db.getFiles().stream()
                        .anyMatch(f -> f.getName().toLowerCase().endsWith(".jar"));
                if (hasJar) {
                    event.acceptTransferModes(TransferMode.COPY);
                    return;
                }
            }
            event.consume();
        });

        // 拖拽释放：对每个JAR文件触发部署流程
        aiChatStack.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
                for (File file : db.getFiles()) {
                    if (file.getName().toLowerCase().endsWith(".jar")) {
                        triggerDeploy(file, controller);
                        success = true;
                    }
                }
            }
            event.setDropCompleted(success);
            event.consume();
        });
    }

    /**
     * 触发JAR包部署流程。
     * 在AI聊天面板显示用户消息和助手气泡，后台执行部署编排。
     *
     * @param jarFile    本地JAR文件
     * @param controller 主控制器
     */
    private static void triggerDeploy(File jarFile, MainController controller) {
        // 前置检查：必须已连接服务器
        String sshKey = controller.getCurrentConnectionKey();
        if (sshKey == null) {
            Platform.runLater(() -> {
                controller.addChatMessage("user", "部署JAR包: " + jarFile.getName());
                controller.startAssistantBubble();
                controller.appendToAssistantBubble(
                        "**无法部署**: 未连接到服务器，请先连接服务器再拖拽JAR包部署。\n");
                controller.finalizeAssistantBubble();
            });
            return;
        }

        ServerConfig server = controller.getSelectedServer();
        Long serverId = (server != null) ? server.getId() : null;
        if (serverId == null) {
            return;
        }

        String localJarPath = jarFile.getAbsolutePath();

        // 在AI聊天面板显示用户消息气泡
        Platform.runLater(() -> {
            controller.addChatMessage("user", "部署JAR包: " + jarFile.getName());
            // 开始assistant气泡，实时显示部署进度
            controller.startAssistantBubble();
        });

        // 后台线程执行部署编排（deployJar是同步方法，在后台线程中运行避免阻塞UI）
        new Thread(() -> {
            DeployOrchestrator.getInstance().deployJar(
                    localJarPath,
                    sshKey,
                    serverId,
                    // onProgress: 部署进度信息
                    progress -> Platform.runLater(() ->
                            controller.appendToAssistantBubble(progress + "\n")),
                    // onCommand: 执行的命令（以代码块格式展示）
                    command -> Platform.runLater(() ->
                            controller.appendToAssistantBubble("```\n" + command + "\n```\n")),
                    // onError: 错误信息
                    error -> Platform.runLater(() ->
                            controller.appendToAssistantBubble(
                                    "\n**部署失败**: " + error.getMessage() + "\n"))
            );
            // 部署完成后最终化助手气泡（渲染Markdown）
            Platform.runLater(() -> controller.finalizeAssistantBubble());
        }, "DeployJar-" + jarFile.getName()).start();
    }
}
