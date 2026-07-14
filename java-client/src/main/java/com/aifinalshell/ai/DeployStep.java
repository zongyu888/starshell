package com.aifinalshell.ai;

/**
 * 部署步骤模型。
 * 表示部署流程中的一个原子操作，包含执行命令、描述、是否关键步骤及回滚命令。
 */
public class DeployStep {

    /** 步骤名称 */
    private String name;

    /** 要执行的命令（SFTP上传步骤此字段为空，由编排器单独处理） */
    private String command;

    /** 步骤描述（展示给用户） */
    private String description;

    /** 是否关键步骤：关键步骤失败将触发回滚 */
    private boolean critical;

    /** 回滚命令：当此步骤或后续关键步骤失败时执行 */
    private String rollbackCommand;

    /** 步骤类型 */
    private StepType type;

    /**
     * 步骤类型枚举。
     */
    public enum StepType {
        /** 创建目录 */
        CREATE_DIR,
        /** 备份旧文件 */
        BACKUP,
        /** SFTP上传文件 */
        UPLOAD,
        /** 停止旧进程 */
        STOP_PROCESS,
        /** 启动新进程 */
        START_PROCESS,
        /** 端口校验 */
        CHECK_PORT,
        /** 查看日志 */
        CHECK_LOG,
        /** 创建systemd服务 */
        CREATE_SERVICE,
        /** 其他Shell命令 */
        SHELL
    }

    public DeployStep() {}

    public DeployStep(String name, String command, String description, boolean critical,
                      String rollbackCommand, StepType type) {
        this.name = name;
        this.command = command;
        this.description = description;
        this.critical = critical;
        this.rollbackCommand = rollbackCommand;
        this.type = type;
    }

    // ========== Getters & Setters ==========

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isCritical() { return critical; }
    public void setCritical(boolean critical) { this.critical = critical; }

    public String getRollbackCommand() { return rollbackCommand; }
    public void setRollbackCommand(String rollbackCommand) { this.rollbackCommand = rollbackCommand; }

    public StepType getType() { return type; }
    public void setType(StepType type) { this.type = type; }

    @Override
    public String toString() {
        return "DeployStep{name='" + name + "', type=" + type +
                ", critical=" + critical + ", command='" + command + "'}";
    }
}
