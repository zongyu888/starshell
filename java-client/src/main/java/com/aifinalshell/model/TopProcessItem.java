package com.aifinalshell.model;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * 进程 TOP 表格数据模型。
 * 用于左侧监控面板的进程 TOP TableView 展示。
 * 每行对应一个进程：USER / CPU% / MEM% / COMMAND。
 */
public class TopProcessItem {

    private final StringProperty user = new SimpleStringProperty("");
    private final StringProperty cpu = new SimpleStringProperty("");
    private final StringProperty mem = new SimpleStringProperty("");
    private final StringProperty command = new SimpleStringProperty("");

    public TopProcessItem(String user, String cpu, String mem, String command) {
        this.user.set(user);
        this.cpu.set(cpu);
        this.mem.set(mem);
        this.command.set(command);
    }

    public StringProperty userProperty() { return user; }
    public StringProperty cpuProperty() { return cpu; }
    public StringProperty memProperty() { return mem; }
    public StringProperty commandProperty() { return command; }

    public String getUser() { return user.get(); }
    public String getCpu() { return cpu.get(); }
    public String getMem() { return mem.get(); }
    public String getCommand() { return command.get(); }

    /** 解析 CPU 百分比值，用于按阈值着色 */
    public double getCpuValue() {
        try {
            return Double.parseDouble(cpu.get().replace("%", "").trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 解析 MEM 百分比值，用于按阈值着色 */
    public double getMemValue() {
        try {
            return Double.parseDouble(mem.get().replace("%", "").trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
