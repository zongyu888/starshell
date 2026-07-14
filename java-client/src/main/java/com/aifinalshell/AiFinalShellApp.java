package com.aifinalshell;

import com.aifinalshell.config.AppConfig;
import com.aifinalshell.config.I18n;
import com.aifinalshell.controller.MainController;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.image.Image;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AiFinalShellApp extends Application {
    private static final Logger logger = LoggerFactory.getLogger(AiFinalShellApp.class);
    private SystemTrayHelper trayHelper;

    @Override
    public void start(Stage primaryStage) {
        try {
            // Set window icon
            try {
                Image icon = new Image(getClass().getResourceAsStream("/images/icon.png"));
                primaryStage.getIcons().add(icon);
            } catch (Exception e) {
                logger.debug("Failed to load window icon", e);
            }

            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/fxml/main.fxml"),
                    I18n.getInstance().getBundle()
            );
            Parent root = loader.load();

            MainController controller = loader.getController();
            controller.setPrimaryStage(primaryStage);

            Scene scene = new Scene(root, 1200, 800);
            scene.getStylesheets().add(getClass().getResource("/css/dark-theme.css").toExternalForm());

            primaryStage.setTitle(I18n.tr("window.title"));
            primaryStage.setScene(scene);
            primaryStage.setMinWidth(900);
            primaryStage.setMinHeight(600);

            // Handle close confirmation
            AppConfig config = AppConfig.getInstance();
            primaryStage.setOnCloseRequest(event -> {
                if (config.isConfirmBeforeClose()) {
                    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                            "确定要退出 StarShell 吗？",
                            ButtonType.OK, ButtonType.CANCEL);
                    confirm.setHeaderText("退出确认");
                    confirm.showAndWait().ifPresent(btn -> {
                        if (btn == ButtonType.OK) {
                            performClose(primaryStage, event);
                        } else {
                            event.consume();
                        }
                    });
                } else {
                    performClose(primaryStage, event);
                }
            });

            // Initialize system tray if minimize to tray is enabled
            if (config.isMinimizeToTray()) {
                initSystemTray(primaryStage);
            }

            primaryStage.show();
            logger.info("StarShell 启动成功");
        } catch (Exception e) {
            logger.error("启动失败", e);
        }
    }

    private void performClose(Stage stage, WindowEvent event) {
        logger.info("正在优雅关闭 StarShell...");
        // 1. 断开所有SSH连接（释放通道与会话）
        try {
            com.aifinalshell.ssh.SshConnectionManager.getInstance().disconnectAll();
        } catch (Exception e) {
            logger.debug("Error disconnecting SSH", e);
        }
        // 2. 关闭数据库连接（确保H2写盘完成，避免数据丢失）
        try {
            com.aifinalshell.service.DatabaseManager.getInstance().close();
        } catch (Exception e) {
            logger.debug("Error closing database", e);
        }
        // 2.1 C5: 刷新 API 密钥 lastUsed 脏数据到配置文件，避免退出丢失
        try {
            com.aifinalshell.config.ApiKeyManager.getInstance().flushAndShutdown();
        } catch (Exception e) {
            logger.debug("Error flushing api key state", e);
        }
        // 3. 移除托盘图标
        if (trayHelper != null) {
            try {
                trayHelper.removeTrayIcon();
            } catch (Exception e) {
                logger.debug("Error removing tray icon", e);
            }
        }
        // 4. 关闭JavaFX平台
        Platform.exit();
        // 5. 给后台线程（SFTP上传/AI流式/监控轮询）短暂时间完成收尾，
        //    再强制退出，避免 System.exit 立即终止导致半成品文件或日志丢失
        try {
            Thread.sleep(800);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        System.exit(0);
    }

    private void initSystemTray(Stage stage) {
        try {
            trayHelper = new SystemTrayHelper(stage);
            trayHelper.addTrayIcon();
        } catch (Exception e) {
            logger.debug("System tray not available: {}", e.getMessage());
        }
    }

    @Override
    public void stop() {
        logger.info("StarShell 正在关闭...");
    }

    public static void main(String[] args) {
        // 任务8：启动时优先使用软件目录内部自带的精简JRE（类似FinalShell安装包机制）
        // 检测到内部 jre/bin/java.exe 且当前JVM非该JRE时，用ProcessBuilder重启到内部JRE，
        // 使程序在未安装JDK/JRE的电脑上也能正常运行。
        if (reexecWithInternalJre(args)) {
            return; // 已用内部JRE重启，当前JVM退出
        }
        logger.info("StarShell 启动，当前 JRE: {}", System.getProperty("java.home"));
        launch(args);
    }

    /**
     * 检测并优先使用软件目录内部的精简JRE。
     * <p>适用于绿色版分发场景（fat-jar + jre 目录）。jpackage安装包由其启动器自动使用内嵌JRE，
     * 此处检测到安装包场景（无独立 jre 目录）会自然回退到当前JVM。</p>
     *
     * @return true 表示已re-exec到内部JRE，调用方应直接退出
     */
    private static boolean reexecWithInternalJre(String[] args) {
        try {
            String osName = System.getProperty("os.name", "").toLowerCase();
            boolean isWindows = osName.contains("win");
            String appDir = getAppDir();
            String javaExe = isWindows ? "java.exe" : "java";
            Path internalJava = Paths.get(appDir, "jre", "bin", javaExe);
            if (!Files.exists(internalJava)) {
                return false; // 无内部JRE，使用系统JRE
            }
            String currentJavaHome = System.getProperty("java.home");
            Path internalJavaHome = Paths.get(appDir, "jre").toAbsolutePath().normalize();
            // 若当前已运行在内部JRE上，无需re-exec（避免无限循环）
            if (currentJavaHome != null) {
                Path curHome = Paths.get(currentJavaHome).toAbsolutePath().normalize();
                if (curHome.equals(internalJavaHome)) {
                    return false;
                }
            }
            logger.info("检测到内部JRE，切换到: {}", internalJava);
            // 用内部JRE重启：java -cp <classpath> --add-opens ... com.aifinalshell.Launcher <args>
            // 注意：reexec 目标必须是 Launcher（非 AiFinalShellApp），否则新 JVM 主类继承 Application
            // 会再次触发"缺少 JavaFX 运行时组件"错误（fat-jar 场景 JavaFX 仅在 classpath）。
            String classpath = System.getProperty("java.class.path");
            List<String> cmd = new ArrayList<>();
            cmd.add(internalJava.toAbsolutePath().toString());
            if (classpath != null && !classpath.isEmpty()) {
                cmd.add("-cp");
                cmd.add(classpath);
            }
            // JavaFX反射需要打开java.base模块
            cmd.add("--add-opens");
            cmd.add("java.base/java.lang=ALL-UNNAMED");
            cmd.add("com.aifinalshell.Launcher");
            Collections.addAll(cmd, args);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(appDir));
            pb.inheritIO();
            Process p = pb.start();
            // 父进程作为启动器，等待子进程退出并用其退出码
            System.exit(p.waitFor());
            return true; // 不会到达
        } catch (Exception e) {
            logger.warn("内部JRE启动失败，回退到系统JRE: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 定位应用所在目录（用于查找内部 jre 目录）。
     * 优先基于 java.class.path 的首个条目（fat-jar所在目录），回退到 user.dir。
     */
    private static String getAppDir() {
        try {
            String classpath = System.getProperty("java.class.path");
            if (classpath != null && !classpath.isEmpty()) {
                String first = classpath.split(File.pathSeparator)[0];
                File f = new File(first);
                if (f.isFile()) {
                    return f.getParentFile() != null
                            ? f.getParentFile().getAbsolutePath()
                            : System.getProperty("user.dir");
                } else if (f.isDirectory()) {
                    return f.getAbsolutePath();
                }
            }
        } catch (Exception ignore) {
        }
        return System.getProperty("user.dir");
    }

    /**
     * 重启应用（用于切换界面语言后使新设置生效）。
     * <p>用同一 classpath 与 main class 启动新 JVM 进程（优先使用内部 JRE），
     * 然后优雅关闭当前 JVM（断开 SSH、关闭数据库、刷新 API 密钥）。</p>
     */
    public static void restart() {
        try {
            String osName = System.getProperty("os.name", "").toLowerCase();
            boolean isWindows = osName.contains("win");
            String appDir = getAppDir();
            String javaExe = isWindows ? "java.exe" : "java";

            // 优先使用内部 JRE，回退到当前 JVM
            Path internalJava = Paths.get(appDir, "jre", "bin", javaExe);
            String javaCmd;
            if (Files.exists(internalJava)) {
                javaCmd = internalJava.toAbsolutePath().toString();
            } else {
                javaCmd = Paths.get(System.getProperty("java.home"), "bin", javaExe)
                        .toAbsolutePath().toString();
            }

            String classpath = System.getProperty("java.class.path");
            List<String> cmd = new ArrayList<>();
            cmd.add(javaCmd);
            if (classpath != null && !classpath.isEmpty()) {
                cmd.add("-cp");
                cmd.add(classpath);
            }
            cmd.add("--add-opens");
            cmd.add("java.base/java.lang=ALL-UNNAMED");
            cmd.add("com.aifinalshell.Launcher");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(appDir));
            pb.inheritIO();
            pb.start();
            logger.info("已启动新进程以应用新语言，准备退出当前进程");

            // 优雅关闭资源
            try {
                com.aifinalshell.ssh.SshConnectionManager.getInstance().disconnectAll();
            } catch (Exception ignored) {
            }
            try {
                com.aifinalshell.service.DatabaseManager.getInstance().close();
            } catch (Exception ignored) {
            }
            try {
                com.aifinalshell.config.ApiKeyManager.getInstance().flushAndShutdown();
            } catch (Exception ignored) {
            }
            Platform.exit();
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        } catch (Exception e) {
            logger.error("重启失败", e);
        }
    }
}
