package com.aifinalshell.agent.tool;

import com.aifinalshell.controller.TerminalCommandBridge;
import com.aifinalshell.ssh.SshConnectionManager;
import com.aifinalshell.util.SecurityUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for AI-callable tools, with built-in ops tools registered.
 */
public class ToolRegistry {
    private static final Logger logger = LoggerFactory.getLogger(ToolRegistry.class);
    private static ToolRegistry instance;
    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ToolRegistry() {
        registerBuiltinTools();
    }

    public static synchronized ToolRegistry getInstance() {
        if (instance == null) {
            instance = new ToolRegistry();
        }
        return instance;
    }

    private void registerBuiltinTools() {
        // execute_shell - Execute shell command exclusively in the user-visible terminal
        register(new ToolDefinition("execute_shell", "execute_shell",
                "Execute a shell command on the remote server. The command will be typed into the user-visible terminal "
                + "so the user can watch it execute in real time (cwd persists across calls). "
                + "Avoid interactive commands like top/vim/tail -f — they never return. "
                + "This is the primary way to run commands. Use it for ALL shell operations.",
                buildSchema("command", "The shell command to execute"),
                (args, ctx) -> {
                    String command = (String) args.get("command");
                    if (command == null || command.isEmpty()) {
                        return "Error: No command provided";
                    }
                    try {
                        return TerminalCommandBridge.getInstance()
                                .execute(ctx.getSshKey(), command, 300000);
                    } catch (Exception e) {
                        return "Error executing command in visible terminal: " + e.getMessage();
                    }
                }));

        // run_in_terminal - Alias for execute_shell (both now run in the visible terminal)
        register(new ToolDefinition("run_in_terminal", "run_in_terminal",
                "Run a command in the user-visible interactive terminal and return its output. "
                + "Same as execute_shell — the command is typed into the live terminal so the user sees it execute. "
                + "cwd persists across calls. Avoid interactive commands like top/vim/tail -f.",
                buildSchema("command", "The shell command to execute in the visible terminal"),
                (args, ctx) -> {
                    String command = (String) args.get("command");
                    if (command == null || command.isEmpty()) {
                        return "Error: No command provided";
                    }
                    try {
                        return TerminalCommandBridge.getInstance().execute(ctx.getSshKey(), command, 300000);
                    } catch (Exception e) {
                        return "Error executing command in terminal: " + e.getMessage();
                    }
                }));

        // read_log - Read log file from server (visible terminal preferred)
        register(new ToolDefinition("read_log", "read_log",
                "Read the last N lines of a log file on the remote server.",
                buildTwoParams("logPath", "Path to the log file", "lines", "Number of lines from the end (default 100)"),
                (args, ctx) -> {
                    String logPath = (String) args.get("logPath");
                    int lines = args.containsKey("lines") ? ((Number) args.get("lines")).intValue() : 100;
                    if (logPath == null || logPath.isEmpty()) return "Error: No log path provided";
                    logPath = SecurityUtils.sanitizePath(logPath);
                    String command = "tail -n " + lines + " " + logPath;
                    return executeViaVisibleTerminal(ctx.getSshKey(), command);
                }));

        // list_processes - List running processes (visible terminal)
        register(new ToolDefinition("list_processes", "list_processes",
                "List top processes by CPU or memory usage.",
                buildSchema("sort", "Sort by: cpu or memory (default cpu)"),
                (args, ctx) -> {
                    String sort = (String) args.getOrDefault("sort", "cpu");
                    String sshKey = ctx.getSshKey();
                    String cmd = "cpu".equals(sort)
                            ? "ps aux --sort=-%cpu | head -15"
                            : "ps aux --sort=-%mem | head -15";
                    return executeViaVisibleTerminal(sshKey, cmd);
                }));

        // check_port - Check if a port is in use (visible terminal)
        register(new ToolDefinition("check_port", "check_port",
                "Check if a specific port is listening on the server.",
                buildSchema("port", "Port number to check"),
                (args, ctx) -> {
                    Object portObj = args.get("port");
                    if (portObj == null) return "Error: No port provided";
                    String port = SecurityUtils.sanitizePort(String.valueOf(portObj));
                    if (port.isEmpty()) return "Error: Invalid port number";
                    String sshKey = ctx.getSshKey();
                    String command = "ss -tlnp | grep :" + port;
                    String result = executeViaVisibleTerminal(sshKey, command);
                    if (result != null && (result.trim().equals("(no output)")
                            || hasNonZeroExitCode(result))) {
                        return "Port " + port + " is NOT listening";
                    }
                    return "Port " + port + " is listening:\n" + result;
                }));

        // check_disk - Check disk usage (visible terminal)
        register(new ToolDefinition("check_disk", "check_disk",
                "Check disk usage on the server.",
                buildSchema("path", "Path to check (default /)"),
                (args, ctx) -> {
                    String path = (String) args.getOrDefault("path", "/");
                    path = SecurityUtils.sanitizePath(path);
                    if (path.isEmpty()) path = "/";
                    String sshKey = ctx.getSshKey();
                    String command = "df -h " + path + " && echo '---' && du -sh " + path + " 2>/dev/null";
                    return executeViaVisibleTerminal(sshKey, command);
                }));

        // check_memory - Check memory usage (visible terminal)
        register(new ToolDefinition("check_memory", "check_memory",
                "Check memory and swap usage on the server.",
                buildSchema("dummy", "No parameters needed"),
                (args, ctx) -> {
                    String sshKey = ctx.getSshKey();
                    String command = "free -h && echo '---' && cat /proc/meminfo | head -10";
                    return executeViaVisibleTerminal(sshKey, command);
                }));

        // check_cpu - Check CPU usage and load (visible terminal)
        register(new ToolDefinition("check_cpu", "check_cpu",
                "Check CPU usage, load average, and top processes.",
                buildSchema("dummy", "No parameters needed"),
                (args, ctx) -> {
                    String sshKey = ctx.getSshKey();
                    String command = "uptime && echo '---' && top -bn1 | head -5";
                    return executeViaVisibleTerminal(sshKey, command);
                }));

        // ========== New Ops Tools ==========

        // upload_file - Upload a local file to the remote server via SFTP
        ObjectNode uploadProps = objectMapper.createObjectNode();
        uploadProps.set("localPath", stringParam("Local file path on the client machine"));
        uploadProps.set("remotePath", stringParam("Destination path on the remote server"));
        register(new ToolDefinition("upload_file", "upload_file",
                "Upload a local file to the remote server via SFTP. The local path must exist on the client machine.",
                buildSchemaFromProps(uploadProps, "localPath", "remotePath"),
                (args, ctx) -> {
                    String localPath = (String) args.get("localPath");
                    String remotePath = (String) args.get("remotePath");
                    if (localPath == null || localPath.isEmpty()) {
                        return "Error: No localPath provided";
                    }
                    if (remotePath == null || remotePath.isEmpty()) {
                        return "Error: No remotePath provided";
                    }
                    // Sanitize the remote path to prevent traversal attacks
                    remotePath = SecurityUtils.sanitizePath(remotePath);
                    if (remotePath.isEmpty()) {
                        return "Error: Invalid remote path";
                    }
                    try {
                        String sshKey = ctx.getSshKey();
                        SshConnectionManager.SftpChannel sftp = SshConnectionManager.getInstance().openSftp(sshKey);
                        try {
                            sftp.upload(localPath, remotePath);
                            return "File uploaded successfully: " + localPath + " -> " + remotePath;
                        } finally {
                            sftp.close();
                        }
                    } catch (Exception e) {
                        return "Error uploading file: " + e.getMessage();
                    }
                }));

        // list_services - List running services on the server (visible terminal)
        register(new ToolDefinition("list_services", "list_services",
                "List currently running services on the remote server (systemd).",
                buildEmptySchema(),
                (args, ctx) -> {
                    String sshKey = ctx.getSshKey();
                    String command = "systemctl list-units --type=service --state=running --no-pager";
                    return executeViaVisibleTerminal(sshKey, command);
                }));

        // manage_service - Start/stop/restart/status a service (visible terminal)
        ObjectNode manageServiceProps = objectMapper.createObjectNode();
        manageServiceProps.set("action", enumParam("Action to perform on the service", "start", "stop", "restart", "status"));
        manageServiceProps.set("serviceName", stringParam("Name of the systemd service (e.g. nginx, docker)"));
        register(new ToolDefinition("manage_service", "manage_service",
                "Manage a systemd service: start, stop, restart, or check status. Requires user confirmation.",
                buildSchemaFromProps(manageServiceProps, "action", "serviceName"),
                (args, ctx) -> {
                    String action = (String) args.get("action");
                    String serviceName = (String) args.get("serviceName");
                    if (action == null || action.isEmpty()) return "Error: No action provided";
                    if (serviceName == null || serviceName.isEmpty()) return "Error: No serviceName provided";
                    if (!action.matches("^(start|stop|restart|status)$")) return "Error: Invalid action";
                    serviceName = SecurityUtils.sanitizePath(serviceName);
                    if (serviceName.isEmpty()) return "Error: Invalid service name";
                    String sshKey = ctx.getSshKey();
                    String command;
                    if ("status".equals(action)) {
                        command = "systemctl status " + serviceName + " --no-pager 2>&1";
                    } else {
                        command = "systemctl " + action + " " + serviceName + " 2>&1";
                    }
                    return executeViaVisibleTerminal(sshKey, command);
                }));

        // check_network - Check network connectivity (visible terminal)
        ObjectNode checkNetworkProps = objectMapper.createObjectNode();
        checkNetworkProps.set("host", stringParam("Hostname or IP address to check"));
        checkNetworkProps.set("port", intParam("Port number to check"));
        register(new ToolDefinition("check_network", "check_network",
                "Check network connectivity to a specific host and port from the remote server.",
                buildSchemaFromProps(checkNetworkProps, "host", "port"),
                (args, ctx) -> {
                    String host = (String) args.get("host");
                    Object portObj = args.get("port");
                    if (host == null || host.isEmpty()) return "Error: No host provided";
                    if (portObj == null) return "Error: No port provided";
                    host = host.replaceAll("[^a-zA-Z0-9.\\-]", "");
                    if (host.isEmpty()) return "Error: Invalid host";
                    String port = SecurityUtils.sanitizePort(String.valueOf(portObj));
                    if (port.isEmpty()) return "Error: Invalid port number";
                    String sshKey = ctx.getSshKey();
                    String command = "timeout 5 bash -c 'echo > /dev/tcp/" + host + "/" + port + "' 2>&1 && echo 'Connection OK' || echo 'Connection FAILED'";
                    return executeViaVisibleTerminal(sshKey, command);
                }));

        // list_files - List files in a directory (visible terminal)
        ObjectNode listFilesProps = objectMapper.createObjectNode();
        listFilesProps.set("path", stringParam("Directory path to list (default /)"));
        listFilesProps.set("detail", boolParam("Show detailed listing (permissions, size, owner). Default false"));
        register(new ToolDefinition("list_files", "list_files",
                "List files in a directory on the remote server. Set detail=true for long format (ls -la).",
                buildSchemaFromProps(listFilesProps, "path"),
                (args, ctx) -> {
                    String path = (String) args.getOrDefault("path", "/");
                    boolean detail = args.containsKey("detail") && Boolean.TRUE.equals(args.get("detail"));
                    path = SecurityUtils.sanitizePath(path);
                    if (path.isEmpty()) path = "/";
                    String sshKey = ctx.getSshKey();
                    String command = detail ? "ls -la " + path : "ls -1 " + path;
                    return executeViaVisibleTerminal(sshKey, command);
                }));

        // install_package - Install a software package (visible terminal, requires confirmation)
        ObjectNode installPackageProps = objectMapper.createObjectNode();
        installPackageProps.set("packageName", stringParam("Name of the package to install"));
        installPackageProps.set("manager", enumParam("Package manager to use", "apt", "yum", "dnf"));
        register(new ToolDefinition("install_package", "install_package",
                "Install a software package using the specified package manager. Requires user confirmation.",
                buildSchemaFromProps(installPackageProps, "packageName", "manager"),
                (args, ctx) -> {
                    String packageName = (String) args.get("packageName");
                    String manager = (String) args.getOrDefault("manager", "apt");
                    if (packageName == null || packageName.isEmpty()) return "Error: No packageName provided";
                    if (!manager.matches("^(apt|yum|dnf)$")) return "Error: Invalid package manager";
                    packageName = packageName.replaceAll("[^a-zA-Z0-9._+\\-]", "");
                    if (packageName.isEmpty()) return "Error: Invalid package name";
                    String sshKey = ctx.getSshKey();
                    String command;
                    if ("apt".equals(manager)) {
                        command = "DEBIAN_FRONTEND=noninteractive apt-get install -y " + packageName + " 2>&1";
                    } else if ("yum".equals(manager)) {
                        command = "yum install -y " + packageName + " 2>&1";
                    } else {
                        command = "dnf install -y " + packageName + " 2>&1";
                    }
                    return executeViaVisibleTerminal(sshKey, command);
                }));

        // read_file - Read the content of a file (visible terminal)
        ObjectNode readFileProps = objectMapper.createObjectNode();
        readFileProps.set("filePath", stringParam("Path to the file to read"));
        readFileProps.set("maxLines", intParam("Maximum number of lines to read (default 200)"));
        register(new ToolDefinition("read_file", "read_file",
                "Read the content of a file on the remote server. Returns the first N lines.",
                buildSchemaFromProps(readFileProps, "filePath"),
                (args, ctx) -> {
                    String filePath = (String) args.get("filePath");
                    int maxLines = args.containsKey("maxLines") ? ((Number) args.get("maxLines")).intValue() : 200;
                    if (filePath == null || filePath.isEmpty()) return "Error: No filePath provided";
                    filePath = SecurityUtils.sanitizePath(filePath);
                    if (filePath.isEmpty()) return "Error: Invalid file path";
                    if (maxLines < 1) maxLines = 200;
                    if (maxLines > 5000) maxLines = 5000;
                    String sshKey = ctx.getSshKey();
                    String command = "head -n " + maxLines + " " + filePath;
                    return executeViaVisibleTerminal(sshKey, command);
                }));

        // write_file - Write content to a file on the remote server via SFTP
        ObjectNode writeFileProps = objectMapper.createObjectNode();
        writeFileProps.set("filePath", stringParam("Remote file path to write"));
        writeFileProps.set("content", stringParam("File content to write (overwrites if exists)"));
        register(new ToolDefinition("write_file", "write_file",
                "Write content to a file on the remote server via SFTP. Overwrites if exists.",
                buildSchemaFromProps(writeFileProps, "filePath", "content"),
                (args, ctx) -> {
                    String filePath = (String) args.get("filePath");
                    String content = (String) args.get("content");
                    if (filePath == null || filePath.isEmpty()) {
                        return "Error: No filePath provided";
                    }
                    if (content == null) content = "";
                    // Sanitize the remote path to prevent traversal attacks
                    filePath = SecurityUtils.sanitizePath(filePath);
                    if (filePath.isEmpty()) {
                        return "Error: Invalid file path";
                    }
                    try {
                        String sshKey = ctx.getSshKey();
                        SshConnectionManager.SftpChannel sftp = SshConnectionManager.getInstance().openSftp(sshKey);
                        try {
                            sftp.writeTextFile(filePath, content);
                            return "Successfully wrote " + content.length() + " bytes to " + filePath;
                        } finally {
                            sftp.close();
                        }
                    } catch (Exception e) {
                        return "Error writing file: " + e.getMessage();
                    }
                }));

        // delete_file - Delete a file on the remote server via SFTP
        ObjectNode deleteFileProps = objectMapper.createObjectNode();
        deleteFileProps.set("filePath", stringParam("Remote file path to delete"));
        register(new ToolDefinition("delete_file", "delete_file",
                "Delete a file on the remote server via SFTP. Use with caution.",
                buildSchemaFromProps(deleteFileProps, "filePath"),
                (args, ctx) -> {
                    String filePath = (String) args.get("filePath");
                    if (filePath == null || filePath.isEmpty()) {
                        return "Error: No filePath provided";
                    }
                    // Sanitize the remote path to prevent traversal attacks
                    filePath = SecurityUtils.sanitizePath(filePath);
                    if (filePath.isEmpty()) {
                        return "Error: Invalid file path";
                    }
                    try {
                        String sshKey = ctx.getSshKey();
                        SshConnectionManager.SftpChannel sftp = SshConnectionManager.getInstance().openSftp(sshKey);
                        try {
                            sftp.rm(filePath);
                            return "Successfully deleted: " + filePath;
                        } finally {
                            sftp.close();
                        }
                    } catch (Exception e) {
                        return "Error deleting file: " + e.getMessage();
                    }
                }));

        // download_file - Download a file from the remote server to the local machine via SFTP
        ObjectNode downloadFileProps = objectMapper.createObjectNode();
        downloadFileProps.set("remotePath", stringParam("Remote file path to download"));
        downloadFileProps.set("localPath", stringParam("Local file path to save"));
        register(new ToolDefinition("download_file", "download_file",
                "Download a file from the remote server to the local machine via SFTP.",
                buildSchemaFromProps(downloadFileProps, "remotePath", "localPath"),
                (args, ctx) -> {
                    String remotePath = (String) args.get("remotePath");
                    String localPath = (String) args.get("localPath");
                    if (remotePath == null || remotePath.isEmpty()) {
                        return "Error: No remotePath provided";
                    }
                    if (localPath == null || localPath.isEmpty()) {
                        return "Error: No localPath provided";
                    }
                    // Sanitize the remote path to prevent traversal attacks
                    remotePath = SecurityUtils.sanitizePath(remotePath);
                    if (remotePath.isEmpty()) {
                        return "Error: Invalid remote path";
                    }
                    try {
                        String sshKey = ctx.getSshKey();
                        SshConnectionManager.SftpChannel sftp = SshConnectionManager.getInstance().openSftp(sshKey);
                        try {
                            sftp.download(remotePath, localPath);
                            return "Successfully downloaded " + remotePath + " to " + localPath;
                        } finally {
                            sftp.close();
                        }
                    } catch (Exception e) {
                        return "Error downloading file: " + e.getMessage();
                    }
                }));

        logger.info("Registered {} built-in tools", tools.size());
    }

    /**
     * 在可见终端执行命令。不可用时返回错误，不允许静默回退到后台 SSH，
     * 从而保证用户看到 AI 执行的每一条 shell 命令及其输出。
     */
    private String executeViaVisibleTerminal(String sshKey, String command) {
        try {
            return TerminalCommandBridge.getInstance().execute(sshKey, command, 300000);
        } catch (Exception e) {
            return "Error executing command in visible terminal: " + e.getMessage();
        }
    }

    private boolean hasNonZeroExitCode(String result) {
        if (result == null) return false;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\[exit code: (\\d+)\\]")
                .matcher(result);
        return matcher.find() && !"0".equals(matcher.group(1));
    }

    public void register(ToolDefinition tool) {
        tools.put(tool.getId(), tool);
    }

    public ToolDefinition getTool(String id) {
        return tools.get(id);
    }

    public List<ToolDefinition> getAllTools() {
        return new ArrayList<>(tools.values());
    }

    /**
     * Generate tools description for system prompt injection.
     */
    public String generateToolsPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You have access to the following tools to help the user manage their server. ");
        sb.append("When you need to perform an action (run a command, upload a file, check status, etc.), ");
        sb.append("you MUST call the appropriate tool using the exact format specified below.\n\n");
        sb.append("## Available Tools\n\n");

        for (ToolDefinition tool : tools.values()) {
            sb.append(String.format("### %s\n%s\n\n",
                    tool.getName(), tool.getDescription()));
        }

        sb.append("## How to Call Tools\n\n");
        sb.append("To call a tool, wrap it in <tool_call> tags using this EXACT format:\n\n");
        sb.append("<tool_call>tool_name({\"param1\": \"value1\", \"param2\": \"value2\"})</tool_call>\n\n");
        sb.append("Rules:\n");
        sb.append("- ALWAYS use <tool_call>...</tool_call> tags around every tool call\n");
        sb.append("- Parameters must be valid JSON object: {\"key\": value}\n");
        sb.append("- You can call MULTIPLE tools in sequence by putting them one after another:\n");
        sb.append("  <tool_call>execute_shell({\"command\": \"mkdir -p /tmp\"})</tool_call>\n");
        sb.append("  <tool_call>check_port({\"port\": 8080})</tool_call>\n");
        sb.append("- Wait for tool results before deciding your next action\n");
        sb.append("- After receiving tool results, continue your analysis or provide a final answer\n");
        sb.append("- For tools without parameters, use: <tool_call>check_cpu({})</tool_call>\n");
        sb.append("- NEVER describe what you are about to do and then make the tool call in a later message. ");
        sb.append("Make the tool call immediately when you decide to use it.\n\n");
        sb.append("Example workflow:\n");
        sb.append("User: \"Check disk space and memory usage\"\n");
        sb.append("You: Let me check the disk and memory status for you.\n");
        sb.append("<tool_call>check_disk({\"path\": \"/\"})</tool_call>\n");
        sb.append("<tool_call>check_memory({})</tool_call>\n");
        sb.append("[System returns tool results]\n");
        sb.append("You: Based on the results, here is the server status: [final answer]\n");

        return sb.toString();
    }

    /**
     * Generate OpenAI tools format JSON array for native function calling.
     * Format: [{ "type": "function", "function": { "name", "description", "parameters" } }]
     */
    public JsonNode generateToolsJson() {
        ArrayNode toolsArray = objectMapper.createArrayNode();

        for (ToolDefinition tool : tools.values()) {
            ObjectNode toolNode = objectMapper.createObjectNode();
            toolNode.put("type", "function");

            ObjectNode functionNode = objectMapper.createObjectNode();
            functionNode.put("name", tool.getName());
            functionNode.put("description", tool.getDescription());

            if (tool.getParameters() != null) {
                functionNode.set("parameters", tool.getParameters());
            } else {
                // Empty schema for tools without parameters
                ObjectNode emptySchema = objectMapper.createObjectNode();
                emptySchema.put("type", "object");
                emptySchema.set("properties", objectMapper.createObjectNode());
                functionNode.set("parameters", emptySchema);
            }

            toolNode.set("function", functionNode);
            toolsArray.add(toolNode);
        }

        return toolsArray;
    }

    private ObjectNode buildSchema(String paramName, String paramDesc) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode param = objectMapper.createObjectNode();
        param.put("type", "string");
        param.put("description", paramDesc);
        properties.set(paramName, param);
        schema.set("properties", properties);

        ArrayNode required = objectMapper.createArrayNode();
        required.add(paramName);
        schema.set("required", required);

        return schema;
    }

    private ObjectNode buildTwoParams(String name1, String desc1, String name2, String desc2) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode p1 = objectMapper.createObjectNode();
        p1.put("type", "string");
        p1.put("description", desc1);
        properties.set(name1, p1);

        ObjectNode p2 = objectMapper.createObjectNode();
        p2.put("type", "string");
        p2.put("description", desc2);
        properties.set(name2, p2);

        schema.set("properties", properties);

        ArrayNode required = objectMapper.createArrayNode();
        required.add(name1);
        schema.set("required", required);

        return schema;
    }

    /**
     * Build schema with no parameters.
     */
    private ObjectNode buildEmptySchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", objectMapper.createObjectNode());
        return schema;
    }

    /**
     * Build a string parameter node.
     */
    private ObjectNode stringParam(String desc) {
        ObjectNode p = objectMapper.createObjectNode();
        p.put("type", "string");
        p.put("description", desc);
        return p;
    }

    /**
     * Build an integer parameter node.
     */
    private ObjectNode intParam(String desc) {
        ObjectNode p = objectMapper.createObjectNode();
        p.put("type", "integer");
        p.put("description", desc);
        return p;
    }

    /**
     * Build a boolean parameter node.
     */
    private ObjectNode boolParam(String desc) {
        ObjectNode p = objectMapper.createObjectNode();
        p.put("type", "boolean");
        p.put("description", desc);
        return p;
    }

    /**
     * Build an enum parameter node (string with constrained values).
     */
    private ObjectNode enumParam(String desc, String... values) {
        ObjectNode p = objectMapper.createObjectNode();
        p.put("type", "string");
        p.put("description", desc);
        ArrayNode enumArray = objectMapper.createArrayNode();
        for (String v : values) {
            enumArray.add(v);
        }
        p.set("enum", enumArray);
        return p;
    }

    /**
     * Build a schema from a properties node and required names.
     */
    private ObjectNode buildSchemaFromProps(ObjectNode properties, String... requiredNames) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        if (requiredNames.length > 0) {
            ArrayNode required = objectMapper.createArrayNode();
            for (String r : requiredNames) {
                required.add(r);
            }
            schema.set("required", required);
        }
        return schema;
    }
}
