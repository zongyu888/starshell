package com.aifinalshell.controller;

import com.aifinalshell.config.AiConfigManager;
import com.aifinalshell.config.ApiKeyManager;
import com.aifinalshell.provider.AiProvider;
import com.aifinalshell.provider.ChatMessage;
import com.aifinalshell.provider.ModelCache;
import com.aifinalshell.provider.ModelFilter;
import com.aifinalshell.provider.ModelFilter.FilterType;
import com.aifinalshell.provider.ModelInfo;
import com.aifinalshell.provider.ProviderRegistry;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI Settings dialog for model selection, API key management, and configuration.
 * 支持多密钥管理、模型过滤、模型缓存。
 */
public class AiSettingsDialog extends Stage {

    // Map display labels to actual model IDs
    private final Map<String, String> modelIdMap = new HashMap<>();

    // UI组件字段 - 供各方法访问
    private ComboBox<String> providerCombo;
    private ComboBox<String> modelCombo;
    private HBox apiKeyBox;
    private TextField baseUrlField;
    private ListView<ApiKeyManager.ApiKeyEntry> keyListView;
    private CheckBox freeOnlyCheck;
    private CheckBox supportsToolsCheck;
    private CheckBox supportsVisionCheck;
    private final AtomicLong modelLoadVersion = new AtomicLong();
    private volatile boolean modelListUpdating;
    /** Provider/model choices remain local until Save; Cancel must be side-effect free. */
    private final Map<String, String> draftModels = new HashMap<>();
    private String draftProvider;

    public AiSettingsDialog(Stage owner) {
        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        setTitle("AI Settings");

        VBox root = new VBox(10);
        root.setPadding(new Insets(15));

        AiConfigManager config = AiConfigManager.getInstance();
        draftProvider = config.getActiveProvider();
        draftModels.put(draftProvider, config.getActiveModel());

        // === Provider选择 ===
        HBox providerBox = new HBox(10);
        providerBox.setAlignment(Pos.CENTER_LEFT);
        Label providerLabel = new Label("Provider:");
        providerLabel.setMinWidth(80);
        providerCombo = new ComboBox<>();
        providerCombo.getItems().addAll(ProviderRegistry.getInstance().getProviderNames());
        providerCombo.setValue(config.getActiveProvider());
        providerCombo.setOnAction(e -> {
            rememberCurrentModelSelection();
            String provider = providerCombo.getValue();
            if (provider == null || provider.isBlank()) return;
            draftProvider = provider;
            // 更新API密钥字段和Base URL
            setApiKeyText(config.getApiKey(provider));
            baseUrlField.setText(config.getBaseUrl(provider));
            // 刷新密钥列表和模型列表
            refreshKeyList(provider);
            refreshModels(provider);
        });
        // 自定义大模型配置入口：打开专用对话框，保存后切换到 custom provider 并刷新模型列表
        Button customModelBtn = new Button("自定义大模型配置");
        customModelBtn.setStyle("-fx-background-color: #0e639c; -fx-text-fill: white; -fx-cursor: hand; -fx-padding: 4 12;");
        customModelBtn.setOnAction(e -> {
            CustomModelConfigDialog customDialog = new CustomModelConfigDialog(this);
            customDialog.setOnSaveCallback(() -> {
                // 保存后切换到 custom provider 并刷新 UI
                providerCombo.setValue("custom");
                setApiKeyText(config.getApiKey("custom"));
                baseUrlField.setText(config.getBaseUrl("custom"));
                refreshKeyList("custom");
                refreshModels("custom");
            });
            customDialog.show();
        });
        providerBox.getChildren().addAll(providerLabel, providerCombo, customModelBtn);

        // === 模型过滤 CheckBox ===
        HBox filterBox = new HBox(15);
        filterBox.setAlignment(Pos.CENTER_LEFT);
        Label filterLabel = new Label("过滤:");
        filterLabel.setMinWidth(80);
        freeOnlyCheck = new CheckBox("仅免费模型");
        supportsToolsCheck = new CheckBox("支持工具调用");
        supportsVisionCheck = new CheckBox("支持视觉");
        // 过滤状态变化时刷新模型列表
        freeOnlyCheck.setOnAction(e -> refreshModels(providerCombo.getValue()));
        supportsToolsCheck.setOnAction(e -> refreshModels(providerCombo.getValue()));
        supportsVisionCheck.setOnAction(e -> refreshModels(providerCombo.getValue()));
        filterBox.getChildren().addAll(filterLabel, freeOnlyCheck, supportsToolsCheck, supportsVisionCheck);

        // === 模型选择 ===
        HBox modelBox = new HBox(10);
        modelBox.setAlignment(Pos.CENTER_LEFT);
        Label modelLabel = new Label("Model:");
        modelLabel.setMinWidth(80);
        modelCombo = new ComboBox<>();
        modelCombo.setPrefWidth(300);
        refreshModels(config.getActiveProvider());
        modelCombo.setOnAction(e -> {
            if (!modelListUpdating && modelCombo.getValue() != null) {
                // Keep the actual model ID in the dialog draft only. Save commits it.
                String modelId = modelIdMap.getOrDefault(modelCombo.getValue(), modelCombo.getValue());
                draftModels.put(draftProvider, modelId);
            }
        });
        modelBox.getChildren().addAll(modelLabel, modelCombo);

        // === API Key ===
        apiKeyBox = new HBox(10);
        apiKeyBox.setAlignment(Pos.CENTER_LEFT);
        Label apiKeyLabel = new Label("API Key:");
        apiKeyLabel.setMinWidth(80);
        PasswordField apiKeyField = new PasswordField();
        apiKeyField.setPrefWidth(300);
        apiKeyField.setText(config.getApiKey(config.getActiveProvider()));
        apiKeyField.setPromptText("Enter API key (leave empty for free models)");
        Button toggleKeyBtn = new Button("👁");
        toggleKeyBtn.setPrefWidth(35);
        toggleKeyBtn.setOnAction(e -> toggleKeyVisibility());
        Button addKeyBtn = new Button("添加");
        addKeyBtn.setOnAction(e -> {
            String provider = providerCombo.getValue();
            String key = getApiKeyText();
            if (key != null && !key.isEmpty()) {
                ApiKeyManager.getInstance().addKey(provider, key);
                refreshKeyList(provider);
                showInfo("密钥已添加");
            } else {
                showError("请先输入API密钥");
            }
        });
        apiKeyBox.getChildren().addAll(apiKeyLabel, apiKeyField, toggleKeyBtn, addKeyBtn);

        // === 多密钥管理区域 ===
        VBox multiKeyBox = new VBox(5);
        Label keyListLabel = new Label("密钥列表:");
        keyListView = new ListView<>();
        keyListView.setPrefHeight(100);
        keyListView.setCellFactory(lv -> new KeyListCell());
        refreshKeyList(config.getActiveProvider());
        multiKeyBox.getChildren().addAll(keyListLabel, keyListView);

        // === Base URL ===
        HBox baseUrlBox = new HBox(10);
        baseUrlBox.setAlignment(Pos.CENTER_LEFT);
        Label baseUrlLabel = new Label("Base URL:");
        baseUrlLabel.setMinWidth(80);
        baseUrlField = new TextField(config.getBaseUrl(config.getActiveProvider()));
        baseUrlField.setPrefWidth(300);
        baseUrlField.setPromptText("API base URL (leave empty for default)");
        baseUrlBox.getChildren().addAll(baseUrlLabel, baseUrlField);

        // === Temperature ===
        HBox tempBox = new HBox(10);
        tempBox.setAlignment(Pos.CENTER_LEFT);
        Label tempLabel = new Label("Temperature:");
        tempLabel.setMinWidth(80);
        Slider tempSlider = new Slider(0, 2, config.getTemperature());
        tempSlider.setPrefWidth(200);
        tempSlider.setShowTickLabels(true);
        tempSlider.setShowTickMarks(true);
        Label tempValue = new Label(String.format("%.1f", config.getTemperature()));
        tempSlider.valueProperty().addListener((obs, old, val) ->
                tempValue.setText(String.format("%.1f", val.doubleValue())));
        tempBox.getChildren().addAll(tempLabel, tempSlider, tempValue);

        // === Max Tokens ===
        HBox tokensBox = new HBox(10);
        tokensBox.setAlignment(Pos.CENTER_LEFT);
        Label tokensLabel = new Label("Max Tokens:");
        tokensLabel.setMinWidth(80);
        Spinner<Integer> tokensSpinner = new Spinner<>(256, 32768, config.getMaxTokens(), 256);
        tokensSpinner.setEditable(true);
        tokensSpinner.setPrefWidth(120);
        tokensBox.getChildren().addAll(tokensLabel, tokensSpinner);

        // === Max Tool Steps（AI 自主工具调用步数上限，调大即近似不限制）===
        HBox stepsBox = new HBox(10);
        stepsBox.setAlignment(Pos.CENTER_LEFT);
        Label stepsLabel = new Label("工具步数:");
        stepsLabel.setMinWidth(80);
        // 范围 1 ~ 9999；默认读取配置（50）。设为很大（如 500）即近似"完全不限制"
        Spinner<Integer> stepsSpinner = new Spinner<>(1, 9999, config.getMaxToolSteps(), 10);
        stepsSpinner.setEditable(true);
        stepsSpinner.setPrefWidth(120);
        Label stepsHint = new Label("(调大=AI 更自主，0 不生效将回退默认)");
        stepsHint.setStyle("-fx-text-fill: #888; -fx-font-size: 11px;");
        stepsBox.getChildren().addAll(stepsLabel, stepsSpinner, stepsHint);

        // === Buttons ===
        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);

        Button testBtn = new Button("Test Connection");
        testBtn.setOnAction(e -> {
            String provider = providerCombo.getValue();
            String apiKey = getApiKeyText();
            String baseUrl = baseUrlField.getText();
            String model = modelIdMap.getOrDefault(modelCombo.getValue(), modelCombo.getValue());

            if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
                showError("请选择 Provider 和 Model 后再测试连接");
                return;
            }

            // custom provider 走真实连通性测试（isAvailable 只是桩函数）
            if ("custom".equalsIgnoreCase(provider)) {
                testBtn.setDisable(true);
                new Thread(() -> {
                    com.aifinalshell.provider.custom.CustomProvider cp =
                            new com.aifinalshell.provider.custom.CustomProvider();
                    com.aifinalshell.provider.custom.CustomProvider.TestResult result =
                            cp.testConnection(apiKey, baseUrl, model);
                    javafx.application.Platform.runLater(() -> {
                        testBtn.setDisable(false);
                        if (result.isSuccess()) {
                            showInfo("✓ " + result.getMessage());
                            if (apiKey != null && !apiKey.isEmpty()) {
                                ApiKeyManager.getInstance().markKeyValid(provider, apiKey);
                                refreshKeyList(provider);
                            }
                        } else {
                            showError("✗ " + result.getMessage());
                            if (apiKey != null && !apiKey.isEmpty()) {
                                ApiKeyManager.getInstance().markKeyFailed(provider, apiKey);
                                refreshKeyList(provider);
                            }
                        }
                    });
                }).start();
                return;
            }

            AiProvider p = ProviderRegistry.getInstance().getProvider(provider);
            if (p == null) {
                showError("Unknown provider: " + provider);
                return;
            }

            // A real, tiny chat request verifies URL, credentials and selected
            // model together. Run it off the FX thread so slow networks never
            // freeze the settings window.
            testBtn.setDisable(true);
            new Thread(() -> {
                String response;
                try {
                    response = p.chat(model, List.of(ChatMessage.user("Reply with OK.")),
                            apiKey, baseUrl, 0.0, 8);
                } catch (Exception ex) {
                    response = "Error: " + ex.getMessage();
                }
                String finalResponse = response == null ? "" : response.trim();
                boolean success = !finalResponse.isEmpty()
                        && !finalResponse.regionMatches(true, 0, "Error", 0, 5)
                        && !finalResponse.regionMatches(true, 0, "API Error", 0, 9)
                        && !finalResponse.regionMatches(true, 0, "No response", 0, 11);
                javafx.application.Platform.runLater(() -> {
                    testBtn.setDisable(false);
                    if (apiKey != null && !apiKey.isEmpty()) {
                        if (success) ApiKeyManager.getInstance().markKeyValid(provider, apiKey);
                        else ApiKeyManager.getInstance().markKeyFailed(provider, apiKey);
                        refreshKeyList(provider);
                    }
                    if (success) showInfo("Connection test passed for " + provider);
                    else showError("Connection test failed for " + provider
                            + (finalResponse.isEmpty() ? "" : "\n" + finalResponse));
                });
            }, "AI-Settings-Connection-Test").start();
        });

        Button saveBtn = new Button("Save");
        saveBtn.setOnAction(e -> {
            rememberCurrentModelSelection();
            String currentProvider = providerCombo.getValue();
            if (currentProvider == null || currentProvider.isBlank()) {
                showError("请选择 Provider");
                return;
            }
            config.setActiveProvider(currentProvider);
            // Save actual model ID from the display label
            String modelId = draftModels.get(currentProvider);
            if (modelId == null || modelId.isBlank()) {
                showError("请选择 Model");
                return;
            }
            config.setActiveModel(modelId);

            // Save API key
            String apiKey = getApiKeyText();
            if (apiKey != null && !apiKey.isEmpty()) {
                config.setApiKey(currentProvider, apiKey);
            }

            config.setBaseUrl(currentProvider, baseUrlField.getText());

            // Save via full config update
            try {
                var node = config.getFullConfig();
                ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("ai"))
                        .put("temperature", tempSlider.getValue())
                        .put("max_tokens", tokensSpinner.getValue())
                        .put("max_tool_steps", stepsSpinner.getValue());
                config.saveConfig();
            } catch (Exception ex) {
                // fallback: just save what we have
                config.saveConfig();
            }

            showInfo("Settings saved!");
            close();
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.setOnAction(e -> close());

        buttonBox.getChildren().addAll(testBtn, saveBtn, cancelBtn);

        root.getChildren().addAll(
                providerBox, filterBox, modelBox, apiKeyBox, multiKeyBox,
                baseUrlBox, tempBox, tokensBox, stepsBox, buttonBox
        );

        Scene scene = new Scene(root, 600, 700);
        scene.getStylesheets().add(getClass().getResource("/css/dark-theme.css").toExternalForm());
        setScene(scene);
    }

    // ========== 辅助方法 ==========

    /**
     * 获取API Key输入框中的文本
     */
    private String getApiKeyText() {
        if (apiKeyBox.getChildren().get(1) instanceof PasswordField pf) {
            return pf.getText();
        } else if (apiKeyBox.getChildren().get(1) instanceof TextField tf) {
            return tf.getText();
        }
        return "";
    }

    /**
     * 设置API Key输入框中的文本
     */
    private void setApiKeyText(String text) {
        if (apiKeyBox.getChildren().get(1) instanceof PasswordField pf) {
            pf.setText(text);
        } else if (apiKeyBox.getChildren().get(1) instanceof TextField tf) {
            tf.setText(text);
        }
    }

    /**
     * 切换API Key输入框的可见性（密码/明文）
     */
    private void toggleKeyVisibility() {
        javafx.scene.Node currentField = apiKeyBox.getChildren().get(1);
        if (currentField instanceof PasswordField pf) {
            // Switch to visible TextField
            TextField textField = new TextField(pf.getText());
            textField.setPrefWidth(300);
            textField.setPromptText(pf.getPromptText());
            apiKeyBox.getChildren().set(1, textField);
            textField.requestFocus();
        } else if (currentField instanceof TextField tf) {
            // Switch back to PasswordField
            PasswordField newPf = new PasswordField();
            newPf.setPrefWidth(300);
            newPf.setText(tf.getText());
            newPf.setPromptText(tf.getPromptText());
            apiKeyBox.getChildren().set(1, newPf);
            newPf.requestFocus();
        }
    }

    /**
     * 刷新模型列表 - 使用ModelCache缓存 + ModelFilter过滤
     */
    private void refreshModels(String provider) {
        long requestVersion = modelLoadVersion.incrementAndGet();
        AiConfigManager config = AiConfigManager.getInstance();
        AiProvider p = ProviderRegistry.getInstance().getProvider(provider);
        if (p == null) return;

        String apiKey = config.getApiKey(provider);
        String baseUrl = config.getBaseUrl(provider);
        String activeModel = draftModels.get(provider);
        List<String> savedCustomModels = new ArrayList<>(config.getCustomModels(provider));
        Set<FilterType> filters = new HashSet<>();
        if (freeOnlyCheck.isSelected()) filters.add(FilterType.FREE_ONLY);
        if (supportsToolsCheck.isSelected()) filters.add(FilterType.SUPPORTS_TOOLS);
        if (supportsVisionCheck.isSelected()) filters.add(FilterType.SUPPORTS_VISION);

        modelListUpdating = true;
        modelCombo.setDisable(true);
        modelCombo.setPromptText("正在加载模型…");

        Thread loader = new Thread(() -> {
            List<ModelInfo> models;
            try {
                models = ModelCache.getInstance().getModels(provider,
                        () -> p.listModels(apiKey, baseUrl));
                models = ModelFilter.filter(models, filters);
            } catch (Exception e) {
                models = new ArrayList<>();
            }
            List<ModelInfo> loaded = new ArrayList<>(models);
            javafx.application.Platform.runLater(() -> {
                if (requestVersion != modelLoadVersion.get()) return;
                modelCombo.getItems().clear();
                modelIdMap.clear();
                for (ModelInfo m : loaded) {
                    String label = m.isFree() ? "★ " + m.getName() + " (Free)" : m.getName();
                    modelCombo.getItems().add(label);
                    modelIdMap.put(label, m.getId());
                }

                if ("custom".equalsIgnoreCase(provider)) {
                    for (String savedModel : savedCustomModels) {
                        if (!modelIdMap.containsValue(savedModel)) {
                            modelCombo.getItems().add(savedModel);
                            modelIdMap.put(savedModel, savedModel);
                        }
                    }
                }

                boolean selected = false;
                if (activeModel != null) {
                    for (Map.Entry<String, String> entry : modelIdMap.entrySet()) {
                        if (entry.getValue().equals(activeModel)) {
                            modelCombo.setValue(entry.getKey());
                            selected = true;
                            break;
                        }
                    }
                }
                if (!selected && activeModel != null && !activeModel.isEmpty()) {
                    modelCombo.getItems().add(activeModel);
                    modelIdMap.put(activeModel, activeModel);
                    modelCombo.setValue(activeModel);
                }
                modelCombo.setDisable(false);
                modelCombo.setPromptText("选择模型");
                modelListUpdating = false;
            });
        }, "AI-Settings-Model-Loader");
        loader.setDaemon(true);
        loader.start();
    }

    /** Remember the currently visible model without touching persisted AI configuration. */
    private void rememberCurrentModelSelection() {
        if (draftProvider == null || modelCombo == null || modelCombo.getValue() == null) return;
        String label = modelCombo.getValue();
        draftModels.put(draftProvider, modelIdMap.getOrDefault(label, label));
    }

    /**
     * 刷新密钥列表
     */
    private void refreshKeyList(String provider) {
        keyListView.getItems().clear();
        keyListView.getItems().addAll(ApiKeyManager.getInstance().getKeys(provider));
    }

    /**
     * 密钥掩码处理 - 只显示前4位和后4位
     */
    private String maskKey(String key) {
        if (key == null || key.isEmpty()) return "";
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    // ========== 密钥列表单元格 ==========

    /**
     * 自定义ListView单元格 - 显示状态图标 + 密钥掩码 + 删除按钮
     */
    private class KeyListCell extends ListCell<ApiKeyManager.ApiKeyEntry> {
        @Override
        protected void updateItem(ApiKeyManager.ApiKeyEntry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setGraphic(null);
            } else {
                HBox box = new HBox(10);
                box.setAlignment(Pos.CENTER_LEFT);

                // 状态图标：valid=绿色✓，invalid=红色✗
                Label statusLabel = new Label(entry.isValid() ? "✓" : "✗");
                statusLabel.setStyle(entry.isValid()
                        ? "-fx-text-fill: green; -fx-font-weight: bold; -fx-font-size: 14;"
                        : "-fx-text-fill: red; -fx-font-weight: bold; -fx-font-size: 14;");
                statusLabel.setMinWidth(20);

                // 密钥掩码
                Label maskedLabel = new Label(maskKey(entry.getKey()));
                maskedLabel.setPrefWidth(200);

                // 失败次数（如果大于0则显示）
                Label failLabel = new Label("");
                if (entry.getFailCount() > 0) {
                    failLabel.setText("(失败" + entry.getFailCount() + "次)");
                    failLabel.setStyle("-fx-text-fill: orange;");
                }

                // 删除按钮
                Button deleteBtn = new Button("删除");
                deleteBtn.setOnAction(e -> {
                    int idx = getIndex();
                    String provider = providerCombo.getValue();
                    ApiKeyManager.getInstance().removeKey(provider, idx);
                    refreshKeyList(provider);
                });

                box.getChildren().addAll(statusLabel, maskedLabel, failLabel, deleteBtn);
                setGraphic(box);
            }
        }
    }

    // ========== 消息提示 ==========

    private void showInfo(String msg) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, msg);
        alert.showAndWait();
    }

    private void showError(String msg) {
        Alert alert = new Alert(Alert.AlertType.ERROR, msg);
        alert.showAndWait();
    }
}
