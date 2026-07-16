package com.aifinalshell;

import com.aifinalshell.config.I18n;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.imageio.ImageIO;

/**
 * System tray helper for minimize-to-tray functionality.
 */
public class SystemTrayHelper {
    private static final Logger logger = LoggerFactory.getLogger(SystemTrayHelper.class);
    private final Stage stage;
    private final Runnable exitAction;
    private TrayIcon trayIcon;
    private boolean isTraySupported;

    public SystemTrayHelper(Stage stage) {
        this(stage, stage::close);
    }

    public SystemTrayHelper(Stage stage, Runnable exitAction) {
        this.stage = stage;
        this.exitAction = exitAction;
        this.isTraySupported = SystemTray.isSupported();
    }

    public synchronized void addTrayIcon() {
        if (trayIcon != null) return;
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

            MenuItem showItem = new MenuItem(I18n.tr("tray.show"));
            showItem.addActionListener(e -> Platform.runLater(this::showStage));

            MenuItem exitItem = new MenuItem(I18n.tr("tray.exit"));
            exitItem.addActionListener(e -> Platform.runLater(exitAction));

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

    public synchronized void removeTrayIcon() {
        if (trayIcon != null && isTraySupported) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
            } catch (Exception e) {
                logger.debug("Failed to remove tray icon", e);
            } finally {
                trayIcon = null;
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

    public boolean isInstalled() {
        return trayIcon != null;
    }
}
