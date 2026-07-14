package com.aifinalshell;

import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URL;
import javax.imageio.ImageIO;

/**
 * System tray helper for minimize-to-tray functionality.
 */
public class SystemTrayHelper {
    private static final Logger logger = LoggerFactory.getLogger(SystemTrayHelper.class);
    private final Stage stage;
    private TrayIcon trayIcon;
    private boolean isTraySupported;

    public SystemTrayHelper(Stage stage) {
        this.stage = stage;
        this.isTraySupported = SystemTray.isSupported();
    }

    public void addTrayIcon() {
        if (!isTraySupported) {
            logger.warn("System tray is not supported on this platform");
            return;
        }

        try {
            // Create tray icon
            java.awt.Image image = ImageIO.read(getClass().getResourceAsStream("/images/icon.png"));
            trayIcon = new TrayIcon(image, "StarShell");
            trayIcon.setImageAutoSize(true);

            // Create popup menu
            PopupMenu popup = new PopupMenu();

            MenuItem showItem = new MenuItem("显示窗口");
            showItem.addActionListener(e -> Platform.runLater(this::showStage));

            MenuItem exitItem = new MenuItem("退出");
            exitItem.addActionListener(e -> {
                Platform.runLater(() -> {
                    stage.close();
                    System.exit(0);
                });
            });

            popup.add(showItem);
            popup.addSeparator();
            popup.add(exitItem);
            trayIcon.setPopupMenu(popup);

            // Double-click to show
            trayIcon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        Platform.runLater(() -> showStage());
                    }
                }
            });

            // Add to system tray
            SystemTray.getSystemTray().add(trayIcon);
            logger.info("System tray icon added");

        } catch (Exception e) {
            logger.error("Failed to add system tray icon", e);
            isTraySupported = false;
        }
    }

    public void removeTrayIcon() {
        if (trayIcon != null && isTraySupported) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
            } catch (Exception e) {
                logger.debug("Failed to remove tray icon", e);
            }
        }
    }

    private void showStage() {
        stage.show();
        stage.toFront();
        stage.setIconified(false);
    }

    public boolean isTraySupported() {
        return isTraySupported;
    }
}
