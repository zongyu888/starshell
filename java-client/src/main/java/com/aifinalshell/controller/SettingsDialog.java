package com.aifinalshell.controller;

import com.aifinalshell.AiFinalShellApp;
import com.aifinalshell.config.AppConfig;
import com.aifinalshell.config.I18n;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.util.*;

/**
 * 设置对话框（TreeView 导航，FinalShell 风格）。
 * 深色主题，支持国际化，含语言切换页（保存后重启生效）。
 * 分类：常规、终端(鼠标/快捷键/配色/背景图片/字体)、语言
 */
public class SettingsDialog extends Stage {
    private final AppConfig config;
    private final StackPane contentArea;
    private final Map<String, Pane> pageCache = new HashMap<>();
    private final List<Runnable> onSaveCallbacks = new ArrayList<>();

    /** 用户打开对话框时的语言，用于检测是否变更 */
    private String originalLanguage;

    /** Task 5.12: 打开对话框时的配置快照，供 Cancel 按钮回滚草稿修改 */
    private java.util.Properties snapshot;

    // 页面 ID（稳定标识，不随语言变化）
    private static final String PAGE_GENERAL = "general";
    private static final String PAGE_MOUSE = "mouse";
    private static final String PAGE_SHORTCUTS = "shortcuts";
    private static final String PAGE_COLORS = "colors";
    private static final String PAGE_BACKGROUND = "background";
    private static final String PAGE_FONT = "font";
    private static final String PAGE_LANGUAGE = "language";
    private static final String PAGE_ABOUT = "about";
    private static final String PAGE_MONITOR = "monitor";

    public SettingsDialog(Stage owner) {
        this.config = AppConfig.getInstance();
        this.originalLanguage = config.getLanguage();
        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        setTitle(I18n.tr("settings.title"));

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #1e1e1e;");

        // ===== 左侧：TreeView 导航 =====
        TreeView<String> tree = buildNavigationTree();
        tree.setPrefWidth(180);
        tree.setMaxWidth(180);
        tree.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> {
            if (item != null && item.isLeaf()) {
                showPage(item.getValue());
            }
        });

        // ===== 右侧：内容区 =====
        contentArea = new StackPane();
        contentArea.setPadding(new Insets(18));
        contentArea.setStyle("-fx-background-color: #1e1e1e;");

        // ===== 底部：按钮栏 =====
        HBox buttonBar = new HBox(10);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);
        buttonBar.setPadding(new Insets(10, 15, 10, 15));
        buttonBar.setStyle("-fx-background-color: #252526; -fx-border-color: #3c3c3c; -fx-border-width: 1 0 0 0;");

        Button resetBtn = new Button(I18n.tr("common.reset"));
        resetBtn.setOnAction(e -> resetToDefaults());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button cancelBtn = new Button(I18n.tr("common.cancel"));
        cancelBtn.setOnAction(e -> {
            // Task 5.12: Cancel 时回滚草稿修改，恢复打开对话框时的配置快照。
            // 控件变更会直接写入 config.properties（内存），若不回滚会"暗箱生效"。
            config.getProperties().clear();
            snapshot.forEach((k, v) -> config.getProperties().put(k, v));
            config.saveConfig();
            close();
        });

        Button okBtn = new Button(I18n.tr("common.ok"));
        okBtn.setDefaultButton(true);
        okBtn.setOnAction(e -> {
            saveAll();
            close();
        });

        Button applyBtn = new Button(I18n.tr("common.apply"));
        applyBtn.setOnAction(e -> saveAll());

        buttonBar.getChildren().addAll(resetBtn, spacer, cancelBtn, okBtn, applyBtn);

        root.setLeft(tree);
        root.setCenter(contentArea);
        root.setBottom(buttonBar);

        // 默认选中常规页
        tree.getSelectionModel().selectFirst();

        Scene scene = new Scene(root, 760, 520);
        scene.getStylesheets().add(getClass().getResource("/css/dark-theme.css").toExternalForm());
        setScene(scene);

        // Task 5.12: 在构造函数末尾保存配置快照（打开对话框时的所有值），
        // 供 Cancel 按钮回滚草稿修改。深拷贝避免持有内部引用。
        snapshot = new java.util.Properties();
        config.getProperties().forEach((k, v) -> snapshot.put(k, v));
    }

    private TreeView<String> buildNavigationTree() {
        TreeItem<String> root = new TreeItem<>("root");
        root.setExpanded(true);

        TreeItem<String> general = new TreeItem<>(PAGE_GENERAL);
        root.getChildren().add(general);

        TreeItem<String> terminal = new TreeItem<>("terminal");
        terminal.setExpanded(true);
        terminal.getChildren().addAll(
                new TreeItem<>(PAGE_MOUSE),
                new TreeItem<>(PAGE_SHORTCUTS),
                new TreeItem<>(PAGE_COLORS),
                new TreeItem<>(PAGE_BACKGROUND),
                new TreeItem<>(PAGE_FONT)
        );
        root.getChildren().add(terminal);

        TreeItem<String> monitor = new TreeItem<>(PAGE_MONITOR);
        root.getChildren().add(monitor);

        TreeItem<String> language = new TreeItem<>(PAGE_LANGUAGE);
        root.getChildren().add(language);

        // Task 5.10: About 页加入导航树（language 节点之后）
        TreeItem<String> about = new TreeItem<>(PAGE_ABOUT);
        root.getChildren().add(about);

        TreeView<String> tree = new TreeView<>(root);
        tree.setShowRoot(false);
        tree.setStyle("-fx-background-color: #252526; -fx-border-color: #3c3c3c; -fx-border-width: 0 1 0 0;");

        // 用 cell factory 将页面 ID 翻译为本地化显示文本
        tree.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("-fx-background-color: transparent;");
                } else {
                    setText(I18n.tr("nav." + item));
                    setStyle("-fx-text-fill: #d4d4d4; -fx-background-color: transparent;");
                }
            }
        });
        return tree;
    }

    private void showPage(String pageId) {
        contentArea.getChildren().clear();
        Pane page = pageCache.computeIfAbsent(pageId, this::createPage);
        contentArea.getChildren().add(page);
    }

    private Pane createPage(String pageId) {
        return switch (pageId) {
            case PAGE_GENERAL -> createGeneralPage();
            case PAGE_MOUSE -> createMousePage();
            case PAGE_SHORTCUTS -> createShortcutsPage();
            case PAGE_COLORS -> createColorSchemePage();
            case PAGE_BACKGROUND -> createBackgroundPage();
            case PAGE_FONT -> createFontPage();
            case PAGE_LANGUAGE -> createLanguagePage();
            case PAGE_ABOUT -> createAboutPage();
            case PAGE_MONITOR -> createMonitorPage();
            default -> new VBox(new Label("Unknown page: " + pageId));
        };
    }

    // ==================== General Page ====================
    private Pane createGeneralPage() {
        VBox box = new VBox(15);
        box.setPadding(new Insets(5));

        CheckBox autoSelectTab = new CheckBox(I18n.tr("general.auto_select_tab"));
        autoSelectTab.setSelected(config.isAutoSelectTab());
        autoSelectTab.selectedProperty().addListener((obs, o, n) -> config.setAutoSelectTab(n));
        styleCheckBox(autoSelectTab);

        CheckBox minimizeToTray = new CheckBox(I18n.tr("general.minimize_to_tray"));
        minimizeToTray.setSelected(config.isMinimizeToTray());
        minimizeToTray.selectedProperty().addListener((obs, o, n) -> config.setMinimizeToTray(n));
        styleCheckBox(minimizeToTray);

        CheckBox confirmClose = new CheckBox(I18n.tr("general.confirm_close"));
        confirmClose.setSelected(config.isConfirmBeforeClose());
        confirmClose.selectedProperty().addListener((obs, o, n) -> config.setConfirmBeforeClose(n));
        styleCheckBox(confirmClose);

        box.getChildren().addAll(autoSelectTab, minimizeToTray, confirmClose);
        return box;
    }

    // ==================== Mouse / Terminal Page ====================
    private Pane createMousePage() {
        VBox box = new VBox(15);
        box.setPadding(new Insets(5));

        // 字符编码
        VBox charsetSection = createSection(I18n.tr("mouse.charset"));
        ComboBox<String> charsetCombo = new ComboBox<>();
        charsetCombo.getItems().addAll("UTF-8", "GBK", "GB2312", "ISO-8859-1", "US-ASCII");
        charsetCombo.setValue(config.getCharset());
        charsetCombo.setOnAction(e -> config.setCharset(charsetCombo.getValue()));
        charsetSection.getChildren().add(charsetCombo);

        // 按键序列
        TitledPane keySeqPane = new TitledPane();
        keySeqPane.setText(I18n.tr("mouse.key_sequence"));
        keySeqPane.setCollapsible(false);

        GridPane keyGrid = new GridPane();
        keyGrid.setHgap(10);
        keyGrid.setVgap(8);
        keyGrid.setPadding(new Insets(5));

        Label bsLabel = new Label(I18n.tr("mouse.backspace"));
        styleLabel(bsLabel);
        ComboBox<String> bsCombo = new ComboBox<>();
        bsCombo.getItems().addAll("VT220 - Delete", "ASCII - Backspace", "VT100 - Delete", "Linux - Backspace");
        bsCombo.setValue(mapBackspaceKey(config.getBackspaceKey()));
        bsCombo.setOnAction(e -> config.setBackspaceKey(parseBackspaceKey(bsCombo.getValue())));

        Label delLabel = new Label(I18n.tr("mouse.delete"));
        styleLabel(delLabel);
        ComboBox<String> delCombo = new ComboBox<>();
        delCombo.getItems().addAll("VT220 - Delete", "ASCII - Delete", "VT100 - Delete");
        delCombo.setValue(mapDeleteKey(config.getDeleteKey()));
        delCombo.setOnAction(e -> config.setDeleteKey(parseDeleteKey(delCombo.getValue())));

        keyGrid.add(bsLabel, 0, 0);
        keyGrid.add(bsCombo, 1, 0);
        keyGrid.add(delLabel, 0, 1);
        keyGrid.add(delCombo, 1, 1);
        keySeqPane.setContent(keyGrid);

        // 鼠标行为
        CheckBox copyOnSelect = new CheckBox(I18n.tr("mouse.copy_on_select"));
        copyOnSelect.setSelected(config.isCopyOnSelect());
        copyOnSelect.selectedProperty().addListener((obs, o, n) -> config.setCopyOnSelect(n));
        styleCheckBox(copyOnSelect);

        CheckBox pasteOnRightClick = new CheckBox(I18n.tr("mouse.paste_on_right_click"));
        pasteOnRightClick.setSelected(config.isPasteOnRightClick());
        pasteOnRightClick.selectedProperty().addListener((obs, o, n) -> config.setPasteOnRightClick(n));
        styleCheckBox(pasteOnRightClick);

        // 滚动速度
        HBox scrollBox = new HBox(10);
        scrollBox.setAlignment(Pos.CENTER_LEFT);
        Label scrollLabel = new Label(I18n.tr("mouse.scroll_speed"));
        styleLabel(scrollLabel);
        scrollLabel.setMinWidth(80);
        Slider scrollSlider = new Slider(1, 10, config.getScrollSpeed());
        scrollSlider.setPrefWidth(200);
        scrollSlider.setShowTickLabels(true);
        Label scrollValue = new Label(String.valueOf(config.getScrollSpeed()));
        styleLabel(scrollValue);
        scrollSlider.valueProperty().addListener((obs, o, v) -> {
            scrollValue.setText(String.valueOf(v.intValue()));
            config.setScrollSpeed(v.intValue());
        });
        scrollBox.getChildren().addAll(scrollLabel, scrollSlider, scrollValue);

        // 滚动缓冲行数
        HBox scrollBufBox = new HBox(10);
        scrollBufBox.setAlignment(Pos.CENTER_LEFT);
        Label scrollBufLabel = new Label(I18n.tr("mouse.scroll_buffer"));
        styleLabel(scrollBufLabel);
        scrollBufLabel.setMinWidth(80);
        Spinner<Integer> scrollBufSpinner = new Spinner<>(1000, 500000, config.getScrollBuffer(), 1000);
        scrollBufSpinner.setEditable(true);
        scrollBufSpinner.setPrefWidth(120);
        scrollBufSpinner.valueProperty().addListener((obs, o, v) -> {
            if (v != null) config.setScrollBuffer(v);
        });
        Label scrollBufHint = new Label(I18n.tr("mouse.scroll_buffer_hint"));
        styleLabel(scrollBufHint);
        scrollBufHint.setStyle("-fx-text-fill: #888; -fx-font-size: 11px;");
        scrollBufBox.getChildren().addAll(scrollBufLabel, scrollBufSpinner, scrollBufHint);

        box.getChildren().addAll(charsetSection, keySeqPane, copyOnSelect, pasteOnRightClick, scrollBox, scrollBufBox);
        return box;
    }

    // ==================== Shortcuts Page ====================
    private Pane createShortcutsPage() {
        VBox box = new VBox(5);
        box.setPadding(new Insets(5));

        Label title = new Label(I18n.tr("shortcuts.title"));
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #d4d4d4;");
        box.getChildren().add(title);

        String[][] shortcuts = {
                {"copy", I18n.tr("shortcuts.copy"), config.getShortcut("copy")},
                {"paste", I18n.tr("shortcuts.paste"), config.getShortcut("paste")},
                {"find", I18n.tr("shortcuts.find"), config.getShortcut("find")},
                {"clear", I18n.tr("shortcuts.clear"), config.getShortcut("clear")},
                {"new_tab", I18n.tr("shortcuts.new_tab"), config.getShortcut("new_tab")},
                {"close_tab", I18n.tr("shortcuts.close_tab"), config.getShortcut("close_tab")},
                {"next_tab", I18n.tr("shortcuts.next_tab"), config.getShortcut("next_tab")},
                {"prev_tab", I18n.tr("shortcuts.prev_tab"), config.getShortcut("prev_tab")},
                {"split_horizontal", I18n.tr("shortcuts.split_horizontal"), config.getShortcut("split_horizontal")},
                {"split_vertical", I18n.tr("shortcuts.split_vertical"), config.getShortcut("split_vertical")},
                {"fullscreen", I18n.tr("shortcuts.fullscreen"), config.getShortcut("fullscreen")},
                {"zoom_in", I18n.tr("shortcuts.zoom_in"), config.getShortcut("zoom_in")},
                {"zoom_out", I18n.tr("shortcuts.zoom_out"), config.getShortcut("zoom_out")},
                {"zoom_reset", I18n.tr("shortcuts.zoom_reset"), config.getShortcut("zoom_reset")},
        };

        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setStyle("-fx-background: #1e1e1e; -fx-background-color: #1e1e1e;");
        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(6);
        grid.setPadding(new Insets(5));

        grid.add(boldLabel(I18n.tr("shortcuts.function")), 0, 0);
        grid.add(boldLabel(I18n.tr("shortcuts.key")), 1, 0);

        for (int i = 0; i < shortcuts.length; i++) {
            String[] s = shortcuts[i];
            Label nameLabel = new Label(s[1]);
            styleLabel(nameLabel);
            nameLabel.setMinWidth(120);

            TextField keyField = new TextField(s[2]);
            keyField.setPrefWidth(200);
            keyField.setEditable(true);
            keyField.setAlignment(Pos.CENTER);
            keyField.setStyle("-fx-control-inner-background: #2d2d2d; -fx-text-fill: #d4d4d4;");
            final String action = s[0];
            keyField.setOnAction(e -> config.setShortcut(action, keyField.getText()));
            keyField.focusedProperty().addListener((obs, o, n) -> {
                if (!n) config.setShortcut(action, keyField.getText());
            });

            grid.add(nameLabel, 0, i + 1);
            grid.add(keyField, 1, i + 1);
        }

        scrollPane.setContent(grid);
        scrollPane.setFitToWidth(true);
        box.getChildren().add(scrollPane);
        return box;
    }

    // ==================== Color Scheme Page ====================
    private Pane createColorSchemePage() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(5));

        HBox presetBox = new HBox(10);
        presetBox.setAlignment(Pos.CENTER_LEFT);
        Label presetLabel = new Label(I18n.tr("colors.preset"));
        styleLabel(presetLabel);
        presetLabel.setMinWidth(80);
        ComboBox<String> presetCombo = new ComboBox<>();
        presetCombo.getItems().addAll("default", "monokai", "solarized", "dracula", "nord", "onedark", "gruvbox");
        presetCombo.setValue(config.getColorScheme());
        presetCombo.setOnAction(e -> {
            String scheme = presetCombo.getValue();
            config.setColorScheme(scheme);
            applyPresetScheme(scheme);
            pageCache.remove(PAGE_COLORS);
            showPage(PAGE_COLORS);
        });
        presetBox.getChildren().addAll(presetLabel, presetCombo);

        GridPane colorGrid = new GridPane();
        colorGrid.setHgap(15);
        colorGrid.setVgap(8);
        colorGrid.setPadding(new Insets(5));

        String[][] colors = {
                {"foreground", I18n.tr("colors.name") + "/" + I18n.tr("colors.color"), config.getTerminalColor("foreground")},
                {"background", "bg", config.getTerminalColor("background")},
                {"cursor", "cursor", config.getTerminalColor("cursor")},
                {"selection", "selection", config.getTerminalColor("selection")},
                {"black", "black", config.getTerminalColor("black")},
                {"red", "red", config.getTerminalColor("red")},
                {"green", "green", config.getTerminalColor("green")},
                {"yellow", "yellow", config.getTerminalColor("yellow")},
                {"blue", "blue", config.getTerminalColor("blue")},
                {"magenta", "magenta", config.getTerminalColor("magenta")},
                {"cyan", "cyan", config.getTerminalColor("cyan")},
                {"white", "white", config.getTerminalColor("white")},
        };

        colorGrid.add(boldLabel(I18n.tr("colors.name")), 0, 0);
        colorGrid.add(boldLabel(I18n.tr("colors.color")), 1, 0);
        colorGrid.add(boldLabel(I18n.tr("colors.preview")), 2, 0);

        for (int i = 0; i < colors.length; i++) {
            String[] c = colors[i];
            Label nameLabel = new Label(c[1]);
            styleLabel(nameLabel);
            nameLabel.setMinWidth(60);

            TextField colorField = new TextField(c[2]);
            colorField.setPrefWidth(100);
            colorField.setStyle("-fx-control-inner-background: #2d2d2d; -fx-text-fill: #d4d4d4;");
            final String colorName = c[0];

            Pane swatch = new Pane();
            swatch.setPrefSize(30, 20);
            swatch.setStyle("-fx-background-color: " + c[2] + "; -fx-border-color: #666; -fx-border-width: 1;");

            colorField.setOnAction(e -> {
                config.setTerminalColor(colorName, colorField.getText());
                swatch.setStyle("-fx-background-color: " + colorField.getText() + "; -fx-border-color: #666; -fx-border-width: 1;");
            });
            // Task 5.9: 颜色色块随 colorField 失焦实时联动
            colorField.focusedProperty().addListener((obs, o, n) -> {
                if (!n) {
                    config.setTerminalColor(colorName, colorField.getText());
                    swatch.setStyle("-fx-background-color: " + colorField.getText() + "; -fx-border-color: #666; -fx-border-width: 1;");
                }
            });

            colorGrid.add(nameLabel, 0, i + 1);
            colorGrid.add(colorField, 1, i + 1);
            colorGrid.add(swatch, 2, i + 1);
        }

        ScrollPane scrollPane = new ScrollPane(colorGrid);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: #1e1e1e; -fx-background-color: #1e1e1e;");

        box.getChildren().addAll(presetBox, scrollPane);
        return box;
    }

    // ==================== Background Image Page ====================
    private Pane createBackgroundPage() {
        VBox box = new VBox(15);
        box.setPadding(new Insets(5));

        CheckBox enableBg = new CheckBox(I18n.tr("bg.enable"));
        enableBg.setSelected(config.isBackgroundImageEnabled());
        enableBg.selectedProperty().addListener((obs, o, n) -> config.setBackgroundImageEnabled(n));
        styleCheckBox(enableBg);

        // 预览区：ImageView 实时显示当前路径缩略图
        StackPane preview = new StackPane();
        preview.setPrefHeight(200);
        preview.setStyle("-fx-background-color: #1e1e1e; -fx-border-color: #666; -fx-border-width: 1;");
        ImageView previewImg = new ImageView();
        previewImg.setPreserveRatio(true);
        previewImg.setFitWidth(360);
        previewImg.setFitHeight(180);
        Label noImgLabel = new Label(I18n.tr("bg.preview_area"));
        noImgLabel.setStyle("-fx-text-fill: #888;");
        preview.getChildren().add(noImgLabel);

        final java.util.function.Consumer<String> updatePreview = path -> {
            preview.getChildren().clear();
            if (path == null || path.isEmpty()) {
                preview.getChildren().add(noImgLabel);
                return;
            }
            File f = new File(path);
            if (!f.exists()) {
                preview.getChildren().add(noImgLabel);
                return;
            }
            try {
                previewImg.setImage(new javafx.scene.image.Image(f.toURI().toString()));
                preview.getChildren().add(previewImg);
            } catch (Exception ex) {
                preview.getChildren().add(noImgLabel);
            }
        };

        // 首次加载当前路径
        updatePreview.accept(config.getBackgroundImagePath());

        HBox fileBox = new HBox(10);
        fileBox.setAlignment(Pos.CENTER_LEFT);
        Label fileLabel = new Label(I18n.tr("bg.path"));
        styleLabel(fileLabel);
        fileLabel.setMinWidth(80);
        TextField fileField = new TextField(config.getBackgroundImagePath());
        fileField.setPrefWidth(320);
        fileField.setEditable(false);
        fileField.setStyle("-fx-control-inner-background: #2d2d2d; -fx-text-fill: #d4d4d4;");
        fileField.focusedProperty().addListener((obs, o, n) -> {
            if (!n) config.setBackgroundImagePath(fileField.getText());
        });
        Button browseBtn = new Button(I18n.tr("common.browse"));
        browseBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle(I18n.tr("bg.chooser_title"));
            fc.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Image", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"),
                    new FileChooser.ExtensionFilter("All", "*.*")
            );
            File file = fc.showOpenDialog(this);
            if (file != null) {
                fileField.setText(file.getAbsolutePath());
                config.setBackgroundImagePath(file.getAbsolutePath());
                updatePreview.accept(file.getAbsolutePath());
            }
        });
        fileBox.getChildren().addAll(fileLabel, fileField, browseBtn);

        HBox opacityBox = new HBox(10);
        opacityBox.setAlignment(Pos.CENTER_LEFT);
        Label opacityLabel = new Label(I18n.tr("bg.opacity"));
        styleLabel(opacityLabel);
        opacityLabel.setMinWidth(80);
        Slider opacitySlider = new Slider(0.05, 1.0, config.getBackgroundImageOpacity());
        opacitySlider.setPrefWidth(250);
        opacitySlider.setShowTickLabels(true);
        opacitySlider.setShowTickMarks(true);
        Label opacityValue = new Label(String.format("%.0f%%", config.getBackgroundImageOpacity() * 100));
        styleLabel(opacityValue);
        opacitySlider.valueProperty().addListener((obs, o, v) -> {
            opacityValue.setText(String.format("%.0f%%", v.doubleValue() * 100));
            config.setBackgroundImageOpacity(v.doubleValue());
            previewImg.setOpacity(v.doubleValue());
        });
        opacityBox.getChildren().addAll(opacityLabel, opacitySlider, opacityValue);

        box.getChildren().addAll(enableBg, fileBox, opacityBox, preview);
        return box;
    }

    // ==================== Monitor Page ====================
    private Pane createMonitorPage() {
        VBox box = new VBox(15);
        box.setPadding(new Insets(5));

        // 监控刷新间隔（秒）
        HBox intervalBox = new HBox(10);
        intervalBox.setAlignment(Pos.CENTER_LEFT);
        Label intervalLabel = new Label(I18n.tr("monitor.interval"));
        styleLabel(intervalLabel);
        intervalLabel.setMinWidth(140);
        Spinner<Integer> intervalSpinner = new Spinner<>(1, 300, config.getMonitorUiIntervalSeconds());
        intervalSpinner.setEditable(true);
        intervalSpinner.setPrefWidth(100);
        intervalSpinner.valueProperty().addListener((obs, o, v) -> {
            if (v != null) config.setMonitorUiIntervalSeconds(v);
        });
        Label intervalHint = new Label(I18n.tr("monitor.interval_hint"));
        styleLabel(intervalHint);
        intervalHint.setStyle("-fx-text-fill: #888; -fx-font-size: 11px;");
        intervalBox.getChildren().addAll(intervalLabel, intervalSpinner, new Label("秒"), intervalHint);

        // CPU 阈值
        HBox cpuBox = new HBox(10);
        cpuBox.setAlignment(Pos.CENTER_LEFT);
        Label cpuLabel = new Label(I18n.tr("monitor.cpu_threshold"));
        styleLabel(cpuLabel);
        cpuLabel.setMinWidth(140);
        Spinner<Integer> cpuSpinner = new Spinner<>(1, 100, config.getCpuThreshold());
        cpuSpinner.setEditable(true);
        cpuSpinner.setPrefWidth(100);
        cpuSpinner.valueProperty().addListener((obs, o, v) -> {
            if (v != null) config.set("monitor.cpu.threshold", String.valueOf(v));
        });
        cpuBox.getChildren().addAll(cpuLabel, cpuSpinner, new Label("%"));

        // MEM 阈值
        HBox memBox = new HBox(10);
        memBox.setAlignment(Pos.CENTER_LEFT);
        Label memLabel = new Label(I18n.tr("monitor.mem_threshold"));
        styleLabel(memLabel);
        memLabel.setMinWidth(140);
        Spinner<Integer> memSpinner = new Spinner<>(1, 100, config.getMemoryThreshold());
        memSpinner.setEditable(true);
        memSpinner.setPrefWidth(100);
        memSpinner.valueProperty().addListener((obs, o, v) -> {
            if (v != null) config.set("monitor.memory.threshold", String.valueOf(v));
        });
        memBox.getChildren().addAll(memLabel, memSpinner, new Label("%"));

        // DISK 阈值
        HBox diskBox = new HBox(10);
        diskBox.setAlignment(Pos.CENTER_LEFT);
        Label diskLabel = new Label(I18n.tr("monitor.disk_threshold"));
        styleLabel(diskLabel);
        diskLabel.setMinWidth(140);
        Spinner<Integer> diskSpinner = new Spinner<>(1, 100, config.getDiskThreshold());
        diskSpinner.setEditable(true);
        diskSpinner.setPrefWidth(100);
        diskSpinner.valueProperty().addListener((obs, o, v) -> {
            if (v != null) config.set("monitor.disk.threshold", String.valueOf(v));
        });
        diskBox.getChildren().addAll(diskLabel, diskSpinner, new Label("%"));

        box.getChildren().addAll(intervalBox, cpuBox, memBox, diskBox);
        return box;
    }

    // ==================== Font Page ====================
    private Pane createFontPage() {
        VBox box = new VBox(15);
        box.setPadding(new Insets(5));

        HBox familyBox = new HBox(10);
        familyBox.setAlignment(Pos.CENTER_LEFT);
        Label familyLabel = new Label(I18n.tr("font.family"));
        styleLabel(familyLabel);
        familyLabel.setMinWidth(80);
        ComboBox<String> familyCombo = new ComboBox<>();
        familyCombo.getItems().addAll(
                "Cascadia Code", "Consolas", "Courier New", "Source Code Pro",
                "Fira Code", "JetBrains Mono", "MS Gothic", "Microsoft YaHei",
                "SimSun", "SimHei", "Monospaced"
        );
        familyCombo.setValue(config.getFontFamily());
        familyCombo.setOnAction(e -> config.setFontFamily(familyCombo.getValue()));
        familyCombo.setPrefWidth(250);
        familyBox.getChildren().addAll(familyLabel, familyCombo);

        HBox sizeBox = new HBox(10);
        sizeBox.setAlignment(Pos.CENTER_LEFT);
        Label sizeLabel = new Label(I18n.tr("font.size"));
        styleLabel(sizeLabel);
        sizeLabel.setMinWidth(80);
        Spinner<Integer> sizeSpinner = new Spinner<>(8, 72, config.getFontSize());
        sizeSpinner.setEditable(true);
        sizeSpinner.setPrefWidth(100);
        sizeSpinner.valueProperty().addListener((obs, o, v) -> config.setFontSize(v));
        sizeBox.getChildren().addAll(sizeLabel, sizeSpinner);

        HBox styleBox = new HBox(15);
        styleBox.setAlignment(Pos.CENTER_LEFT);
        Label styleLabel = new Label(I18n.tr("font.style"));
        styleLabel(styleLabel);
        styleLabel.setMinWidth(80);
        CheckBox boldCheck = new CheckBox(I18n.tr("font.bold"));
        boldCheck.setSelected(config.isFontBold());
        boldCheck.selectedProperty().addListener((obs, o, n) -> config.setFontBold(n));
        styleCheckBox(boldCheck);
        CheckBox italicCheck = new CheckBox(I18n.tr("font.italic"));
        italicCheck.setSelected(config.isFontItalic());
        italicCheck.selectedProperty().addListener((obs, o, n) -> config.setFontItalic(n));
        styleCheckBox(italicCheck);
        styleBox.getChildren().addAll(styleLabel, boldCheck, italicCheck);

        TitledPane previewPane = new TitledPane();
        previewPane.setText(I18n.tr("font.preview"));
        previewPane.setCollapsible(false);
        TextArea previewArea = new TextArea("AaBbCcDd 0123456789\nHello, 你好世界!");
        previewArea.setEditable(false);
        previewArea.setPrefHeight(90);
        previewArea.setStyle("-fx-control-inner-background: #0c0c0c; -fx-text-fill: #cccccc;");
        updateFontPreview(previewArea, familyCombo, sizeSpinner, boldCheck, italicCheck);

        familyCombo.setOnAction(e -> {
            config.setFontFamily(familyCombo.getValue());
            updateFontPreview(previewArea, familyCombo, sizeSpinner, boldCheck, italicCheck);
        });
        sizeSpinner.valueProperty().addListener((obs, o, v) -> {
            config.setFontSize(v);
            updateFontPreview(previewArea, familyCombo, sizeSpinner, boldCheck, italicCheck);
        });
        boldCheck.selectedProperty().addListener((obs, o, n) -> {
            config.setFontBold(n);
            updateFontPreview(previewArea, familyCombo, sizeSpinner, boldCheck, italicCheck);
        });
        italicCheck.selectedProperty().addListener((obs, o, n) -> {
            config.setFontItalic(n);
            updateFontPreview(previewArea, familyCombo, sizeSpinner, boldCheck, italicCheck);
        });

        previewPane.setContent(previewArea);

        box.getChildren().addAll(familyBox, sizeBox, styleBox, previewPane);
        return box;
    }

    // ==================== Language Page ====================
    private Pane createLanguagePage() {
        VBox box = new VBox(15);
        box.setPadding(new Insets(5));

        VBox langSection = createSection(I18n.tr("language.label"));
        ComboBox<String> langCombo = new ComboBox<>();
        langCombo.getItems().addAll(I18n.tr("language.chinese"), I18n.tr("language.english"));
        String currentLang = config.getLanguage();
        langCombo.setValue("en".equalsIgnoreCase(currentLang)
                ? I18n.tr("language.english")
                : I18n.tr("language.chinese"));
        // 选择后立即写入 config（保存时由 saveAll 检测变更并触发重启）
        langCombo.setOnAction(e -> {
            String selected = langCombo.getValue();
            config.setLanguage(I18n.tr("language.english").equals(selected) ? "en" : "zh_CN");
        });
        langSection.getChildren().add(langCombo);

        Label hint = new Label(I18n.tr("language.hint"));
        hint.setStyle("-fx-text-fill: #888; -fx-font-size: 11px;");
        hint.setWrapText(true);
        hint.setMaxWidth(450);

        box.getChildren().addAll(langSection, hint);
        return box;
    }

    // ==================== About Page ====================
    private Pane createAboutPage() {
        VBox box = new VBox(15);
        box.setPadding(new Insets(5));

        Label appName = new Label("StarShell");
        appName.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: #4fc3f7;");
        box.getChildren().add(appName);

        box.getChildren().add(boldLabel(I18n.tr("about.title")));
        box.getChildren().addAll(
                new Label(I18n.tr("about.author")),
                new Label(I18n.tr("about.version")),
                new Label(I18n.tr("about.description"))
        );
        return box;
    }

    // ==================== Helper Methods ====================

    private void updateFontPreview(TextArea preview, ComboBox<String> family,
                                   Spinner<Integer> size, CheckBox bold, CheckBox italic) {
        preview.setStyle(String.format(
                "-fx-font-family: '%s'; -fx-font-size: %dpx; -fx-font-weight: %s; -fx-font-style: %s; "
                        + "-fx-control-inner-background: #0c0c0c; -fx-text-fill: #cccccc;",
                family.getValue(), size.getValue(),
                bold.isSelected() ? "bold" : "normal",
                italic.isSelected() ? "italic" : "normal"));
    }

    private VBox createSection(String title) {
        VBox section = new VBox(5);
        Label label = new Label(title);
        label.setStyle("-fx-font-weight: bold; -fx-text-fill: #d4d4d4;");
        section.getChildren().add(label);
        return section;
    }

    private Label boldLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold; -fx-text-fill: #d4d4d4;");
        return label;
    }

    private void styleLabel(Label label) {
        label.setStyle("-fx-text-fill: #d4d4d4;");
    }

    private void styleCheckBox(CheckBox cb) {
        cb.setStyle("-fx-text-fill: #d4d4d4;");
    }

    private String mapBackspaceKey(String key) {
        return switch (key) {
            case "VT220" -> "VT220 - Delete";
            case "ASCII" -> "ASCII - Backspace";
            case "VT100" -> "VT100 - Delete";
            case "Linux" -> "Linux - Backspace";
            default -> "VT220 - Delete";
        };
    }

    private String parseBackspaceKey(String display) {
        if (display.startsWith("VT220")) return "VT220";
        if (display.startsWith("ASCII")) return "ASCII";
        if (display.startsWith("VT100")) return "VT100";
        if (display.startsWith("Linux")) return "Linux";
        return "VT220";
    }

    private String mapDeleteKey(String key) {
        return switch (key) {
            case "VT220" -> "VT220 - Delete";
            case "ASCII" -> "ASCII - Delete";
            case "VT100" -> "VT100 - Delete";
            default -> "VT220 - Delete";
        };
    }

    private String parseDeleteKey(String display) {
        if (display.startsWith("VT220")) return "VT220";
        if (display.startsWith("ASCII")) return "ASCII";
        if (display.startsWith("VT100")) return "VT100";
        return "VT220";
    }

    private void applyPresetScheme(String scheme) {
        switch (scheme) {
            case "monokai" -> {
                config.setTerminalColor("foreground", "#f8f8f2");
                config.setTerminalColor("background", "#272822");
                config.setTerminalColor("black", "#272822");
                config.setTerminalColor("red", "#f92672");
                config.setTerminalColor("green", "#a6e22e");
                config.setTerminalColor("yellow", "#f4bf75");
                config.setTerminalColor("blue", "#66d9ef");
                config.setTerminalColor("magenta", "#ae81ff");
                config.setTerminalColor("cyan", "#a1efe4");
                config.setTerminalColor("white", "#f8f8f2");
            }
            case "solarized" -> {
                config.setTerminalColor("foreground", "#839496");
                config.setTerminalColor("background", "#002b36");
                config.setTerminalColor("black", "#073642");
                config.setTerminalColor("red", "#dc322f");
                config.setTerminalColor("green", "#859900");
                config.setTerminalColor("yellow", "#b58900");
                config.setTerminalColor("blue", "#268bd2");
                config.setTerminalColor("magenta", "#d33682");
                config.setTerminalColor("cyan", "#2aa198");
                config.setTerminalColor("white", "#eee8d5");
            }
            case "dracula" -> {
                config.setTerminalColor("foreground", "#f8f8f2");
                config.setTerminalColor("background", "#282a36");
                config.setTerminalColor("black", "#21222c");
                config.setTerminalColor("red", "#ff5555");
                config.setTerminalColor("green", "#50fa7b");
                config.setTerminalColor("yellow", "#f1fa8c");
                config.setTerminalColor("blue", "#bd93f9");
                config.setTerminalColor("magenta", "#ff79c6");
                config.setTerminalColor("cyan", "#8be9fd");
                config.setTerminalColor("white", "#f8f8f2");
            }
            case "nord" -> {
                config.setTerminalColor("foreground", "#d8dee9");
                config.setTerminalColor("background", "#2e3440");
                config.setTerminalColor("black", "#3b4252");
                config.setTerminalColor("red", "#bf616a");
                config.setTerminalColor("green", "#a3be8c");
                config.setTerminalColor("yellow", "#ebcb8b");
                config.setTerminalColor("blue", "#81a1c1");
                config.setTerminalColor("magenta", "#b48ead");
                config.setTerminalColor("cyan", "#88c0d0");
                config.setTerminalColor("white", "#e5e9f0");
            }
            case "onedark" -> {
                config.setTerminalColor("foreground", "#abb2bf");
                config.setTerminalColor("background", "#282c34");
                config.setTerminalColor("black", "#282c34");
                config.setTerminalColor("red", "#e06c75");
                config.setTerminalColor("green", "#98c379");
                config.setTerminalColor("yellow", "#e5c07b");
                config.setTerminalColor("blue", "#61afef");
                config.setTerminalColor("magenta", "#c678dd");
                config.setTerminalColor("cyan", "#56b6c2");
                config.setTerminalColor("white", "#abb2bf");
            }
            case "gruvbox" -> {
                config.setTerminalColor("foreground", "#ebdbb2");
                config.setTerminalColor("background", "#282828");
                config.setTerminalColor("black", "#282828");
                config.setTerminalColor("red", "#cc241d");
                config.setTerminalColor("green", "#98971a");
                config.setTerminalColor("yellow", "#d79921");
                config.setTerminalColor("blue", "#458588");
                config.setTerminalColor("magenta", "#b16286");
                config.setTerminalColor("cyan", "#689d6a");
                config.setTerminalColor("white", "#ebdbb2");
            }
            default -> {
                config.setTerminalColor("foreground", "#d4d4d4");
                config.setTerminalColor("background", "#1e1e1e");
                config.setTerminalColor("black", "#000000");
                config.setTerminalColor("red", "#cd3131");
                config.setTerminalColor("green", "#0dbc79");
                config.setTerminalColor("yellow", "#d29922");
                config.setTerminalColor("blue", "#2472c8");
                config.setTerminalColor("magenta", "#bc3fbc");
                config.setTerminalColor("cyan", "#11a8cd");
                config.setTerminalColor("white", "#e5e5e5");
            }
        }
    }

    private void saveAll() {
        boolean languageChanged = !config.getLanguage().equals(originalLanguage);
        originalLanguage = config.getLanguage();
        config.saveConfig();
        for (Runnable callback : onSaveCallbacks) {
            callback.run();
        }

        // 语言变更：提示重启以应用新界面语言（重启会退出当前进程，对话框自然消失）
        if (languageChanged) {
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                        I18n.tr("language.restart_msg"),
                        ButtonType.YES, ButtonType.NO);
                alert.setTitle(I18n.tr("language.restart_title"));
                alert.setHeaderText(null);
                alert.showAndWait().ifPresent(btn -> {
                    if (btn == ButtonType.YES) {
                        AiFinalShellApp.restart();
                    }
                });
            });
        }
    }

    private void resetToDefaults() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, I18n.tr("settings.reset_confirm"));
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                // Task 5.11: 真正重置——清空内存属性并重新填入默认值（替代旧逻辑仅删文件+saveConfig，
                // 旧逻辑无法覆盖运行期已写入 properties 的修改值）。
                config.clearAndReloadDefaults();
                pageCache.clear();
                originalLanguage = config.getLanguage();
                showPage(PAGE_GENERAL);
            }
        });
    }

    public void addOnSaveCallback(Runnable callback) {
        onSaveCallbacks.add(callback);
    }
}
