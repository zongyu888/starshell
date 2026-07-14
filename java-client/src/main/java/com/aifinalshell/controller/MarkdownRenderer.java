package com.aifinalshell.controller;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;

/**
 * 轻量级Markdown渲染器
 * 使用正则表达式解析Markdown，通过JavaFX TextFlow渲染
 * 不引入外部库，支持代码块、语法高亮、行内格式、标题、列表、链接
 */
public class MarkdownRenderer {

    private static final Logger logger = LoggerFactory.getLogger(MarkdownRenderer.class);

    // ========== 颜色常量 ==========
    private static final String COLOR_KEYWORD  = "#ff7b72"; // 关键字红色
    private static final String COLOR_STRING   = "#a5d6ff"; // 字符串蓝色
    private static final String COLOR_COMMENT  = "#8b949e"; // 注释灰色
    private static final String COLOR_NUMBER   = "#79c0ff"; // 数字蓝色
    private static final String COLOR_LINE_NUM = "#6e7681"; // 行号灰色
    private static final String COLOR_TEXT     = "#e6edf3"; // 默认文本色
    private static final String COLOR_ERROR    = "#f85149"; // 错误红色
    private static final String COLOR_WARNING   = "#d29922"; // 警告黄色

    // 等宽字体
    private static final String MONO_FONT =
            "'Cascadia Code', 'Consolas', 'Courier New', monospace";

    // ========== 正则模式 ==========

    /** 代码块：```lang\n...```  */
    private static final Pattern CODE_BLOCK_PATTERN =
            Pattern.compile("```(\\w*)\\n([\\s\\S]*?)```");

    /** 标题：# / ## / ###  */
    private static final Pattern HEADING_PATTERN =
            Pattern.compile("^(#{1,3})\\s+(.+)$");

    /** 无序列表：- item 或 * item */
    private static final Pattern UNORDERED_LIST_PATTERN =
            Pattern.compile("^[-*]\\s+(.+)");

    /** 有序列表：1. item */
    private static final Pattern ORDERED_LIST_PATTERN =
            Pattern.compile("^(\\d+)\\.\\s+(.+)");

    /**
     * 行内格式组合正则（按优先级排列）
     * 组1: 粗体 **text**
     * 组2: 斜体 *text*
     * 组3: 行内代码 `code`
     * 组4: 错误关键字 (error/exception/failed/refused/denied/fatal)
     * 组5: 警告关键字 (warning/deprecated/caution)
     */
    private static final Pattern INLINE_PATTERN = Pattern.compile(
            "\\*\\*(.+?)\\*\\*"                                    // 1: 粗体
          + "|\\*(.+?)\\*"                                         // 2: 斜体
          + "|`([^`]+)`"                                           // 3: 行内代码
          + "|(?i)\\b(error|exception|failed|refused|denied|fatal)\\b" // 4: 错误关键字
          + "|(?i)\\b(warning|deprecated|caution)\\b"              // 5: 警告关键字
          + "|\\[([^]]+)\\]\\(([^)]+)\\)"                          // 6: 链接文本, 7: 链接URL
    );

    // ========== 语法高亮模式（按语言） ==========

    private static final Pattern BASH_SYNTAX = buildSyntaxPattern(
            "#[^\\n]*",                                              // 注释
            "\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'",       // 字符串
            "\\b\\d+(?:\\.\\d+)?\\b",                               // 数字
            "if|then|else|elif|fi|for|in|do|done|while|case|esac|function|return|"
          + "export|local|echo|printf|read|cd|ls|cat|grep|sed|awk|chmod|chown|"
          + "mkdir|rmdir|rm|cp|mv|sudo|apt|apt-get|yum|dnf|systemctl|service|"
          + "ps|kill|top|htop|df|du|free|uptime|ping|ssh|scp|wget|curl|tar|zip|"
          + "unzip|docker|kubectl|git|npm|pip|python|python3|java|node|bash|sh|source"
    );

    private static final Pattern JAVA_SYNTAX = buildSyntaxPattern(
            "//[^\\n]*|/\\*[\\s\\S]*?\\*/",                          // 注释
            "\"(?:[^\"\\\\]|\\\\.)*\"",                              // 字符串
            "\\b\\d+(?:\\.\\d+)?[fFdDlL]?\\b",                      // 数字
            "public|private|protected|class|interface|enum|extends|implements|"
          + "import|package|static|final|void|int|long|double|float|boolean|char|"
          + "byte|short|String|if|else|for|while|do|switch|case|break|continue|"
          + "return|new|this|super|try|catch|finally|throw|throws|null|true|false|"
          + "instanceof|abstract|synchronized|volatile|transient|native|var|record"
    );

    private static final Pattern PYTHON_SYNTAX = buildSyntaxPattern(
            "#[^\\n]*",                                              // 注释
            "\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'",       // 字符串
            "\\b\\d+(?:\\.\\d+)?\\b",                               // 数字
            "def|class|import|from|as|if|elif|else|for|while|break|continue|return|"
          + "print|lambda|try|except|finally|raise|with|yield|global|nonlocal|pass|"
          + "del|in|is|not|and|or|None|True|False|assert|async|await|self|cls"
    );

    private static final Pattern JSON_SYNTAX = buildSyntaxPattern(
            "\\b\\B",                                               // JSON无注释，永不匹配
            "\"(?:[^\"\\\\]|\\\\.)*\"",                              // 字符串
            "\\b\\d+(?:\\.\\d+)?\\b",                               // 数字
            "true|false|null"
    );

    // ========== 主入口 ==========

    /**
     * 渲染Markdown文本，返回包含渲染节点的VBox
     *
     * @param markdown                 Markdown文本
     * @param sendToTerminalCallback   发送到终端回调（接收该代码块内容，用于Shell代码块的"发送到终端"按钮）
     * @return 包含所有渲染节点的VBox
     */
    public static VBox render(String markdown, Consumer<String> sendToTerminalCallback) {
        VBox container = new VBox(6);
        // C1: 不再硬编码 360，使用 MAX_VALUE 由父容器（ChatBubbleFactory 的 MAX_BUBBLE_WIDTH-28）约束宽度，随气泡宽度自适应
        container.setMaxWidth(Double.MAX_VALUE);
        if (markdown == null || markdown.isEmpty()) {
            return container;
        }

        // 按代码块分割：非代码文本收集到 TextArea（原生支持拖选 + Ctrl+C），代码块保留 TextFlow（语法高亮 + 复制按钮）
        StringBuilder textPart = new StringBuilder();
        List<VBox> codeBlocks = new ArrayList<>();

        Matcher codeMatcher = CODE_BLOCK_PATTERN.matcher(markdown);
        int lastEnd = 0;
        while (codeMatcher.find()) {
            // 代码块前的普通文本 → 拼接到 TextArea 文本
            if (codeMatcher.start() > lastEnd) {
                textPart.append(markdown.substring(lastEnd, codeMatcher.start()));
            }
            // 代码块 → TextFlow 渲染（保留语法高亮 + 复制/发送到终端按钮）
            String lang = codeMatcher.group(1);
            String code = codeMatcher.group(2);
            // 去除尾部多余换行
            if (code.endsWith("\n")) {
                code = code.substring(0, code.length() - 1);
            }
            codeBlocks.add(renderCodeBlock(lang, code, sendToTerminalCallback));
            lastEnd = codeMatcher.end();
        }

        // 剩余文本
        if (lastEnd < markdown.length()) {
            textPart.append(markdown.substring(lastEnd));
        }

        // 创建 TextArea 承载非代码文本（editable=false 原生支持鼠标拖选 + Ctrl+C）
        if (textPart.length() > 0) {
            TextArea textArea = new TextArea(textPart.toString().trim());
            textArea.setEditable(false);
            textArea.setWrapText(true);
            textArea.setFocusTraversable(false);
            textArea.setMaxWidth(ChatBubbleFactory.MAX_BUBBLE_WIDTH - 28);
            textArea.getStyleClass().add("ai-bubble-text-selectable");
            container.getChildren().add(textArea);
        }

        // 代码块（TextFlow + 语法高亮 + 复制按钮）
        container.getChildren().addAll(codeBlocks);

        return container;
    }

    // ========== 普通文本渲染 ==========

    /**
     * 渲染普通文本段（逐行处理标题、列表、段落）
     */
    private static void renderTextSegment(VBox container, String text) {
        String[] lines = text.split("\n", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            Matcher hm = HEADING_PATTERN.matcher(trimmed);
            if (hm.matches()) {
                // 标题
                int level = hm.group(1).length();
                renderHeading(container, level, hm.group(2).trim());
            } else {
                Matcher ulm = UNORDERED_LIST_PATTERN.matcher(trimmed);
                if (ulm.matches()) {
                    // 无序列表
                    renderListItem(container, "•  ", ulm.group(1));
                } else {
                    Matcher olm = ORDERED_LIST_PATTERN.matcher(trimmed);
                    if (olm.matches()) {
                        // 有序列表
                        renderListItem(container, olm.group(1) + ".  ", olm.group(2));
                    } else {
                        // 普通段落
                        renderParagraph(container, trimmed);
                    }
                }
            }
        }
    }

    /**
     * 渲染标题（h1/h2/h3，不同字号加粗）
     */
    private static void renderHeading(VBox container, int level, String content) {
        TextFlow flow = new TextFlow();
        flow.getStyleClass().add("ai-md-h" + level);
        int size = switch (level) {
            case 1  -> 18;
            case 2  -> 16;
            default -> 14;
        };
        flow.setStyle("-fx-font-size: " + size + "px; -fx-font-weight: bold;");
        renderInline(content, flow);
        container.getChildren().add(flow);
    }

    /**
     * 渲染列表项
     */
    private static void renderListItem(VBox container, String prefix, String content) {
        TextFlow flow = new TextFlow();
        flow.getStyleClass().add("ai-md-paragraph");
        flow.setStyle("-fx-font-size: 13px;");
        Text bullet = new Text(prefix);
        bullet.setFill(Color.web(COLOR_TEXT));
        flow.getChildren().add(bullet);
        renderInline(content, flow);
        container.getChildren().add(flow);
    }

    /**
     * 渲染普通段落
     */
    private static void renderParagraph(VBox container, String content) {
        TextFlow flow = new TextFlow();
        flow.getStyleClass().add("ai-md-paragraph");
        flow.setStyle("-fx-font-size: 13px;");
        renderInline(content, flow);
        container.getChildren().add(flow);
    }

    // ========== 行内格式渲染 ==========

    /**
     * 渲染行内格式（粗体、斜体、行内代码、错误/警告关键字高亮）
     * 将解析后的Text节点添加到TextFlow中
     */
    private static void renderInline(String text, TextFlow flow) {
        Matcher m = INLINE_PATTERN.matcher(text);
        int lastEnd = 0;
        while (m.find()) {
            // 添加匹配前的普通文本
            if (m.start() > lastEnd) {
                addText(flow, text.substring(lastEnd, m.start()), COLOR_TEXT, null);
            }
            if (m.group(1) != null) {
                // 粗体
                addText(flow, m.group(1), COLOR_TEXT, "-fx-font-weight: bold;");
            } else if (m.group(2) != null) {
                // 斜体
                addText(flow, m.group(2), COLOR_TEXT, "-fx-font-style: italic;");
            } else if (m.group(3) != null) {
                // 行内代码
                addText(flow, m.group(3), COLOR_STRING,
                        "-fx-font-family: " + MONO_FONT + ";");
            } else if (m.group(4) != null) {
                // 错误关键字 → 标红加粗
                addText(flow, m.group(4), COLOR_ERROR, "-fx-font-weight: bold;");
            } else if (m.group(5) != null) {
                // 警告关键字 → 黄色
                addText(flow, m.group(5), COLOR_WARNING, null);
            } else if (m.group(6) != null) {
                // 链接 → Hyperlink，点击用 Desktop.browse 打开
                Hyperlink link = new Hyperlink(m.group(6));
                link.setStyle("-fx-font-size: 13px; -fx-text-fill: #58a6ff; -fx-underline: true;");
                final String url = m.group(7);
                link.setOnAction(ev -> openUrl(url));
                flow.getChildren().add(link);
            }
            lastEnd = m.end();
        }
        // 添加末尾剩余文本
        if (lastEnd < text.length()) {
            addText(flow, text.substring(lastEnd), COLOR_TEXT, null);
        }
    }

    /**
     * 创建带样式的Text节点并添加到TextFlow
     *
     * @param flow   目标TextFlow
     * @param text   文本内容
     * @param color  填充颜色（-fx-fill）
     * @param style  额外CSS样式（如字体粗细/斜体/等宽），可为null
     */
    private static void addText(TextFlow flow, String text, String color, String style) {
        Text t = new Text(text);
        t.setFill(Color.web(color));
        if (style != null) {
            t.setStyle(style);
        }
        flow.getChildren().add(t);
    }

    /**
     * C3: 打开超链接 URL（guard Desktop 支持，失败仅记日志不弹窗，避免打断对话）。
     */
    private static void openUrl(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            } else {
                logger.warn("当前环境不支持 Desktop.browse，无法打开链接: {}", url);
            }
        } catch (Exception e) {
            logger.warn("打开链接失败: {} ({})", url, e.getMessage());
        }
    }

    // ========== 代码块渲染 ==========

    /**
     * 渲染代码块（含头部栏 + 带行号的代码内容 + 语法高亮）
     *
     * @param lang                    语言标识（如bash/java/python/json）
     * @param code                    代码内容
     * @param sendToTerminalCallback  发送到终端回调（接收本块代码内容；仅Shell/Bash代码块显示按钮）
     * @return 包含头部和代码内容的VBox
     */
    private static VBox renderCodeBlock(String lang, String code, Consumer<String> sendToTerminalCallback) {
        VBox block = new VBox(2);
        boolean isShell = isShellLanguage(lang);
        block.getStyleClass().add(isShell ? "ai-shell-block" : "ai-code-block");

        // ----- 头部栏 -----
        HBox header = new HBox(8);
        header.getStyleClass().add("ai-code-header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.setMaxWidth(Double.MAX_VALUE);

        // 语言标签
        Label langLabel = new Label(lang == null || lang.isEmpty() ? "code" : lang);
        header.getChildren().add(langLabel);

        // 弹性间隔（推开右侧按钮）
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        header.getChildren().add(spacer);

        // 复制按钮
        Button copyBtn = new Button("复制");
        copyBtn.getStyleClass().add("ai-copy-btn");
        final String codeToCopy = code;
        copyBtn.setOnAction(e -> {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent content = new ClipboardContent();
            content.putString(codeToCopy);
            clipboard.setContent(content);
        });
        header.getChildren().add(copyBtn);

        // 发送到终端按钮（仅Shell/Bash代码块）：发送本块代码内容
        if (isShell && sendToTerminalCallback != null) {
            final String codeToSend = code;
            Button sendBtn = new Button("发送到终端");
            sendBtn.getStyleClass().add("ai-send-terminal-btn");
            sendBtn.setOnAction(e -> sendToTerminalCallback.accept(codeToSend));
            header.getChildren().add(sendBtn);
        }

        block.getChildren().add(header);

        // ----- 代码内容（带行号 + 语法高亮） -----
        TextFlow codeFlow = new TextFlow();
        codeFlow.setStyle("-fx-font-family: " + MONO_FONT + "; -fx-font-size: 12px; "
                        + "-fx-padding: 4 0 0 0;");

        String[] lines = code.split("\n", -1);
        Pattern syntaxPattern = getSyntaxPattern(lang);
        // C2: 行号宽度按总行数位数动态计算，避免超过99行时行号错位
        int lineNumWidth = String.valueOf(lines.length).length();

        for (int i = 0; i < lines.length; i++) {
            // 行号
            Text lineNum = new Text(String.format("%" + lineNumWidth + "d  ", i + 1));
            lineNum.setFill(Color.web(COLOR_LINE_NUM));
            lineNum.getStyleClass().add("ai-line-number");
            codeFlow.getChildren().add(lineNum);

            // 代码行（带语法高亮或默认颜色）
            if (syntaxPattern != null) {
                highlightLine(codeFlow, lines[i], syntaxPattern);
            } else {
                Text t = new Text(lines[i]);
                t.setFill(Color.web(COLOR_TEXT));
                codeFlow.getChildren().add(t);
            }

            // 行间换行
            if (i < lines.length - 1) {
                codeFlow.getChildren().add(new Text("\n"));
            }
        }

        block.getChildren().add(codeFlow);
        return block;
    }

    // ========== 语法高亮 ==========

    /**
     * 对单行代码进行语法高亮，将带颜色的Text节点添加到TextFlow
     *
     * @param flow    目标TextFlow
     * @param line    代码行
     * @param pattern 语法高亮正则（4个捕获组：注释/字符串/数字/关键字）
     */
    private static void highlightLine(TextFlow flow, String line, Pattern pattern) {
        Matcher m = pattern.matcher(line);
        int lastEnd = 0;
        while (m.find()) {
            // 添加匹配前的普通文本
            if (m.start() > lastEnd) {
                Text t = new Text(line.substring(lastEnd, m.start()));
                t.setFill(Color.web(COLOR_TEXT));
                flow.getChildren().add(t);
            }
            // 根据匹配的捕获组确定颜色和样式类
            String color = COLOR_TEXT;
            String styleClass = null;
            for (int g = 1; g <= m.groupCount(); g++) {
                if (m.group(g) != null) {
                    switch (g) {
                        case 1 -> { color = COLOR_COMMENT; styleClass = "ai-syntax-comment"; }
                        case 2 -> { color = COLOR_STRING;  styleClass = "ai-syntax-string"; }
                        case 3 -> { color = COLOR_NUMBER;  styleClass = "ai-syntax-number"; }
                        case 4 -> { color = COLOR_KEYWORD; styleClass = "ai-syntax-keyword"; }
                    }
                    break;
                }
            }
            Text t = new Text(m.group());
            t.setFill(Color.web(color));
            if (styleClass != null) {
                t.getStyleClass().add(styleClass);
            }
            flow.getChildren().add(t);
            lastEnd = m.end();
        }
        // 添加末尾剩余文本
        if (lastEnd < line.length()) {
            Text t = new Text(line.substring(lastEnd));
            t.setFill(Color.web(COLOR_TEXT));
            flow.getChildren().add(t);
        }
    }

    // ========== 辅助方法 ==========

    /**
     * 判断是否为Shell类语言
     */
    private static boolean isShellLanguage(String lang) {
        if (lang == null) return false;
        return switch (lang.toLowerCase()) {
            case "bash", "sh", "shell", "zsh" -> true;
            default -> false;
        };
    }

    /**
     * 根据语言标识获取对应的语法高亮正则
     *
     * @param lang 语言标识
     * @return 语法高亮Pattern，不支持的语言返回null
     */
    private static Pattern getSyntaxPattern(String lang) {
        if (lang == null || lang.isEmpty()) return null;
        return switch (lang.toLowerCase()) {
            case "bash", "sh", "shell", "zsh" -> BASH_SYNTAX;
            case "java"                       -> JAVA_SYNTAX;
            case "python", "py"               -> PYTHON_SYNTAX;
            case "json"                       -> JSON_SYNTAX;
            default                           -> null;
        };
    }

    /**
     * 构建语法高亮正则模式
     * 4个捕获组：注释(1) | 字符串(2) | 数字(3) | 关键字(4)
     *
     * @param comment  注释正则（为空则用永不匹配的占位符）
     * @param string   字符串正则
     * @param number   数字正则
     * @param keywords 关键字（用|分隔）
     * @return 组合后的Pattern
     */
    private static Pattern buildSyntaxPattern(String comment, String string, String number, String keywords) {
        if (comment == null || comment.isEmpty()) {
            comment = "\\b\\B"; // 永不匹配（\b和\B互斥）
        }
        return Pattern.compile(
                "(" + comment + ")"       // 组1: 注释
              + "|(" + string + ")"        // 组2: 字符串
              + "|(" + number + ")"        // 组3: 数字
              + "|\\b(" + keywords + ")\\b" // 组4: 关键字
        );
    }
}
