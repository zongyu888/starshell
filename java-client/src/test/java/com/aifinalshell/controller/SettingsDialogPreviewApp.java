package com.aifinalshell.controller;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;
import java.lang.reflect.Method;

/** Manual visual-test launcher used by the repository's UI verification workflow. */
public class SettingsDialogPreviewApp extends Application {
    @Override
    public void start(Stage owner) {
        System.setProperty("starshell.app.config",
                new File("target/preview-config/app.properties").getAbsolutePath());
        SettingsDialog dialog = new SettingsDialog(owner);
        String page = System.getProperty("starshell.preview.page", "general");
        if (!"general".equals(page)) {
            try {
                Method showPage = SettingsDialog.class.getDeclaredMethod("showPage", String.class);
                showPage.setAccessible(true);
                showPage.invoke(dialog, page);
            } catch (Exception ex) {
                throw new IllegalStateException("Unable to select settings preview page: " + page, ex);
            }
        }
        dialog.setOnHidden(e -> Platform.exit());
        dialog.show();
        Platform.runLater(() -> {
            try {
                dialog.getScene().getRoot().applyCss();
                dialog.getScene().getRoot().layout();
                WritableImage image = dialog.getScene().snapshot(null);
                File output = new File(System.getProperty(
                        "starshell.preview.output", "target/starshell-settings.png"));
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", output);
                System.out.println("Settings preview written to " + output.getAbsolutePath());
            } catch (Exception ex) {
                ex.printStackTrace();
            } finally {
                dialog.close();
            }
        });
    }

    public static void main(String[] args) {
        launch(args);
    }
}
