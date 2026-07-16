package com.aifinalshell.controller;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可见终端命令完成协议。
 *
 * <p>PTY 会回显用户输入，因此完成探针的命令文本绝不能包含最终完成标记；
 * 否则仅仅回显 {@code printf} 命令就会被误判成命令已经执行完毕。这里把随机 ID
 * 作为 printf 参数传入，最终标记只会在 shell 真正执行探针后出现在输出中。</p>
 */
final class TerminalCommandProtocol {
    private static final String PREFIX = "__STAR_CMD_DONE_";

    private TerminalCommandProtocol() {}

    static String markerPrefix(String commandId) {
        return PREFIX + commandId + "_";
    }

    /**
     * 构建完成探针。{@code $?} 在 shell 执行该行时展开，代表上一条命令的退出码。
     * 命令文本中不会出现 {@link #markerPrefix(String)} 的完整字符串。
     */
    static String buildCompletionProbe(String commandId) {
        if (commandId == null || !commandId.matches("[0-9a-f]+")) {
            throw new IllegalArgumentException("commandId must be lowercase hexadecimal");
        }
        return "printf '\\n__STAR_CMD_DONE_%s_%s__\\n' '" + commandId + "' \"$?\"";
    }

    static Optional<Completion> findCompletion(CharSequence output, String commandId) {
        if (output == null) return Optional.empty();
        Pattern pattern = Pattern.compile(
                Pattern.quote(markerPrefix(commandId)) + "(-?\\d+)__");
        Matcher matcher = pattern.matcher(output);
        if (!matcher.find()) return Optional.empty();
        try {
            return Optional.of(new Completion(
                    matcher.start(), matcher.end(), Integer.parseInt(matcher.group(1))));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    /** 完成标记在捕获缓冲区中的位置及上一条命令退出码。 */
    record Completion(int markerStart, int markerEnd, int exitCode) {}
}
