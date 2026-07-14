package com.aifinalshell.controller;

import com.aifinalshell.model.DownloadTask;
import com.aifinalshell.model.ServerConfig;
import com.aifinalshell.service.DownloadManager;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.util.Map;

public class DownloadManagerDialog {
    private Stage dialog;
    private ComboBox<String> serverSelector;
    private Label downloadPathLabel;
    private TableView<DownloadTask> taskTable;
    private ObservableList<DownloadTask> taskList;
    private Map<Long, ServerConfig> serverConfigMap;

    public void show(Map<Long, ServerConfig> serverConfigMap) {
        this.serverConfigMap = serverConfigMap;
        dialog = new Stage();
        dialog.setTitle("传输管理");
        dialog.initModality(Modality.NONE);
        dialog.setAlwaysOnTop(true);

        VBox root = new VBox(5);
        root.setStyle("-fx-background-color: #f5f5f5; -fx-padding: 10;");
        root.setPrefSize(550, 400);

        // Top: server selector + buttons
        HBox topBar = createTopBar();
        root.getChildren().add(topBar);

        // Quick action buttons
        HBox actionButtons = createActionButtons();
        root.getChildren().add(actionButtons);

        // Download path
        HBox pathBar = createPathBar();
        root.getChildren().add(pathBar);

        // Task table
        taskList = FXCollections.observableArrayList(DownloadManager.getInstance().getTasks());
        taskTable = createTaskTable();
        VBox.setVgrow(taskTable, Priority.ALWAYS);
        root.getChildren().add(taskTable);

        // Bottom buttons
        HBox bottomBar = createBottomBar();
        root.getChildren().add(bottomBar);

        Scene scene = new Scene(root, 550, 400);
        dialog.setScene(scene);
        dialog.show();

        // Periodically refresh task list
        Thread refreshThread = new Thread(() -> {
            while (dialog.isShowing()) {
                Platform.runLater(() -> {
                    taskList.clear();
                    taskList.addAll(DownloadManager.getInstance().getTasks());
                });
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            }
        });
        refreshThread.setDaemon(true);
        refreshThread.start();
    }

    private HBox createTopBar() {
        HBox bar = new HBox(8);
        bar.setAlignment(Pos.CENTER_LEFT);

        Label hostLabel = new Label("主机:");
        hostLabel.setStyle("-fx-font-size: 12px;");

        serverSelector = new ComboBox<>();
        serverSelector.setPrefWidth(250);
        serverSelector.setStyle("-fx-font-size: 12px;");
        if (serverConfigMap != null) {
            for (ServerConfig config : serverConfigMap.values()) {
                serverSelector.getItems().add(config.getName() + " (" + config.getHost() + ")");
            }
            if (!serverSelector.getItems().isEmpty()) {
                serverSelector.getSelectionModel().select(0);
            }
        }

        Button refreshBtn = new Button("刷新连接");
        refreshBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4 8;");
        refreshBtn.setOnAction(e -> {
            if (serverConfigMap != null) {
                serverSelector.getItems().clear();
                for (ServerConfig config : serverConfigMap.values()) {
                    serverSelector.getItems().add(config.getName() + " (" + config.getHost() + ")");
                }
            }
        });

        bar.getChildren().addAll(hostLabel, serverSelector, refreshBtn);
        return bar;
    }

    private HBox createActionButtons() {
        HBox bar = new HBox(5);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(5, 0, 0, 0));

        String[] buttons = {"路由追踪", "进程管理", "网络监控", "速度测试"};
        for (String text : buttons) {
            Button btn = new Button(text);
            btn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8; -fx-background-color: #e8e8e8; -fx-border-color: #ccc; -fx-border-radius: 3; -fx-background-radius: 3;");
            btn.setOnAction(e -> {
                // Placeholder for future functionality
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle(text);
                alert.setHeaderText(null);
                alert.setContentText("功能开发中: " + text);
                alert.showAndWait();
            });
            bar.getChildren().add(btn);
        }

        return bar;
    }

    private HBox createPathBar() {
        HBox bar = new HBox(8);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(5, 0, 0, 0));

        Label pathLabel = new Label("下载到:");
        pathLabel.setStyle("-fx-font-size: 12px;");

        downloadPathLabel = new Label(DownloadManager.getInstance().getDefaultDownloadPath());
        downloadPathLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #333; -fx-background-color: white; -fx-border-color: #ccc; -fx-padding: 3 6;");

        Button browseBtn = new Button("...");
        browseBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        browseBtn.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择下载目录");
            File dir = chooser.showDialog(dialog);
            if (dir != null) {
                downloadPathLabel.setText(dir.getAbsolutePath());
                DownloadManager.getInstance().setDefaultDownloadPath(dir.getAbsolutePath());
            }
        });

        Button openFolderBtn = new Button("打开文件夹");
        openFolderBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        openFolderBtn.setOnAction(e -> {
            try {
                String path = downloadPathLabel.getText();
                new ProcessBuilder("explorer.exe", path).start();
            } catch (Exception ex) {
                // ignore
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        bar.getChildren().addAll(pathLabel, downloadPathLabel, browseBtn, openFolderBtn, spacer);
        return bar;
    }

    @SuppressWarnings("unchecked")
    private TableView<DownloadTask> createTaskTable() {
        TableView<DownloadTask> table = new TableView<>();
        table.setItems(taskList);
        table.setStyle("-fx-background-color: white;");

        // File name column
        TableColumn<DownloadTask, String> nameCol = new TableColumn<>("文件名");
        nameCol.setCellValueFactory(cellData -> cellData.getValue().fileNameProperty());
        nameCol.setPrefWidth(180);
        nameCol.setCellFactory(col -> new TableCell<DownloadTask, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    DownloadTask task = getTableView().getItems().get(getIndex());
                    Label icon = new Label(task.isDirectory() ? "\uD83D\uDCC1 " : "\uD83D\uDCC4 ");
                    Label name = new Label(item);
                    name.setStyle("-fx-font-size: 12px;");
                    HBox box = new HBox(4, icon, name);
                    box.setAlignment(Pos.CENTER_LEFT);
                    setGraphic(box);
                    setText(null);
                }
            }
        });

        // Progress column
        TableColumn<DownloadTask, String> progressCol = new TableColumn<>("进度");
        progressCol.setCellValueFactory(cellData -> {
            DownloadTask task = cellData.getValue();
            return new SimpleStringProperty(
                    String.format("%.0f%% %s", task.getProgress() * 100, task.getSpeed()));
        });
        progressCol.setPrefWidth(150);
        progressCol.setCellFactory(col -> new TableCell<DownloadTask, String>() {
            private final ProgressBar progressBar = new ProgressBar();
            private final Label infoLabel = new Label();
            private final VBox vbox = new VBox(2, progressBar, infoLabel);

            {
                progressBar.setPrefWidth(130);
                progressBar.setPrefHeight(12);
                progressBar.setStyle("-fx-accent: #4CAF50;");
                infoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");
                vbox.setAlignment(Pos.CENTER_LEFT);
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    DownloadTask task = getTableView().getItems().get(getIndex());
                    progressBar.setProgress(task.getProgress());
                    String sizeInfo = task.getTotalSize() > 0 ?
                            formatSize(task.getDownloadedSize()) + "/" + formatSize(task.getTotalSize()) : "";
                    infoLabel.setText(task.getSpeed() + "  " + sizeInfo);
                    setGraphic(vbox);
                    setText(null);
                }
            }
        });

        // Status column
        TableColumn<DownloadTask, String> statusCol = new TableColumn<>("状态");
        statusCol.setCellValueFactory(cellData -> cellData.getValue().statusProperty());
        statusCol.setPrefWidth(120);
        statusCol.setCellFactory(col -> new TableCell<DownloadTask, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    DownloadTask task = getTableView().getItems().get(getIndex());
                    Label label = new Label(item);
                    if ("已完成".equals(item) || (item != null && item.startsWith("已完成"))) {
                        label.setStyle("-fx-text-fill: #4CAF50; -fx-font-size: 12px;");
                    } else if ("失败".equals(item) || (item != null && item.startsWith("失败"))) {
                        label.setStyle("-fx-text-fill: #f44336; -fx-font-size: 12px;");
                    } else if ("已取消".equals(item)) {
                        label.setStyle("-fx-text-fill: #999; -fx-font-size: 12px;");
                    } else {
                        label.setStyle("-fx-text-fill: #2196F3; -fx-font-size: 12px;");
                    }
                    setGraphic(label);
                    setText(null);
                }
            }
        });

        table.getColumns().addAll(nameCol, progressCol, statusCol);
        return table;
    }

    private HBox createBottomBar() {
        HBox bar = new HBox(8);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(8, 0, 0, 0));

        Button cancelBtn = new Button("取消选中");
        cancelBtn.setStyle("-fx-font-size: 12px; -fx-padding: 5 12;");
        cancelBtn.setOnAction(e -> {
            DownloadTask selected = taskTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                selected.cancel();
            }
        });

        Button cancelAllBtn = new Button("全部取消");
        cancelAllBtn.setStyle("-fx-font-size: 12px; -fx-padding: 5 12;");
        cancelAllBtn.setOnAction(e -> {
            for (DownloadTask task : DownloadManager.getInstance().getTasks()) {
                task.cancel();
            }
        });

        Button clearBtn = new Button("清空已完成");
        clearBtn.setStyle("-fx-font-size: 12px; -fx-padding: 5 12;");
        clearBtn.setOnAction(e -> {
            DownloadManager.getInstance().getTasks().removeIf(
                    t -> t.getStatus().startsWith("已完成") || t.getStatus().equals("已取消"));
        });

        bar.getChildren().addAll(cancelBtn, cancelAllBtn, clearBtn);
        return bar;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1073741824) return String.format("%.1f MB", bytes / 1048576.0);
        return String.format("%.1f GB", bytes / 1073741824.0);
    }
}
