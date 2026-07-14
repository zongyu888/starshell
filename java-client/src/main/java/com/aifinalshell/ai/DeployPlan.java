package com.aifinalshell.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * 部署计划模型。
 * 由 AI 根据JAR文件名和服务器上下文生成，包含一系列有序的部署步骤。
 */
public class DeployPlan {

    /** JAR文件名 */
    private String jarName;

    /** 应用名称（从JAR文件名提取，去掉版本号和扩展名） */
    private String appName;

    /** 远程部署目录（如 /opt/appname/） */
    private String deployDir;

    /** 远程JAR包路径（如 /opt/appname/appname.jar） */
    private String remoteJarPath;

    /** 应用日志路径（如 /opt/appname/appname.log） */
    private String logPath;

    /** 预期监听端口（AI根据上下文推断，可能为空） */
    private String expectedPort;

    /** 启动命令（完整命令字符串） */
    private String startCommand;

    /** 是否使用systemd服务管理 */
    private boolean useSystemd;

    /** 部署步骤列表 */
    private List<DeployStep> steps;

    /** 计划描述（AI生成的说明） */
    private String description;

    public DeployPlan() {
        this.steps = new ArrayList<>();
    }

    /**
     * 添加部署步骤
     */
    public void addStep(DeployStep step) {
        this.steps.add(step);
    }

    /**
     * 获取所有关键步骤
     */
    public List<DeployStep> getCriticalSteps() {
        List<DeployStep> critical = new ArrayList<>();
        for (DeployStep step : steps) {
            if (step.isCritical()) {
                critical.add(step);
            }
        }
        return critical;
    }

    /**
     * 生成完整的回滚脚本（按步骤逆序执行回滚命令）
     */
    public String buildRollbackScript() {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/bash\n");
        sb.append("# 自动生成的回滚脚本\n");
        sb.append("set +e\n\n");

        // 逆序执行回滚命令
        for (int i = steps.size() - 1; i >= 0; i--) {
            DeployStep step = steps.get(i);
            if (step.getRollbackCommand() != null && !step.getRollbackCommand().isEmpty()) {
                sb.append("# 回滚步骤: ").append(step.getName()).append("\n");
                sb.append(step.getRollbackCommand()).append("\n\n");
            }
        }

        return sb.toString();
    }

    // ========== Getters & Setters ==========

    public String getJarName() { return jarName; }
    public void setJarName(String jarName) { this.jarName = jarName; }

    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }

    public String getDeployDir() { return deployDir; }
    public void setDeployDir(String deployDir) { this.deployDir = deployDir; }

    public String getRemoteJarPath() { return remoteJarPath; }
    public void setRemoteJarPath(String remoteJarPath) { this.remoteJarPath = remoteJarPath; }

    public String getLogPath() { return logPath; }
    public void setLogPath(String logPath) { this.logPath = logPath; }

    public String getExpectedPort() { return expectedPort; }
    public void setExpectedPort(String expectedPort) { this.expectedPort = expectedPort; }

    public String getStartCommand() { return startCommand; }
    public void setStartCommand(String startCommand) { this.startCommand = startCommand; }

    public boolean isUseSystemd() { return useSystemd; }
    public void setUseSystemd(boolean useSystemd) { this.useSystemd = useSystemd; }

    public List<DeployStep> getSteps() { return steps; }
    public void setSteps(List<DeployStep> steps) { this.steps = steps; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    @Override
    public String toString() {
        return "DeployPlan{appName='" + appName + "', deployDir='" + deployDir +
                "', steps=" + steps.size() + ", useSystemd=" + useSystemd + "}";
    }
}
