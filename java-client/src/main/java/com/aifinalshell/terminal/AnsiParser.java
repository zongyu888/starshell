package com.aifinalshell.terminal;

import java.util.regex.Pattern;

/**
 * ANSI 转义码剥离器：剥离终端输出中的 ANSI 颜色/光标控制序列与回车擦行序列，
 * 供 TerminalController 在渲染纯文本前清洗原始 PTY 输出。
 *
 * 纯无状态工具类，全部为 static 方法。
 */
public final class AnsiParser {
    // 标准 CSI 序列：ESC [ 参数... 字母
    private static final Pattern ANSI_PATTERN = Pattern.compile("\u001B\\[[0-9;]*[a-zA-Z]");

    private AnsiParser() {}

    /**
     * 剥离 ANSI 转义序列与回车擦行序列，返回纯文本。
     *
     * @param text 原始 PTY 输出，可为 null
     * @return 清洗后的纯文本；入参为 null/空时原样返回
     */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) return text;
        // 把 \r\u001B[K / \r\u001B[2K 中的 \r 转 \n（保留 \r 的换行语义），
        // 而非删除——否则后续裸 \r→\n 替换时 \r 已无存，进度条会堆叠在一行。
        text = text.replaceAll("\r\u001B\\[K", "\n\u001B[K");
        text = text.replaceAll("\r\u001B\\[2K", "\n\u001B[2K");
        // 再删剩余 ANSI 转义（此时 \r 已变 \n，不会被删）
        return ANSI_PATTERN.matcher(text).replaceAll("");
    }
}
