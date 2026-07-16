package com.aifinalshell.controller;

import com.aifinalshell.config.AppConfig;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;

/** Manual visual regression launcher for the terminal background layer. */
public class TerminalBackgroundPreviewApp extends Application {
    @Override
    public void start(Stage stage) {
        System.setProperty("starshell.app.config",
                new File("target/preview-config/app.properties").getAbsolutePath());
        AppConfig config = AppConfig.getInstance();
        boolean oldEnabled = config.isBackgroundImageEnabled();
        String oldPath = config.getBackgroundImagePath();
        double oldOpacity = config.getBackgroundImageOpacity();

        File image = new File("src/main/resources/images/icon.png").getAbsoluteFile();
        config.setBackgroundImageEnabled(true);
        config.setBackgroundImagePath(image.getAbsolutePath());
        config.setBackgroundImageOpacity(0.55);

        Region background = new Region();
        background.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        Region overlay = new Region();
        overlay.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        TextArea terminal = new TextArea(
                "$ starshell status\n"
                        + "✓ AI commands run in this visible terminal\n"
                        + "✓ Background image remains visible below the text overlay\n\n"
                        + "ops@server:/srv/app$ _");
        terminal.getStyleClass().add("terminal-output");
        StackPane root = new StackPane(background, overlay, terminal);
        Scene scene = new Scene(root, 820, 420);
        scene.getStylesheets().add(getClass().getResource("/css/dark-theme.css").toExternalForm());
        new TerminalController(terminal, background, overlay);
        stage.setScene(scene);
        stage.show();

        Platform.runLater(() -> {
            try {
                root.applyCss();
                root.layout();
                WritableImage snapshot = scene.snapshot(null);
                File output = new File("target/starshell-terminal-background.png");
                ImageIO.write(SwingFXUtils.fromFXImage(snapshot, null), "png", output);
                System.out.println("Terminal background preview written to " + output.getAbsolutePath());
            } catch (Exception ex) {
                ex.printStackTrace();
            } finally {
                config.setBackgroundImageEnabled(oldEnabled);
                config.setBackgroundImagePath(oldPath);
                config.setBackgroundImageOpacity(oldOpacity);
                config.saveConfig();
                stage.close();
                Platform.exit();
            }
        });
    }

    public static void main(String[] args) {
        launch(args);
    }
}
