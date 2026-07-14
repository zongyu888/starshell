package com.aifinalshell.ai;

import com.aifinalshell.ssh.SshConnectionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 部署编排器。
 * 用户拖拽JAR包到AI对话窗口后，AI自动编排部署流程：
 * 分析JAR -> 生成部署计划 -> 创建目录 -> SFTP上传 -> 停旧进程 -> 启新进程 -> 校验 -> 查日志。
 * 支持关键步骤失败自动回滚。
 */
public class DeployOrchestrator {
    private static final Logger logger = LoggerFactory.getLogger(DeployOrchestrator.class);
    private static DeployOrchestrator instance;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DeployOrchestrator() {}

    public static synchronized DeployOrchestrator getInstance() {
        if (instance == null) {
            instance = new DeployOrchestrator();
        }
        return instance;
    }

    /**
     * 净化JAR文件名，仅保留安全字符 [a-zA-Z0-9._-]，其余替换为下划线。
     * 确保结果仍以 .jar 结尾，避免破坏部署命令中的文件名引用。
     * 防止 pkill -f 'jarName' 等命令拼接的注入风险。
     */
    private static String sanitizeJarName(String name) {
        if (name == null || name.isEmpty()) {
            return "app.jar";
        }
        String safe = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (!safe.toLowerCase().endsWith(".jar")) {
            safe = safe + ".jar";
        }
        return safe;
    }

    /**
     * 从本地JAR文件部署到服务器。
     * 完整流程：拉取上下文 -> 生成计划 -> 逐步执行 -> 失败回滚。
     *
     * @param localJarPath 本地JAR文件路径
     * @param sshKey       SSH连接键
     * @param serverId     服务器ID
     * @param onProgress   进度回调（步骤描述/状态）
     * @param onCommand    命令回调（展示给用户的命令）
     * @param onError      错误回调
     */
    public void deployJar(String localJarPath, String sshKey, Long serverId,
                          Consumer<String> onProgress, Consumer<String> onCommand,
                          Consumer<Exception> onError) {
        try {
            // 1. 校验本地JAR文件
            File jarFile = new File(localJarPath);
            if (!jarFile.exists() || !jarFile.isFile()) {
                onError.accept(new IllegalArgumentException("本地JAR文件不存在: " + localJarPath));
                return;
            }
            String rawJarName = jarFile.getName();
            // 安全修复：净化文件名，仅保留 [a-zA-Z0-9._-]，防止 pkill -f 'jarName' 等命令拼接注入
            String jarName = sanitizeJarName(rawJarName);
            if (!jarName.equals(rawJarName)) {
                onProgress.accept("注意: 文件名含特殊字符，已净化为 " + jarName);
            }
            onProgress.accept("开始部署: " + jarName);

            // 2. 拉取服务器上下文（快速模式）
            onProgress.accept("正在拉取服务器上下文...");
            ServerContext context = ServerContextFetcher.getInstance().fetchQuick(sshKey, serverId);

            // 检查Java环境
            if (!context.hasJava()) {
                onError.accept(new IllegalStateException("服务器未安装Java环境，无法部署JAR应用"));
                return;
            }
            onProgress.accept("服务器Java环境: " + context.getJavaVersion().split("\n")[0]);

            // 3. AI生成部署计划
            onProgress.accept("AI正在生成部署计划...");
            DeployPlan plan = generatePlan(jarName, context);
            onProgress.accept("部署计划已生成: " + plan.getSteps().size() + "个步骤");
            onProgress.accept("部署目录: " + plan.getDeployDir());
            if (plan.getDescription() != null) {
                onProgress.accept("计划说明: " + plan.getDescription());
            }

            // 4. 逐步执行部署
            List<DeployStep> executedSteps = new ArrayList<>();
            boolean deploySuccess = true;
            Exception deployError = null;

            for (int i = 0; i < plan.getSteps().size(); i++) {
                DeployStep step = plan.getSteps().get(i);
                String progressPrefix = "[" + (i + 1) + "/" + plan.getSteps().size() + "] ";
                onProgress.accept(progressPrefix + step.getDescription());

                try {
                    boolean success = executeStep(step, sshKey, onCommand, plan, localJarPath);

                    if (!success && step.isCritical()) {
                        // 关键步骤失败，触发回滚
                        onProgress.accept(progressPrefix + "关键步骤失败，开始回滚...");
                        rollback(executedSteps, sshKey, onProgress, onCommand);
                        deploySuccess = false;
                        deployError = new RuntimeException("关键步骤失败: " + step.getName());
                        break;
                    } else if (!success) {
                        onProgress.accept(progressPrefix + "步骤完成（有警告）");
                    } else {
                        onProgress.accept(progressPrefix + "步骤完成");
                    }
                    executedSteps.add(step);

                } catch (Exception e) {
                    logger.error("部署步骤执行异常: {}", step.getName(), e);
                    if (step.isCritical()) {
                        onProgress.accept(progressPrefix + "步骤异常，开始回滚...");
                        rollback(executedSteps, sshKey, onProgress, onCommand);
                        deploySuccess = false;
                        deployError = e;
                        break;
                    } else {
                        onProgress.accept(progressPrefix + "步骤异常（非关键，继续）: " + e.getMessage());
                    }
                    executedSteps.add(step);
                }
            }

            // 5. 部署结果通知
            if (deploySuccess) {
                onProgress.accept("=== 部署完成 ===");
                onProgress.accept("应用: " + plan.getAppName());
                onProgress.accept("目录: " + plan.getDeployDir());
                if (plan.getExpectedPort() != null && !plan.getExpectedPort().isEmpty()) {
                    onProgress.accept("端口: " + plan.getExpectedPort());
                }
                onProgress.accept("日志: " + plan.getLogPath());
            } else {
                onError.accept(deployError);
            }

        } catch (Exception e) {
            logger.error("部署流程异常", e);
            onError.accept(e);
        }
    }

    /**
     * AI生成部署计划。
     * 根据JAR文件名和服务器上下文，调用AI生成结构化部署计划。
     * AI不可用时回退到默认计划。
     *
     * @param jarName JAR文件名
     * @param context 服务器上下文
     * @return 部署计划
     */
    public DeployPlan generatePlan(String jarName, ServerContext context) {
        // 提取应用名称
        String appName = extractAppName(jarName);

        // 尝试AI生成计划
        DeployPlan aiPlan = tryGenerateAiPlan(jarName, appName, context);
        if (aiPlan != null) {
            logger.info("AI部署计划生成成功: {}", aiPlan);
            return aiPlan;
        }

        // 回退到默认计划
        logger.info("使用默认部署计划: jarName={}", jarName);
        return buildDefaultPlan(jarName, appName, context);
    }

    /**
     * 执行单个部署步骤。
     *
     * @param step     部署步骤
     * @param sshKey   SSH连接键
     * @param onOutput 输出回调
     */
    public void executeStep(DeployStep step, String sshKey, Consumer<String> onOutput) {
        executeStep(step, sshKey, onOutput, null, null);
    }

    // ========== 内部核心方法 ==========

    /**
     * 执行单个部署步骤（内部完整版）。
     *
     * @param step         部署步骤
     * @param sshKey       SSH连接键
     * @param onCommand    命令展示回调
     * @param plan         部署计划（上传步骤需要路径信息）
     * @param localJarPath 本地JAR路径（上传步骤需要）
     * @return 步骤是否成功
     */
    private boolean executeStep(DeployStep step, String sshKey, Consumer<String> onCommand,
                                DeployPlan plan, String localJarPath) {
        // SFTP上传步骤特殊处理
        if (step.getType() == DeployStep.StepType.UPLOAD) {
            return executeUploadStep(step, sshKey, onCommand, plan, localJarPath);
        }

        // 普通Shell命令步骤
        String command = step.getCommand();
        if (command == null || command.isEmpty()) {
            return true; // 空命令视为成功
        }

        // 展示命令给用户
        onCommand.accept("$ " + command);

        try {
            String output = SshConnectionManager.getInstance().executeCommand(sshKey, command);
            if (output != null && !output.isEmpty()) {
                onCommand.accept(output);
            }

            // 判断步骤是否成功
            return isStepSuccessful(step, output);

        } catch (Exception e) {
            logger.error("步骤执行失败: {}", step.getName(), e);
            onCommand.accept("错误: " + e.getMessage());
            return false;
        }
    }

    /**
     * 执行SFTP上传步骤。
     */
    private boolean executeUploadStep(DeployStep step, String sshKey, Consumer<String> onCommand,
                                      DeployPlan plan, String localJarPath) {
        if (plan == null || localJarPath == null) {
            onCommand.accept("错误: 上传步骤缺少计划或本地文件路径");
            return false;
        }

        String remotePath = plan.getRemoteJarPath();
        String deployDir = plan.getDeployDir();
        onCommand.accept("$ [SFTP] 上传 " + new File(localJarPath).getName() + " -> " + remotePath);

        SshConnectionManager.SftpChannel sftp = null;
        try {
            sftp = SshConnectionManager.getInstance().openSftp(sshKey);

            // 确保部署目录存在（尝试创建，忽略已存在错误）
            try {
                sftp.mkdir(deployDir);
            } catch (Exception ignored) {
                // 目录可能已存在，忽略
            }

            // 上传JAR文件
            sftp.upload(localJarPath, remotePath);
            onCommand.accept("[SFTP] 上传完成");
            return true;

        } catch (Exception e) {
            logger.error("SFTP上传失败", e);
            onCommand.accept("[SFTP] 上传失败: " + e.getMessage());
            return false;
        } finally {
            if (sftp != null) {
                try {
                    sftp.close();
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 判断步骤执行是否成功。
     * 根据步骤类型和输出内容判断。
     */
    private boolean isStepSuccessful(DeployStep step, String output) {
        if (output == null) {
            return true;
        }
        String lowerOutput = output.toLowerCase();

        switch (step.getType()) {
            case CHECK_PORT:
                // 端口检查步骤：输出包含端口信息视为成功
                // 若无输出可能端口未监听
                return !output.trim().isEmpty();

            case STOP_PROCESS:
                // 停止进程：即使没有匹配进程也算成功
                return true;

            case CHECK_LOG:
                // 日志检查：始终成功（仅查看）
                return true;

            default:
                // 其他步骤：检查是否包含严重错误关键词
                return !containsCriticalError(output);
        }
    }

    /**
     * 检测输出中是否包含严重错误关键词。
     */
    private boolean containsCriticalError(String output) {
        if (output == null) return false;
        String lower = output.toLowerCase();
        return lower.contains("command not found")
                || lower.contains("no such file or directory")
                || lower.contains("permission denied")
                || lower.contains("connection refused")
                || lower.contains("address already in use")
                || (lower.contains("error:") && !lower.contains("0 errors"));
    }

    /**
     * 执行回滚：逆序执行已完成步骤的回滚命令。
     */
    private void rollback(List<DeployStep> executedSteps, String sshKey,
                          Consumer<String> onProgress, Consumer<String> onCommand) {
        onProgress.accept("=== 开始回滚 ===");
        for (int i = executedSteps.size() - 1; i >= 0; i--) {
            DeployStep step = executedSteps.get(i);
            if (step.getRollbackCommand() == null || step.getRollbackCommand().isEmpty()) {
                continue;
            }
            onProgress.accept("回滚: " + step.getName());
            onCommand.accept("$ " + step.getRollbackCommand());
            try {
                String output = SshConnectionManager.getInstance().executeCommand(
                        sshKey, step.getRollbackCommand());
                if (output != null && !output.isEmpty()) {
                    onCommand.accept(output);
                }
            } catch (Exception e) {
                logger.warn("回滚步骤失败: {}", step.getName(), e);
                onCommand.accept("回滚警告: " + e.getMessage());
            }
        }
        onProgress.accept("=== 回滚完成 ===");
    }

    // ========== AI计划生成 ==========

    /**
     * 尝试调用AI生成部署计划。
     * 返回null表示AI不可用或解析失败，调用方应使用默认计划。
     */
    private DeployPlan tryGenerateAiPlan(String jarName, String appName, ServerContext context) {
        try {
            if (!AiServiceClient.getInstance().isServiceAvailable()) {
                return null;
            }

            String contextPrompt = ServerContextFetcher.getInstance().toPromptContext(context);
            String prompt = buildAiPlanPrompt(jarName, appName, contextPrompt);

            String response = AiServiceClient.getInstance().chat(prompt, "部署计划生成");
            if (response == null || response.isEmpty() || response.startsWith("Error:")) {
                return null;
            }

            return parseAiPlanResponse(response, jarName, appName);

        } catch (Exception e) {
            logger.warn("AI计划生成失败，将使用默认计划: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建AI部署计划的提示词。
     */
    private String buildAiPlanPrompt(String jarName, String appName, String contextPrompt) {
        return "请为以下JAR包生成一个部署计划，以JSON格式返回（不要包含其他文本）。\n\n" +
                "JAR文件名: " + jarName + "\n" +
                "应用名称: " + appName + "\n\n" +
                contextPrompt + "\n\n" +
                "请返回如下JSON格式:\n" +
                "```json\n" +
                "{\n" +
                "  \"appName\": \"" + appName + "\",\n" +
                "  \"deployDir\": \"/opt/" + appName + "\",\n" +
                "  \"remoteJarPath\": \"/opt/" + appName + "/" + jarName + "\",\n" +
                "  \"logPath\": \"/opt/" + appName + "/" + appName + ".log\",\n" +
                "  \"expectedPort\": \"\",\n" +
                "  \"useSystemd\": false,\n" +
                "  \"startCommand\": \"nohup java -jar /opt/" + appName + "/" + jarName + " > /opt/" + appName + "/" + appName + ".log 2>&1 &\",\n" +
                "  \"description\": \"部署说明\",\n" +
                "  \"steps\": [\n" +
                "    {\"name\":\"创建目录\",\"command\":\"mkdir -p /opt/" + appName + "\",\"description\":\"创建部署目录\",\"critical\":false,\"rollbackCommand\":\"\",\"type\":\"CREATE_DIR\"},\n" +
                "    {\"name\":\"备份旧版\",\"command\":\"cp /opt/" + appName + "/" + jarName + " /opt/" + appName + "/" + appName + ".jar.bak 2>/dev/null\",\"description\":\"备份现有JAR\",\"critical\":false,\"rollbackCommand\":\"\",\"type\":\"BACKUP\"},\n" +
                "    {\"name\":\"上传JAR\",\"command\":\"\",\"description\":\"通过SFTP上传JAR包\",\"critical\":true,\"rollbackCommand\":\"cp /opt/" + appName + "/" + appName + ".jar.bak /opt/" + appName + "/" + jarName + " 2>/dev/null\",\"type\":\"UPLOAD\"},\n" +
                "    {\"name\":\"停止旧进程\",\"command\":\"pkill -f '" + jarName + "' 2>/dev/null; sleep 2\",\"description\":\"停止运行中的旧应用\",\"critical\":true,\"rollbackCommand\":\"\",\"type\":\"STOP_PROCESS\"},\n" +
                "    {\"name\":\"启动应用\",\"command\":\"nohup java -jar /opt/" + appName + "/" + jarName + " > /opt/" + appName + "/" + appName + ".log 2>&1 &\",\"description\":\"启动新应用\",\"critical\":true,\"rollbackCommand\":\"nohup java -jar /opt/" + appName + "/" + appName + ".jar.bak > /opt/" + appName + "/" + appName + ".log 2>&1 &\",\"type\":\"START_PROCESS\"},\n" +
                "    {\"name\":\"端口校验\",\"command\":\"sleep 5 && ss -tlnp | grep LISTEN\",\"description\":\"等待启动并检查端口\",\"critical\":false,\"rollbackCommand\":\"\",\"type\":\"CHECK_PORT\"},\n" +
                "    {\"name\":\"查看日志\",\"command\":\"tail -50 /opt/" + appName + "/" + appName + ".log\",\"description\":\"检查启动日志确认无错误\",\"critical\":false,\"rollbackCommand\":\"\",\"type\":\"CHECK_LOG\"}\n" +
                "  ]\n" +
                "}\n" +
                "```\n" +
                "请根据服务器实际情况调整端口、JVM参数等。如果服务器有systemd，可考虑useSystemd=true并生成service文件。";
    }

    /**
     * 解析AI返回的部署计划JSON。
     */
    private DeployPlan parseAiPlanResponse(String response, String jarName, String appName) {
        try {
            // 提取JSON部分
            String json = response;
            if (response.contains("```json")) {
                json = response.split("```json")[1].split("```")[0].trim();
            } else if (response.contains("```")) {
                json = response.split("```")[1].split("```")[0].trim();
            }

            JsonNode root = objectMapper.readTree(json);
            DeployPlan plan = new DeployPlan();
            plan.setJarName(jarName);
            plan.setAppName(getTextOrDefault(root, "appName", appName));
            plan.setDeployDir(getTextOrDefault(root, "deployDir", "/opt/" + appName));
            plan.setRemoteJarPath(getTextOrDefault(root, "remoteJarPath",
                    plan.getDeployDir() + "/" + jarName));
            plan.setLogPath(getTextOrDefault(root, "logPath",
                    plan.getDeployDir() + "/" + appName + ".log"));
            plan.setExpectedPort(getTextOrDefault(root, "expectedPort", ""));
            plan.setUseSystemd(root.has("useSystemd") && root.get("useSystemd").asBoolean());
            plan.setStartCommand(getTextOrDefault(root, "startCommand", ""));
            plan.setDescription(getTextOrDefault(root, "description", ""));

            // 解析步骤列表
            JsonNode stepsNode = root.get("steps");
            if (stepsNode != null && stepsNode.isArray()) {
                for (JsonNode stepNode : stepsNode) {
                    DeployStep step = new DeployStep();
                    step.setName(getTextOrDefault(stepNode, "name", "未命名步骤"));
                    step.setCommand(getTextOrDefault(stepNode, "command", ""));
                    step.setDescription(getTextOrDefault(stepNode, "description", step.getName()));
                    step.setCritical(stepNode.has("critical") && stepNode.get("critical").asBoolean());
                    step.setRollbackCommand(getTextOrDefault(stepNode, "rollbackCommand", ""));
                    step.setType(parseStepType(getTextOrDefault(stepNode, "type", "SHELL")));
                    plan.addStep(step);
                }
            }

            // 确保至少有步骤
            if (plan.getSteps().isEmpty()) {
                return null;
            }

            return plan;

        } catch (Exception e) {
            logger.warn("解析AI部署计划失败: {}", e.getMessage());
            return null;
        }
    }

    // ========== 默认计划生成 ==========

    /**
     * 构建默认部署计划（AI不可用时的回退方案）。
     */
    private DeployPlan buildDefaultPlan(String jarName, String appName, ServerContext context) {
        DeployPlan plan = new DeployPlan();
        plan.setJarName(jarName);
        plan.setAppName(appName);
        plan.setDeployDir("/opt/" + appName);
        plan.setRemoteJarPath("/opt/" + appName + "/" + jarName);
        plan.setLogPath("/opt/" + appName + "/" + appName + ".log");
        plan.setExpectedPort("");
        plan.setUseSystemd(false);
        plan.setStartCommand("nohup java -jar " + plan.getRemoteJarPath() +
                " > " + plan.getLogPath() + " 2>&1 &");
        plan.setDescription("默认部署计划（AI不可用时生成）");

        String deployDir = plan.getDeployDir();
        String remoteJar = plan.getRemoteJarPath();
        String logPath = plan.getLogPath();
        String backupPath = deployDir + "/" + appName + ".jar.bak";

        // 步骤1: 创建部署目录
        plan.addStep(new DeployStep(
                "创建部署目录",
                "mkdir -p " + deployDir,
                "创建部署目录 " + deployDir,
                false,
                "",
                DeployStep.StepType.CREATE_DIR
        ));

        // 步骤2: 备份现有JAR
        plan.addStep(new DeployStep(
                "备份现有JAR",
                "cp " + remoteJar + " " + backupPath + " 2>/dev/null",
                "备份当前运行的JAR文件",
                false,
                "",
                DeployStep.StepType.BACKUP
        ));

        // 步骤3: SFTP上传JAR包（关键）
        plan.addStep(new DeployStep(
                "上传JAR包",
                "",
                "通过SFTP上传 " + jarName + " 到 " + remoteJar,
                true,
                "cp " + backupPath + " " + remoteJar + " 2>/dev/null",
                DeployStep.StepType.UPLOAD
        ));

        // 步骤4: 停止旧进程（关键）
        plan.addStep(new DeployStep(
                "停止旧进程",
                "pkill -f '" + jarName + "' 2>/dev/null; sleep 2",
                "停止正在运行的旧应用进程",
                true,
                "",
                DeployStep.StepType.STOP_PROCESS
        ));

        // 步骤5: 启动新应用（关键）
        plan.addStep(new DeployStep(
                "启动应用",
                plan.getStartCommand(),
                "启动新应用进程",
                true,
                "nohup java -jar " + backupPath + " > " + logPath + " 2>&1 &",
                DeployStep.StepType.START_PROCESS
        ));

        // 步骤6: 等待启动并校验端口
        plan.addStep(new DeployStep(
                "端口校验",
                "sleep 5 && ss -tlnp 2>/dev/null | grep LISTEN",
                "等待应用启动并检查监听端口",
                false,
                "",
                DeployStep.StepType.CHECK_PORT
        ));

        // 步骤7: 查看启动日志
        plan.addStep(new DeployStep(
                "查看启动日志",
                "tail -50 " + logPath,
                "检查启动日志确认无错误",
                false,
                "",
                DeployStep.StepType.CHECK_LOG
        ));

        return plan;
    }

    // ========== 工具方法 ==========

    /**
     * 从JAR文件名提取应用名称。
     * 例如: "my-app-1.0.0.jar" -> "my-app"
     *      "user-service-2.3.1-SNAPSHOT.jar" -> "user-service"
     */
    private String extractAppName(String jarName) {
        // 去掉 .jar 扩展名
        String name = jarName.toLowerCase();
        if (name.endsWith(".jar")) {
            name = name.substring(0, name.length() - 4);
        }
        // 去掉版本号部分（匹配 -数字 开头的版本段）
        name = name.replaceAll("-\\d+.*$", "");
        // 如果处理后为空，返回原始名（去扩展名）
        if (name.isEmpty()) {
            name = jarName.replaceAll("(?i)\\.jar$", "");
        }
        return name;
    }

    /**
     * 从JsonNode安全获取文本字段，提供默认值。
     */
    private String getTextOrDefault(JsonNode node, String field, String defaultValue) {
        if (node != null && node.has(field) && !node.get(field).isNull()) {
            String text = node.get(field).asText();
            return text != null && !text.isEmpty() ? text : defaultValue;
        }
        return defaultValue;
    }

    /**
     * 解析步骤类型字符串为枚举。
     */
    private DeployStep.StepType parseStepType(String typeStr) {
        try {
            return DeployStep.StepType.valueOf(typeStr.toUpperCase());
        } catch (Exception e) {
            return DeployStep.StepType.SHELL;
        }
    }
}
