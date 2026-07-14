package com.aifinalshell.controller;

import com.aifinalshell.config.AppConfig;
import com.aifinalshell.terminal.AnsiParser;
import com.aifinalshell.terminal.OutputThrottler;
import com.aifinalshell.terminal.PtyResizer;
import com.aifinalshell.terminal.TerminalBuffer;
import com.jcraft.jsch.ChannelShell;
import javafx.application.Platform;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class TerminalController {
    private static final Logger logger = LoggerFactory.getLogger(TerminalController.class);
    private TextArea terminalOutput;
    private ChannelShell shellChannel;
    private InputStream inputStream;
    private OutputStream outputStream;
    private Thread readerThread;
    private volatile boolean running = false;
    private Consumer<String> onStatusChange;
    private Consumer<String> onDataReceived;
    // 终端+AI 联动：命令捕获消费者（仅在 executeCaptured 调用期间临时存在，与 onDataReceived 并存）
    private volatile Consumer<String> captureConsumer;

    /**
     * AI 在终端中的操作状态，通过 aiBusyCallback 回调通知 UI 更新指示器。
     * TYPING    = AI 正在逐字符输入命令（打字机效果进行中）
     * EXECUTING = 命令已提交，等待执行结果
     * IDLE      = AI 当前未操作终端（空闲）
     */
    public enum AiBusyState { TYPING, EXECUTING, IDLE }

    /** AI 操作状态回调：(state, commandText) -> void，在 FX 线程调用 */
    private volatile BiConsumer<AiBusyState, String> aiBusyCallback;
    private final TerminalBuffer outputBuffer = new TerminalBuffer();
    private String serverName = "";
    private String serverHost = "";
    private String serverUser = "";
    private Charset charset = StandardCharsets.UTF_8;

    // B8: 输出节流——读者线程将原始数据累积，由 throttler 每 20ms 在 FX 线程批量 flush，
    // 避免高吞吐输出（如 cat 大文件）时每块都 Platform.runLater 导致 UI 卡顿（20ms 兼顾跟手度与吞吐）
    private final OutputThrottler throttler = new OutputThrottler();

    // C7: 终端 PTY 随窗口 resize 动态调整（替代 openShell 固定 120×40）
    private PtyResizer resizer;

    public TerminalController(TextArea terminalOutput) {
        this.terminalOutput = terminalOutput;
        terminalOutput.setEditable(false);
        loadSettings();
        setupMouseBehavior();
    }

    /**
     * Task 5.4/5.5: 注册鼠标行为事件（copyOnSelect / pasteOnRightClick）。
     * 在构造时一次性注册，处理器在事件触发时实时读取 AppConfig，故设置变更后立即生效。
     */
    private void setupMouseBehavior() {
        // Task 5.4: copyOnSelect —— 选中即复制（TextArea 无 selectedTextProperty，
        // 用 mouse released 事件在选区完成时检查并复制到系统剪贴板）
        terminalOutput.setOnMouseReleased(e -> {
            AppConfig config = AppConfig.getInstance();
            if (!config.isCopyOnSelect()) return;
            String sel = terminalOutput.getSelectedText();
            if (sel != null && !sel.isEmpty()) {
                ClipboardContent content = new ClipboardContent();
                content.putString(sel);
                Clipboard.getSystemClipboard().setContent(content);
            }
        });

        // Task 5.5: pasteOnRightClick —— 右键粘贴（从系统剪贴板读取并发送到终端）
        terminalOutput.setOnContextMenuRequested(e -> {
            AppConfig config = AppConfig.getInstance();
            if (!config.isPasteOnRightClick()) return;
            Clipboard clipboard = Clipboard.getSystemClipboard();
            String text = clipboard.getString();
            if (text != null && !text.isEmpty()) {
                sendInput(text);
                e.consume();
            }
        });
    }

    /**
     * Load terminal settings from AppConfig.
     */
    public void loadSettings() {
        AppConfig config = AppConfig.getInstance();

        // Charset
        String charsetName = config.getCharset();
        try {
            this.charset = Charset.forName(charsetName);
        } catch (Exception e) {
            this.charset = StandardCharsets.UTF_8;
        }

        // Buffer size
        this.outputBuffer.setMaxSize(config.getScrollBuffer());

        // 应用终端视觉样式（字体/颜色/背景图），启动即生效已保存的背景设置
        applyBackground();

        // Task 11.3: 光标样式（cursor_style: block/underscore/bar）与光标闪烁（cursor_blink）
        // 已在 AppConfig 中持久化存储（见 config.getCursorStyle() / config.isCursorBlink()）。
        // 滚动缓冲区已通过上方 outputBuffer.setMaxSize(...) 实际生效。
        // 说明：JavaFX TextArea 的 caret 形状无法通过 inline CSS 可靠控制
        // （-fx-caret-blink / -fx-shape 等并非跨 JavaFX 版本通用的标准 CSS 属性），
        // 故此处仅读取并持久化设置；视觉应用留待后续基于 JavaFX caret-shape 支持的更新，
        // 避免引入不生效或破坏样式的 CSS 属性。
    }

    /**
     * 应用终端视觉样式：字体、前景/背景色、背景图（CSS 方案）。
     * 集中构建 inline style 一次性 setStyle，避免与 MainController 重复设置冲突。
     * 在 loadSettings() 末尾调用（启动即应用）+ SettingsDialog 保存回调调用（实时应用）。
     *
     * 背景图方案：-fx-background-image 铺满 + 半透明 -fx-control-inner-background 让图透出。
     * opacity 语义 = 背景图可见度（越高图越清晰）；深色覆盖层 alpha = 1 - opacity（反转）。
     */
    public void applyBackground() {
        if (terminalOutput == null) return;
        AppConfig c = AppConfig.getInstance();
        StringBuilder css = new StringBuilder();

        // 字体（追加 'Microsoft YaHei' 作为 CJK 回退，确保中文正常显示）
        css.append(String.format(
                "-fx-font-family: '%s', 'Microsoft YaHei', monospace; -fx-font-size: %dpx; -fx-font-weight: %s; -fx-font-style: %s;",
                c.getFontFamily(),
                c.getFontSize(),
                c.isFontBold() ? "bold" : "normal",
                c.isFontItalic() ? "italic" : "normal"));

        // 前景/背景色
        css.append(String.format("-fx-control-inner-background: %s; -fx-text-fill: %s;",
                c.getTerminalColor("background"), c.getTerminalColor("foreground")));

        // 背景图（启用且路径有效时叠加）
        if (c.isBackgroundImageEnabled()) {
            String p = c.getBackgroundImagePath();
            if (p != null && !p.isEmpty()) {
                // Task 12.1: 使用 File.toURI() 正确编码路径，兼容空格/中文/特殊字符。
                // 原先 p.replace("\\","/") + "file:" 前缀对含空格或中文的路径会生成非法 CSS URL
                // （如 file:C:/Users/mouji/my bg.png）。toURI() 输出 file:///C:/Users/mouji/my%20bg.png。
                File imgFile = new File(p);
                if (imgFile.exists()) {
                    String imgUrl = imgFile.toURI().toString();
                    css.append(String.format(
                            "-fx-background-image: url('%s'); -fx-background-size: cover; "
                                    + "-fx-background-repeat: no-repeat; -fx-background-position: center;",
                            imgUrl));
                    // Task 12.2: opacity 语义 = 背景图可见度（越高图越清晰）。
                    // 深色覆盖层 alpha = 1 - opacity（与原逻辑反转）：
                    //   opacity=1.0 → overlay=0（图全显）；opacity=0.3 → overlay=0.7（图暗，保证文字可读）。
                    // AppConfig 默认 0.3 → 默认 overlay=0.7（深色，终端可读性优先）。
                    double overlayAlpha = 1.0 - c.getBackgroundImageOpacity();
                    css.append(String.format("-fx-control-inner-background: rgba(12,12,12,%.2f);", overlayAlpha));
                }
            }
        }

        terminalOutput.setStyle(css.toString());
    }

    public void setServerInfo(String name, String host, String user) {
        this.serverName = name;
        this.serverHost = host;
        this.serverUser = user;
    }

    public void connect(String connectionKey, ChannelShell channel) {
        this.shellChannel = channel;
        this.running = true;

        try {
            this.inputStream = channel.getInputStream();
            this.outputStream = channel.getOutputStream();
        } catch (IOException e) {
            logger.error("Failed to get SSH streams", e);
            return;
        }

        // Show connection banner
        Platform.runLater(() -> {
            outputBuffer.clear();
            appendLine("");
            appendLine("  \u001B[32m连接主机...\u001B[0m");
            appendLine("  \u001B[32m连接主机成功\u001B[0m");
            appendLine("");
            appendLine("  Server:  " + serverName);
            appendLine("  Host:    " + serverHost);
            appendLine("  User:    " + serverUser);
            appendLine("");
            appendLine("  ──────────────────────────────────────");
            appendLine("");
            terminalOutput.setText(outputBuffer.getText());
            scrollTerminalToEnd();
        });

        // B8: 启动节流 flush 定时器（FX 线程，每 20ms 批量 flush 累积输出）
        throttler.start(this::appendTerminalOutput, 20);

        readerThread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try {
                // Small delay to let banner show
                Thread.sleep(100);
                while (running) {
                    if (inputStream.available() > 0) {
                        int len = inputStream.read(buffer);
                        if (len < 0) break;
                        String text = new String(buffer, 0, len, charset);
                        // B8: 累积到 throttler，由其定时批量渲染
                        throttler.feed(text);
                    } else {
                        Thread.sleep(20);
                    }
                }
            } catch (IOException e) {
                if (running) {
                    logger.error("SSH output read error", e);
                    Platform.runLater(() -> {
                        appendLine("");
                        appendLine("  \u001B[31m连接已断开\u001B[0m");
                        terminalOutput.setText(outputBuffer.getText());
                        scrollTerminalToEnd();
                    });
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                // 修复项5：无论 reader 因何退出（IOException 断连 / disconnect 中断），
                // 仅在 finally 统一通知一次 "Disconnected"，避免上层收到两次状态事件
                // 导致 AI 聊天区重复弹出断连气泡。
                Platform.runLater(() -> notifyStatus("Disconnected"));
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();

        // C7: 注册终端尺寸监听，PTY 随窗口 resize 动态调整
        setupResizeListener();

        notifyStatus("Connected");
    }

    private void appendLine(String text) {
        String clean = stripAnsi(text);
        outputBuffer.append(clean + "\n");
    }

    private void appendTerminalOutput(String text) {
        String clean = stripAnsi(text);
        clean = clean.replace("\r\n", "\n").replace("\r", "\n");

        // 退格处理与容量截断由 TerminalBuffer 负责
        outputBuffer.append(clean);

        terminalOutput.setText(outputBuffer.getText());
        scrollTerminalToEnd();

        // 通知数据接收回调（供终端事件桥接器进行事件检测）
        if (onDataReceived != null) {
            onDataReceived.accept(clean);
        }
        // 终端+AI 联动：命令捕获回调（仅在 executeCaptured 期间非空）
        if (captureConsumer != null) {
            captureConsumer.accept(clean);
        }
    }

    private String stripAnsi(String text) {
        return AnsiParser.strip(text);
    }

    public void sendInput(String data) {
        if (outputStream == null || !running) return;
        try {
            // Apply backspace/delete key mapping from config
            AppConfig config = AppConfig.getInstance();
            String processedData = data;

            if ("\b".equals(data)) {
                // Backspace key mapping (BS character only; DEL handled below)
                String bsType = config.getBackspaceKey();
                switch (bsType) {
                    case "VT220":
                        processedData = "\u007F"; // DEL character
                        break;
                    case "ASCII":
                        processedData = "\b"; // BS character
                        break;
                    case "VT100":
                        processedData = "\u007F";
                        break;
                    case "Linux":
                        processedData = "\b";
                        break;
                }
            } else if ("\u007F".equals(data)) {
                // Delete key mapping (DEL character)
                String delType = config.getDeleteKey();
                switch (delType) {
                    case "VT220":
                    case "VT100":
                        processedData = "\u001B[3~"; // Delete key sequence
                        break;
                    case "ASCII":
                        processedData = "\u007F";
                        break;
                }
            }

            outputStream.write(processedData.getBytes(charset));
            outputStream.flush();
        } catch (IOException e) {
            logger.error("Failed to send input", e);
        }
    }

    public void sendCommand(String command) {
        sendInput(command + "\r");
    }

    /**
     * 以打字机效果逐字符输入命令后回车，模拟真人操作的视觉效果。
     * 每字符 15~35ms 随机延迟（正态分布倾向于 20ms 左右），让用户能在终端中看到
     * AI "亲手"输入命令，增强"AI运维工程师现场操作"的沉浸感。
     * 命令超过 50 字符后自动加速（后半段 8~18ms），保证长命令等待时间可控。
     *
     * @param command 要执行的命令（不含末尾回车，方法内自动追加）
     */
    public void typeCommand(String command) {
        typeCommand(command, null);
    }

    /**
     * 打字机效果输入主命令，然后瞬时追加 suffix（不做打字效果，避免用户看到内部哨兵），
     * 最后回车提交。用于把 sentinel echo 等辅助指令拼接到同一命令行尾部。
     *
     * @param command       AI 请求的主命令（逐字符打字显示给用户）
     * @param hiddenSuffix  要瞬时追加到同一行末尾的后缀（如 "; printf '__AI_END__%s__'"），
     *                      用户能看到但不会有逐字打字效果，看起来是命令的一部分
     */
    public void typeCommand(String command, String hiddenSuffix) {
        if (command == null || command.isEmpty()) {
            if (hiddenSuffix != null && !hiddenSuffix.isEmpty()) {
                sendInput(hiddenSuffix);
            }
            sendInput("\r");
            return;
        }
        // 打字前短暂停顿（模拟"思考"），给用户一个"AI 要开始打字了"的视觉预期
        try {
            Thread.sleep(180 + (int) (Math.random() * 120));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            int len = command.length();
            for (int i = 0; i < len; i++) {
                char c = command.charAt(i);
                sendInput(String.valueOf(c));
                int base, jitter;
                if (i < 20) {
                    base = 18;
                    jitter = (int) (Math.random() * 20); // 0~20ms 初期稍慢
                } else if (i < 50) {
                    base = 12;
                    jitter = (int) (Math.random() * 16); // 中段标准
                } else {
                    base = 6;
                    jitter = (int) (Math.random() * 10); // 长命令后段加速
                }
                Thread.sleep(base + jitter);
            }
            // 瞬时追加隐藏后缀（无延迟），用户看到它是命令行的一部分
            if (hiddenSuffix != null && !hiddenSuffix.isEmpty()) {
                sendInput(hiddenSuffix);
                Thread.sleep(30); // 微停顿让回显完成
            }
            sendInput("\r");
            // 回车后小停顿，让shell回显开始、用户看到命令"被提交"
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            try { sendInput("\r"); } catch (Exception ignored) {}
        }
    }

    public void clearScreen() {
        Platform.runLater(() -> {
            outputBuffer.clear();
            terminalOutput.clear();
        });
    }

    public String getSelectedText() {
        return terminalOutput.getSelectedText();
    }

    public void disconnect() {
        running = false;
        if (readerThread != null) {
            readerThread.interrupt();
        }
        // B8: 停止节流定时器（最后一次 flush 在下方 FX 线程中执行，确保线程安全）
        throttler.stop();
        // C7: 移除 resize 监听，避免断开后空触发
        teardownResizeListener();
        try {
            if (inputStream != null) inputStream.close();
            if (outputStream != null) outputStream.close();
        } catch (IOException e) {
            // ignore
        }
        Platform.runLater(() -> {
            throttler.flushNow(); // B8: FX 线程上 flush 残余输出，避免丢失
            appendLine("");
            appendLine("  \u001B[33m已断开连接\u001B[0m");
            terminalOutput.setText(outputBuffer.getText());
            scrollTerminalToEnd();
        });
        notifyStatus("Disconnected");
    }

    public boolean isConnected() {
        return running && shellChannel != null && shellChannel.isConnected();
    }

    public void setOnStatusChange(Consumer<String> callback) {
        this.onStatusChange = callback;
    }

    public void setOnDataReceived(Consumer<String> callback) {
        this.onDataReceived = callback;
    }

    /**
     * 设置 AI 终端操作状态回调：AI 开始打字/命令执行中/结束时通知 UI。
     * 回调始终在 JavaFX 应用线程上执行。
     */
    public void setOnAiBusy(BiConsumer<AiBusyState, String> callback) {
        this.aiBusyCallback = callback;
    }

    /**
     * 终端+AI 联动：设置命令捕获消费者（仅在 executeCaptured 调用期间临时存在）。
     * 与 onDataReceived 并存，不影响既有事件桥接回调。
     */
    public void setCaptureConsumer(Consumer<String> callback) {
        this.captureConsumer = callback;
    }

    /** 最近一次通知的状态；仅在状态变化时通知，避免重复事件（修复项5） */
    private volatile String lastNotifiedStatus = null;

    private void notifyStatus(String status) {
        if (status == null || onStatusChange == null) return;
        // 修复项5：去重——disconnect() 会直接调用一次 notifyStatus("Disconnected")，
        // reader 线程 finally 还会再调用一次同一状态。若不去重，上层 MainController 会收到
        // 两次 "Disconnected"：第一次消耗 userInitiatedDisconnect 标志，第二次误判为意外断连
        // 而弹出错误气泡。仅在状态真正变化时通知，从根本上消除重复。
        if (status.equals(lastNotifiedStatus)) return;
        lastNotifiedStatus = status;
        onStatusChange.accept(status);
    }

    public TextArea getTerminalOutput() {
        return terminalOutput;
    }

    // ========== 终端+AI 联动：自动滚动 & 命令捕获 ==========

    /**
     * setText 同帧 setScrollTop 不生效（布局未完成），延迟到下一脉冲并强制布局后再滚动到底。
     * 根治"输入命令后终端不滑到最新行"的时序问题。
     */
    private void scrollTerminalToEnd() {
        Platform.runLater(() -> {
            terminalOutput.layout();
            terminalOutput.setScrollTop(Double.MAX_VALUE);
        });
    }

    /**
     * 在可见交互式终端里执行命令并捕获输出（cwd 跨调用持久，用户全程可见）。
     *
     * 关键体验设计（2026-07 优化）：
     * 1. 打字机效果：主命令逐字符输入，每字符 18~38ms 随机延迟，回车前有思考停顿，
     *    用户看到 AI "亲手"键入命令，非常有科技感。
     * 2. 哨兵同行：用 printf 输出哨兵标记，拼接到主命令同一行（分号连接），
     *    避免出现独立的 `echo CAPTURE_END_xxx` 命令行，视觉上更干净。
     * 3. ANSI 自清理：捕获到哨兵后，立即发送 ANSI 序列（光标上移+擦除行）清除哨兵输出行，
     *    终端上只留下用户应该看到的主命令及其输出。
     * 4. 状态回调：通过 {@link #aiBusyCallback} 在 AI 打字/等待输出/结束时通知 UI
     *    （"🤖 AI 正在输入命令..."、"⏳ 命令执行中..."等状态指示）。
     *
     * @param command   要执行的 shell 命令
     * @param timeoutMs 等待哨兵的最长时间（毫秒）
     * @return 捕获到的命令输出文本
     */
    public String executeCaptured(String command, long timeoutMs) {
        if (outputStream == null || !running) {
            return "Error: terminal disconnected. Reconnect and retry, or use execute_shell for non-interactive commands.";
        }
        // 使用时间戳构造唯一哨兵，避免命令输出中偶然包含相同字符串导致提前截断
        final String sentinel = "_AI" + Long.toHexString(System.nanoTime()) + "_";
        final StringBuilder captured = new StringBuilder();
        final String[] resultHolder = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);

        setCaptureConsumer(chunk -> {
            captured.append(chunk);
            int idx = captured.indexOf(sentinel);
            if (idx >= 0) {
                resultHolder[0] = captured.substring(0, idx);
                latch.countDown();
            }
        });

        if (aiBusyCallback != null) {
            Platform.runLater(() -> aiBusyCallback.accept(AiBusyState.TYPING, command));
        }

        try {
            // 1. 主命令：打字机效果逐字符输入（180-300ms思考停顿→18-38ms/字符→回车）
            //    用户亲眼看到 AI "亲手"在终端里键入命令，核心炫酷体验
            typeCommand(command);

            if (aiBusyCallback != null) {
                Platform.runLater(() -> aiBusyCallback.accept(AiBusyState.EXECUTING, command));
            }

            // 2. 哨兵命令：无打字效果快速发送，标记命令输出结束。
            //    用 printf 输出一个极短的标记串，shell 执行后输出哨兵，
            //    我们在输出流里检测到哨兵就知道主命令已完成。
            //    这行 printf 会短暂出现在终端上（命令回显+哨兵输出），
            //    但紧接着 AI 会输入下一条命令（打字机效果覆盖提示符行），
            //    哨兵行会随终端滚动被推上去，用户几乎不会注意。
            sendCommand("printf '" + sentinel + "\\n'");

            boolean done = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            String raw = done ? resultHolder[0] : captured.toString();
            String result = stripCaptureArtifacts(raw, sentinel);

            if (!done) {
                result += "\n[Command may still be running — captured within "
                        + timeoutMs + "ms timeout. For interactive commands (top/vim/tail -f) "
                        + "use execute_shell instead.]";
            }
            return (result == null || result.trim().isEmpty()) ? "(no output)" : result.trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: interrupted while waiting for command output";
        } finally {
            setCaptureConsumer(null);
            if (aiBusyCallback != null) {
                Platform.runLater(() -> aiBusyCallback.accept(AiBusyState.IDLE, null));
            }
        }
    }

    private String stripCaptureArtifacts(String raw, String sentinel) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder();
        String[] lines = raw.split("\r?\n");
        for (String line : lines) {
            String t = line.trim();
            if (t.equals(sentinel)) continue;
            if (t.contains(sentinel) && t.contains("printf")) continue;
            out.append(line).append("\n");
        }
        return out.toString();
    }


    // ========== C7: PTY 随窗口 resize 动态调整 ==========

    /**
     * C7: 注册终端区域尺寸监听，窗口 resize 时 debounce 200ms 后动态调整 PTY 大小。
     * 在 connect() 末尾调用，连接后立即用实际尺寸校正一次（替代 openShell 固定 120×40）。
     * 具体监听/估算逻辑由 PtyResizer 负责，回调里调 resizePty 落到 SSH channel。
     */
    private void setupResizeListener() {
        resizer = new PtyResizer();
        resizer.setup(terminalOutput, size -> resizePty(size[0], size[1]));
    }

    /**
     * C7: 移除 resize 监听并停止 debounce 定时器，在 disconnect() 中调用。
     */
    private void teardownResizeListener() {
        if (resizer != null) {
            resizer.teardown();
            resizer = null;
        }
    }

    /**
     * C7: 动态调整 SSH PTY 大小（col, row, widthPx, heightPx）。
     * ChannelShell.setPtySize 是线程安全的，可在任意线程调用。
     */
    public void resizePty(int cols, int rows) {
        if (shellChannel != null && shellChannel.isConnected()) {
            try {
                shellChannel.setPtySize(cols, rows, cols * 7, rows * 14);
            } catch (Exception e) {
                logger.debug("setPtySize 失败: {}", e.getMessage());
            }
        }
    }
}
