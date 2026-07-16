package com.aifinalshell.controller;

import com.aifinalshell.config.AppConfig;
import com.aifinalshell.terminal.AnsiParser;
import com.aifinalshell.terminal.OutputThrottler;
import com.aifinalshell.terminal.PtyResizer;
import com.aifinalshell.terminal.TerminalBuffer;
import com.jcraft.jsch.ChannelShell;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class TerminalController {
    private static final Logger logger = LoggerFactory.getLogger(TerminalController.class);
    private TextArea terminalOutput;
    private final Region terminalBackground;
    private final Region terminalOverlay;
    private String cachedBackgroundKey;
    private Image cachedBackgroundImage;
    private String cachedBackgroundUrl;
    private ChannelShell shellChannel;
    private InputStream inputStream;
    private OutputStream outputStream;
    private Thread readerThread;
    private volatile boolean running = false;
    /** 每次挂载/卸载 PTY 都递增，确保旧 reader 永远不会读取或回调到新会话。 */
    private final AtomicLong connectionGeneration = new AtomicLong();
    private Consumer<String> onStatusChange;
    private Consumer<String> onDataReceived;
    // 终端+AI 联动：命令捕获消费者（仅在 executeCaptured 调用期间临时存在，与 onDataReceived 并存）
    private volatile Consumer<String> captureConsumer;
    /** 同一可见 PTY 同时只允许一个 AI 命令捕获，防止完成标记和输出串线。 */
    private final ReentrantLock aiCommandLock = new ReentrantLock();
    private volatile boolean aiCommandActive = false;
    private static final int MAX_CAPTURE_CHARS = 256_000;

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
        this(terminalOutput, null, null);
    }

    public TerminalController(TextArea terminalOutput, Region terminalBackground) {
        this(terminalOutput, terminalBackground, null);
    }

    public TerminalController(TextArea terminalOutput, Region terminalBackground, Region terminalOverlay) {
        this.terminalOutput = terminalOutput;
        this.terminalBackground = terminalBackground;
        this.terminalOverlay = terminalOverlay;
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

        // 设置页的滚动速度实际控制终端历史滚动；事件发生时读取配置，Apply 后无需重绑。
        terminalOutput.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (e.getDeltaY() == 0) return;
            int speed = Math.max(1, AppConfig.getInstance().getScrollSpeed());
            double pixels = Math.max(12.0, Math.abs(e.getDeltaY())) * speed;
            terminalOutput.setScrollTop(Math.max(0,
                    terminalOutput.getScrollTop() - Math.copySign(pixels, e.getDeltaY())));
            e.consume();
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

        // 光标样式作用于 MainController 的命令输入框；滚动缓冲区在上方实际生效。
    }

    /**
     * 应用终端视觉样式。背景图绘制到 TextArea 后面的专用 Region；TextArea 的 content 只保留
     * 半透明颜色遮罩，因此图片不会再被 JavaFX 内部不透明内容层盖住。
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

        String backgroundColor = c.getTerminalColor("background");
        String backgroundSize = backgroundSizeCss(c.getBackgroundImageFit());
        String innerBackground = backgroundColor;
        String contentImageUrl = null;
        boolean imageApplied = false;

        if (c.isBackgroundImageEnabled()) {
            String path = c.getBackgroundImagePath();
            if (path != null && !path.isBlank()) {
                File imageFile = new File(path);
                if (imageFile.isFile()) {
                    String imageUrl = imageFile.toURI().toASCIIString().replace("'", "%27");
                    contentImageUrl = imageUrl;
                    String layerCss = "-fx-background-color: " + backgroundColor + ";"
                            + "-fx-background-image: url('" + imageUrl + "');"
                            + "-fx-background-size: " + backgroundSize + ";"
                            + "-fx-background-repeat: no-repeat;"
                            + "-fx-background-position: center;";
                    if (terminalBackground != null) {
                        terminalBackground.setStyle(layerCss);
                        terminalBackground.setVisible(true);
                    } else {
                        // Compatibility for isolated TerminalController use without the FXML background layer.
                        css.append(layerCss);
                    }
                    double visibility = clamp(c.getBackgroundImageOpacity(), 0.0, 1.0);
                    if (terminalOverlay != null) {
                        terminalOverlay.setStyle("-fx-background-color: " + backgroundColor + ";");
                        terminalOverlay.setOpacity(1.0 - visibility);
                        terminalOverlay.setVisible(visibility < 1.0);
                        innerBackground = "transparent";
                    } else {
                        innerBackground = toRgba(backgroundColor, 1.0 - visibility);
                    }
                    imageApplied = true;
                }
            }
        }

        if (!imageApplied && terminalBackground != null) {
            terminalBackground.setStyle("-fx-background-color: " + backgroundColor + ";");
            terminalBackground.setVisible(true);
        }
        if (!imageApplied && terminalOverlay != null) {
            terminalOverlay.setVisible(false);
            terminalOverlay.setOpacity(0);
            innerBackground = "transparent";
        }

        // 前景、半透明内容层、选区
        css.append(String.format(
                "-fx-control-inner-background: %s; -fx-text-fill: %s; "
                        + "-fx-highlight-fill: %s; -fx-highlight-text-fill: %s;",
                innerBackground, c.getTerminalColor("foreground"),
                c.getTerminalColor("selection"), c.getTerminalColor("foreground")));

        terminalOutput.setStyle(css.toString());
        // TextArea skin may be created after this call; force every internal paint layer transparent.
        final String finalContentImageUrl = contentImageUrl;
        final String finalBackgroundColor = backgroundColor;
        final String finalBackgroundSize = backgroundSize;
        final double finalImageVisibility = clamp(c.getBackgroundImageOpacity(), 0.0, 1.0);
        Platform.runLater(() -> {
            for (String selector : new String[]{".scroll-pane", ".viewport"}) {
                javafx.scene.Node node = terminalOutput.lookup(selector);
                if (node != null) node.setStyle("-fx-background-color: transparent;");
            }
            javafx.scene.Node content = terminalOutput.lookup(".content");
            if (content instanceof Region contentRegion) {
                if (finalContentImageUrl != null) {
                    String blendedUrl = createBlendedImageUrl(
                            finalContentImageUrl, finalBackgroundColor, finalImageVisibility);
                    contentRegion.setStyle("-fx-padding: 2;"
                            + "-fx-background-color: " + finalBackgroundColor + ";"
                            + "-fx-background-image: url('" + blendedUrl + "');"
                            + "-fx-background-size: " + finalBackgroundSize + ";"
                            + "-fx-background-repeat: no-repeat;"
                            + "-fx-background-position: center;");
                } else {
                    contentRegion.setStyle("-fx-padding: 2; -fx-background-color: "
                            + finalBackgroundColor + "; -fx-background-image: null;");
                }
            }
        });
    }

    private String createBlendedImageUrl(String imageUrl, String backgroundColor, double visibility) {
        Color base = Color.web(backgroundColor);
        int targetWidth = (int) Math.max(640, Math.min(1920, terminalOutput.getWidth()));
        int targetHeight = (int) Math.max(360, Math.min(1080, terminalOutput.getHeight()));
        String key = imageUrl + "|" + backgroundColor + "|" + visibility
                + "|" + targetWidth + "x" + targetHeight;
        if (!key.equals(cachedBackgroundKey) || cachedBackgroundImage == null) {
            Image source = new Image(imageUrl, targetWidth, targetHeight, true, true, false);
            if (source.isError() || source.getPixelReader() == null) {
                return imageUrl;
            }
            cachedBackgroundImage = blendImage(source, base, visibility);
            cachedBackgroundKey = key;
            cachedBackgroundUrl = writeBackgroundCache(cachedBackgroundImage, key);
        }
        return cachedBackgroundUrl == null ? imageUrl : cachedBackgroundUrl;
    }

    private String writeBackgroundCache(Image image, String key) {
        try {
            File dir = new File(System.getProperty("java.io.tmpdir"), "starshell-background-cache");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            File output = new File(dir, "terminal-" + Integer.toUnsignedString(key.hashCode(), 16) + ".png");
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", output);
            output.deleteOnExit();
            return output.toURI().toASCIIString().replace("'", "%27");
        } catch (IOException ex) {
            logger.warn("Unable to create terminal background cache: {}", ex.getMessage());
            return null;
        }
    }

    static WritableImage blendImage(Image source, Color background, double visibility) {
        int width = Math.max(1, (int) Math.round(source.getWidth()));
        int height = Math.max(1, (int) Math.round(source.getHeight()));
        WritableImage result = new WritableImage(width, height);
        PixelReader reader = source.getPixelReader();
        PixelWriter writer = result.getPixelWriter();
        double imageVisibility = clamp(visibility, 0.0, 1.0);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Color pixel = reader.getColor(x, y);
                double weight = pixel.getOpacity() * imageVisibility;
                writer.setColor(x, y, Color.color(
                        pixel.getRed() * weight + background.getRed() * (1.0 - weight),
                        pixel.getGreen() * weight + background.getGreen() * (1.0 - weight),
                        pixel.getBlue() * weight + background.getBlue() * (1.0 - weight)));
            }
        }
        return result;
    }

    static String toRgba(String cssColor, double alpha) {
        Color color = Color.web(cssColor == null ? "#0c0c0c" : cssColor);
        return String.format(Locale.ROOT, "rgba(%d,%d,%d,%.3f)",
                Math.round(color.getRed() * 255),
                Math.round(color.getGreen() * 255),
                Math.round(color.getBlue() * 255),
                clamp(alpha, 0.0, 1.0));
    }

    /** Map the persisted background-fit mode to JavaFX CSS. */
    static String backgroundSizeCss(String fitMode) {
        if ("cover".equalsIgnoreCase(fitMode)) return "cover";
        if ("stretch".equalsIgnoreCase(fitMode)) return "100% 100%";
        return "contain";
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public void setServerInfo(String name, String host, String user) {
        this.serverName = name;
        this.serverHost = host;
        this.serverUser = user;
    }

    public void connect(String connectionKey, ChannelShell channel) {
        connect(connectionKey, channel, null);
    }

    /** 挂载 PTY；restoredOutput 非空时恢复该会话历史，而不是清空并显示首次连接横幅。 */
    public void connect(String connectionKey, ChannelShell channel, String restoredOutput) {
        // 同一可见终端一次只能挂载一个 PTY；切换会话时静默卸载旧通道。
        detachForSessionSwitch();
        final long generation = connectionGeneration.incrementAndGet();
        this.shellChannel = channel;
        this.running = true;
        this.lastNotifiedStatus = null;

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
            if (restoredOutput != null && !restoredOutput.isBlank()) {
                outputBuffer.append(restoredOutput);
                if (!restoredOutput.endsWith("\n")) outputBuffer.append("\n");
                appendLine("  ── 已恢复会话终端 ──");
            } else {
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
            }
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
                while (running && connectionGeneration.get() == generation) {
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
                if (connectionGeneration.get() == generation) {
                    running = false;
                    Platform.runLater(() -> notifyStatus("Disconnected"));
                }
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
        shutdownTerminal(true, true);
    }

    /** 会话标签切换时使用：关闭当前 shell，但不显示“已断开”横幅或触发断连气泡。 */
    public void detachForSessionSwitch() {
        shutdownTerminal(false, false);
    }

    private void shutdownTerminal(boolean showBanner, boolean notify) {
        connectionGeneration.incrementAndGet();
        running = false;
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
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
        if (shellChannel != null) {
            try { shellChannel.disconnect(); } catch (Exception ignored) { }
        }
        inputStream = null;
        outputStream = null;
        shellChannel = null;
        if (showBanner) {
            Platform.runLater(() -> {
                throttler.flushNow();
                appendLine("");
                appendLine("  \u001B[33m已断开连接\u001B[0m");
                terminalOutput.setText(outputBuffer.getText());
                scrollTerminalToEnd();
            });
        }
        if (notify) notifyStatus("Disconnected");
    }

    public boolean isConnected() {
        return running && shellChannel != null && shellChannel.isConnected();
    }

    /** AI 是否正在独占 PTY 执行一条可见命令；用户仍可用 Ctrl+C 主动中断。 */
    public boolean isAiCommandActive() {
        return aiCommandActive;
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
        try {
            aiCommandLock.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: interrupted before visible terminal command started";
        }
        try {
            return executeCapturedLocked(command, timeoutMs);
        } finally {
            aiCommandLock.unlock();
        }
    }

    private String executeCapturedLocked(String command, long timeoutMs) {
        if (outputStream == null || !running) {
            return "Error: visible terminal disconnected. Reconnect the server and retry.";
        }
        if (command == null || command.isBlank()) {
            return "Error: visible terminal command is empty";
        }
        if (timeoutMs <= 0) {
            return "Error: visible terminal timeout must be positive";
        }

        // 使用随机性更高的十六进制 ID。最终标记由 printf 的格式化参数拼出，
        // 不会原样出现在 PTY 回显的探针命令中，从根本上避免“回显即完成”的竞态。
        final String commandId = Long.toHexString(System.nanoTime())
                + Long.toHexString(Double.doubleToLongBits(Math.random()));
        final StringBuilder captured = new StringBuilder();
        final String[] resultHolder = new String[1];
        final int[] exitCodeHolder = new int[] {-1};
        final boolean[] captureTruncated = new boolean[] {false};
        final CountDownLatch latch = new CountDownLatch(1);

        setCaptureConsumer(chunk -> {
            captured.append(chunk);
            if (captured.length() > MAX_CAPTURE_CHARS) {
                captured.delete(0, captured.length() - MAX_CAPTURE_CHARS);
                captureTruncated[0] = true;
            }
            java.util.Optional<TerminalCommandProtocol.Completion> completion =
                    TerminalCommandProtocol.findCompletion(captured, commandId);
            if (completion.isPresent()) {
                TerminalCommandProtocol.Completion done = completion.get();
                resultHolder[0] = captured.substring(0, done.markerStart());
                exitCodeHolder[0] = done.exitCode();
                latch.countDown();
            }
        });

        aiCommandActive = true;
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

            // 2. 完成探针：命令回显不包含最终标记；真正执行后才输出标记与上一条命令退出码。
            sendCommand(TerminalCommandProtocol.buildCompletionProbe(commandId));

            boolean done = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            String raw = done ? resultHolder[0] : captured.toString();
            String result = stripCaptureArtifacts(raw, command);

            if (!done) {
                // 不能让超时命令继续占用同一个 PTY，否则下一次 AI 工具调用会与它串线。
                sendInput("\u0003");
                return "Error: visible terminal command timed out after " + timeoutMs
                        + "ms and was interrupted. Partial output:\n" + result.trim();
            }
            String normalized = (result == null || result.trim().isEmpty()) ? "(no output)" : result.trim();
            if (captureTruncated[0]) {
                normalized = "[earlier output truncated; showing the latest "
                        + MAX_CAPTURE_CHARS + " characters]\n" + normalized;
            }
            if (exitCodeHolder[0] != 0) {
                normalized += "\n[exit code: " + exitCodeHolder[0] + "]";
            }
            return normalized;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // “停止生成”同时终止当前远端命令，确保终端回到可交互状态。
            sendInput("\u0003");
            return "Error: interrupted while waiting for command output";
        } finally {
            aiCommandActive = false;
            setCaptureConsumer(null);
            if (aiBusyCallback != null) {
                Platform.runLater(() -> aiBusyCallback.accept(AiBusyState.IDLE, null));
            }
        }
    }

    private String stripCaptureArtifacts(String raw, String command) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder();
        String[] lines = raw.split("\r?\n");
        String firstCommandLine = command == null ? "" : command.strip().split("\\R", 2)[0].trim();
        boolean commandEchoRemoved = false;
        for (String line : lines) {
            String t = line.trim();
            // 移除内部完成探针的回显；它只用于协议控制，不应进入 AI 上下文。
            if (t.contains("__STAR_CMD_DONE_%s_%s__") && t.contains("printf")) continue;
            // PTY 会回显用户输入。仅移除第一次出现的主命令行（含常见 shell 提示符前缀），
            // 保留后续同名输出，避免把命令本身重复送回模型。
            if (!commandEchoRemoved && !firstCommandLine.isEmpty()
                    && (t.equals(firstCommandLine) || t.endsWith(" " + firstCommandLine))) {
                commandEchoRemoved = true;
                continue;
            }
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
