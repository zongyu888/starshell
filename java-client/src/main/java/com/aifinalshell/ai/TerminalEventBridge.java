package com.aifinalshell.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 终端事件桥接器。
 * 监听终端输出，当服务器返回错误或需要输入时，通知AI进行进一步处理。
 *
 * 工作模式：终端控制器通过 {@link #feedOutput} 推送输出 -> 桥接器检测事件模式 ->
 * 通过 {@link #watch} 注册的回调通知调用方 -> 调方可触发AI分析或自动响应。
 */
public class TerminalEventBridge {
    private static final Logger logger = LoggerFactory.getLogger(TerminalEventBridge.class);
    private static TerminalEventBridge instance;

    /** 每个sshKey对应一个事件监听器 */
    private final ConcurrentHashMap<String, Consumer<TerminalEvent>> watchers = new ConcurrentHashMap<>();

    /** 事件冷却记录：防止同一模式短时间内重复触发（key: sshKey + "|" + eventType + "|" + pattern） */
    private final ConcurrentHashMap<String, Long> cooldownTracker = new ConcurrentHashMap<>();

    /** 冷却时间（毫秒）：同一事件模式3秒内不重复触发 */
    private static final long COOLDOWN_MS = 3000;

    // ========== 事件检测模式 ==========

    /** 错误关键词模式 */
    private static final List<Pattern> ERROR_PATTERNS = List.of(
            Pattern.compile("error", Pattern.CASE_INSENSITIVE),
            Pattern.compile("exception", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bfailed\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("refused", Pattern.CASE_INSENSITIVE),
            Pattern.compile("denied", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bfatal\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("cannot find", Pattern.CASE_INSENSITIVE),
            Pattern.compile("no such file", Pattern.CASE_INSENSITIVE),
            Pattern.compile("segmentation fault", Pattern.CASE_INSENSITIVE),
            Pattern.compile("core dumped", Pattern.CASE_INSENSITIVE)
    );

    /** 需要输入的提示模式 */
    private static final List<Pattern> INPUT_PATTERNS = List.of(
            Pattern.compile("password:\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("passphrase:\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\[\\s*[Yy]\\s*/\\s*[Nn]\\s*\\]\\s*:?\\s*$"),
            Pattern.compile("\\(yes/no\\)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("continue\\?\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("are you sure\\?\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("enter passphrase", Pattern.CASE_INSENSITIVE),
            Pattern.compile("username:\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("login:\\s*$", Pattern.CASE_INSENSITIVE)
    );

    /** 服务启动成功模式 */
    private static final List<Pattern> SERVICE_STARTED_PATTERNS = List.of(
            Pattern.compile("Started\\s+\\S+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Active:\\s*active\\s*\\(running\\)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("started\\s+successfully", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\[OK\\]"),
            Pattern.compile("Application\\s+started", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Tomcat\\s+started", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Started\\s+\\S+Application", Pattern.CASE_INSENSITIVE)
    );

    /** 端口监听模式 */
    private static final List<Pattern> PORT_LISTENING_PATTERNS = List.of(
            Pattern.compile("LISTEN", Pattern.CASE_INSENSITIVE),
            Pattern.compile("listening\\s+on", Pattern.CASE_INSENSITIVE),
            Pattern.compile("started\\s+on\\s+port", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Tomcat\\s+started\\s+on\\s+port", Pattern.CASE_INSENSITIVE),
            Pattern.compile("started\\s+at\\s+port", Pattern.CASE_INSENSITIVE)
    );

    /** 部署完成模式 */
    private static final List<Pattern> DEPLOYMENT_COMPLETE_PATTERNS = List.of(
            Pattern.compile("BUILD\\s+SUCCESS", Pattern.CASE_INSENSITIVE),
            Pattern.compile("deployed\\s+successfully", Pattern.CASE_INSENSITIVE),
            Pattern.compile("deployment\\s+complete", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Application\\s+has\\s+been\\s+deployed", Pattern.CASE_INSENSITIVE)
    );

    private TerminalEventBridge() {}

    public static synchronized TerminalEventBridge getInstance() {
        if (instance == null) {
            instance = new TerminalEventBridge();
        }
        return instance;
    }

    /**
     * 注册终端输出监听器。
     * 注册后，通过 {@link #feedOutput} 推送的终端输出将被检测，
     * 检测到事件时通过 onEvent 回调通知。
     *
     * @param sshKey  SSH连接键
     * @param onEvent 事件回调
     */
    public void watch(String sshKey, Consumer<TerminalEvent> onEvent) {
        watchers.put(sshKey, onEvent);
        logger.info("已注册终端事件监听: sshKey={}", sshKey);
    }

    /**
     * 停止监听。
     *
     * @param sshKey SSH连接键
     */
    public void stopWatching(String sshKey) {
        watchers.remove(sshKey);
        // 清理冷却记录
        String prefix = sshKey + "|";
        cooldownTracker.keySet().removeIf(key -> key.startsWith(prefix));
        logger.info("已停止终端事件监听: sshKey={}", sshKey);
    }

    /**
     * 推送终端输出到桥接器进行事件检测。
     * 由终端控制器在读取到新输出时调用。
     *
     * @param sshKey SSH连接键
     * @param output 终端输出文本
     */
    public void feedOutput(String sshKey, String output) {
        if (output == null || output.isEmpty()) {
            return;
        }
        Consumer<TerminalEvent> watcher = watchers.get(sshKey);
        if (watcher == null) {
            return; // 没有注册监听器，忽略
        }

        TerminalEvent event = detectEvent(output);
        if (event != null) {
            event.setSshKey(sshKey);

            // 冷却检查：防止同一事件模式短时间内重复触发
            String cooldownKey = sshKey + "|" + event.getEventType() + "|" + event.getDetectedPattern();
            long now = System.currentTimeMillis();
            Long lastTime = cooldownTracker.get(cooldownKey);
            if (lastTime != null && (now - lastTime) < COOLDOWN_MS) {
                logger.debug("事件冷却中，跳过: {}", cooldownKey);
                return;
            }
            cooldownTracker.put(cooldownKey, now);

            logger.info("检测到终端事件: type={}, pattern={}, sshKey={}",
                    event.getEventType(), event.getDetectedPattern(), sshKey);

            try {
                watcher.accept(event);
            } catch (Exception e) {
                logger.error("终端事件回调执行异常", e);
            }
        }
    }

    /**
     * 检测终端输出中的事件模式。
     * 按优先级检测：错误 > 需要输入 > 部署完成 > 服务启动 > 端口监听。
     *
     * @param output 终端输出
     * @return 检测到的事件，未检测到返回null
     */
    private TerminalEvent detectEvent(String output) {
        // 优先级1: 错误检测
        String errorPattern = matchPattern(output, ERROR_PATTERNS);
        if (errorPattern != null) {
            return new TerminalEvent(TerminalEvent.EventType.ERROR_DETECTED, output, errorPattern);
        }

        // 优先级2: 需要输入检测
        String inputPattern = matchPattern(output, INPUT_PATTERNS);
        if (inputPattern != null) {
            return new TerminalEvent(TerminalEvent.EventType.INPUT_REQUIRED, output, inputPattern);
        }

        // 优先级3: 部署完成检测
        String deployPattern = matchPattern(output, DEPLOYMENT_COMPLETE_PATTERNS);
        if (deployPattern != null) {
            return new TerminalEvent(TerminalEvent.EventType.DEPLOYMENT_COMPLETE, output, deployPattern);
        }

        // 优先级4: 服务启动检测
        String servicePattern = matchPattern(output, SERVICE_STARTED_PATTERNS);
        if (servicePattern != null) {
            return new TerminalEvent(TerminalEvent.EventType.SERVICE_STARTED, output, servicePattern);
        }

        // 优先级5: 端口监听检测
        String portPattern = matchPattern(output, PORT_LISTENING_PATTERNS);
        if (portPattern != null) {
            return new TerminalEvent(TerminalEvent.EventType.PORT_LISTENING, output, portPattern);
        }

        return null;
    }

    /**
     * 在输出中匹配模式列表，返回第一个匹配的模式字符串。
     *
     * @param output   终端输出
     * @param patterns 模式列表
     * @return 匹配到的模式字符串，未匹配返回null
     */
    private String matchPattern(String output, List<Pattern> patterns) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(output).find()) {
                return pattern.pattern();
            }
        }
        return null;
    }

    /**
     * 清理所有监听器和冷却记录（如断开所有连接时调用）。
     */
    public void clearAll() {
        watchers.clear();
        cooldownTracker.clear();
        logger.info("已清理所有终端事件监听器");
    }

    /**
     * 检查指定SSH连接是否正在被监听。
     *
     * @param sshKey SSH连接键
     * @return 是否正在监听
     */
    public boolean isWatching(String sshKey) {
        return watchers.containsKey(sshKey);
    }

    /**
     * 获取当前正在监听的连接数量。
     */
    public int getWatcherCount() {
        return watchers.size();
    }
}
