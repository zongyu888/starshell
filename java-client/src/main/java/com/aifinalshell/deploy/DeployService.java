package com.aifinalshell.deploy;

import com.aifinalshell.ai.AiServiceClient;
import com.aifinalshell.controller.TerminalCommandBridge;
import com.aifinalshell.model.DeployTask;
import com.aifinalshell.model.ServerConfig;
import com.aifinalshell.service.DatabaseManager;
import com.aifinalshell.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

public class DeployService {
    private static final Logger logger = LoggerFactory.getLogger(DeployService.class);
    private static DeployService instance;

    private DeployService() {}

    public static synchronized DeployService getInstance() {
        if (instance == null) {
            instance = new DeployService();
        }
        return instance;
    }

    public String generateDeployScript(String requirement, ServerConfig server) {
        String serverInfo = String.format("OS: Linux, Host: %s, User: %s", server.getHost(), server.getUsername());
        return AiServiceClient.getInstance().generateDeployScript(requirement, serverInfo);
    }

    public DeployTask executeDeploy(ServerConfig server, String script, Consumer<String> outputCallback) {
        return executeDeploy(server, null, script, outputCallback);
    }

    /** Execute an AI-generated deployment only through the registered visible terminal. */
    public DeployTask executeDeploy(ServerConfig server, String connectionKey,
                                    String script, Consumer<String> outputCallback) {
        DeployTask task = new DeployTask(server.getId(), "部署任务", "AI生成的部署脚本", script);

        try {
            DatabaseManager.getInstance().saveDeployTask(task);
            if (connectionKey == null || connectionKey.isBlank()) {
                throw new IllegalStateException("部署需要当前可见终端的连接键，已拒绝后台执行");
            }
            outputCallback.accept("开始执行部署脚本...\n");

            // Execute the entire script as one block via SSH bash -c
            // This preserves multi-line constructs (heredocs, if/fi, for loops, etc.)
            outputCallback.accept("$ [Executing deployment script via bash]\n");

            // 关键修复：在同一个 bash -c 会话中执行脚本并打印退出码，
            // 这样 $? 反映的是脚本的退出码。此前在独立 channel 执行 `echo $?`
            // 拿到的是 echo 自身的退出码（恒为 0），导致部署失败也报成功。
            String wrappedScript = script + "\necho \"EXIT_CODE=$?\"";
            String result = TerminalCommandBridge.getInstance().execute(
                    connectionKey, "bash -lc " + shellQuote(wrappedScript), 600_000);

            outputCallback.accept(result + "\n");

            // 从输出末尾解析真实退出码
            int exitCode = parseExitCode(result);

            // 辅助判断：检测关键错误关键字（部分命令退出码为0但实际出错）
            String lowerResult = result.toLowerCase();
            boolean hasErrorKeywords = lowerResult.contains("command not found")
                    || lowerResult.contains("no such file or directory")
                    || lowerResult.contains("permission denied")
                    || lowerResult.contains("connection refused")
                    || (lowerResult.contains("error:") && !lowerResult.contains("0 errors"));

            // 失败条件：退出码非0，或命中错误关键字
            boolean hasError = (exitCode != 0 && exitCode != -1) || hasErrorKeywords;

            if (hasError) {
                task.setStatus("FAILED");
                task.setOutput(result);
                task.setCompletedAt(java.time.LocalDateTime.now());
                DatabaseManager.getInstance().saveDeployTask(task);
                outputCallback.accept("部署失败，请检查错误信息\n");
                return task;
            }

            task.setStatus("SUCCESS");
            task.setOutput(result);
            task.setCompletedAt(java.time.LocalDateTime.now());
            DatabaseManager.getInstance().saveDeployTask(task);
            outputCallback.accept("部署完成！\n");

        } catch (Exception e) {
            logger.error("执行部署失败", e);
            task.setStatus("FAILED");
            task.setOutput("执行失败: " + e.getMessage());
            try {
                DatabaseManager.getInstance().saveDeployTask(task);
            } catch (Exception ex) {
                logger.error("保存部署任务失败", ex);
            }
            outputCallback.accept("部署失败: " + e.getMessage() + "\n");
        }

        return task;
    }

    public String generateRollbackScript(DeployTask task) {
        if (task.getRollbackScript() != null) {
            return task.getRollbackScript();
        }
        return "# 未生成回滚脚本";
    }

    /**
     * 从命令输出中解析 EXIT_CODE=N 标记，返回真实退出码。
     * 取最后一个 EXIT_CODE= 出现后的数字序列；未找到返回 -1。
     */
    private int parseExitCode(String output) {
        if (output == null || output.isEmpty()) {
            return -1;
        }
        int idx = output.lastIndexOf("EXIT_CODE=");
        if (idx < 0) {
            return -1;
        }
        String tail = output.substring(idx + "EXIT_CODE=".length());
        StringBuilder num = new StringBuilder();
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (c >= '0' && c <= '9') {
                num.append(c);
            } else if (num.length() > 0) {
                break;
            }
        }
        if (num.length() == 0) {
            return -1;
        }
        try {
            return Integer.parseInt(num.toString());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public boolean validateDeployment(ServerConfig server, String port) {
        return validateDeployment(server, null, port);
    }

    public boolean validateDeployment(ServerConfig server, String connectionKey, String port) {
        try {
            if (connectionKey == null || connectionKey.isBlank()) return false;
            // 净化端口：仅保留数字，防止命令注入（如 port="; rm -rf /"）
            String safePort = SecurityUtils.sanitizePort(port);
            if (safePort.isEmpty()) {
                logger.warn("validateDeployment: 非法端口被拒绝: {}", port);
                return false;
            }
            String cmd = "netstat -tlnp | grep " + safePort;
            String result = TerminalCommandBridge.getInstance().execute(connectionKey, cmd, 60_000);
            return result.contains(":" + safePort);
        } catch (Exception e) {
            logger.error("验证部署失败", e);
            return false;
        }
    }

    public void rollback(ServerConfig server, String rollbackScript, Consumer<String> outputCallback) {
        rollback(server, null, rollbackScript, outputCallback);
    }

    public void rollback(ServerConfig server, String connectionKey,
                         String rollbackScript, Consumer<String> outputCallback) {
        outputCallback.accept("开始执行回滚脚本...\n");
        try {
            if (connectionKey == null || connectionKey.isBlank()) {
                throw new IllegalStateException("回滚需要当前可见终端的连接键，已拒绝后台执行");
            }
            // Execute entire rollback script as one block
            outputCallback.accept("$ [Executing rollback script via bash]\n");
            String result = TerminalCommandBridge.getInstance().execute(
                    connectionKey, "bash -lc " + shellQuote(rollbackScript), 600_000);
            outputCallback.accept(result + "\n");
            if (result.toLowerCase().startsWith("error:")) {
                throw new IllegalStateException(result);
            }
            outputCallback.accept("回滚完成！\n");
        } catch (Exception e) {
            outputCallback.accept("回滚失败: " + e.getMessage() + "\n");
        }
    }

    private String shellQuote(String value) {
        String safe = value == null ? "" : value;
        return "'" + safe.replace("'", "'\"'\"'") + "'";
    }
}
