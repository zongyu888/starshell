package com.aifinalshell.controller;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 聊天气泡工厂
 * 从MainController提取聊天气泡创建逻辑，集成MarkdownRenderer
 * 提供用户气泡、AI气泡的创建以及流式更新（120ms节流渲染）
 */
public class ChatBubbleFactory {

    /** 气泡最大宽度 */
    public static final double MAX_BUBBLE_WIDTH = 404;

    /** 节流渲染间隔（毫秒）：120ms 兼顾流式顺滑度与渲染开销 */
    private static final long THROTTLE_MS = 120;

    /** 每个气泡的上次渲染时间戳 */
    private static final Map<VBox, Long> lastRenderTime = new ConcurrentHashMap<>();

    /** 每个气泡的待处理渲染定时器 */
    private static final Map<VBox, Timeline> pendingTimers = new ConcurrentHashMap<>();

    /**
     * 创建用户消息气泡（蓝色背景#1f6feb，右对齐，白色文字）
     * <p>内容用 {@link TextArea}（editable=false, wrapText=true）承载，原生支持鼠标拖选 + Ctrl+C，
     * 解决 Label 不可选、用户无法复制自己输入的问题（修复项1）。</p>
     *
     * @param content 消息内容
     * @return 用户气泡VBox
     */
    public static VBox createUserBubble(String content) {
        VBox bubble = new VBox(7);
        bubble.setMaxWidth(MAX_BUBBLE_WIDTH);
        bubble.getStyleClass().add("ai-bubble-user");
        bubble.setPadding(new Insets(11, 14, 11, 14));
        bubble.setAlignment(Pos.CENTER_RIGHT);

        Label role = new Label("YOU");
        role.getStyleClass().add("ai-bubble-role-user");
        HBox roleRow = new HBox(role);
        roleRow.setAlignment(Pos.CENTER_RIGHT);
        bubble.getChildren().add(roleRow);

        TextArea contentArea = new TextArea(content);
        contentArea.setEditable(false);
        contentArea.setWrapText(true);
        contentArea.setMaxWidth(MAX_BUBBLE_WIDTH - 28);
        fitMessageArea(contentArea, content, 168);
        // CSS 伪装成蓝色气泡内的白色文字：透明背景/无边框/无内边距，与 Label 视觉一致
        contentArea.getStyleClass().add("ai-bubble-user-text");
        bubble.getChildren().add(contentArea);

        return bubble;
    }

    /**
     * 创建AI消息气泡（深灰背景#2a2a2a，左对齐，🤖 Agent标签 + Markdown渲染内容）
     * 气泡结构：index 0 = agentTag(HBox)，index 1 = content(VBox)
     *
     * @param content                 消息内容（Markdown格式）
     * @param sendToTerminalCallback   发送到终端回调（用于代码块的"发送到终端"按钮）
     * @return AI气泡VBox
     */
    public static VBox createAssistantBubble(String content, Consumer<String> sendToTerminalCallback) {
        VBox bubble = new VBox(8);
        bubble.setMaxWidth(MAX_BUBBLE_WIDTH);
        bubble.getStyleClass().add("ai-bubble-assistant");
        bubble.setPadding(new Insets(12, 14, 11, 14));
        bubble.setAlignment(Pos.CENTER_LEFT);

        // Agent标签（index 0）
        Label avatar = new Label("✦");
        avatar.getStyleClass().add("ai-agent-avatar");
        Label agentName = new Label("STARSHELL AGENT");
        agentName.getStyleClass().add("ai-bubble-role-agent");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label liveState = new Label("● LIVE");
        liveState.getStyleClass().add("ai-bubble-live-state");
        HBox agentTag = new HBox(7, avatar, agentName, spacer, liveState);
        agentTag.setAlignment(Pos.CENTER_LEFT);
        bubble.getChildren().add(agentTag);
        bubble.getProperties().put("liveStateLabel", liveState);

        // 内容区域（index 1）
        VBox contentBox = MarkdownRenderer.render(content, sendToTerminalCallback);
        contentBox.setMaxWidth(MAX_BUBBLE_WIDTH - 28);
        bubble.getChildren().add(contentBox);

        // 暂存回调与原文，供 replaceContent 流式更新时还原"发送到终端"回调
        bubble.getProperties().put("sendToTerminalCallback", sendToTerminalCallback);
        bubble.getProperties().put("fullText", content == null ? "" : content);

        // 右键上下文菜单：复制全部 / 复制选中
        attachContextMenu(bubble);

        return bubble;
    }

    /**
     * 为 AI 气泡附加右键 ContextMenu（复制全部 / 复制选中）。
     * <p>"复制全部"从 bubble properties 取最新 fullText（流式更新时由 replaceContent 刷新）；
     * "复制选中"遍历气泡内 TextArea 取选中文本，无选中时禁用。</p>
     *
     * @param bubble 目标 AI 气泡 VBox
     */
    private static void attachContextMenu(VBox bubble) {
        ContextMenu contextMenu = new ContextMenu();

        MenuItem copyAll = new MenuItem("复制全部");
        copyAll.setOnAction(e -> {
            Object raw = bubble.getProperties().get("fullText");
            String fullText = raw == null ? "" : raw.toString();
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent cc = new ClipboardContent();
            cc.putString(fullText);
            clipboard.setContent(cc);
        });

        MenuItem copySelected = new MenuItem("复制选中");
        copySelected.setOnAction(e -> {
            String selected = getSelectedTextFromBubble(bubble);
            if (selected != null && !selected.isEmpty()) {
                Clipboard clipboard = Clipboard.getSystemClipboard();
                ClipboardContent cc = new ClipboardContent();
                cc.putString(selected);
                clipboard.setContent(cc);
            }
        });

        contextMenu.getItems().addAll(copyAll, copySelected);

        // 菜单显示前根据是否有选中文本启用/禁用"复制选中"
        contextMenu.setOnShowing(e -> {
            String selected = getSelectedTextFromBubble(bubble);
            copySelected.setDisable(selected == null || selected.isEmpty());
        });

        bubble.setOnContextMenuRequested(e -> {
            contextMenu.show(bubble, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    /**
     * 遍历 AI 气泡内容区（index 1 的 VBox）查找有选中文本的 TextArea。
     *
     * @param bubble 目标 AI 气泡 VBox
     * @return 第一个有选中文本的 TextArea 的选中文本，无则 null
     */
    private static String getSelectedTextFromBubble(VBox bubble) {
        if (bubble.getChildren().size() < 2) return null;
        Node content = bubble.getChildren().get(1);
        if (!(content instanceof VBox contentBox)) return null;
        for (Node node : contentBox.getChildren()) {
            if (node instanceof TextArea ta) {
                String selected = ta.getSelectedText();
                if (selected != null && !selected.isEmpty()) {
                    return selected;
                }
            }
        }
        return null;
    }

    /**
     * 流式更新AI气泡内容（200ms节流渲染）
     * 保留index 0的agentTag，替换index 1的content
     * 节流策略：距上次渲染不足200ms时取消旧定时器并安排新渲染
     *
     * @param bubble                  目标气泡VBox
     * @param accumulatedText          累积的完整文本
     * @param sendToTerminalCallback   发送到终端回调
     */
    public static void updateAssistantBubble(VBox bubble, String accumulatedText, Consumer<String> sendToTerminalCallback) {
        if (bubble == null) return;

        long now = System.currentTimeMillis();
        Long last = lastRenderTime.get(bubble);

        if (last == null || (now - last) >= THROTTLE_MS) {
            // 距上次渲染已超过200ms，立即渲染
            lastRenderTime.put(bubble, now);
            cancelPending(bubble);
            replaceContent(bubble, accumulatedText, sendToTerminalCallback);
        } else {
            // 节流：取消之前的定时器，安排延迟渲染（使用最新文本）
            cancelPending(bubble);
            long delay = THROTTLE_MS - (now - last);
            Timeline timer = new Timeline(new KeyFrame(Duration.millis(delay), e -> {
                lastRenderTime.put(bubble, System.currentTimeMillis());
                pendingTimers.remove(bubble);
                replaceContent(bubble, accumulatedText, sendToTerminalCallback);
            }));
            pendingTimers.put(bubble, timer);
            timer.play();
        }
    }

    /**
     * 最终渲染（流式结束时调用，取消待处理定时器并立即渲染完整内容）
     *
     * @param bubble                  目标气泡VBox
     * @param fullText                完整文本
     * @param sendToTerminalCallback   发送到终端回调
     */
    public static void finalizeBubble(VBox bubble, String fullText, Consumer<String> sendToTerminalCallback) {
        if (bubble == null) return;
        cancelPending(bubble);
        lastRenderTime.remove(bubble);
        replaceContent(bubble, fullText, sendToTerminalCallback);
        markComplete(bubble);
    }

    /**
     * 释放气泡占用的资源：取消待处理渲染定时器并从静态 Map 移除引用。
     * <p>P0-8 修复：必须在气泡从 UI 移除（清空聊天区/切换会话）时调用，
     * 否则 static {@link #lastRenderTime} 与 {@link #pendingTimers} 会持有已移除气泡的
     * VBox 引用，导致 GC 无法回收（内存泄漏），且 pendingTimers 中的 Timeline 会继续
     * 持有气泡触发空渲染。</p>
     *
     * @param bubble 待释放的气泡 VBox（null 安全）
     */
    public static void disposeBubble(VBox bubble) {
        if (bubble == null) return;
        cancelPending(bubble);
        lastRenderTime.remove(bubble);
    }

    /**
     * 为已完成的 AI 气泡添加操作按钮（复制 / 重试）。
     * 在流式结束（finalizeBubble）或加载历史消息后调用。
     * 气泡结构：index 0 = agentTag，index 1 = content，index 2 = actionBox（本方法添加）
     *
     * @param bubble               目标气泡 VBox
     * @param fullText             完整消息文本（用于复制）
     * @param regenerateCallback   重试回调（重新生成最后回复）
     */
    public static void addActionButtons(VBox bubble, String fullText, Runnable regenerateCallback) {
        if (bubble == null) return;
        markComplete(bubble);
        // 避免重复添加（已有 actionBox 时跳过）
        if (bubble.getChildren().size() >= 3) return;

        HBox actions = new HBox(6);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setPadding(new Insets(4, 0, 0, 0));

        // 复制按钮：复制完整消息文本到剪贴板，1.5s 内显示"已复制"反馈
        Button copyBtn = new Button("⧉ 复制");
        copyBtn.getStyleClass().add("ai-msg-action-btn");
        copyBtn.setOnAction(e -> {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent content = new ClipboardContent();
            content.putString(fullText);
            clipboard.setContent(content);
            copyBtn.setText("✓ 已复制");
            new Timeline(new KeyFrame(Duration.millis(1500), ev -> copyBtn.setText("⧉ 复制"))).play();
        });

        // 重试按钮：触发重新生成
        Button regenBtn = new Button("↻ 重试");
        regenBtn.getStyleClass().add("ai-msg-action-btn");
        regenBtn.setOnAction(e -> regenerateCallback.run());

        actions.getChildren().addAll(copyBtn, regenBtn);
        bubble.getChildren().add(actions);

        // 刷新 fullText 为最终完整文本，供右键菜单"复制全部"使用
        bubble.getProperties().put("fullText", fullText == null ? "" : fullText);
    }

    /**
     * 替换气泡内容（index 1）
     */
    private static void replaceContent(VBox bubble, String text, Consumer<String> sendToTerminalCallback) {
        VBox contentBox = MarkdownRenderer.render(text, sendToTerminalCallback);
        contentBox.setMaxWidth(MAX_BUBBLE_WIDTH - 28);
        if (bubble.getChildren().size() >= 2) {
            // 替换index 1的内容
            bubble.getChildren().set(1, contentBox);
        } else if (bubble.getChildren().size() == 1) {
            // 尚无内容节点，添加
            bubble.getChildren().add(contentBox);
        }
        // 刷新 fullText，供右键菜单"复制全部"取最新文本
        bubble.getProperties().put("fullText", text == null ? "" : text);
    }

    private static void markComplete(VBox bubble) {
        Object node = bubble.getProperties().get("liveStateLabel");
        if (node instanceof Label label) {
            label.setText("● DONE");
            label.getStyleClass().remove("ai-bubble-live-state");
            if (!label.getStyleClass().contains("ai-bubble-done-state")) {
                label.getStyleClass().add("ai-bubble-done-state");
            }
        }
    }

    /** 根据文本长度估算只读消息框高度，避免短消息仍占用 TextArea 的默认大块空白。 */
    private static void fitMessageArea(TextArea area, String text, double maxHeight) {
        String safe = text == null ? "" : text;
        int visualLines = 0;
        for (String line : safe.split("\\R", -1)) {
            visualLines += Math.max(1, (line.length() + 38) / 39);
        }
        double height = Math.max(30, Math.min(maxHeight, visualLines * 20.0 + 8));
        area.setPrefHeight(height);
        area.setMinHeight(height);
        area.setMaxHeight(maxHeight);
    }

    /**
     * 取消待处理的渲染定时器
     */
    private static void cancelPending(VBox bubble) {
        Timeline timer = pendingTimers.remove(bubble);
        if (timer != null) {
            timer.stop();
        }
    }
}
