package com.aifinalshell.terminal;

/**
 * 终端输出缓冲区：维护已清洗的纯文本缓冲，处理退格（\b）删除前一字符，
 * 并在超过最大容量时截断旧内容以防无限增长。
 *
 * <p>线程安全说明：设计为仅在 FX 线程访问（appendTerminalOutput / flushPendingOutput
 * 均在 FX 线程执行），内部不做同步。</p>
 */
public class TerminalBuffer {
    private final StringBuilder buffer = new StringBuilder();
    private int maxSize = 50000;

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }

    public int getMaxSize() {
        return maxSize;
    }

    /**
     * 追加已清洗的文本：先做退格处理（\b 删除前一字符），再做容量截断。
     * 调用方需先完成 ANSI 剥离与 \r\n 归一化。
     *
     * @param cleanText 已清洗的纯文本，可为 null/空（直接忽略）
     */
    public void append(String cleanText) {
        if (cleanText == null || cleanText.isEmpty()) return;

        if (cleanText.contains("\b")) {
            // 退格：逐字符处理，\b 删除前一字符，其余追加
            StringBuilder sb = new StringBuilder(buffer.toString());
            for (char c : cleanText.toCharArray()) {
                if (c == '\b') {
                    if (sb.length() > 0) sb.deleteCharAt(sb.length() - 1);
                } else {
                    sb.append(c);
                }
            }
            buffer.setLength(0);
            buffer.append(sb);
        } else {
            buffer.append(cleanText);
        }

        // 超过容量时保留后半段，防止无限增长
        if (buffer.length() > maxSize) {
            String content = buffer.toString();
            buffer.setLength(0);
            buffer.append(content.substring(content.length() - maxSize / 2));
        }
    }

    public String getText() {
        return buffer.toString();
    }

    public void clear() {
        buffer.setLength(0);
    }

    public int length() {
        return buffer.length();
    }
}
