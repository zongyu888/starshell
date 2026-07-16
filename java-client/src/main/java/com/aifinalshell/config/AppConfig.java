package com.aifinalshell.config;

import java.io.*;
import java.util.Objects;
import java.util.Properties;

public class AppConfig {
    private static AppConfig instance;
    private final Properties properties;
    private final boolean persistent;
    private final File configFile;
    private final boolean migrateLegacyWorkingDirectoryConfig;
    private static final String CONFIG_PATH_PROPERTY = "starshell.app.config";
    private static final String CONFIG_PATH_ENV = "STARSHELL_APP_CONFIG";
    private static final String LEGACY_CONFIG_FILE = "config.properties";

    private AppConfig() {
        this(new Properties(), true, resolveConfigFile(), !hasExplicitConfigPath());
        loadConfig();
    }

    private AppConfig(Properties properties, boolean persistent) {
        this(properties, persistent, null, false);
    }

    private AppConfig(Properties properties, boolean persistent,
                      File configFile, boolean migrateLegacyWorkingDirectoryConfig) {
        this.properties = properties;
        this.persistent = persistent;
        this.configFile = configFile;
        this.migrateLegacyWorkingDirectoryConfig = migrateLegacyWorkingDirectoryConfig;
    }

    public static synchronized AppConfig getInstance() {
        if (instance == null) {
            instance = new AppConfig();
        }
        return instance;
    }

    private void loadConfig() {
        File source = configFile;
        if ((source == null || !source.isFile()) && migrateLegacyWorkingDirectoryConfig) {
            File legacy = new File(LEGACY_CONFIG_FILE).getAbsoluteFile();
            if (legacy.isFile()) source = legacy;
        }
        if (source != null && source.isFile()) {
            try (FileInputStream fis = new FileInputStream(source)) {
                properties.load(fis);
            } catch (IOException e) {
                System.err.println("加载配置文件失败: " + e.getMessage());
            }
        }
        setDefaults();
        migrateLegacyValues();
        saveConfig();
    }

    private static boolean hasExplicitConfigPath() {
        String property = System.getProperty(CONFIG_PATH_PROPERTY);
        String env = System.getenv(CONFIG_PATH_ENV);
        return (property != null && !property.isBlank()) || (env != null && !env.isBlank());
    }

    private static File resolveConfigFile() {
        String override = System.getProperty(CONFIG_PATH_PROPERTY);
        if (override == null || override.isBlank()) override = System.getenv(CONFIG_PATH_ENV);
        if (override != null && !override.isBlank()) return new File(override).getAbsoluteFile();

        File configDir = new File(System.getProperty("user.home"), ".aifinalshell");
        return new File(configDir, "app.properties").getAbsoluteFile();
    }

    private void migrateLegacyValues() {
        // 旧版本把粘贴与垂直分屏都设为 Ctrl+Shift+V，JavaFX accelerator 会静默覆盖其中一个。
        if ("Ctrl+Shift+V".equalsIgnoreCase(properties.getProperty("shortcut.paste"))
                && "Ctrl+Shift+V".equalsIgnoreCase(properties.getProperty("shortcut.split_vertical"))) {
            properties.setProperty("shortcut.split_vertical", "Ctrl+Alt+V");
        }
        if ("Ctrl++".equalsIgnoreCase(properties.getProperty("shortcut.zoom_in"))) {
            properties.setProperty("shortcut.zoom_in", "Ctrl+Shift+Equals");
        }
        migrateBackgroundImageSelection(properties);
    }

    /**
     * Older settings dialogs saved the selected image path but forgot to enable
     * the background-image switch.  Repair that legacy state exactly once when
     * the path still points to a real file.  The marker ensures a later explicit
     * user choice to disable the image is never reversed on the next launch.
     */
    static void migrateBackgroundImageSelection(Properties values) {
        String marker = "background.image.auto_enable_migrated";
        if (Boolean.parseBoolean(values.getProperty(marker, "false"))) return;

        String path = values.getProperty("background.image.path", "").trim();
        boolean enabled = Boolean.parseBoolean(
                values.getProperty("background.image.enabled", "false"));
        if (!enabled && !path.isEmpty() && new File(path).isFile()) {
            values.setProperty("background.image.enabled", "true");
        }
        values.setProperty(marker, "true");
    }

    private void setDefaults() {
        // AI Service
        setDefault("ai.service.url", "http://localhost:8000");
        setDefault("ai.service.timeout", "60");

        // Monitor
        setDefault("monitor.default.interval", "5");
        setDefault("monitor.ui.interval.seconds", "3");
        setDefault("monitor.cpu.threshold", "90");
        setDefault("monitor.memory.threshold", "95");
        setDefault("monitor.disk.threshold", "90");
        setDefault("alert.dedup.minutes", "5");

        // Theme & Language
        setDefault("theme", "dark");
        setDefault("language", "zh_CN");

        // ========== Session ==========
        setDefault("session.last_id", "");

        // ========== General Settings ==========
        setDefault("general.auto_select_tab", "false");
        setDefault("general.minimize_to_tray", "false");
        setDefault("general.confirm_before_close", "true");

        // ========== Terminal Settings ==========
        setDefault("terminal.charset", "UTF-8");
        setDefault("terminal.backspace_key", "VT220");
        setDefault("terminal.delete_key", "VT220");
        setDefault("terminal.scroll_buffer", "50000");
        setDefault("terminal.cursor_blink", "true");
        setDefault("terminal.cursor_style", "block");

        // ========== Mouse Settings ==========
        setDefault("mouse.copy_on_select", "false");
        setDefault("mouse.paste_on_right_click", "true");
        setDefault("mouse.scroll_speed", "3");

        // ========== Keyboard Shortcuts ==========
        setDefault("shortcut.copy", "Ctrl+Shift+C");
        setDefault("shortcut.paste", "Ctrl+Shift+V");
        setDefault("shortcut.find", "Ctrl+F");
        setDefault("shortcut.clear", "Ctrl+L");
        setDefault("shortcut.new_tab", "Ctrl+T");
        setDefault("shortcut.close_tab", "Ctrl+W");
        setDefault("shortcut.next_tab", "Ctrl+Tab");
        setDefault("shortcut.prev_tab", "Ctrl+Shift+Tab");
        setDefault("shortcut.split_horizontal", "Ctrl+Shift+H");
        // Ctrl+Shift+V 已用于粘贴，分屏必须使用独立默认键，避免 accelerator 后注册者覆盖前者。
        setDefault("shortcut.split_vertical", "Ctrl+Alt+V");
        setDefault("shortcut.fullscreen", "F11");
        setDefault("shortcut.zoom_in", "Ctrl+Shift+Equals");
        setDefault("shortcut.zoom_out", "Ctrl+-");
        setDefault("shortcut.zoom_reset", "Ctrl+0");

        // ========== Color Scheme ==========
        setDefault("color.scheme", "default");
        setDefault("color.foreground", "#d4d4d4");
        setDefault("color.background", "#1e1e1e");
        setDefault("color.cursor", "#ffffff");
        setDefault("color.selection", "#264f78");
        setDefault("color.black", "#000000");
        setDefault("color.red", "#cd3131");
        setDefault("color.green", "#0dbc79");
        setDefault("color.yellow", "#d29922");
        setDefault("color.blue", "#2472c8");
        setDefault("color.magenta", "#bc3fbc");
        setDefault("color.cyan", "#11a8cd");
        setDefault("color.white", "#e5e5e5");
        setDefault("color.bright_black", "#666666");
        setDefault("color.bright_red", "#f85149");
        setDefault("color.bright_green", "#3fb950");
        setDefault("color.bright_yellow", "#d29922");
        setDefault("color.bright_blue", "#58a6ff");
        setDefault("color.bright_magenta", "#bc8cff");
        setDefault("color.bright_cyan", "#39c5cf");
        setDefault("color.bright_white", "#e5e5e5");

        // ========== Background Image ==========
        setDefault("background.image.enabled", "false");
        setDefault("background.image.path", "");
        setDefault("background.image.opacity", "0.3");
        // "contain" keeps the whole image visible; cover remains an explicit option.
        setDefault("background.image.fit", "contain");
        setDefault("background.image.auto_enable_migrated", "false");

        // ========== Font Settings ==========
        setDefault("font.family", "Cascadia Code");
        setDefault("font.size", "14");
        setDefault("font.bold", "false");
        setDefault("font.italic", "false");
    }

    private void setDefault(String key, String defaultValue) {
        if (!properties.containsKey(key)) {
            properties.setProperty(key, defaultValue);
        }
    }

    public synchronized void saveConfig() {
        // Draft 永不直接写盘；只有 applyDraft() 能提交到单例配置。
        if (!persistent) return;
        if (configFile == null) return;
        File parent = configFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            System.err.println("创建配置目录失败: " + parent);
            return;
        }
        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            properties.store(fos, "StarShell Configuration");
        } catch (IOException e) {
            System.err.println("保存配置文件失败: " + e.getMessage());
        }
    }

    /** Absolute stable path used by diagnostics and packaging verification. */
    public String getConfigPath() {
        return configFile == null ? "" : configFile.getAbsolutePath();
    }

    /**
     * Task 5.11: 真正重置——清空所有属性后重新填入默认值并落盘。
     * 供 SettingsDialog.resetToDefaults 调用，确保覆盖运行期间被修改的值。
     */
    public synchronized void clearAndReloadDefaults() {
        properties.clear();
        setDefaults();
        saveConfig();
    }

    /**
     * 创建与当前配置隔离的设置草稿。草稿复用全部类型化 getter/setter，
     * 但 {@link #saveConfig()} 不会写盘，必须通过 {@link #applyDraft(Draft)} 提交。
     */
    public synchronized Draft createDraft() {
        Properties copy = new Properties();
        properties.forEach(copy::put);
        return new Draft(copy);
    }

    /**
     * 原子提交一个设置草稿并写盘。提交后草稿仍可继续编辑，再次 Apply 会形成新基线。
     */
    public synchronized void applyDraft(Draft draft) {
        Objects.requireNonNull(draft, "draft");
        properties.clear();
        draft.snapshot().forEach(properties::put);
        setDefaults();
        saveConfig();
    }

    /** 返回当前配置的防御性副本，调用方无法绕过 Apply/Cancel 语义修改全局状态。 */
    public synchronized Properties snapshot() {
        Properties copy = new Properties();
        properties.forEach(copy::put);
        return copy;
    }

    /**
     * 设置窗口使用的隔离草稿。clearAndReloadDefaults() 仅重置草稿内存，不会碰配置文件。
     */
    public static final class Draft extends AppConfig {
        Draft(Properties properties) {
            super(properties, false);
        }
    }

    /**
     * Task 5.12: 暴露内部 Properties 对象引用，供 SettingsDialog 在打开时做快照、
     * Cancel 时回滚。返回的是内部引用（非副本），调用方可读可写。
     */
    @Deprecated
    public Properties getProperties() {
        return snapshot();
    }

    public String get(String key) {
        return properties.getProperty(key);
    }

    public String get(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(properties.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return Boolean.parseBoolean(properties.getProperty(key, String.valueOf(defaultValue)));
    }

    public double getDouble(String key, double defaultValue) {
        try {
            return Double.parseDouble(properties.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public void set(String key, String value) {
        properties.setProperty(key, value);
    }

    public void setAndSave(String key, String value) {
        properties.setProperty(key, value);
        saveConfig();
    }

    // ========== General ==========
    public boolean isAutoSelectTab() { return getBoolean("general.auto_select_tab", false); }
    public void setAutoSelectTab(boolean v) { set("general.auto_select_tab", String.valueOf(v)); }

    public boolean isMinimizeToTray() { return getBoolean("general.minimize_to_tray", false); }
    public void setMinimizeToTray(boolean v) { set("general.minimize_to_tray", String.valueOf(v)); }

    public boolean isConfirmBeforeClose() { return getBoolean("general.confirm_before_close", true); }
    public void setConfirmBeforeClose(boolean v) { set("general.confirm_before_close", String.valueOf(v)); }

    // ========== Terminal ==========
    public String getCharset() { return get("terminal.charset", "UTF-8"); }
    public void setCharset(String v) { set("terminal.charset", v); }

    public String getBackspaceKey() { return get("terminal.backspace_key", "VT220"); }
    public void setBackspaceKey(String v) { set("terminal.backspace_key", v); }

    public String getDeleteKey() { return get("terminal.delete_key", "VT220"); }
    public void setDeleteKey(String v) { set("terminal.delete_key", v); }

    public int getScrollBuffer() { return getInt("terminal.scroll_buffer", 50000); }
    public void setScrollBuffer(int v) { set("terminal.scroll_buffer", String.valueOf(v)); }

    public boolean isCursorBlink() { return getBoolean("terminal.cursor_blink", true); }
    public void setCursorBlink(boolean v) { set("terminal.cursor_blink", String.valueOf(v)); }

    public String getCursorStyle() { return get("terminal.cursor_style", "block"); }
    public void setCursorStyle(String v) { set("terminal.cursor_style", v); }

    // ========== Mouse ==========
    public boolean isCopyOnSelect() { return getBoolean("mouse.copy_on_select", false); }
    public void setCopyOnSelect(boolean v) { set("mouse.copy_on_select", String.valueOf(v)); }

    public boolean isPasteOnRightClick() { return getBoolean("mouse.paste_on_right_click", true); }
    public void setPasteOnRightClick(boolean v) { set("mouse.paste_on_right_click", String.valueOf(v)); }

    public int getScrollSpeed() { return getInt("mouse.scroll_speed", 3); }
    public void setScrollSpeed(int v) { set("mouse.scroll_speed", String.valueOf(v)); }

    // ========== Keyboard Shortcuts ==========
    public String getShortcut(String action) { return get("shortcut." + action, ""); }
    public void setShortcut(String action, String value) { set("shortcut." + action, value); }

    // ========== Color Scheme ==========
    public String getColorScheme() { return get("color.scheme", "default"); }
    public void setColorScheme(String v) { set("color.scheme", v); }

    public String getTerminalColor(String name) { return get("color." + name, "#ffffff"); }
    public void setTerminalColor(String name, String value) { set("color." + name, value); }

    // ========== Background Image ==========
    public boolean isBackgroundImageEnabled() { return getBoolean("background.image.enabled", false); }
    public void setBackgroundImageEnabled(boolean v) { set("background.image.enabled", String.valueOf(v)); }

    public String getBackgroundImagePath() { return get("background.image.path", ""); }
    public void setBackgroundImagePath(String v) { set("background.image.path", v); }

    public double getBackgroundImageOpacity() { return getDouble("background.image.opacity", 0.3); }
    public void setBackgroundImageOpacity(double v) { set("background.image.opacity", String.valueOf(v)); }

    public String getBackgroundImageFit() { return get("background.image.fit", "contain"); }
    public void setBackgroundImageFit(String v) { set("background.image.fit", v); }

    // ========== Font ==========
    public String getFontFamily() { return get("font.family", "Cascadia Code"); }
    public void setFontFamily(String v) { set("font.family", v); }

    public int getFontSize() { return getInt("font.size", 14); }
    public void setFontSize(int v) { set("font.size", String.valueOf(v)); }

    public boolean isFontBold() { return getBoolean("font.bold", false); }
    public void setFontBold(boolean v) { set("font.bold", String.valueOf(v)); }

    public boolean isFontItalic() { return getBoolean("font.italic", false); }
    public void setFontItalic(boolean v) { set("font.italic", String.valueOf(v)); }

    // ========== Language ==========
    public String getLanguage() { return get("language", "zh_CN"); }
    public void setLanguage(String v) { set("language", v); }

    // ========== Session ==========
    public String getLastSessionId() { return get("session.last_id", ""); }
    public void setLastSessionId(String v) { setAndSave("session.last_id", v == null ? "" : v); }

    // ========== Existing AI/Monitor getters ==========
    public String getAiServiceUrl() { return get("ai.service.url", "http://localhost:8000"); }
    public int getAiServiceTimeout() { return getInt("ai.service.timeout", 60); }
    public int getMonitorInterval() { return getInt("monitor.default.interval", 5); }
    public int getMonitorUiIntervalSeconds() { return getInt("monitor.ui.interval.seconds", 3); }
    public void setMonitorUiIntervalSeconds(int v) { set("monitor.ui.interval.seconds", String.valueOf(v)); }
    public int getCpuThreshold() { return getInt("monitor.cpu.threshold", 90); }
    public int getMemoryThreshold() { return getInt("monitor.memory.threshold", 95); }
    public int getDiskThreshold() { return getInt("monitor.disk.threshold", 90); }
    public int getAlertDedupMinutes() { return getInt("alert.dedup.minutes", 5); }
}
