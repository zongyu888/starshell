package com.aifinalshell.agent.tool;

import com.aifinalshell.util.SecurityUtils;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Permission checker for AI tool calls.
 * Implements a three-state permission model (opencode-dev inspired):
 * - ALLOW: Execute directly without asking
 * - ASK:   Ask user for confirmation via async callback (CompletableFuture)
 * - BLOCK: Refuse to execute
 *
 * Supports dangerous command exact matching, wildcard patterns,
 * and an "always allow" memory mechanism for user-approved patterns.
 */
public class ToolPermission {

    // ========== Three-State Permission Model ==========

    public enum PermissionResult {
        ALLOW,   // Execute without asking
        ASK,     // Ask user for confirmation (async via callback)
        BLOCK    // Refuse to execute
    }

    // ========== "Always Allow" Memory Cache ==========

    /**
     * Cache of user-approved command/tool patterns.
     * Key is a pattern string (e.g. "execute_shell:ls" or "install_package"),
     * value is always true once added.
     */
    private static final Map<String, Boolean> alwaysAllowCache = new ConcurrentHashMap<>();

    // ========== Dangerous Command Patterns ==========

    /**
     * Exact-match dangerous commands (case-insensitive comparison).
     */
    private static final Set<String> DANGEROUS_EXACT = Set.of(
            "rm -rf /",
            "rm -rf ~",
            "rm -rf $home",
            "rm -rf /*",
            "rm -rf .",
            "mkfs.ext4 /dev/sda",
            "mkfs /dev/sda",
            "dd if=/dev/zero of=/dev/sda",
            "dd if=/dev/zero of=/dev/sdb",
            ":(){ :|:& };:",
            "shutdown -h now",
            "shutdown now",
            "reboot",
            "halt",
            "init 0",
            "kill -9 1",
            "iptables -f",
            "echo b > /proc/sysrq-trigger"
    );

    /**
     * Wildcard dangerous patterns (glob-style: * matches any characters, ? matches single).
     */
    private static final List<String> DANGEROUS_WILDCARDS = List.of(
            "rm -rf *",
            "rm -rf /tmp/*",
            "rm -rf /home/*",
            "rm -rf /var/*",
            "rm -rf /etc/*",
            "chmod -r 777 /*",
            "chmod -r 777 /",
            "dd if=* of=/dev/sd*",
            "dd if=/dev/zero of=/dev/sd*",
            "mkfs.* /dev/sd*",
            "shutdown *",
            "reboot *",
            "> /dev/sda*",
            "> /dev/sdb*",
            "systemctl stop sshd",
            "systemctl stop network",
            "systemctl stop systemd*",
            "systemctl stop dbus",
            "iptables -f *",
            "shred -f /*",
            "wipe -f /*",
            "mv / *",
            "mv /* *"
    );

    /**
     * Critical services that must never be stopped.
     */
    private static final Set<String> CRITICAL_SERVICES = Set.of(
            "sshd", "network", "systemd", "dbus", "networking"
    );

    // ========== Permission Check Methods ==========

    /**
     * Check if a tool call is allowed (non-async).
     * Returns the permission result without blocking.
     */
    public static PermissionResult check(String toolName, Map<String, Object> args) {
        // Check "always allow" cache first
        if (isAlwaysAllowed(toolName, args)) {
            return PermissionResult.ALLOW;
        }

        if ("execute_shell".equals(toolName)) {
            return checkShellCommand(args);
        }
        if ("manage_service".equals(toolName)) {
            return checkManageService(args);
        }
        if ("install_package".equals(toolName)) {
            return PermissionResult.ASK;
        }
        if ("upload_file".equals(toolName) || "write_file".equals(toolName)
                || "delete_file".equals(toolName) || "download_file".equals(toolName)) {
            return PermissionResult.ASK;
        }
        return PermissionResult.ALLOW;
    }

    /**
     * Check tool permission with async callback for ASK results.
     * This method blocks until the user responds (via CompletableFuture).
     *
     * @param toolName    the tool name
     * @param args        the tool arguments
     * @param askCallback callback invoked when permission is ASK;
     *                    the callback receives a PermissionRequest and should
     *                    present a confirmation dialog to the user
     * @return true if the tool call is allowed, false if blocked or denied
     */
    public static boolean checkWithCallback(String toolName, Map<String, Object> args,
                                             Consumer<PermissionRequest> askCallback) {
        // Check "always allow" cache first
        if (isAlwaysAllowed(toolName, args)) {
            return true;
        }

        PermissionResult result = check(toolName, args);

        switch (result) {
            case ALLOW:
                return true;
            case BLOCK:
                return false;
            case ASK:
                String warning = getWarning(toolName, args, result);
                PermissionRequest request = new PermissionRequest(toolName, args, warning);
                askCallback.accept(request);
                try {
                    // B10: 60秒超时，超时按拒绝处理，避免对话框未响应时工具调用永久阻塞 AI 线程
                    return request.getResponse().get(60, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    return false;
                }
            default:
                return false;
        }
    }

    // ========== Internal Check Logic ==========

    /**
     * Check shell command safety using exact match, wildcards, and regex.
     */
    private static PermissionResult checkShellCommand(Map<String, Object> args) {
        String command = (String) args.get("command");
        if (command == null || command.isEmpty()) {
            return PermissionResult.ALLOW;
        }

        String trimmedCmd = command.trim();

        // 1. Check exact match (case-insensitive)
        String lowerCmd = trimmedCmd.toLowerCase();
        for (String dangerous : DANGEROUS_EXACT) {
            if (dangerous.equals(lowerCmd)) {
                return PermissionResult.BLOCK;
            }
        }

        // 2. Check wildcard patterns
        for (String pattern : DANGEROUS_WILDCARDS) {
            if (matchesWildcard(lowerCmd, pattern)) {
                return PermissionResult.BLOCK;
            }
        }

        // 3. Use SecurityUtils regex-based check
        if (SecurityUtils.isDangerousCommand(command)) {
            return PermissionResult.BLOCK;
        }

        if (SecurityUtils.isSuspiciousCommand(command)) {
            return PermissionResult.ASK;
        }

        return PermissionResult.ALLOW;
    }

    /**
     * Check manage_service permissions.
     * - status: ALLOW (read-only)
     * - stop on critical services: BLOCK
     * - start/stop/restart on non-critical: ASK
     */
    private static PermissionResult checkManageService(Map<String, Object> args) {
        String action = (String) args.get("action");
        String serviceName = (String) args.get("serviceName");

        // status is read-only, allow directly
        if ("status".equals(action)) {
            return PermissionResult.ALLOW;
        }

        // Block stopping critical services
        if ("stop".equals(action) && serviceName != null) {
            String lower = serviceName.toLowerCase();
            for (String critical : CRITICAL_SERVICES) {
                if (lower.equals(critical) || lower.startsWith(critical + "@")) {
                    return PermissionResult.BLOCK;
                }
            }
        }

        // start/stop/restart need user confirmation
        return PermissionResult.ASK;
    }

    // ========== Wildcard Matching ==========

    /**
     * Glob-style wildcard matching: * matches any sequence, ? matches single char.
     */
    private static boolean matchesWildcard(String input, String pattern) {
        StringBuilder regex = new StringBuilder("^");
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '*': regex.append(".*"); break;
                case '?': regex.append("."); break;
                case '.': regex.append("\\."); break;
                case '(': regex.append("\\("); break;
                case ')': regex.append("\\)"); break;
                case '[': regex.append("\\["); break;
                case ']': regex.append("\\]"); break;
                case '$': regex.append("\\$"); break;
                case '^': regex.append("\\^"); break;
                case '+': regex.append("\\+"); break;
                case '|': regex.append("\\|"); break;
                case '{': regex.append("\\{"); break;
                case '}': regex.append("\\}"); break;
                default: regex.append(c);
            }
        }
        regex.append("$");
        return input.matches(regex.toString());
    }

    // ========== "Always Allow" Memory Mechanism ==========

    /**
     * Check if a tool/args pattern is in the "always allow" cache.
     */
    private static boolean isAlwaysAllowed(String toolName, Map<String, Object> args) {
        String pattern = buildPatternKey(toolName, args);
        return alwaysAllowCache.getOrDefault(pattern, false);
    }

    /**
     * Add a pattern to the "always allow" cache.
     * Future calls with the same pattern will be allowed without asking.
     */
    public static void alwaysAllow(String toolName, Map<String, Object> args) {
        String pattern = buildPatternKey(toolName, args);
        alwaysAllowCache.put(pattern, true);
    }

    /**
     * Build a cache key from tool name and args.
     * - execute_shell: uses the command prefix (first token) as the pattern key
     * - manage_service: uses tool name + action as the pattern key
     * - other tools: uses the tool name as the pattern key
     */
    private static String buildPatternKey(String toolName, Map<String, Object> args) {
        if ("execute_shell".equals(toolName)) {
            String command = (String) args.get("command");
            if (command != null && !command.isEmpty()) {
                String[] parts = command.trim().split("\\s+");
                if (parts.length > 0) {
                    return toolName + ":" + parts[0];
                }
            }
            return toolName;
        }
        if ("manage_service".equals(toolName)) {
            String action = (String) args.get("action");
            if (action != null) {
                return toolName + ":action=" + action;
            }
        }
        return toolName;
    }

    /**
     * Clear the "always allow" cache (e.g. when user resets permissions).
     */
    public static void clearAlwaysAllowCache() {
        alwaysAllowCache.clear();
    }

    // ========== Warning Messages ==========

    /**
     * Get user-facing warning message for a permission result.
     */
    public static String getWarning(String toolName, Map<String, Object> args, PermissionResult result) {
        if (result == PermissionResult.BLOCK) {
            if ("execute_shell".equals(toolName)) {
                return "BLOCKED: Dangerous command detected!\n" +
                        "Command: " + args.getOrDefault("command", "N/A") + "\n" +
                        (SecurityUtils.getSecurityWarning((String) args.get("command")) != null
                                ? SecurityUtils.getSecurityWarning((String) args.get("command"))
                                : "This command matches a known dangerous pattern.");
            }
            if ("manage_service".equals(toolName)) {
                return "BLOCKED: Cannot stop critical service: " + args.get("serviceName") +
                        "\nStopping this service would make the server inaccessible.";
            }
            return "BLOCKED: This operation is not allowed.";
        }
        if (result == PermissionResult.ASK) {
            if ("execute_shell".equals(toolName)) {
                String secWarning = SecurityUtils.getSecurityWarning((String) args.get("command"));
                return "Confirmation required for command:\n" +
                        "Command: " + args.getOrDefault("command", "N/A") + "\n" +
                        (secWarning != null ? secWarning : "This command requires user confirmation.");
            }
            if ("manage_service".equals(toolName)) {
                return "Confirmation required to " + args.get("action") +
                        " service: " + args.get("serviceName");
            }
            if ("install_package".equals(toolName)) {
                return "Confirmation required to install package: " + args.get("packageName") +
                        " via " + args.getOrDefault("manager", "apt");
            }
            if ("upload_file".equals(toolName)) {
                return "Confirmation required to upload file:\n" +
                        "  Local: " + args.get("localPath") + "\n" +
                        "  Remote: " + args.get("remotePath");
            }
            if ("write_file".equals(toolName)) {
                return "Confirmation required to write file: " + args.getOrDefault("filePath", "N/A");
            }
            if ("delete_file".equals(toolName)) {
                return "Confirmation required to delete file: " + args.getOrDefault("filePath", "N/A");
            }
            if ("download_file".equals(toolName)) {
                return "Confirmation required to download file:\n" +
                        "  Remote: " + args.getOrDefault("remotePath", "N/A") + "\n" +
                        "  Local: " + args.getOrDefault("localPath", "N/A");
            }
            return "Confirmation required for tool: " + toolName;
        }
        return null;
    }

    // ========== PermissionRequest Inner Class ==========

    /**
     * Represents a permission request that requires user confirmation.
     * The UI layer receives this object, displays a confirmation dialog,
     * and calls approve()/deny()/approveAlways() based on user's choice.
     */
    public static class PermissionRequest {
        private final String toolName;
        private final Map<String, Object> args;
        private final String warning;
        private final CompletableFuture<Boolean> response;

        public PermissionRequest(String toolName, Map<String, Object> args, String warning) {
            this.toolName = toolName;
            this.args = args != null ? args : Collections.emptyMap();
            this.warning = warning;
            this.response = new CompletableFuture<>();
        }

        public String getToolName() { return toolName; }
        public Map<String, Object> getArgs() { return args; }
        public String getWarning() { return warning; }
        public CompletableFuture<Boolean> getResponse() { return response; }

        /**
         * User approved the action (execute once).
         */
        public void approve() {
            response.complete(true);
        }

        /**
         * User denied the action.
         */
        public void deny() {
            response.complete(false);
        }

        /**
         * User approved and chose "always allow" for this pattern.
         * Future calls with the same pattern will skip confirmation.
         */
        public void approveAlways() {
            ToolPermission.alwaysAllow(toolName, args);
            response.complete(true);
        }
    }
}
