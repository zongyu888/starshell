package com.aifinalshell.controller;

import com.aifinalshell.config.AiConfigManager;
import com.aifinalshell.provider.custom.CustomProvider;
import com.aifinalshell.provider.custom.CustomProvider.TestResult;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义大模型 API 配置对话框。
 * 为用户提供专用 UI 填入 OpenAI 兼容 API 的 Base URL、API Key、模型名称，
 * 支持拉取可用模型列表、测试连通性/模型可用性，并保存到配置（custom provider）。
 *
 * 适用场景：DeepSeek、智谱 GLM、Moonshot Kimi、零一万物、本地 Ollama/LM Studio 等
 * 任何 OpenAI Chat Completions 兼容服务。
 */
public class CustomModelConfigDialog extends Stage {

    private static final String PROVIDER = "custom";

    private final TextField baseUrlField;
    private final HBox apiKeyBox;
    private final ComboBox<String> modelCombo;
    private final Label testResultLabel;
    private final Button testBtn;

    /** 保存成功后的回调（供父对话框刷新模型列表） */
    private Runnable onSaveCallback;

    public CustomModelConfigDialog(Stage owner) {
        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        setTitle("自定义大模型配置");

        AiConfigManager config = AiConfigManager.getInstance();

        VBox root = new VBox(12);
        root.setPadding(new Insets(18));
        root.setStyle("-fx-background-color: #1e1e1e;");

        // === 标题与说明 ===
        Label titleLabel = new Label("自定义大模型配置");
        titleLabel.setStyle("-fx-text-fill: #d4d4d4; -fx-font-size: 16px; -fx-font-weight: bold;");

        Label descLabel = new Label("配置 OpenAI 兼容的自定义 API（如 DeepSeek、智谱 GLM、Moonshot、本地 Ollama 等）");
        descLabel.setStyle("-fx-text-fill: #888; -fx-font-size: 11px;");
        descLabel.setWrapText(true);
        descLabel.setMaxWidth(520);

        // === Base URL ===
        HBox baseUrlBox = new HBox(10);
        baseUrlBox.setAlignment(Pos.CENTER_LEFT);
        Label baseUrlLabel = new Label("Base URL:");
        baseUrlLabel.setMinWidth(80);
        baseUrlLabel.setStyle("-fx-text-fill: #d4d4d4; -fx-font-size: 12px;");
        baseUrlField = new TextField(config.getBaseUrl(PROVIDER));
        baseUrlField.setPrefWidth(420);
        baseUrlField.setPromptText("https://api.deepseek.com/v1");
        baseUrlBox.getChildren().addAll(baseUrlLabel, baseUrlField);

        // === API Key（带显示/隐藏切换） ===
        HBox apiKeyRow = new HBox(10);
        apiKeyRow.setAlignment(Pos.CENTER_LEFT);
        Label apiKeyLabel = new Label("API Key:");
        apiKeyLabel.setMinWidth(80);
        apiKeyLabel.setStyle("-fx-text-fill: #d4d4d4; -fx-font-size: 12px;");
        PasswordField apiKeyField = new PasswordField();
        apiKeyField.setPrefWidth(360);
        apiKeyField.setText(config.getApiKey(PROVIDER));
        apiKeyField.setPromptText("sk-...（本地服务可留空）");
        Button toggleKeyBtn = new Button("👁");
        toggleKeyBtn.setPrefWidth(35);
        toggleKeyBtn.setStyle("-fx-background-color: #3c3c3c; -fx-text-fill: #d4d4d4; -fx-cursor: hand;");
        toggleKeyBtn.setOnAction(e -> toggleKeyVisibility());
        apiKeyBox = new HBox(10, apiKeyLabel, apiKeyField, toggleKeyBtn);
        apiKeyRow.getChildren().addAll(apiKeyBox);

        // === 模型名称（可手动输入，也可拉取列表选择） ===
        HBox modelRow = new HBox(10);
        modelRow.setAlignment(Pos.CENTER_LEFT);
        Label modelLabel = new Label("模型名称:");
        modelLabel.setMinWidth(80);
        modelLabel.setStyle("-fx-text-fill: #d4d4d4; -fx-font-size: 12px;");
        modelCombo = new ComboBox<>();
        modelCombo.setEditable(true);
        modelCombo.setPrefWidth(360);
        modelCombo.setPromptText("如 deepseek-chat（可手动输入或拉取后选择）");
        // 预填已有自定义模型列表
        List<String> existingModels = config.getCustomModels(PROVIDER);
        if (existingModels != null && !existingModels.isEmpty()) {
            modelCombo.getItems().addAll(existingModels);
        }
        // 预选当前活跃模型（若属于 custom）
        String activeModel = config.getActiveModel();
        if (activeModel != null && !activeModel.isEmpty()) {
            modelCombo.setValue(activeModel);
        }

        Button fetchModelsBtn = new Button("拉取列表");
        fetchModelsBtn.setStyle("-fx-background-color: #3c3c3c; -fx-text-fill: #d4d4d4; -fx-cursor: hand;");
        fetchModelsBtn.setOnAction(e -> fetchModels());
        modelRow.getChildren().addAll(modelLabel, modelCombo, fetchModelsBtn);

        // === 测试区 ===
        HBox testRow = new HBox(10);
        testRow.setAlignment(Pos.CENTER_LEFT);
        testBtn = new Button("测试连接");
        testBtn.setStyle("-fx-background-color: #0e639c; -fx-text-fill: white; -fx-cursor: hand; -fx-padding: 6 16;");
        testBtn.setOnAction(e -> testConnection());
        testResultLabel = new Label("");
        testResultLabel.setStyle("-fx-font-size: 11px;");
        testResultLabel.setWrapText(true);
        testResultLabel.setMaxWidth(380);
        testRow.getChildren().addAll(testBtn, testResultLabel);

        // === 底部按钮 ===
        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        buttonBox.setPadding(new Insets(8, 0, 0, 0));

        Button saveBtn = new Button("保存");
        saveBtn.setStyle("-fx-background-color: #0dbc79; -fx-text-fill: white; -fx-cursor: hand; -fx-padding: 6 20;");
        saveBtn.setDefaultButton(true);
        saveBtn.setOnAction(e -> save());

        Button cancelBtn = new Button("取消");
        cancelBtn.setStyle("-fx-background-color: #3c3c3c; -fx-text-fill: #d4d4d4; -fx-cursor: hand; -fx-padding: 6 16;");
        cancelBtn.setCancelButton(true);
        cancelBtn.setOnAction(e -> close());

        buttonBox.getChildren().addAll(saveBtn, cancelBtn);

        root.getChildren().addAll(titleLabel, descLabel, baseUrlBox, apiKeyRow, modelRow, testRow, buttonBox);

        Scene scene = new Scene(root, 580, 360);
        scene.getStylesheets().add(getClass().getResource("/css/dark-theme.css").toExternalForm());
        setScene(scene);
    }

    public void setOnSaveCallback(Runnable callback) {
        this.onSaveCallback = callback;
    }

    // ========== API Key 显隐切换 ==========

    private String getApiKeyText() {
        if (apiKeyBox.getChildren().get(1) instanceof PasswordField pf) {
            return pf.getText();
        } else if (apiKeyBox.getChildren().get(1) instanceof TextField tf) {
            return tf.getText();
        }
        return "";
    }

    private void setApiKeyText(String text) {
        if (apiKeyBox.getChildren().get(1) instanceof PasswordField pf) {
            pf.setText(text);
        } else if (apiKeyBox.getChildren().get(1) instanceof TextField tf) {
            tf.setText(text);
        }
    }

    private void toggleKeyVisibility() {
        javafx.scene.Node currentField = apiKeyBox.getChildren().get(1);
        if (currentField instanceof PasswordField pf) {
            TextField textField = new TextField(pf.getText());
            textField.setPrefWidth(360);
            textField.setPromptText(pf.getPromptText());
            apiKeyBox.getChildren().set(1, textField);
            textField.requestFocus();
        } else if (currentField instanceof TextField tf) {
            PasswordField newPf = new PasswordField();
            newPf.setPrefWidth(360);
            newPf.setText(tf.getText());
            newPf.setPromptText(tf.getPromptText());
            apiKeyBox.getChildren().set(1, newPf);
            newPf.requestFocus();
        }
    }

    // ========== 拉取可用模型列表 ==========

    private void fetchModels() {
        String baseUrl = baseUrlField.getText().trim();
        String apiKey = getApiKeyText();
        if (baseUrl.isEmpty()) {
            showTestResult(false, "请先填写 Base URL");
            return;
        }

        modelCombo.getItems().clear();
        testBtn.setDisable(true);
        showTestResult(true, "拉取模型列表中...");

        new Thread(() -> {
            try {
                CustomProvider provider = new CustomProvider();
                // 复用 testConnection 的连通性检查并获取模型列表
                TestResult result = provider.testConnection(apiKey, baseUrl, null);
                Platform.runLater(() -> {
                    testBtn.setDisable(false);
                    if (result.isSuccess() && result.getAvailableModels() != null) {
                        List<String> models = result.getAvailableModels();
                        modelCombo.getItems().addAll(models);
                        if (!models.isEmpty() && modelCombo.getValue() == null) {
                            modelCombo.setValue(models.get(0));
                        }
                        showTestResult(true, "拉取成功，共 " + models.size() + " 个模型");
                    } else {
                        showTestResult(false, result.getMessage());
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    testBtn.setDisable(false);
                    showTestResult(false, "拉取失败：" + ex.getMessage());
                });
            }
        }).start();
    }

    // ========== 测试连接 ==========

    private void testConnection() {
        String baseUrl = baseUrlField.getText().trim();
        String apiKey = getApiKeyText();
        String model = modelCombo.getValue();

        testBtn.setDisable(true);
        showTestResult(true, "测试中...");

        new Thread(() -> {
            try {
                CustomProvider provider = new CustomProvider();
                TestResult result = provider.testConnection(apiKey, baseUrl, model);
                Platform.runLater(() -> {
                    testBtn.setDisable(false);
                    showTestResult(result.isSuccess(), result.getMessage());
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    testBtn.setDisable(false);
                    showTestResult(false, "测试异常：" + ex.getMessage());
                });
            }
        }).start();
    }

    private void showTestResult(boolean success, String message) {
        testResultLabel.setText((success ? "✓ " : "✗ ") + message);
        testResultLabel.setStyle(success
                ? "-fx-text-fill: #0dbc79; -fx-font-size: 11px;"
                : "-fx-text-fill: #cd3131; -fx-font-size: 11px;");
    }

    // ========== 保存 ==========

    private void save() {
        String baseUrl = baseUrlField.getText().trim();
        String apiKey = getApiKeyText().trim();
        String model = modelCombo.getValue() != null ? modelCombo.getValue().trim() : "";

        if (baseUrl.isEmpty()) {
            showTestResult(false, "请填写 Base URL");
            return;
        }
        if (model.isEmpty()) {
            showTestResult(false, "请填写或选择模型名称");
            return;
        }

        AiConfigManager config = AiConfigManager.getInstance();
        // 保存 Base URL 和 API Key 到 custom provider
        config.setBaseUrl(PROVIDER, baseUrl);
        if (!apiKey.isEmpty()) {
            config.setApiKey(PROVIDER, apiKey);
        }

        // 维护自定义模型列表：合并已有 + 当前输入，去重
        List<String> models = new ArrayList<>(config.getCustomModels(PROVIDER));
        if (!models.contains(model)) {
            models.add(model);
        }
        config.setCustomModels(PROVIDER, models);

        // 设为当前活跃 provider/model，使配置立即生效
        config.setActiveProvider(PROVIDER);
        config.setActiveModel(model);
        config.saveConfig();

        if (onSaveCallback != null) {
            onSaveCallback.run();
        }
        close();
    }
}
