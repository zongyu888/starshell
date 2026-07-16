package com.aifinalshell.controller;

import com.aifinalshell.AiFinalShellApp;
import com.aifinalshell.ai.AiServiceClient;
import com.aifinalshell.ai.OpsPromptTemplates;
import com.aifinalshell.ai.TerminalEvent;
import com.aifinalshell.agent.AgentDef;
import com.aifinalshell.agent.AgentRegistry;
import com.aifinalshell.agent.tool.ToolAgent;
import com.aifinalshell.agent.tool.ToolContext;
import com.aifinalshell.agent.tool.ToolPermission;
import com.aifinalshell.config.AiConfigManager;
import com.aifinalshell.config.AppConfig;
import com.aifinalshell.model.*;
import com.aifinalshell.monitor.MonitorService;
import com.aifinalshell.provider.ChatMessage;
import com.aifinalshell.provider.ModelInfo;
import com.aifinalshell.service.DatabaseManager;
import com.aifinalshell.service.DownloadManager;
import com.aifinalshell.service.LogCollectorService;
import com.aifinalshell.session.Message;
import com.aifinalshell.session.Session;
import com.aifinalshell.session.SessionManager;
import com.aifinalshell.ssh.SshConnectionManager;
import com.aifinalshell.ui.PasswordRetryDialog;
import com.aifinalshell.ui.PasswordToggleField;
import com.aifinalshell.util.SecurityUtils;
import com.jcraft.jsch.ChannelShell;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.css.PseudoClass;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class MainController implements Initializable {
    private static final Logger logger = LoggerFactory.getLogger(MainController.class);
    private Stage primaryStage;

    // ========== UI Components ==========
    // Main layout
    @FXML private SplitPane mainSplitPane, centerSplitPane;
    @FXML private VBox leftPanel, rightPanel;
    @FXML private VBox leftContent, rightContent;
    @FXML private Button leftCollapseBtn, rightCollapseBtn;
    @FXML private Button leftCollapseInBtn; // 左面板内折叠按钮（与右面板 ✕ 对称）
    private boolean leftCollapsed = false;
    private boolean rightCollapsed = false;
    // 折叠前保存的 divider 位置，用于展开恢复（用户可能拖动过 divider）
    private double savedLeftDivider = 0.18;
    private double savedRightDivider = 0.65;

    // Left Panel - Connected Server Info
    @FXML private VBox serverInfoPanel;
    @FXML private Label connectedIpLabel;
    @FXML private Label sysInfoBrief;

    // Model & Agent Selectors
    @FXML private ComboBox<String> modelSelector;

    // Terminal
    @FXML private TextArea terminalOutput;
    @FXML private Region terminalBackground, terminalOverlay;
    @FXML private TextField commandInput;
    @FXML private Label aiTerminalStatus;
    private TerminalController terminalController;
    private final Set<KeyCombination> registeredShortcuts = new HashSet<>();
    private Timeline caretVisibilityEnforcer;

    // File Manager
    @FXML private SplitPane fileSplitPane;
    @FXML private TreeView<String> dirTree;
    @FXML private HBox breadcrumbBar;
    @FXML private TableView<FileItem> fileTable;
    @FXML private TableColumn<FileItem, String> fileNameCol, fileSizeCol, fileTypeCol, filePermCol, fileTimeCol, fileOwnerCol;

    // Left Panel - Status Section
    @FXML private VBox statusSection;
    @FXML private HBox statusHeader;
    @FXML private Label statusArrow;
    @FXML private VBox statusContent;
    @FXML private Label uptimeLabel, loadLabel, pingLabel, procsLabel;

    // Left Panel - Monitor Section
    @FXML private VBox monitorSection;
    @FXML private HBox monitorHeader;
    @FXML private Label monitorArrow;
    @FXML private VBox monitorContent;
    @FXML private ProgressBar cpuBar, memBar, diskBar;
    @FXML private Label cpuLabel, memLabel, diskLabel;
    // V-P1-7: 监控采集失败时显示的红色状态条（动态注入左侧监控面板顶部）
    private Label monitorFailureLabel;

    // Left Panel - Top Processes Section
    @FXML private VBox topProcSection;
    @FXML private HBox topProcHeader;
    @FXML private Label topProcArrow;
    @FXML private VBox topProcContent;
    @FXML private TableView<TopProcessItem> topProcessTable;
    @FXML private TableColumn<TopProcessItem, String> topUserCol, topCpuCol, topMemCol, topCmdCol;

    // Left Panel - Network Section
    @FXML private VBox networkSection;
    @FXML private HBox networkHeader;
    @FXML private Label networkArrow;
    @FXML private VBox networkContent;
    @FXML private Label netUpLabel, netDownLabel;
    @FXML private ProgressBar netUpBar, netDownBar;

    // Left Panel - Disk Section
    @FXML private VBox diskSection;
    @FXML private HBox diskHeader;
    @FXML private Label diskArrow;
    @FXML private VBox diskContent;
    @FXML private Label diskFreeLabel;
    @FXML private ProgressBar diskDetailBar;

    // Left Panel - Log Section
    @FXML private VBox logSection;
    @FXML private HBox logHeader;
    @FXML private Label logArrow;
    @FXML private VBox logContent;
    @FXML private Label logPathLabel, logErrorLabel;

    // Session Bar (above terminal)
    @FXML private HBox sessionBar;
    // 会话标签 active 伪类（用 session ID 判断 active，替代旧的 name 比较，避免同名误高亮）
    private static final PseudoClass ACTIVE_PC = PseudoClass.getPseudoClass("active");

    // AI Assistant (TRAE Style)
    @FXML private StackPane aiChatStack;
    @FXML private VBox aiWelcomeScreen;
    @FXML private ScrollPane aiChatScroll;
    @FXML private VBox aiChatMessages;
    @FXML private TextArea aiInput;
    @FXML private FlowPane fileReferences;
    @FXML private Button aiSendBtn;
    @FXML private Button aiModelConfigBtn;
    @FXML private Button aiUploadBtn;
    @FXML private Label aiConnectionStatus;
    @FXML private Label aiConnectionIndicator;
    @FXML private Label aiActivityIndicator;

    // Status Bar
    @FXML private Label statusText, serverInfo, modelInfo;

    // ========== Internal State ==========
    private ServerConfig selectedServer;
    /** C4: 所有可用模型缓存（任务7筛选系统，供筛选时重建选择器列表） */
    private List<ModelInfo> allModels = new ArrayList<>();
    private final Map<Long, ServerConfig> serverConfigMap = new HashMap<>();
    private final Map<String, ServerConfig> serverNameMap = new HashMap<>();
    private java.util.Timer monitorTimer;

    private final SessionWorkspaceState workspaceState = new SessionWorkspaceState();

    // 服务器上下文相关：当前SSH连接键（用于终端事件桥接和上下文拉取）
    private String currentConnectionKey = null;
    // 修复项5：区分"用户主动断开"与"终端意外断连"。用户主动断开（disconnectServer）前置 true，
    // 状态回调据此跳过 AI 聊天区的意外断连气泡（主动断开已有专属气泡，避免重复）。
    private volatile boolean userInitiatedDisconnect = false;

    // AI 生成状态：用于发送/停止按钮切换、停止生成、重新生成最后回复
    private volatile boolean aiGenerating = false;
    private volatile Thread aiGenerationThread = null;
    /** 每次发送递增；所有异步回调必须匹配当前 ID，防止停止后的旧请求污染新对话。 */
    private final AtomicLong aiRequestSequence = new AtomicLong();
    private volatile long activeAiRequestId = 0L;
    private volatile Session activeAiSession = null;
    private volatile String activeAiUserMessage = null;
    private String lastUserMessage = "";

    // 拖入 AI 面板的本地文件（待上传/部署），用户发送消息时注入 AI 上下文
    private final List<File> pendingAttachments = new ArrayList<>();

    // 文件/文件夹引用（来自右键菜单"引用到AI"），显示在输入框上方标签栏，发送时注入 AI 上下文
    private final LinkedHashSet<String> aiReferencedPaths = new LinkedHashSet<>();

    public String getCurrentConnectionKey() {
        // 优先返回 currentConnectionKey —— 这是 connectToServer 连接成功时设置、
        // disconnectServer 断开时清除的权威字段，指向 SshConnectionManager 中实际存储的键。
        // 不再用当前会话 ID 重算，避免「连接时会话为 null（键=0_xxx）→ AI 发送时新建会话
        // （重算键=1_xxx）」的会话 ID 漂移导致 SFTP/文件/AI 工具全部命中错误连接键。
        if (currentConnectionKey != null) return currentConnectionKey;
        Session current = SessionManager.getInstance().getCurrentSession();
        if (current == null || selectedServer == null) return null;
        return SshConnectionManager.connectionKey(current.getId(), selectedServer.getId());
    }

    private Long getCurrentSessionId() {
        Session current = SessionManager.getInstance().getCurrentSession();
        return current == null ? null : current.getId();
    }

    private String getCurrentRemotePath() {
        Long sessionId = getCurrentSessionId();
        return workspaceState.remotePath(sessionId);
    }

    private void setCurrentRemotePath(String path) {
        Long sessionId = getCurrentSessionId();
        workspaceState.setRemotePath(sessionId, path);
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        initModelAgentSelectors();
        initTerminal();
        initFileTable();
        initLeftMonitor();
        initTopProcessTable();
        initFileDragDrop();
        initSessionBar();
        initAiInput();
        loadServerConfigs();
        loadSessions();
        checkAiService();

        // AI面板拖拽：任意文件类型拖入后由用户在聊天里指定上传位置，AI 用工具完成上传/部署
        initAiPanelDragDrop();

        // 工具栏新按钮提示
        if (aiModelConfigBtn != null) aiModelConfigBtn.setTooltip(new Tooltip("配置自定义模型 API"));
        if (aiUploadBtn != null) aiUploadBtn.setTooltip(new Tooltip("选择文件添加到对话，由 AI 上传到服务器"));

        // V-P1-7: 监控采集失败时在左侧监控面板顶部显示红色状态条
        MonitorService.getInstance().setFailureCallback(serverName -> Platform.runLater(() -> {
            if (monitorFailureLabel == null) {
                // 动态创建失败状态标签，挂在左侧监控面板顶部
                monitorFailureLabel = new Label();
                monitorFailureLabel.setStyle(
                    "-fx-background-color: #cd3131; -fx-text-fill: white; "
                    + "-fx-padding: 6 10; -fx-font-size: 11px; -fx-font-weight: bold;");
                monitorFailureLabel.setMaxWidth(Double.MAX_VALUE);
                monitorFailureLabel.setWrapText(true);
                // 插到 monitorContent 父容器（monitorSection，VBox）的最顶端
                try {
                    if (monitorContent != null && monitorContent.getParent() instanceof VBox) {
                        VBox parent = (VBox) monitorContent.getParent();
                        parent.getChildren().add(0, monitorFailureLabel);
                    }
                } catch (Exception ex) {
                    logger.warn("Failed to inject monitor failure label", ex);
                }
            }
            monitorFailureLabel.setText("⚠ 监控采集失败: " + serverName);
            monitorFailureLabel.setVisible(true);
            monitorFailureLabel.setManaged(true);

            // 5 分钟后自动隐藏（避免一直显示），但下次失败会再次显示
            new Thread(() -> {
                try { Thread.sleep(5 * 60 * 1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                Platform.runLater(() -> {
                    if (monitorFailureLabel != null) {
                        monitorFailureLabel.setVisible(false);
                        monitorFailureLabel.setManaged(false);
                    }
                });
            }).start();
        }));

        // M-P2-4: 启动时恢复上次的会话
        try {
            String lastId = AppConfig.getInstance().getLastSessionId();
            if (lastId != null && !lastId.isEmpty()) {
                try {
                    Long id = Long.parseLong(lastId);
                    Session last = SessionManager.getInstance().getSession(id);
                    if (last != null) {
                        SessionManager.getInstance().setCurrentSession(last);
                        Platform.runLater(() -> {
                            restoreSessionState(last);
                            loadSessionMessages(last);
                            refreshSessionBarStyle();
                        });
                        logger.info("Restored last session: {} (id={})", last.getName(), last.getId());
                    }
                } catch (NumberFormatException nfe) {
                    logger.warn("Invalid last session id: {}", lastId);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to restore last session", e);
        }
    }

    public void setPrimaryStage(Stage primaryStage) {
        this.primaryStage = primaryStage;
        // Task 5.1: 在 stage shown 时绑定快捷键（此时 scene 已就绪）。
        primaryStage.setOnShown(e -> setupShortcuts());
    }

    /** 绑定设置页列出的全部快捷键；每次 Apply 会先移除旧绑定再按新配置重建。 */
    private void setupShortcuts() {
        if (primaryStage == null || primaryStage.getScene() == null) return;
        Scene scene = primaryStage.getScene();
        AppConfig config = AppConfig.getInstance();

        for (KeyCombination combination : registeredShortcuts) {
            scene.getAccelerators().remove(combination);
        }
        registeredShortcuts.clear();

        bindShortcut(scene, config.getShortcut("copy"), () -> {
            if (terminalController != null) {
                String sel = terminalController.getSelectedText();
                if (sel != null && !sel.isEmpty()) {
                    ClipboardContent content = new ClipboardContent();
                    content.putString(sel);
                    Clipboard.getSystemClipboard().setContent(content);
                }
            }
        });

        bindShortcut(scene, config.getShortcut("paste"), () -> {
            if (terminalController != null && terminalController.isConnected()) {
                String text = Clipboard.getSystemClipboard().getString();
                if (text != null && !text.isEmpty()) {
                    terminalController.sendInput(text);
                }
            }
        });

        bindShortcut(scene, config.getShortcut("find"), this::findInTerminal);
        bindShortcut(scene, config.getShortcut("clear"), () -> {
            if (terminalController != null) {
                terminalController.clearScreen();
            }
        });

        bindShortcut(scene, config.getShortcut("new_tab"), this::showNewSessionDialog);
        bindShortcut(scene, config.getShortcut("close_tab"), () -> {
            Session current = SessionManager.getInstance().getCurrentSession();
            if (current != null) closeSession(current);
        });
        bindShortcut(scene, config.getShortcut("next_tab"), () -> cycleSession(1));
        bindShortcut(scene, config.getShortcut("prev_tab"), () -> cycleSession(-1));

        // "水平分屏"=上下排列；"垂直分屏"=左右排列。
        bindShortcut(scene, config.getShortcut("split_horizontal"),
                () -> setWorkspaceSplit(Orientation.VERTICAL));
        bindShortcut(scene, config.getShortcut("split_vertical"),
                () -> setWorkspaceSplit(Orientation.HORIZONTAL));
        bindShortcut(scene, config.getShortcut("fullscreen"),
                () -> primaryStage.setFullScreen(!primaryStage.isFullScreen()));
        bindShortcut(scene, config.getShortcut("zoom_in"), () -> zoomTerminal(1));
        bindShortcut(scene, config.getShortcut("zoom_out"), () -> zoomTerminal(-1));
        bindShortcut(scene, config.getShortcut("zoom_reset"), () -> setTerminalZoom(14));
    }

    /**
     * 解析快捷键字符串并注册到 scene accelerators。
     * 使用 JavaFX KeyCombination.keyCombination() 解析 "Ctrl+Shift+C" 格式字符串；
     * 解析失败或为空则跳过。
     */
    private void bindShortcut(Scene scene, String shortcut, Runnable action) {
        if (shortcut == null || shortcut.isEmpty()) return;
        try {
            KeyCombination combo = KeyCombination.keyCombination(shortcut);
            scene.getAccelerators().put(combo, action);
            registeredShortcuts.add(combo);
        } catch (Exception e) {
            logger.warn("Failed to bind shortcut '{}': {}", shortcut, e.getMessage());
        }
    }

    private void findInTerminal() {
        if (terminalOutput == null) return;
        TextInputDialog dialog = new TextInputDialog(terminalOutput.getSelectedText());
        dialog.initOwner(primaryStage);
        dialog.setTitle("查找终端输出");
        dialog.setHeaderText(null);
        dialog.setContentText("查找内容:");
        dialog.showAndWait().map(String::trim).filter(s -> !s.isEmpty()).ifPresent(needle -> {
            String haystack = terminalOutput.getText();
            int start = Math.max(0, terminalOutput.getSelection().getEnd());
            int index = haystack.indexOf(needle, start);
            if (index < 0 && start > 0) index = haystack.indexOf(needle);
            if (index >= 0) {
                terminalOutput.selectRange(index, index + needle.length());
                terminalOutput.requestFocus();
            } else {
                updateStatus("未找到: " + needle);
            }
        });
    }

    private void cycleSession(int direction) {
        List<Session> sessions = SessionManager.getInstance().getAllSessions();
        if (sessions.isEmpty()) return;
        Session current = SessionManager.getInstance().getCurrentSession();
        int index = current == null ? -1 : sessions.indexOf(current);
        int next = Math.floorMod(index + direction, sessions.size());
        switchToSession(sessions.get(next));
    }

    private void setWorkspaceSplit(Orientation orientation) {
        if (centerSplitPane == null) return;
        centerSplitPane.setOrientation(orientation);
        Platform.runLater(() -> centerSplitPane.setDividerPosition(0, 0.60));
    }

    private void zoomTerminal(int delta) {
        setTerminalZoom(AppConfig.getInstance().getFontSize() + delta);
    }

    private void setTerminalZoom(int requestedSize) {
        int size = Math.max(8, Math.min(72, requestedSize));
        AppConfig config = AppConfig.getInstance();
        config.setFontSize(size);
        config.saveConfig();
        if (terminalController != null) terminalController.loadSettings();
        applyCommandInputSettings();
        updateStatus("终端缩放: " + size + "px");
    }

    /**
     * 初始化 AI 输入框：Enter 发送，Shift+Enter 换行。
     */
    private void initAiInput() {
        if (aiInput == null) return;
        aiInput.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER && !e.isShiftDown()) {
                e.consume();
                handleSendOrStop();
            }
        });
        setupAutoGrowInput();
    }

    /**
     * AI 输入框自适应增高：随内容行数动态调整高度，封顶后改为内部滚动条。
     * 单行时保持紧凑（56px），多行/换行时按内容增高，达到 220px 后出现滚动条。
     */
    private void setupAutoGrowInput() {
        if (aiInput == null) return;
        final double minHeight = 56.0;
        final double maxHeight = 220.0;
        final double padY = 20.0;   // 上下内边距（10 + 10）
        final double padX = 24.0;   // 左右内边距（12 + 12）

        // 用一个独立 Text 节点按相同字体/换行宽度测量每段实际占用高度
        final Text measure = new Text();
        measure.fontProperty().bind(aiInput.fontProperty());

        Runnable recompute = () -> {
            double width = aiInput.getWidth() - padX;
            if (width <= 0) width = 240;
            measure.setWrappingWidth(width);
            double h = 0;
            for (CharSequence p : aiInput.getParagraphs()) {
                measure.setText(p.length() == 0 ? " " : p.toString());
                h += measure.getLayoutBounds().getHeight();
            }
            h += padY;
            aiInput.setPrefHeight(Math.max(minHeight, Math.min(maxHeight, h)));
        };

        aiInput.textProperty().addListener((obs, o, n) -> Platform.runLater(recompute));
        aiInput.widthProperty().addListener((obs, o, n) -> Platform.runLater(recompute));
        Platform.runLater(recompute);
    }

    /**
     * 初始化 AI 面板拖拽：支持任意文件类型（jar/图片/配置等）拖入。
     * 拖入后文件路径存入 pendingAttachments，在聊天区显示文件列表，
     * 用户随后在输入框指定上传位置或说"部署项目"，发送时 AI 用 upload_file/execute_shell 完成。
     */
    private void initAiPanelDragDrop() {
        if (aiChatStack == null) return;
        // 拖拽悬停：接受任意文件类型
        aiChatStack.setOnDragOver(event -> {
            Dragboard db = event.getDragboard();
            if (db.hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });

        // 拖拽释放：收集文件并加入待上传列表（复用 appendAiAttachments）
        aiChatStack.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
                success = true;
                appendAiAttachments(db.getFiles());
            }
            event.setDropCompleted(success);
            event.consume();
        });
    }

    /**
     * 将文件加入 AI 待上传列表，并在聊天区显示提示气泡、聚焦输入框。
     * 拖拽释放与"上传"按钮均调用此方法。
     */
    private void appendAiAttachments(List<File> files) {
        if (files == null || files.isEmpty()) return;
        pendingAttachments.addAll(files);
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < files.size(); i++) {
            if (i > 0) names.append(", ");
            names.append(files.get(i).getName());
        }
        addChatMessage("user", "📎 已添加文件: " + names
                + "\n请告诉我上传到服务器的哪个位置，或说\"部署项目\"由我决定。");
        if (aiInput != null) aiInput.requestFocus();
    }

    /**
     * "模型配置"按钮：直接打开自定义模型配置对话框（Base URL / API Key / 模型名）。
     */
    @FXML
    private void showCustomModelConfig() {
        try {
            CustomModelConfigDialog dialog = new CustomModelConfigDialog(primaryStage);
            dialog.show();
        } catch (Exception e) {
            showAlert("打开自定义模型配置失败: " + e.getMessage());
        }
    }

    /**
     * "上传"按钮：通过文件选择器选取文件，加入 AI 待上传列表（同拖拽到 AI 面板）。
     */
    @FXML
    private void attachFilesToAi() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择要添加到对话的文件");
        List<File> files = chooser.showOpenMultipleDialog(primaryStage);
        if (files != null && !files.isEmpty()) {
            appendAiAttachments(files);
        }
    }

    // ========== Server Manager Popup ==========

    @FXML
    private void showServerManager() {
        final Dialog<ServerConfig> dialog = new Dialog<>();
        dialog.setTitle("Server Manager");
        dialog.setHeaderText("Manage server connections");

        ButtonType connectBtn = new ButtonType("Connect", ButtonBar.ButtonData.OK_DONE);
        ButtonType closeBtn = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(connectBtn, closeBtn);

        TextField searchField = new TextField();
        searchField.setPromptText("Search servers...");
        searchField.setPrefWidth(350);

        ListView<ServerConfig> serverList = new ListView<>();
        serverList.setPrefHeight(280);
        refreshServerList(serverList, null);

        serverList.setCellFactory(lv -> new ListCell<ServerConfig>() {
            @Override
            protected void updateItem(ServerConfig item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setGraphic(null); }
                else {
                    boolean connected = SshConnectionManager.getInstance().isServerConnected(item.getId());
                    setText((connected ? "[Connected] " : "") + item.getName() + "  (" + item.getHost() + ")");
                    setStyle(connected ? "-fx-text-fill: #0dbc79;" : "-fx-text-fill: #d4d4d4;");
                }
            }
        });

        searchField.textProperty().addListener((obs, old, val) -> refreshServerList(serverList, val));

        serverList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                ServerConfig c = serverList.getSelectionModel().getSelectedItem();
                if (c != null) {
                    Button button = (Button) dialog.getDialogPane().lookupButton(connectBtn);
                    button.fire();
                }
            }
        });

        ContextMenu ctx = new ContextMenu();
        ctx.getItems().add(makeMenuItem("Connect", e -> {
            ServerConfig c = serverList.getSelectionModel().getSelectedItem();
            if (c != null) {
                Button button = (Button) dialog.getDialogPane().lookupButton(connectBtn);
                button.fire();
            }
        }));
        ctx.getItems().add(makeMenuItem("Edit", e -> {
            ServerConfig c = serverList.getSelectionModel().getSelectedItem();
            if (c != null) { dialog.close(); Platform.runLater(() -> addOrEditServer(c)); }
        }));
        ctx.getItems().add(makeMenuItem("Rename", e -> {
            ServerConfig c = serverList.getSelectionModel().getSelectedItem();
            if (c != null) renameServer(c, serverList, searchField);
        }));
        ctx.getItems().add(new SeparatorMenuItem());
        ctx.getItems().add(makeMenuItem("Delete", e -> {
            ServerConfig c = serverList.getSelectionModel().getSelectedItem();
            if (c != null) {
                new Alert(Alert.AlertType.CONFIRMATION, "Delete \"" + c.getName() + "\"?").showAndWait().ifPresent(b -> {
                    if (b == ButtonType.OK) {
                        try { DatabaseManager.getInstance().deleteServerConfig(c.getId()); }
                        catch (Exception ex) { showAlert("Delete failed: " + ex.getMessage()); }
                        serverConfigMap.remove(c.getId()); serverNameMap.remove(c.getName());
                        refreshServerList(serverList, searchField.getText()); loadServerConfigs();
                    }
                });
            }
        }));
        serverList.setContextMenu(ctx);

        Button addBtn = new Button("+ Add Server");
        addBtn.setStyle("-fx-background-color: #0e639c; -fx-text-fill: white; -fx-background-radius: 4; -fx-padding: 6 16; -fx-cursor: hand;");
        addBtn.setOnAction(e -> { dialog.close(); Platform.runLater(() -> addOrEditServer(null)); });

        VBox content = new VBox(8, searchField, serverList, addBtn);
        content.setStyle("-fx-padding: 15;");
        dialog.getDialogPane().setContent(content);

        dialog.setResultConverter(btn -> btn == connectBtn
                ? serverList.getSelectionModel().getSelectedItem() : null);
        dialog.showAndWait().ifPresent(config ->
                openServerSessionAndConnect(config, config.getName(), true));
    }

    private MenuItem makeMenuItem(String text, javafx.event.EventHandler<javafx.event.ActionEvent> h) {
        MenuItem i = new MenuItem(text); i.setOnAction(h); return i;
    }

    private void refreshServerList(ListView<ServerConfig> list, String filter) {
        list.getItems().clear();
        for (Map.Entry<Long, ServerConfig> entry : serverConfigMap.entrySet()) {
            ServerConfig config = entry.getValue();
            if (filter == null || filter.isEmpty() ||
                    config.getName().toLowerCase().contains(filter.toLowerCase()) ||
                    config.getHost().toLowerCase().contains(filter.toLowerCase())) {
                list.getItems().add(config);
            }
        }
    }

    private void renameServer(ServerConfig config, ListView<ServerConfig> list, TextField searchField) {
        TextInputDialog dialog = new TextInputDialog(config.getName());
        dialog.setTitle("Rename Server");
        dialog.setHeaderText("Enter new name for \"" + config.getName() + "\"");
        dialog.setContentText("Name:");
        dialog.showAndWait().ifPresent(newName -> {
            if (newName != null && !newName.isEmpty() && !newName.equals(config.getName())) {
                String oldName = config.getName();
                config.setName(newName);
                try {
                    DatabaseManager.getInstance().saveServerConfig(config);
                    serverNameMap.remove(oldName);
                    serverNameMap.put(newName, config);
                    refreshServerList(list, searchField.getText());
                } catch (Exception e) {
                    config.setName(oldName);
                    showAlert("Rename failed: " + e.getMessage());
                }
            }
        });
    }

    // ========== Connected Server Info ==========

    @FXML
    private void copyServerIp() {
        if (selectedServer != null) {
            ClipboardContent content = new ClipboardContent();
            content.putString(selectedServer.getHost());
            Clipboard.getSystemClipboard().setContent(content);
            updateStatus("IP copied: " + selectedServer.getHost());
        }
    }

    @FXML
    private void showSystemInfo(javafx.scene.input.MouseEvent event) {
        String connKey = getCurrentConnectionKey();
        if (selectedServer == null || connKey == null || !SshConnectionManager.getInstance().isConnected(connKey)) {
            showAlert("Please connect to a server first");
            return;
        }

        new Thread(() -> {
            try {
                String sysInfo = collectSystemInfo(connKey);
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("System Info - " + selectedServer.getName());
                    alert.setHeaderText(selectedServer.getUsername() + "@" + selectedServer.getHost());

                    TextArea textArea = new TextArea(sysInfo);
                    textArea.setEditable(false);
                    textArea.setWrapText(true);
                    textArea.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px;");
                    textArea.setPrefWidth(600);
                    textArea.setPrefHeight(450);
                    alert.getDialogPane().setContent(textArea);
                    alert.setResizable(true);
                    alert.showAndWait();
                });
            } catch (Exception e) {
                Platform.runLater(() -> showAlert("Failed to get system info: " + e.getMessage()));
            }
        }).start();
    }

    private String collectSystemInfo(String connKey) throws Exception {
        StringBuilder sb = new StringBuilder();
        String cmd = "echo '=== HOSTNAME ===' && hostname " +
                "&& echo '=== KERNEL ===' && uname -a " +
                "&& echo '=== OS ===' && cat /etc/os-release 2>/dev/null | head -5 " +
                "&& echo '=== CPU ===' && (lscpu 2>/dev/null | grep -E 'Model name|CPU.s.|Architecture|MHz' || cat /proc/cpuinfo 2>/dev/null | head -10) " +
                "&& echo '=== MEMORY ===' && free -h " +
                "&& echo '=== DISK ===' && df -h " +
                "&& echo '=== NETWORK ===' && ip -4 addr show 2>/dev/null | grep inet || ifconfig 2>/dev/null | grep inet " +
                "&& echo '=== UPTIME ===' && uptime " +
                "&& echo '=== TOP PROCESSES ===' && ps aux --sort=-%cpu | head -8";

        String result = SshConnectionManager.getInstance().executeCommand(connKey, cmd);

        String[] sections = result.split("===");
        for (int i = 1; i < sections.length; i += 2) {
            if (i + 1 < sections.length) {
                String key = sections[i].trim();
                String value = sections[i + 1].trim();
                sb.append("── ").append(key).append(" ──\n");
                sb.append(value).append("\n\n");
            }
        }
        return sb.toString();
    }

    private void updateConnectedServerInfo() {
        String connKey = getCurrentConnectionKey();
        if (selectedServer != null && connKey != null && SshConnectionManager.getInstance().isConnected(connKey)) {
            connectedIpLabel.setText(selectedServer.getHost());
            sysInfoBrief.setText(selectedServer.getUsername() + "@" + selectedServer.getHost());
        } else {
            connectedIpLabel.setText("--");
            sysInfoBrief.setText("Click for details");
        }
    }

    // ========== Server Config Management ==========

    private void loadServerConfigs() {
        try {
            List<ServerConfig> configs = DatabaseManager.getInstance().getAllServerConfigs();
            serverConfigMap.clear();
            serverNameMap.clear();

            for (ServerConfig config : configs) {
                serverConfigMap.put(config.getId(), config);
                serverNameMap.put(config.getName(), config);
            }
        } catch (Exception e) {
            logger.error("Failed to load server configs", e);
        }
    }

    @FXML
    private void addServer() {
        addOrEditServer(null);
    }

    private void addOrEditServer(ServerConfig existing) {
        boolean editing = existing != null;
        Dialog<ServerConfig> dialog = new Dialog<>();
        dialog.setTitle(editing ? "Edit Server" : "New Server Connection");
        dialog.setHeaderText(editing ? "Edit server connection" : "Add a new server connection");

        ButtonType saveBtn = new ButtonType(editing ? "Save" : "Connect", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtn, ButtonType.CANCEL);

        TextField nameField = new TextField(editing ? existing.getName() : "");
        nameField.setPromptText("Server name");
        nameField.setPrefWidth(250);
        TextField hostField = new TextField(editing ? existing.getHost() : "");
        hostField.setPromptText("192.168.1.100");
        TextField portField = new TextField(editing ? String.valueOf(existing.getPort()) : "22");
        TextField userField = new TextField(editing ? existing.getUsername() : "");
        userField.setPromptText("root");
        PasswordToggleField passField = new PasswordToggleField();
        passField.setPromptText("Password");
        if (editing && existing.getPassword() != null) passField.setText(existing.getPassword());
        TextField keyPathField = new TextField(editing ? existing.getPrivateKeyPath() : "");
        keyPathField.setPromptText("Key path (optional)");
        TextField logPathField = new TextField(editing ? existing.getLogPath() : "/var/log");
        TextField intervalField = new TextField(editing ? String.valueOf(existing.getMonitorInterval()) : "5");

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setMinWidth(380);
        grid.setPadding(new Insets(10));
        grid.add(new Label("Name:"), 0, 0); grid.add(nameField, 1, 0);
        grid.add(new Label("Host:"), 0, 1); grid.add(hostField, 1, 1);
        grid.add(new Label("Port:"), 0, 2); grid.add(portField, 1, 2);
        grid.add(new Label("User:"), 0, 3); grid.add(userField, 1, 3);
        grid.add(new Label("Password:"), 0, 4); grid.add(passField, 1, 4);
        grid.add(new Label("Key Path:"), 0, 5); grid.add(keyPathField, 1, 5);
        grid.add(new Label("Log Path:"), 0, 6); grid.add(logPathField, 1, 6);
        grid.add(new Label("Interval(min):"), 0, 7); grid.add(intervalField, 1, 7);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().setPrefWidth(450);

        dialog.setResultConverter(btn -> {
            if (btn == saveBtn) {
                if (nameField.getText().isEmpty() || hostField.getText().isEmpty()) return null;
                ServerConfig c;
                if (editing) {
                    c = existing;
                    c.setName(nameField.getText());
                    c.setHost(hostField.getText());
                } else {
                    c = new ServerConfig(nameField.getText(), hostField.getText(),
                            Integer.parseInt(portField.getText()), userField.getText(), passField.getText());
                }
                c.setPort(Integer.parseInt(portField.getText()));
                c.setUsername(userField.getText());
                c.setPassword(passField.getText());
                c.setPrivateKeyPath(keyPathField.getText());
                c.setLogPath(logPathField.getText());
                c.setMonitorInterval(Integer.parseInt(intervalField.getText()));
                return c;
            }
            return null;
        });

        dialog.showAndWait().ifPresent(config -> {
            if (config != null) {
                try {
                    DatabaseManager.getInstance().saveServerConfig(config);
                    loadServerConfigs();
                    if (!editing) openServerSessionAndConnect(config, config.getName(), true);
                } catch (Exception e) {
                    logger.error("Failed to save server", e);
                    showAlert("Save failed: " + e.getMessage());
                }
            }
        });
    }

    @FXML
    private void connectSelectedServer() {
        if (selectedServer != null) {
            openServerSessionAndConnect(selectedServer, selectedServer.getName(), true);
        } else {
            showAlert("Please select a server from Servers menu");
        }
    }

    /**
     * 所有“选择服务器并连接”交互的唯一入口。它在网络线程启动前同步创建并激活标签、
     * 初始化会话状态，因此即使 SSH 很慢或失败，用户也能立即看到目标标签和连接状态。
     */
    private Session openServerSessionAndConnect(ServerConfig config, String requestedName, boolean forceNewTab) {
        Objects.requireNonNull(config, "config");
        if (aiGenerating) {
            showAlert("AI 正在当前可见终端中工作，请先停止生成再连接其他服务器。");
            return null;
        }
        saveCurrentSessionState();
        if (currentConnectionKey != null) TerminalBridgeHelper.teardownBridge(currentConnectionKey);
        if (terminalController != null) terminalController.detachForSessionSwitch();
        currentConnectionKey = null;
        stopLeftMonitorRefresh();

        Session session = SessionManager.getInstance().getCurrentSession();
        if (forceNewTab || session == null) {
            String name = requestedName == null || requestedName.isBlank()
                    ? config.getName() : requestedName.trim();
            session = SessionManager.getInstance().createSession(name, config.getId());
        }
        if (session == null || session.getId() == null) {
            showAlert("无法创建会话，请检查本地数据库后重试");
            return null;
        }

        selectedServer = config;
        SessionManager.getInstance().setCurrentSession(session);
        SessionWorkspaceState.Data data = workspaceState.get(session.getId());
        data.serverId = config.getId();
        data.serverConfig = config;
        data.remotePath = "/";
        workspaceState.setRemotePath(session.getId(), "/");

        loadSessions();
        refreshSessionBarStyle();
        loadSessionMessages(session);
        updateStatus("Connecting: " + config.getName() + "...");

        connectToServer(session, config);
        return session;
    }

    private void connectToServer(Session session, ServerConfig config) {
        connectToServerWithRetry(session, config, 3);
    }

    /**
     * 带密码重试的连接。当 SSH 认证失败（AUTH_FAILED）时弹出密码重输对话框，
     * 用户输入新密码后递归重试，最多 maxRetries 次；取消则终止。
     */
    private void connectToServerWithRetry(Session targetSession, ServerConfig config, int retriesLeft) {
        updateStatus("Connecting: " + config.getName() + "...");
        final String connKey = SshConnectionManager.connectionKey(targetSession.getId(), config.getId());

        new Thread(() -> {
            try {
                SshConnectionManager.getInstance().connect(connKey, config);
                ChannelShell channel = SshConnectionManager.getInstance().openShell(connKey);

                Platform.runLater(() -> {
                    // The user may close the tab while the network connection is
                    // still in progress.  Never resurrect its workspace or leak
                    // the newly-created SSH session after that point.
                    if (SessionManager.getInstance().getSession(targetSession.getId()) == null) {
                        try { channel.disconnect(); } catch (Exception ignored) { }
                        SshConnectionManager.getInstance().disconnect(connKey);
                        return;
                    }
                    // 用户若在连接期间切到了别的标签，仍保留 SSH/监控，但不覆盖当前可见终端。
                    Session visible = SessionManager.getInstance().getCurrentSession();
                    boolean targetVisible = visible != null && targetSession.getId().equals(visible.getId());

                    SessionWorkspaceState.Data data = workspaceState.get(targetSession.getId());
                    data.serverId = config.getId();
                    data.serverConfig = config;
                    data.connectionKey = connKey;
                    data.remotePath = workspaceState.remotePath(targetSession.getId());

                    MonitorService.getInstance().startMonitoring(config, connKey);
                    if (!targetVisible) {
                        try { channel.disconnect(); } catch (Exception ignored) { }
                        if (AppConfig.getInstance().isAutoSelectTab()) {
                            switchToSession(targetSession);
                            return;
                        }
                        updateStatus("Connected in background: " + config.getName());
                        return;
                    }

                    selectedServer = config;
                    terminalController.setServerInfo(config.getName(), config.getHost(), config.getUsername());
                    terminalController.connect(connKey, channel);

                    // AI与服务器联动：注册终端事件监听
                    currentConnectionKey = connKey;
                    TerminalBridgeHelper.setupBridge(connKey, terminalController, this);
                    updateAiTerminalStatus(TerminalController.AiBusyState.IDLE, null);
                    // 终端+AI 联动：连接成功后在 AI 对话区加系统气泡，告知用户终端与 AI 已同步就绪
                    addChatMessage("assistant",
                        "✅ 已连接到服务器 " + config.getName()
                        + " (" + config.getUsername() + "@" + config.getHost() + ")，终端已就绪。\n"
                        + "我可以在可见终端里执行命令并读取反馈，你可以实时看到每条命令的输入与输出。"
                        + "需要我做什么，直接说就行。");
                    // 后台拉取服务器上下文
                    ServerContextHelper.fetchQuickContext(connKey, config.getId(), targetSession.getId());
                    startLeftMonitorRefresh(config);

                    updateStatus("Connected: " + config.getName());
                    serverInfo.setText(config.getUsername() + "@" + config.getHost());

                    updateConnectedServerInfo();
                    loadRemoteDirectory("/");
                    // Refresh directory tree when connected
                    loadTreeChildren(rootTreeItem, "/");

                    // 连接成功后自动聚焦底部命令输入框，方便立即键入命令
                    commandInput.requestFocus();
                });
            } catch (Exception e) {
                if (SessionManager.getInstance().getSession(targetSession.getId()) == null) {
                    SshConnectionManager.getInstance().disconnect(connKey);
                    return;
                }
                String emsg = e.getMessage() == null ? "" : e.getMessage();
                // 认证失败（密码错误）：弹密码重输对话框，用户输入新密码后重试
                if (emsg.startsWith("AUTH_FAILED:") && retriesLeft > 0) {
                    final String detail = emsg.substring("AUTH_FAILED:".length());
                    Platform.runLater(() -> {
                        PasswordRetryDialog dlg = new PasswordRetryDialog(
                                config.getHost(), config.getUsername(), detail);
                        dlg.initOwner(primaryStage);
                        java.util.Optional<String> newPwd = dlg.showAndWait();
                        if (newPwd.isPresent() && !newPwd.get().isEmpty()) {
                            config.setPassword(newPwd.get());
                            // 同步最新密码到数据库，避免下次连接仍用旧密码
                            try {
                                DatabaseManager.getInstance().saveServerConfig(config);
                            } catch (Exception saveEx) {
                                logger.warn("重试密码保存失败（不影响本次连接）: {}", saveEx.getMessage());
                            }
                            // 用新密码重试，递减剩余次数
                            connectToServerWithRetry(targetSession, config, retriesLeft - 1);
                        } else {
                            updateStatus("连接已取消");
                        }
                    });
                    logger.warn("SSH 认证失败，等待用户重输密码: {} host={}", emsg, config.getHost());
                    return;
                }
                Platform.runLater(() -> {
                    if (emsg.startsWith("HOST_KEY_CHANGED:")) {
                        // P0-7: 主机密钥变更，可能是中间人攻击，给出明确安全提示而非通用连接失败
                        Alert alert = new Alert(Alert.AlertType.WARNING);
                        alert.setTitle("安全警告：主机密钥变更");
                        alert.setHeaderText("服务器 " + config.getHost() + " 的主机密钥已变更");
                        alert.setContentText(
                            "连接失败：该服务器的主机密钥与之前记录不一致。\n\n"
                            + "⚠️ 这可能意味着中间人攻击（MITM），或服务器已重装/SSH 服务重配置。\n\n"
                            + "建议：联系服务器管理员确认此次变更是否预期。\n"
                            + "若确认安全，请删除本地 known_hosts 中该主机的旧记录后重试连接。\n\n"
                            + "原始信息：" + emsg.substring("HOST_KEY_CHANGED:".length()));
                        alert.initOwner(primaryStage);
                        alert.showAndWait();
                        updateStatus("连接失败：主机密钥变更（潜在安全风险）");
                    } else if (emsg.startsWith("AUTH_FAILED:")) {
                        // 重试次数耗尽，按普通失败处理并提示
                        updateStatus("连接失败：认证错误次数过多");
                        showAlert("连接失败：密码或密钥认证被拒绝（已重试 " + (3 - retriesLeft) + " 次）。\n"
                                + "请检查服务器密码/密钥配置后重试。\n\n原始信息：" + emsg.substring("AUTH_FAILED:".length()));
                    } else {
                        updateStatus("Connection failed: " + emsg);
                        showAlert("Connection failed: " + emsg);
                    }
                });
                logger.error("Failed to connect", e);
            }
        }).start();
    }

    @FXML
    private void disconnectServer() {
        if (aiGenerating) stopAiGeneration();
        if (selectedServer != null) {
            String connKey = getCurrentConnectionKey();
            if (connKey != null) {
                // 清理终端事件监听和服务器上下文
                if (currentConnectionKey != null) {
                    TerminalBridgeHelper.teardownBridge(currentConnectionKey);
                    currentConnectionKey = null;
                }
                // 终端+AI 联动：断开后在 AI 对话区加系统气泡
                addChatMessage("assistant",
                    "⚠️ 已断开服务器 " + selectedServer.getName()
                    + "，终端已关闭。重新连接后我可继续协助。");
                Session current = SessionManager.getInstance().getCurrentSession();
                if (current != null) {
                    ServerContextHelper.clearContext(current.getId());
                    workspaceState.get(current.getId()).connectionKey = null;
                }

                terminalController.disconnect();
                updateAiTerminalStatus(TerminalController.AiBusyState.IDLE, null);
                // 修复项5：标记为用户主动断开，状态回调据此跳过"意外断连"AI 气泡（此处已有专属断开气泡）
                userInitiatedDisconnect = true;
                SshConnectionManager.getInstance().disconnect(connKey);
                MonitorService.getInstance().releaseConnection(selectedServer.getId(), connKey);
            }
            stopLeftMonitorRefresh();
            updateStatus("Disconnected: " + selectedServer.getName());
            serverInfo.setText("");
            updateConnectedServerInfo();
        }
    }

    /**
     * 修复项5（AI 与终端同步）：终端断连时的同步自动重连。
     * <p>由 {@link TerminalCommandBridge} 在 AI 工具（run_in_terminal）检测到终端断连时调用一次。
     * 策略：优先在仍存活的 SSH 会话上开新 shell 通道（常见场景：仅 shell 通道断开、会话仍在）；
     * 若会话也断了则全量重连。网络部分在调用线程（AI 后台线程）执行，
     * {@link TerminalController#connect} 涉及 JavaFX Timeline/listener，切到 FX 线程执行并等待。</p>
     *
     * <p><b>已知限制</b>：重连后 cwd/环境变量重置为登录默认值（AI 可自行 cd 恢复）。
     * 任何异常都视为重连失败，由 Bridge 统一返回 ERR_DISCONNECTED 并要求恢复可见终端。</p>
     *
     * @param sshKey SSH 连接键
     * @return true 表示重连成功且终端已就绪
     */
    private boolean reconnectTerminalSync(String sshKey) {
        if (selectedServer == null || sshKey == null) return false;
        try {
            ServerConfig config = selectedServer;
            ChannelShell channel;
            try {
                // 优先：会话仍存活，仅 shell 通道断开 → 开新通道（SshConnectionManager.connect 幂等）
                channel = SshConnectionManager.getInstance().openShell(sshKey);
            } catch (Exception openEx) {
                // 会话也断了 → 全量重连
                logger.debug("openShell 失败，尝试全量重连: {}", openEx.getMessage());
                SshConnectionManager.getInstance().connect(sshKey, config);
                channel = SshConnectionManager.getInstance().openShell(sshKey);
            }
            final ChannelShell ch = channel;
            final CountDownLatch latch = new CountDownLatch(1);
            final boolean[] ok = {false};
            // connect() 内部创建 Timeline / 注册 listener，必须在 FX 线程执行
            Platform.runLater(() -> {
                try {
                    terminalController.connect(sshKey, ch);
                    ok[0] = terminalController.isConnected();
                } catch (Exception e) {
                    logger.warn("重连后终端初始化失败: {}", e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
            latch.await(8, TimeUnit.SECONDS);
            if (ok[0]) {
                Platform.runLater(() -> addChatMessage("assistant",
                    "🔄 终端已自动重连，可继续操作。（注意：工作目录已重置为登录默认目录）"));
            }
            return ok[0];
        } catch (Exception e) {
            logger.warn("自动重连失败: {}", e.getMessage());
            return false;
        }
    }

    // ========== Session Bar ==========

    private void initSessionBar() {
        sessionBar.getChildren().clear();
        // Add "+" button at the start
        Button addBtn = new Button("+");
        addBtn.getStyleClass().add("session-add-btn");
        addBtn.setOnAction(e -> showNewSessionDialog());
        sessionBar.getChildren().add(addBtn);
    }

    private void loadSessions() {
        // Keep the "+" button, remove everything else
        javafx.scene.Node addBtn = sessionBar.getChildren().get(0);
        sessionBar.getChildren().clear();
        sessionBar.getChildren().add(addBtn);

        List<Session> sessions = SessionManager.getInstance().getAllSessions();
        Session current = SessionManager.getInstance().getCurrentSession();

        for (Session s : sessions) {
            SessionWorkspaceState.Data data = workspaceState.get(s.getId());
            if (data.serverId == null && s.getServerId() != null) {
                data.serverId = s.getServerId();
                data.serverConfig = serverConfigMap.get(s.getServerId());
            }
            HBox tab = createSessionTab(s, current != null && current.getId().equals(s.getId()));
            sessionBar.getChildren().add(tab);
        }
        logger.info("loadSessions: {} sessions loaded, sessionBar children={}",
                sessions.size(), sessionBar.getChildren().size());
    }

    private HBox createSessionTab(Session session, boolean active) {
        HBox tab = new HBox(0);
        tab.setAlignment(Pos.CENTER);
        tab.getStyleClass().add("session-tab");
        // 用 session ID 作为 userData，供 refreshSessionBarStyle 按 ID 判断 active（避免同名误判）
        tab.setUserData(session.getId());
        tab.pseudoClassStateChanged(ACTIVE_PC, active);

        Label nameLabel = new Label(session.getName());
        nameLabel.getStyleClass().add("session-tab-label");
        nameLabel.setPadding(new Insets(0, 4, 0, 8));

        Button closeBtn = new Button("x");
        closeBtn.getStyleClass().add("session-tab-close");
        closeBtn.setPadding(new Insets(0, 6, 0, 0));
        closeBtn.setOnAction(e -> closeSession(session));

        tab.getChildren().addAll(nameLabel, closeBtn);

        tab.setOnMouseClicked(e -> {
            if (e.getTarget() == closeBtn) return;
            switchToSession(session);
        });

        return tab;
    }

    private void switchToSession(Session session) {
        if (session == null) return;
        Session previous = SessionManager.getInstance().getCurrentSession();
        if (previous != null && Objects.equals(previous.getId(), session.getId())) {
            refreshSessionBarStyle();
            return;
        }
        if (aiGenerating) {
            showStatusFeedback("AI 正在当前可见终端中工作；请先点击“停止”再切换标签");
            return;
        }
        // Save current session state
        saveCurrentSessionState();

        if (currentConnectionKey != null) {
            TerminalBridgeHelper.teardownBridge(currentConnectionKey);
        }
        if (terminalController != null) terminalController.detachForSessionSwitch();
        currentConnectionKey = null;
        stopLeftMonitorRefresh();

        // Switch to new session
        SessionManager.getInstance().setCurrentSession(session);

        // Restore new session state
        restoreSessionState(session);

        // Update UI
        loadSessionMessages(session);
        refreshSessionBarStyle();
    }

    private void saveCurrentSessionState() {
        Session current = SessionManager.getInstance().getCurrentSession();
        if (current == null) return;

        SessionWorkspaceState.Data data = workspaceState.get(current.getId());
        data.terminalText = terminalOutput.getText();
        data.remotePath = workspaceState.remotePath(current.getId());
        if (selectedServer != null) {
            data.serverId = selectedServer.getId();
            data.serverConfig = selectedServer;
        }
    }

    private void restoreSessionState(Session session) {
        SessionWorkspaceState.Data data = workspaceState.get(session.getId());

        // Restore terminal text
        terminalOutput.setText(data.terminalText);
        terminalOutput.setScrollTop(Double.MAX_VALUE);

        // Restore remote path
        workspaceState.setRemotePath(session.getId(), data.remotePath);

        // Restore server connection state
        if (data.serverConfig != null) {
            selectedServer = data.serverConfig;
            updateConnectedServerInfo();
            serverInfo.setText(data.serverConfig.getUsername() + "@" + data.serverConfig.getHost());

            // 恢复该会话的实际连接键（连接时存储的 connKey），避免会话切换后 currentConnectionKey
            // 仍指向旧会话的连接键，导致 AI/SFTP 命中错误连接。连接已失效则置 null。
            if (data.connectionKey != null && SshConnectionManager.getInstance().isConnected(data.connectionKey)) {
                currentConnectionKey = data.connectionKey;
                MonitorService.getInstance().startMonitoring(data.serverConfig, data.connectionKey);
                startLeftMonitorRefresh(data.serverConfig);
                attachTerminalForSession(session, data);
            } else {
                currentConnectionKey = null;
                stopLeftMonitorRefresh();
            }
        } else {
            selectedServer = null;
            currentConnectionKey = null;
            updateConnectedServerInfo();
            serverInfo.setText("");
            stopLeftMonitorRefresh();
        }
    }

    /** 为刚激活的标签创建一个新的可见 PTY，并恢复该标签自己的终端历史。 */
    private void attachTerminalForSession(Session session, SessionWorkspaceState.Data data) {
        final String connKey = data.connectionKey;
        final ServerConfig config = data.serverConfig;
        if (connKey == null || config == null) return;
        Thread thread = new Thread(() -> {
            try {
                ChannelShell channel = SshConnectionManager.getInstance().openShell(connKey);
                Platform.runLater(() -> {
                    Session visible = SessionManager.getInstance().getCurrentSession();
                    if (visible == null || !Objects.equals(visible.getId(), session.getId())) {
                        channel.disconnect();
                        return;
                    }
                    selectedServer = config;
                    currentConnectionKey = connKey;
                    terminalController.setServerInfo(config.getName(), config.getHost(), config.getUsername());
                    terminalController.connect(connKey, channel, data.terminalText);
                    TerminalBridgeHelper.setupBridge(connKey, terminalController, this);
                    updateAiTerminalStatus(TerminalController.AiBusyState.IDLE, null);
                    updateConnectedServerInfo();
                    serverInfo.setText(config.getUsername() + "@" + config.getHost());
                    loadRemoteDirectory(data.remotePath == null ? "/" : data.remotePath);
                });
            } catch (Exception ex) {
                logger.warn("恢复会话终端失败: session={} key={}: {}",
                        session.getId(), connKey, ex.getMessage());
                Platform.runLater(() -> updateStatus("会话 SSH 仍存在，但终端恢复失败: " + ex.getMessage()));
            }
        }, "SessionTerminal-" + session.getId());
        thread.setDaemon(true);
        thread.start();
    }

    private void refreshSessionBarStyle() {
        // 按 session ID 判断 active（替代旧的 name 比较，避免同名会话同时高亮）
        Session current = SessionManager.getInstance().getCurrentSession();
        Long currentId = current != null ? current.getId() : null;
        for (javafx.scene.Node node : sessionBar.getChildren()) {
            if (node instanceof HBox) {
                HBox tab = (HBox) node;
                Object data = tab.getUserData();
                boolean isActive = currentId != null && data != null && currentId.equals(data);
                tab.pseudoClassStateChanged(ACTIVE_PC, isActive);
            }
        }
    }

    // ========== New Session Dialog ==========

    private void showNewSessionDialog() {
        if (aiGenerating) {
            showStatusFeedback("AI 正在当前可见终端中工作；请先点击“停止”再新建标签");
            return;
        }
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle("New Session");
        dialog.setHeaderText("Create a new chat session");

        ButtonType createBtn = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtn = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(createBtn, cancelBtn);

        // Session name
        TextField nameField = new TextField("Chat " + java.time.LocalTime.now().toString().substring(0, 5));
        nameField.setPromptText("Session name");

        // Quick Connect section
        Label quickConnectLabel = new Label("Quick Connect");
        quickConnectLabel.setStyle("-fx-text-fill: #0dbc79; -fx-font-weight: bold; -fx-font-size: 12px;");

        // Server list
        ListView<ServerConfig> serverList = new ListView<>();
        serverList.setPrefHeight(200);
        serverList.setCellFactory(lv -> new ListCell<ServerConfig>() {
            @Override
            protected void updateItem(ServerConfig item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    boolean connected = SshConnectionManager.getInstance().isServerConnected(item.getId());
                    String prefix = connected ? "[Connected] " : "";
                    setText(prefix + item.getName() + "  (" + item.getHost() + ")");
                    setStyle(connected ? "-fx-text-fill: #0dbc79;" : "-fx-text-fill: #d4d4d4;");
                }
            }
        });

        // Load servers
        for (Map.Entry<Long, ServerConfig> entry : serverConfigMap.entrySet()) {
            serverList.getItems().add(entry.getValue());
        }

        // Double-click to create session and connect
        serverList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                ServerConfig config = serverList.getSelectionModel().getSelectedItem();
                if (config != null) {
                    String name = nameField.getText().isEmpty() ? config.getName() : nameField.getText();
                    dialog.setResult(false);
                    dialog.close();
                    Platform.runLater(() -> openServerSessionAndConnect(config, name, true));
                }
            }
        });

        Label hintLabel = new Label("Double-click a server to connect");
        hintLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 10px;");

        VBox content = new VBox(8,
                new Label("Session Name:"), nameField,
                new Separator(),
                quickConnectLabel, hintLabel, serverList
        );
        content.setStyle("-fx-padding: 15; -fx-pref-width: 400;");
        dialog.getDialogPane().setContent(content);

        dialog.setResultConverter(btn -> {
            if (btn == createBtn) {
                return true;
            }
            return false;
        });

        dialog.showAndWait().ifPresent(result -> {
            if (result) {
                ServerConfig config = serverList.getSelectionModel().getSelectedItem();
                // 用户没输入会话名时默认用服务器名（若有），否则回退 Chat + 当前时间
                String name;
                if (!nameField.getText().isEmpty()) {
                    name = nameField.getText();
                } else if (config != null) {
                    name = config.getName();
                } else {
                    name = "Chat " + java.time.LocalTime.now().toString().substring(0, 5);
                }

                if (config != null) {
                    openServerSessionAndConnect(config, name, true);
                } else {
                    createBlankSessionAndActivate(name);
                }
            }
        });
    }

    @FXML
    private void newSession() {
        showNewSessionDialog();
    }

    /**
     * Create an unconnected chat tab through the same session-switch lifecycle
     * used by existing tabs. This saves/detaches the old PTY, clears its visible
     * terminal bridge, and restores an empty per-session workspace so a blank
     * chat can never inherit the previous server connection.
     */
    private Session createBlankSessionAndActivate(String name) {
        Session session = SessionManager.getInstance().createSession(name, null);
        if (session == null || session.getId() == null) {
            showAlert("无法创建会话，请检查本地数据库后重试");
            return null;
        }
        switchToSession(session);
        loadSessions();
        refreshSessionBarStyle();
        updateStatus("New session: " + name);
        return session;
    }

    private void closeSession(Session session) {
        if (aiGenerating && activeAiSession != null
                && Objects.equals(activeAiSession.getId(), session.getId())) {
            showStatusFeedback("AI 正在此标签中工作；请先点击“停止”再关闭标签");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Close Session");
        confirm.setHeaderText(null);
        confirm.setContentText("Close session \"" + session.getName() + "\"?");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                Session current = SessionManager.getInstance().getCurrentSession();
                boolean closingCurrent = current != null && current.getId().equals(session.getId());
                SessionWorkspaceState.Data removed = workspaceState.remove(session.getId());

                if (removed != null && removed.connectionKey != null) {
                    TerminalBridgeHelper.teardownBridge(removed.connectionKey);
                    SshConnectionManager.getInstance().disconnect(removed.connectionKey);
                    if (removed.serverId != null) {
                        MonitorService.getInstance().releaseConnection(
                                removed.serverId, removed.connectionKey);
                    }
                }
                if (closingCurrent) {
                    if (terminalController != null) terminalController.detachForSessionSwitch();
                    currentConnectionKey = null;
                    stopLeftMonitorRefresh();
                }

                SessionManager.getInstance().deleteSession(session.getId());
                if (closingCurrent) {
                    SessionManager.getInstance().setCurrentSession(null);
                    clearAiChat();
                    terminalOutput.clear();
                    selectedServer = null;
                    updateConnectedServerInfo();
                }
                loadSessions();
                if (closingCurrent) {
                    List<Session> remaining = SessionManager.getInstance().getAllSessions();
                    if (!remaining.isEmpty()) switchToSession(remaining.get(0));
                }
            }
        });
    }

    private void loadSessionMessages(Session session) {
        clearAiChat();
        if (session == null) return;
        for (var msg : session.getMessages()) {
            addChatMessage(msg.getRole(), msg.getContent());
        }
    }

    // ========== Model Selector ==========

    private void initModelAgentSelectors() {
        // 设置单元格工厂，添加Tooltip显示模型能力
        modelSelector.setCellFactory(lv -> new ListCell<String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(item);
                    ModelInfo model = findModelByDisplayName(allModels, item);
                    if (model != null) {
                        setTooltip(new Tooltip(buildModelTooltip(model)));
                    }
                }
            }
        });

        modelSelector.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                ModelInfo mi = findModelByDisplayName(allModels, newVal);
                String clean = newVal.replace(" ★", "").trim();
                String provider;
                String model;
                if (mi != null) {
                    provider = mi.getProvider();
                    model = mi.getId();
                } else {
                    int slash = clean.indexOf('/');
                    provider = slash > 0 ? clean.substring(0, slash) :
                            AiConfigManager.getInstance().getActiveProvider();
                    model = slash > 0 ? clean.substring(slash + 1) : clean;
                }
                // 聚合选择器显示 provider/model，但 API 的 model 字段只能保存纯模型 ID。
                // 同时切换 provider，避免选了其他供应商模型却仍向旧供应商发送请求。
                AiConfigManager config = AiConfigManager.getInstance();
                if (!provider.equals(config.getActiveProvider())) {
                    config.setActiveProvider(provider);
                }
                if (!model.equals(config.getActiveModel())) {
                    config.setActiveModel(model);
                }
                modelInfo.setText(provider + "/" + model);
                // 更新选择器Tooltip显示当前模型能力
                if (mi != null) {
                    modelSelector.setTooltip(new Tooltip(buildModelTooltip(mi)));
                }
            }
        });

        // 先立即显示已保存模型，远端 /models 聚合放到后台线程，避免启动时阻塞 JavaFX 线程。
        AiConfigManager config = AiConfigManager.getInstance();
        String provider = config.getActiveProvider();
        String activeModel = normalizeStoredModelId(provider, config.getActiveModel());
        if (!activeModel.isEmpty()) {
            allModels = new ArrayList<>(List.of(
                    new ModelInfo(activeModel, activeModel, provider, false)));
            refreshModelSelector();
            modelSelector.getSelectionModel().select(provider + "/" + activeModel);
        }
        modelSelector.setPromptText("正在加载模型…");
        modelSelector.setDisable(true);

        Thread modelLoader = new Thread(() -> {
            List<ModelInfo> fetched;
            try {
                fetched = AiServiceClient.getInstance().listAllModels();
            } catch (Exception e) {
                logger.warn("后台加载模型列表失败: {}", e.getMessage());
                fetched = new ArrayList<>();
            }
            List<ModelInfo> loadedModels = fetched;
            Platform.runLater(() -> {
                AiConfigManager currentConfig = AiConfigManager.getInstance();
                String currentProvider = currentConfig.getActiveProvider();
                String currentModel = normalizeStoredModelId(
                        currentProvider, currentConfig.getActiveModel());

                List<ModelInfo> merged = new ArrayList<>(loadedModels);
                boolean hasActive = merged.stream().anyMatch(m ->
                        currentProvider.equals(m.getProvider()) && currentModel.equals(m.getId()));
                // /models 不可用时也保留用户手工配置的模型，聊天能力不应依赖模型枚举接口成功。
                if (!currentModel.isEmpty() && !hasActive) {
                    merged.add(new ModelInfo(currentModel, currentModel, currentProvider, false));
                }
                allModels = merged;
                refreshModelSelector();
                String display = currentProvider + "/" + currentModel;
                if (!currentModel.isEmpty()) {
                    for (String item : modelSelector.getItems()) {
                        if (item.replace(" ★", "").trim().equals(display)) {
                            modelSelector.getSelectionModel().select(item);
                            break;
                        }
                    }
                }
                modelSelector.setDisable(false);
                modelSelector.setPromptText("选择模型");
            });
        }, "AI-Model-Loader");
        modelLoader.setDaemon(true);
        modelLoader.start();
    }

    /** 兼容旧版本把 provider/model 整串误存进 active_model 的配置。 */
    private String normalizeStoredModelId(String provider, String storedModel) {
        if (storedModel == null) return "";
        String prefix = provider == null ? "" : provider + "/";
        if (!prefix.isEmpty() && storedModel.startsWith(prefix)) {
            String normalized = storedModel.substring(prefix.length());
            AiConfigManager.getInstance().setActiveModel(normalized);
            return normalized;
        }
        return storedModel;
    }

    /**
     * 刷新模型选择器列表：展示所有已聚合的可用模型（不再筛选，筛选栏已移除）。
     */
    private void refreshModelSelector() {
        ObservableList<String> modelNames = FXCollections.observableArrayList();
        for (ModelInfo m : allModels) {
            modelNames.add(m.getProvider() + "/" + m.getId() + (m.isFree() ? " ★" : ""));
        }
        modelSelector.setItems(modelNames);
    }

    /**
     * 根据显示名称查找模型信息
     */
    private ModelInfo findModelByDisplayName(List<ModelInfo> models, String displayName) {
        String clean = displayName.replace(" ★", "").trim();
        for (ModelInfo m : models) {
            if ((m.getProvider() + "/" + m.getId()).equals(clean)) {
                return m;
            }
        }
        return null;
    }

    /**
     * 构建模型能力Tooltip文本
     */
    private String buildModelTooltip(ModelInfo m) {
        return String.format(
                "%s/%s\n" +
                "提供商: %s\n" +
                "上下文窗口: %,d tokens\n" +
                "最大输出: %,d tokens\n" +
                "工具调用: %s | 视觉: %s | 流式: %s\n" +
                "费用: $%.4f/1K输入, $%.4f/1K输出%s",
                m.getProvider(), m.getId(),
                m.getProvider(),
                m.getContextWindow(),
                m.getMaxOutputTokens(),
                m.supportsTools() ? "支持" : "不支持",
                m.supportsVision() ? "支持" : "不支持",
                m.supportsStreaming() ? "支持" : "不支持",
                m.getInputCostPer1k(),
                m.getOutputCostPer1k(),
                m.isFree() ? " [免费]" : ""
        );
    }

    @FXML
    private void showSettings() {
        SettingsDialog dialog = new SettingsDialog(primaryStage);
        dialog.addOnSaveCallback(this::applySettings);
        dialog.show();
    }

    /**
     * Apply settings changes to running components.
     */
    private void applySettings() {
        try {
            // 终端视觉样式（字体/颜色/背景图）统一由 TerminalController.applyBackground 负责，
            // 避免与 TerminalController 内部 setStyle 重复或互相覆盖。
            if (terminalController != null) {
                terminalController.loadSettings();
            }
            applyCommandInputSettings();
            setupShortcuts();
            AiFinalShellApp.applyRuntimePreferences();

            logger.info("Settings applied successfully");
        } catch (Exception e) {
            logger.error("Failed to apply settings", e);
        }

        // V-P2-8: 监控间隔配置变更后重启监控调度（若监控正在运行）
        if (selectedServer != null && currentConnectionKey != null
                && SshConnectionManager.getInstance().isConnected(currentConnectionKey)) {
            String connKey = currentConnectionKey;
            MonitorService.getInstance().stopMonitoring(selectedServer.getId());
            MonitorService.getInstance().startMonitoring(selectedServer, connKey);
            stopLeftMonitorRefresh();
            startLeftMonitorRefresh(selectedServer);
        }
    }

    // ========== Terminal ==========

    private void initTerminal() {
        terminalController = new TerminalController(terminalOutput, terminalBackground, terminalOverlay);
        applyCommandInputSettings();
        terminalController.setOnStatusChange(status -> {
            Platform.runLater(() -> {
                updateStatus(status);
                if ("Connected".equals(status) && selectedServer != null) {
                    serverInfo.setText(selectedServer.getUsername() + "@" + selectedServer.getHost());
                } else if ("Disconnected".equals(status)) {
                    serverInfo.setText("");
                    updateConnectedServerInfo();
                    // 修复项5（AI 与终端同步）：终端意外断连时（非用户主动断开），
                    // 在 AI 聊天区插入系统气泡通知用户——这是 AI 与终端"同步"的关键，
                    // 让用户立刻知道 AI 已无法操作终端，避免 AI 盲目重试 / 用户不知情。
                    // 用户主动断开（disconnectServer）已置 userInitiatedDisconnect=true 并有专属气泡，此处跳过。
                    if (!userInitiatedDisconnect && currentConnectionKey != null) {
                        addChatMessage("assistant",
                            "⚠️ 终端已意外断开连接，AI 暂时无法在可见终端执行命令。\n"
                            + "请重新连接服务器；恢复后我会继续在你看得到的终端中操作，不会静默转入后台执行。");
                    }
                    userInitiatedDisconnect = false; // 复位，准备下一次断连判定
                }
            });
        });

        // 修复项5：注册自动重连策略——AI 工具检测到终端断连时，Bridge 调用本策略尝试恢复可见终端，
        // 成功则重试命令，失败则返回明确错误并要求恢复可见终端。
        TerminalCommandBridge.getInstance().setReconnectStrategy(this::reconnectTerminalSync);

        // 终端直接键盘输入：用户可在终端区域内直接打字，不止用底部输入框。
        // TextArea 设为 editable=false（避免本地双回显），PTY shell 会回显输入内容。
        // onKeyPressed 处理特殊键 + Ctrl 组合，onKeyTyped 处理可打印字符。
        terminalOutput.setOnKeyPressed(event -> {
            if (terminalController == null || !terminalController.isConnected()) return;
            KeyCode code = event.getCode();
            String input = null;

            // AI 捕获命令期间保持 PTY 输入原子性；只保留 Ctrl+C 作为用户随时干预/中断的通道。
            if (terminalController.isAiCommandActive()
                    && !(event.isControlDown() && code == KeyCode.C)) {
                event.consume();
                showStatusFeedback("AI 正在执行命令；如需中断请按 Ctrl+C");
                return;
            }

            if (event.isControlDown()) {
                // Ctrl 组合键 → 对应控制字符
                if (code == KeyCode.C) input = "\u0003";       // Ctrl+C 中断
                else if (code == KeyCode.D) input = "\u0004";   // Ctrl+D EOF
                else if (code == KeyCode.Z) input = "\u001A";   // Ctrl+Z 挂起
                else if (code == KeyCode.L) input = "\u000C";   // Ctrl+L 清屏
                else if (code == KeyCode.A) input = "\u0001";   // Ctrl+A 行首
                else if (code == KeyCode.E) input = "\u0005";   // Ctrl+E 行尾
                else if (code == KeyCode.W) input = "\u0017";   // Ctrl+W 删词
                else if (code == KeyCode.U) input = "\u0015";   // Ctrl+U 删行
                else if (code == KeyCode.K) input = "\u000B";   // Ctrl+K 删至行尾
                event.consume();
            } else {
                switch (code) {
                    case ENTER:      input = "\r";          event.consume(); break;
                    case BACK_SPACE: input = "\b";          event.consume(); break;
                    case TAB:        input = "\t";          event.consume(); break;
                    case UP:         input = "\u001B[A";    event.consume(); break;
                    case DOWN:       input = "\u001B[B";    event.consume(); break;
                    case RIGHT:      input = "\u001B[C";    event.consume(); break;
                    case LEFT:       input = "\u001B[D";    event.consume(); break;
                    case HOME:       input = "\u001B[H";    event.consume(); break;
                    case END:        input = "\u001B[F";    event.consume(); break;
                    case DELETE:     input = "\u001B[3~";   event.consume(); break;
                    case PAGE_UP:    input = "\u001B[5~";   event.consume(); break;
                    case PAGE_DOWN:  input = "\u001B[6~";   event.consume(); break;
                    default: break; // 可打印字符交给 onKeyTyped
                }
            }
            if (input != null) terminalController.sendInput(input);
        });

        terminalOutput.setOnKeyTyped(event -> {
            if (terminalController == null || !terminalController.isConnected()) return;
            if (terminalController.isAiCommandActive()) {
                event.consume();
                return;
            }
            if (event.isControlDown() || event.isAltDown()) return; // 已在 onKeyPressed 处理
            String ch = event.getCharacter();
            if (ch != null && !ch.isEmpty()) {
                char c = ch.charAt(0);
                if (c >= 32) { // 仅可打印字符（Enter/Backspace/Tab 等已在 onKeyPressed 消费）
                    terminalController.sendInput(ch);
                }
            }
        });

        // 点击终端区域时获取焦点，使键盘输入直达 SSH
        terminalOutput.setOnMouseClicked(e -> terminalOutput.requestFocus());
    }

    /** 应用字体与光标设置到真正接收命令的输入框。 */
    private void applyCommandInputSettings() {
        if (commandInput == null) return;
        AppConfig config = AppConfig.getInstance();
        commandInput.setStyle(String.format(
                "-fx-background-color: transparent; -fx-text-fill: %s; "
                        + "-fx-font-family: '%s', 'Microsoft YaHei', monospace; -fx-font-size: %dpx; "
                        + "-fx-font-weight: %s; -fx-font-style: %s; -fx-border-color: transparent; "
                        + "-fx-background-radius: 6; -fx-padding: 8 10; -fx-prompt-text-fill: #555; "
                        + "-fx-highlight-fill: %s; -fx-highlight-text-fill: %s;",
                config.getTerminalColor("foreground"), config.getFontFamily(), config.getFontSize(),
                config.isFontBold() ? "bold" : "normal",
                config.isFontItalic() ? "italic" : "normal",
                config.getTerminalColor("selection"), config.getTerminalColor("foreground")));

        if (caretVisibilityEnforcer != null) {
            caretVisibilityEnforcer.stop();
            caretVisibilityEnforcer = null;
        }
        Platform.runLater(() -> {
            Node caret = commandInput.lookup(".caret");
            if (caret == null) return;
            applyCaretShape(caret, config.getCursorStyle(), config.getTerminalColor("cursor"));
            if (!config.isCursorBlink()) {
                // TextField 的原生 blink timer 没有公开开关；周期性恢复可见即可可靠关闭闪烁。
                caretVisibilityEnforcer = new Timeline(new KeyFrame(Duration.millis(120), e -> {
                    caret.setOpacity("block".equals(config.getCursorStyle()) ? 0.48 : 1.0);
                    caret.setVisible(true);
                }));
                caretVisibilityEnforcer.setCycleCount(Timeline.INDEFINITE);
                caretVisibilityEnforcer.play();
            }
        });
    }

    private void applyCaretShape(Node caret, String style, String color) {
        caret.setRotate(0);
        caret.setScaleX(1);
        caret.setScaleY(1);
        caret.setTranslateY(0);
        caret.setOpacity(1);
        String width = "bar".equals(style) ? "1.4" : "1.0";
        if ("block".equals(style)) {
            caret.setScaleX(6.5);
            caret.setOpacity(0.48);
        } else if ("underscore".equals(style)) {
            caret.setRotate(90);
            caret.setScaleY(0.9);
            caret.setTranslateY(7);
            width = "1.6";
        }
        caret.setStyle("-fx-stroke: " + color + "; -fx-fill: " + color
                + "; -fx-stroke-width: " + width + ";");
    }

    @FXML
    private void sendCommand() {
        if (terminalController == null || !terminalController.isConnected()) {
            terminalOutput.appendText("\nNot connected to server\n");
            return;
        }
        if (terminalController.isAiCommandActive()) {
            showStatusFeedback("AI 正在执行命令；如需中断请按 Ctrl+C");
            return;
        }
        String command = commandInput.getText();
        if (command.isEmpty()) return;
        terminalController.sendCommand(command);
        commandInput.clear();
    }

    // ========== Left Panel Monitor ==========

    private void initLeftMonitor() {
        cpuBar.setProgress(0);
        memBar.setProgress(0);
        diskBar.setProgress(0);
        cpuLabel.setText("--%");
        memLabel.setText("--%");
        diskLabel.setText("--%");
        uptimeLabel.setText("--");
        loadLabel.setText("-- / -- / --");
        pingLabel.setText("-- ms");
        procsLabel.setText("--");
        if (topProcessTable != null) topProcessTable.setItems(FXCollections.observableArrayList());
        netUpLabel.setText("-- KB/s");
        netDownLabel.setText("-- KB/s");
        netUpBar.setProgress(0);
        netDownBar.setProgress(0);
        diskDetailBar.setProgress(0);
        diskFreeLabel.setText("--");
        logPathLabel.setText("--");
        logErrorLabel.setText("0");
        connectedIpLabel.setText("--");
        sysInfoBrief.setText("点击查看详情");
    }

    /**
     * 初始化进程 TOP 表格：绑定列属性 + 设置按值着色的 cell factory。
     * CPU%/MEM% 列根据数值高低动态着色（高→红、中→黄、低→绿），USER 列蓝色、COMMAND 列灰色。
     */
    private void initTopProcessTable() {
        if (topProcessTable == null) return;

        // 列属性绑定
        topUserCol.setCellValueFactory(cell -> cell.getValue().userProperty());
        topCpuCol.setCellValueFactory(cell -> cell.getValue().cpuProperty());
        topMemCol.setCellValueFactory(cell -> cell.getValue().memProperty());
        topCmdCol.setCellValueFactory(cell -> cell.getValue().commandProperty());

        // USER 列：浅蓝色文字
        topUserCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
                setStyle(empty ? "" : "-fx-text-fill: #79c0ff; -fx-font-size: 10px; -fx-alignment: CENTER-LEFT;");
            }
        });

        // CPU% 列：按值着色（>50 红 / >20 黄 / 其他绿）
        topCpuCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); return; }
                setText(item);
                double val = 0;
                try { val = Double.parseDouble(item.replace("%", "").trim()); } catch (Exception ignored) {}
                String color = val > 50 ? "#f85149" : val > 20 ? "#d29922" : "#0dbc79";
                setStyle("-fx-text-fill: " + color + "; -fx-font-size: 10px; -fx-font-weight: bold; -fx-alignment: CENTER-RIGHT;");
            }
        });

        // MEM% 列：按值着色（>50 红 / >20 黄 / 其他蓝）
        topMemCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); return; }
                setText(item);
                double val = 0;
                try { val = Double.parseDouble(item.replace("%", "").trim()); } catch (Exception ignored) {}
                String color = val > 50 ? "#f85149" : val > 20 ? "#d29922" : "#2472c8";
                setStyle("-fx-text-fill: " + color + "; -fx-font-size: 10px; -fx-font-weight: bold; -fx-alignment: CENTER-RIGHT;");
            }
        });

        // COMMAND 列：灰色等宽字体
        topCmdCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
                setStyle(empty ? "" : "-fx-text-fill: #d4d4d4; -fx-font-size: 10px; -fx-font-family: 'Consolas', monospace;");
            }
        });

        topProcessTable.setPlaceholder(new Label("暂无进程数据"));
    }

    private void startLeftMonitorRefresh(ServerConfig config) {
        stopLeftMonitorRefresh();
        monitorTimer = new java.util.Timer(true);
        int intervalSec = AppConfig.getInstance().getMonitorUiIntervalSeconds();
        monitorTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                if (selectedServer == null) return;
                // V-P1-6: 不再主动调用 collectMetrics（与 MonitorService 调度器重复采集）。
                // 改为读 MonitorService 缓存的最近一次 metrics。
                ServerMetrics metrics = MonitorService.getInstance().getLastMetrics(selectedServer.getId());
                if (metrics == null) return;
                Platform.runLater(() -> {
                    // 原有 UI 更新逻辑全部保留
                    updateConnectedServerInfo();
                    uptimeLabel.setText(metrics.getUptime() != null ? metrics.getUptime() : "--");
                    loadLabel.setText(String.format("%.2f / %.2f / %.2f",
                            metrics.getLoad1(), metrics.getLoad5(), metrics.getLoad15()));
                    long ping = metrics.getPingMs();
                    pingLabel.setText(ping + " ms");
                    if (ping < 50) {
                        pingLabel.setStyle("-fx-text-fill: #0dbc79; -fx-font-size: 10px;");
                    } else if (ping < 200) {
                        pingLabel.setStyle("-fx-text-fill: #e5e510; -fx-font-size: 10px;");
                    } else {
                        pingLabel.setStyle("-fx-text-fill: #cd3131; -fx-font-size: 10px;");
                    }
                    procsLabel.setText(String.valueOf(metrics.getProcessCount() > 0 ?
                            metrics.getProcessCount() : "--"));
                    cpuBar.setProgress(Math.min(metrics.getCpuUsage() / 100.0, 1.0));
                    cpuLabel.setText(String.format("%.1f%%", metrics.getCpuUsage()));
                    memBar.setProgress(Math.min(metrics.getMemoryUsage() / 100.0, 1.0));
                    memLabel.setText(String.format("%.1f%% (%d/%d MB)",
                            metrics.getMemoryUsage(), metrics.getMemoryUsedMB(), metrics.getMemoryTotalMB()));
                    diskBar.setProgress(Math.min(metrics.getDiskUsage() / 100.0, 1.0));
                    diskLabel.setText(String.format("%.1f%%", metrics.getDiskUsage()));
                    ObservableList<TopProcessItem> procItems = FXCollections.observableArrayList();
                    for (String proc : metrics.getTopProcesses()) {
                        String[] parts = proc.split("\\|");
                        if (parts.length >= 4) {
                            String cmd = parts[3];
                            if (cmd.length() > 24) cmd = cmd.substring(0, 21) + "...";
                            procItems.add(new TopProcessItem(parts[0], parts[1], parts[2], cmd));
                        }
                    }
                    topProcessTable.setItems(procItems);
                    long upSpeed = metrics.getNetworkOutSpeed();
                    long downSpeed = metrics.getNetworkInSpeed();
                    netUpLabel.setText(formatSpeed(upSpeed));
                    netDownLabel.setText(formatSpeed(downSpeed));
                    netUpBar.setProgress(Math.min(upSpeed / 1048576.0, 1.0));
                    netDownBar.setProgress(Math.min(downSpeed / 1048576.0, 1.0));
                    diskDetailBar.setProgress(Math.min(metrics.getDiskUsage() / 100.0, 1.0));
                    long freeGB = metrics.getDiskFreeKB() / 1048576;
                    long totalGB = metrics.getDiskTotalKB() / 1048576;
                    diskFreeLabel.setText(String.format("%dG/%dG", freeGB, totalGB));
                    logPathLabel.setText(metrics.getLogPath() != null ? metrics.getLogPath() : "--");
                    logErrorLabel.setText(String.valueOf(metrics.getRecentErrorCount()));
                    if (metrics.getRecentErrorCount() > 5) {
                        logErrorLabel.setStyle("-fx-text-fill: #cd3131; -fx-font-size: 10px;");
                    } else if (metrics.getRecentErrorCount() > 0) {
                        logErrorLabel.setStyle("-fx-text-fill: #e5e510; -fx-font-size: 10px;");
                    } else {
                        logErrorLabel.setStyle("-fx-text-fill: #0dbc79; -fx-font-size: 10px;");
                    }
                });
            }
        }, 3000, intervalSec * 1000L); // 首次 3 秒延迟，之后按配置间隔刷新
    }

    private String formatSpeed(long bytesPerSec) {
        if (bytesPerSec < 1024) {
            return bytesPerSec + " B/s";
        } else if (bytesPerSec < 1048576) {
            return String.format("%.1f KB/s", bytesPerSec / 1024.0);
        } else {
            return String.format("%.1f MB/s", bytesPerSec / 1048576.0);
        }
    }

    private void stopLeftMonitorRefresh() {
        if (monitorTimer != null) {
            monitorTimer.cancel();
            monitorTimer = null;
        }
    }

    // ========== Collapsible Sections ==========

    @FXML
    private void toggleStatusSection(javafx.scene.input.MouseEvent event) {
        toggleSection(statusContent, statusArrow);
    }

    @FXML
    private void toggleMonitorSection(javafx.scene.input.MouseEvent event) {
        toggleSection(monitorContent, monitorArrow);
    }

    @FXML
    private void toggleTopProcSection(javafx.scene.input.MouseEvent event) {
        toggleSection(topProcContent, topProcArrow);
    }

    @FXML
    private void toggleNetworkSection(javafx.scene.input.MouseEvent event) {
        toggleSection(networkContent, networkArrow);
    }

    @FXML
    private void toggleDiskSection(javafx.scene.input.MouseEvent event) {
        toggleSection(diskContent, diskArrow);
    }

    @FXML
    private void toggleLogSection(javafx.scene.input.MouseEvent event) {
        toggleSection(logContent, logArrow);
    }

    private void toggleSection(javafx.scene.Node content, Label arrow) {
        boolean visible = content.isVisible();
        content.setVisible(!visible);
        content.setManaged(!visible);
        arrow.setText(visible ? "▸" : "▾");
    }

    // ========== Panel Collapse/Expand ==========

    @FXML
    private void toggleLeftPanel() {
        leftCollapsed = !leftCollapsed;
        if (leftCollapsed) {
            // 折叠前保存当前 divider 位置（用户可能拖动过），再把 divider 推到最左
            savedLeftDivider = mainSplitPane.getDividers().get(0).getPosition();
            // 关键：将宽度约束全部归零，SplitPane 才会把空间真正让给中间面板。
            // 仅 setVisible(false)/setManaged(false) 不够，因为 minWidth=200 会阻止收缩。
            leftPanel.setMinWidth(0);
            leftPanel.setPrefWidth(0);
            leftPanel.setMaxWidth(0);
            leftPanel.setVisible(false);
            leftPanel.setManaged(false);
            mainSplitPane.getDividers().get(0).setPosition(0.0);
        } else {
            // 恢复宽度约束
            leftPanel.setMinWidth(200);
            leftPanel.setPrefWidth(260);
            leftPanel.setMaxWidth(Double.MAX_VALUE);
            leftPanel.setVisible(true);
            leftPanel.setManaged(true);
            mainSplitPane.getDividers().get(0).setPosition(savedLeftDivider);
        }
        leftCollapseBtn.setText(leftCollapsed ? "▶" : "◀");
        if (leftCollapseInBtn != null) {
            leftCollapseInBtn.setText(leftCollapsed ? "▶" : "◀");
        }
    }

    @FXML
    private void toggleRightPanel() {
        rightCollapsed = !rightCollapsed;
        if (rightCollapsed) {
            savedRightDivider = mainSplitPane.getDividers().get(1).getPosition();
            // 同左面板：归零宽度约束，确保空间完全释放给中间面板
            rightPanel.setMinWidth(0);
            rightPanel.setPrefWidth(0);
            rightPanel.setMaxWidth(0);
            rightPanel.setVisible(false);
            rightPanel.setManaged(false);
            // divider 推到最右（1.0），空间全给中间面板
            mainSplitPane.getDividers().get(1).setPosition(1.0);
        } else {
            rightPanel.setMinWidth(0);
            rightPanel.setPrefWidth(380);
            rightPanel.setMaxWidth(Double.MAX_VALUE);
            rightPanel.setVisible(true);
            rightPanel.setManaged(true);
            mainSplitPane.getDividers().get(1).setPosition(savedRightDivider);
        }
        rightCollapseBtn.setText(rightCollapsed ? "◀" : "▶");
    }

    // ========== File Manager ==========

    private void initFileTable() {
        fileNameCol.setCellValueFactory(cellData -> cellData.getValue().nameProperty());
        fileSizeCol.setCellValueFactory(cellData -> cellData.getValue().sizeProperty());
        fileTypeCol.setCellValueFactory(cellData -> cellData.getValue().fileTypeProperty());
        filePermCol.setCellValueFactory(cellData -> cellData.getValue().permissionsProperty());
        fileTimeCol.setCellValueFactory(cellData -> cellData.getValue().modifiedTimeProperty());
        fileOwnerCol.setCellValueFactory(cellData -> cellData.getValue().ownerGroupProperty());

        // Name column with icon
        fileNameCol.setCellFactory(col -> new TableCell<FileItem, String>() {
            private final javafx.scene.control.Label iconLabel = new javafx.scene.control.Label();
            private final javafx.scene.control.Label nameLabel = new javafx.scene.control.Label();
            private final javafx.scene.layout.HBox hbox = new javafx.scene.layout.HBox(6, iconLabel, nameLabel);

            {
                hbox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                iconLabel.setStyle("-fx-font-size: 16px;");
                nameLabel.setStyle("-fx-text-fill: #d4d4d4; -fx-font-size: 12px;");
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    FileItem fileItem = getTableView().getItems().get(getIndex());
                    if (fileItem == null) {
                        setText(null);
                        setGraphic(null);
                        return;
                    }
                    nameLabel.setText(item);

                    String name = fileItem.getName();
                    if (fileItem.isDirectory()) {
                        if (name.equals("..")) {
                            iconLabel.setText("⬆");
                        } else if (name.startsWith(".")) {
                            iconLabel.setText("📂");
                        } else {
                            iconLabel.setText("📁");
                        }
                    } else {
                        int dot = name.lastIndexOf('.');
                        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
                        switch (ext) {
                            case "sh": case "bash": case "zsh": case "py": case "pl": case "rb":
                                iconLabel.setText("📜");
                                break;
                            case "java": case "go": case "rs": case "c": case "cpp": case "h":
                                iconLabel.setText("💻");
                                break;
                            case "js": case "ts": case "jsx": case "tsx":
                                iconLabel.setText("🟨");
                                break;
                            case "html": case "htm": case "css": case "scss":
                                iconLabel.setText("🌐");
                                break;
                            case "json": case "xml": case "yml": case "yaml":
                                iconLabel.setText("📋");
                                break;
                            case "txt": case "log": case "md": case "cfg": case "conf": case "ini": case "properties":
                                iconLabel.setText("📝");
                                break;
                            case "jpg": case "jpeg": case "png": case "gif": case "bmp": case "svg":
                                iconLabel.setText("🖼");
                                break;
                            case "zip": case "tar": case "gz": case "bz2": case "xz": case "7z": case "rar":
                                iconLabel.setText("📦");
                                break;
                            case "pdf":
                                iconLabel.setText("📕");
                                break;
                            case "doc": case "docx":
                                iconLabel.setText("📘");
                                break;
                            case "xls": case "xlsx":
                                iconLabel.setText("📗");
                                break;
                            case "so": case "dll": case "dylib":
                                iconLabel.setText("🔧");
                                break;
                            case "bin": case "exe":
                                iconLabel.setText("⚙");
                                break;
                            default:
                                iconLabel.setText("📄");
                                break;
                        }
                    }

                    setGraphic(hbox);
                }
            }
        });

        fileTable.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                FileItem selected = fileTable.getSelectionModel().getSelectedItem();
                if (selected != null && selected.isDirectory()) {
                    loadRemoteDirectory(selected.getFullPath());
                }
            }
        });

        // Initialize directory tree
        initDirTree();

        // Right-click context menu
        initFileContextMenu();
    }

    // ========== Directory Tree ==========

    private TreeItem<String> rootTreeItem;

    private void initDirTree() {
        rootTreeItem = new TreeItem<>("/");
        rootTreeItem.setExpanded(true);

        dirTree.setRoot(rootTreeItem);
        dirTree.setShowRoot(true);

        dirTree.setCellFactory(tv -> new TreeCell<String>() {
            private final javafx.scene.control.Label icon = new javafx.scene.control.Label();
            private final javafx.scene.layout.HBox hbox = new javafx.scene.layout.HBox(5, icon, new javafx.scene.control.Label());

            {
                hbox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                icon.setStyle("-fx-font-size: 14px;");
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setContextMenu(null);
                } else {
                    javafx.scene.control.Label label = (javafx.scene.control.Label) hbox.getChildren().get(1);
                    label.setText(item);
                    label.setStyle("-fx-text-fill: #d4d4d4; -fx-font-size: 12px;");

                    TreeItem<String> treeItem = getTreeItem();
                    if (treeItem == rootTreeItem) {
                        icon.setText("💻");
                    } else if (treeItem.isLeaf()) {
                        icon.setText("📄");
                    } else if (treeItem.isExpanded()) {
                        icon.setText("📂");
                    } else {
                        icon.setText("📁");
                    }
                    setGraphic(hbox);

                    // Set right-click context menu for tree items
                    if (treeItem != rootTreeItem) {
                        setContextMenu(createTreeContextMenu(treeItem));
                    } else {
                        setContextMenu(createRootTreeContextMenu());
                    }
                }
            }
        });

        // When tree item is expanded, load its children
        rootTreeItem.expandedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                loadTreeChildren(rootTreeItem, "/");
            }
        });

        dirTree.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                String path = getTreeItemPath(newVal);
                loadRemoteDirectory(path);
            }
        });

        // Load root children
        loadTreeChildren(rootTreeItem, "/");
    }

    private ContextMenu createRootTreeContextMenu() {
        ContextMenu menu = new ContextMenu();
        menu.setStyle("-fx-font-size: 12px;");

        MenuItem refreshItem = new MenuItem("刷新");
        refreshItem.setOnAction(e -> loadTreeChildren(rootTreeItem, "/"));
        menu.getItems().add(refreshItem);

        return menu;
    }

    private ContextMenu createTreeContextMenu(TreeItem<String> treeItem) {
        ContextMenu menu = new ContextMenu();
        menu.setStyle("-fx-font-size: 12px;");

        String path = getTreeItemPath(treeItem);

        MenuItem openItem = new MenuItem("打开");
        openItem.setOnAction(e -> loadRemoteDirectory(path));

        MenuItem refreshItem = new MenuItem("刷新");
        refreshItem.setOnAction(e -> {
            treeItem.getChildren().clear();
            treeItem.getChildren().add(new TreeItem<>("(loading...)"));
            loadTreeChildren(treeItem, path);
        });

        MenuItem copyPathItem = new MenuItem("复制路径");
        copyPathItem.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(path);
            Clipboard.getSystemClipboard().setContent(content);
            updateStatus("路径已复制: " + path);
        });

        MenuItem treeReferenceToAiItem = new MenuItem("引用到AI");
        treeReferenceToAiItem.setOnAction(e -> addAiFileReference(path));
        treeReferenceToAiItem.setStyle("-fx-text-fill: #4a9eff;");

        SeparatorMenuItem sep1 = new SeparatorMenuItem();

        MenuItem downloadItem = new MenuItem("下载文件夹");
        downloadItem.setOnAction(e -> {
            String connKey = getCurrentConnectionKey();
            if (connKey == null) { showAlert("请先连接服务器"); return; }
            String localPath = DownloadManager.getInstance().getDefaultDownloadPath()
                    + File.separator + treeItem.getValue();
            DownloadManager.getInstance().downloadFolder(connKey, path, localPath);
            showAlert("开始下载文件夹: " + treeItem.getValue());
        });

        SeparatorMenuItem sep2 = new SeparatorMenuItem();

        MenuItem newFolderItem = new MenuItem("新建文件夹");
        newFolderItem.setOnAction(e -> {
            TextInputDialog dialog = new TextInputDialog("new_folder");
            dialog.setTitle("新建文件夹");
            dialog.setHeaderText("在 " + path + " 下创建文件夹");
            dialog.setContentText("文件夹名:");
            dialog.showAndWait().ifPresent(name -> {
                if (name != null && !name.isEmpty()) {
                    new Thread(() -> {
                        try {
                            String fullPath = path.endsWith("/") ? path + name : path + "/" + name;
                            SshConnectionManager.SftpChannel sftp = SshConnectionManager.getInstance().openSftp(getCurrentConnectionKey());
                            sftp.mkdir(fullPath);
                            sftp.close();
                            Platform.runLater(() -> {
                                loadTreeChildren(treeItem, path);
                                loadRemoteDirectory(path);
                            });
                        } catch (Exception ex) {
                            Platform.runLater(() -> showAlert("创建失败: " + ex.getMessage()));
                        }
                    }).start();
                }
            });
        });

        MenuItem newFileItem = new MenuItem("新建文件");
        newFileItem.setOnAction(e -> {
            TextInputDialog dialog = new TextInputDialog("new_file.txt");
            dialog.setTitle("新建文件");
            dialog.setHeaderText("在 " + path + " 下创建文件");
            dialog.setContentText("文件名:");
            dialog.showAndWait().ifPresent(name -> {
                if (name != null && !name.isEmpty()) {
                    new Thread(() -> {
                        try {
                            String fullPath = path.endsWith("/") ? path + name : path + "/" + name;
                            SshConnectionManager.SftpChannel sftp = SshConnectionManager.getInstance().openSftp(getCurrentConnectionKey());
                            sftp.writeTextFile(fullPath, "");
                            sftp.close();
                            Platform.runLater(() -> loadRemoteDirectory(path));
                        } catch (Exception ex) {
                            Platform.runLater(() -> showAlert("创建失败: " + ex.getMessage()));
                        }
                    }).start();
                }
            });
        });

        SeparatorMenuItem sep3 = new SeparatorMenuItem();

        MenuItem renameItem = new MenuItem("重命名");
        renameItem.setOnAction(e -> {
            TextInputDialog dialog = new TextInputDialog(treeItem.getValue());
            dialog.setTitle("重命名");
            dialog.setHeaderText("重命名: " + treeItem.getValue());
            dialog.setContentText("新名称:");
            dialog.showAndWait().ifPresent(newName -> {
                if (newName != null && !newName.isEmpty() && !newName.equals(treeItem.getValue())) {
                    String parentPath = path.substring(0, path.lastIndexOf('/'));
                    if (parentPath.isEmpty()) parentPath = "/";
                    final String finalParentPath = parentPath;
                    final TreeItem<String> finalTreeItem = treeItem;
                    String oldPath = path;
                    String newPath = parentPath + "/" + newName;
                    new Thread(() -> {
                        try {
                            SshConnectionManager.SftpChannel sftp = SshConnectionManager.getInstance().openSftp(getCurrentConnectionKey());
                            sftp.rename(oldPath, newPath);
                            sftp.close();
                            Platform.runLater(() -> {
                                loadTreeChildren(finalTreeItem.getParent(), finalParentPath);
                                loadRemoteDirectory(finalParentPath);
                            });
                        } catch (Exception ex) {
                            Platform.runLater(() -> showAlert("重命名失败: " + ex.getMessage()));
                        }
                    }).start();
                }
            });
        });

        MenuItem deleteItem = new MenuItem("删除");
        deleteItem.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("删除确认");
            confirm.setHeaderText(null);
            confirm.setContentText("确定删除文件夹 \"" + treeItem.getValue() + "\" ?\n此操作将删除文件夹内所有内容！");
            confirm.showAndWait().ifPresent(btn -> {
                if (btn == ButtonType.OK) {
                    new Thread(() -> {
                        try {
                            // 安全修复：用单引号转义路径，防止命令注入（如 path 含 ; rm -rf /）
                            String cmd = "rm -rf " + SecurityUtils.sanitizeInput(path);
                            SshConnectionManager.getInstance().executeCommand(getCurrentConnectionKey(), cmd);
                            Platform.runLater(() -> {
                                treeItem.getParent().getChildren().remove(treeItem);
                                loadRemoteDirectory(getTreeItemPath(treeItem.getParent()));
                            });
                        } catch (Exception ex) {
                            Platform.runLater(() -> showAlert("删除失败: " + ex.getMessage()));
                        }
                    }).start();
                }
            });
        });

        SeparatorMenuItem sep4 = new SeparatorMenuItem();

        MenuItem propertiesItem = new MenuItem("属性");
        propertiesItem.setOnAction(e -> {
            new Thread(() -> {
                try {
                    String cmd = "ls -ld " + path;
                    String result = SshConnectionManager.getInstance().executeCommand(getCurrentConnectionKey(), cmd);
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.INFORMATION);
                        alert.setTitle("属性 - " + treeItem.getValue());
                        alert.setHeaderText(null);
                        TextArea textArea = new TextArea(result);
                        textArea.setEditable(false);
                        textArea.setWrapText(true);
                        textArea.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px;");
                        textArea.setPrefWidth(400);
                        textArea.setPrefHeight(150);
                        alert.getDialogPane().setContent(textArea);
                        alert.showAndWait();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> showAlert("获取属性失败: " + ex.getMessage()));
                }
            }).start();
        });

        menu.getItems().addAll(
                openItem, refreshItem, copyPathItem, treeReferenceToAiItem,
                sep1,
                downloadItem,
                sep2,
                newFolderItem, newFileItem,
                renameItem,
                deleteItem,
                sep4,
                propertiesItem
        );

        return menu;
    }

    private String getTreeItemPath(TreeItem<String> item) {
        StringBuilder path = new StringBuilder();
        TreeItem<String> current = item;
        while (current != null && current != rootTreeItem) {
            path.insert(0, "/" + current.getValue());
            current = current.getParent();
        }
        if (path.length() == 0) return "/";
        return path.toString();
    }

    private void loadTreeChildren(TreeItem<String> parent, String path) {
        new Thread(() -> {
            try {
                String connKey = getCurrentConnectionKey();
                if (connKey == null) return;

                // Fix path for root
                String fixedPath = path.equals("/") ? "/" : path + "/";

                String cmd = "find " + fixedPath + " -maxdepth 1 -mindepth 1 -type d 2>/dev/null | head -50 | sort";
                String result = SshConnectionManager.getInstance().executeCommand(connKey, cmd);

                Platform.runLater(() -> {
                    parent.getChildren().clear();

                    if (result == null || result.trim().isEmpty()) {
                        // No children = leaf node (no expand arrow)
                        return;
                    }

                    String[] lines = result.split("\n");
                    for (String line : lines) {
                        String fullPath = line.trim();
                        if (fullPath.isEmpty()) continue;

                        // Extract folder name from full path
                        String name = fullPath.substring(fullPath.lastIndexOf('/') + 1);
                        if (name.isEmpty() || name.equals(".") || name.equals("..")) continue;

                        TreeItem<String> child = new TreeItem<>(name);

                        // Add a dummy child so the expand arrow shows
                        child.getChildren().add(new TreeItem<>("(loading...)"));

                        // When this child is expanded, load its children
                        final String childPath = fullPath;
                        child.expandedProperty().addListener((obs, oldVal, newVal) -> {
                            if (newVal) {
                                // Check if still has dummy child
                                if (child.getChildren().size() == 1
                                        && child.getChildren().get(0).getValue().equals("(loading...)")) {
                                    loadTreeChildren(child, childPath);
                                }
                            }
                        });

                        // When collapsed, reset to dummy so it can be reloaded
                        child.expandedProperty().addListener((obs, oldVal, newVal) -> {
                            if (!newVal && child.getChildren().isEmpty()) {
                                child.getChildren().add(new TreeItem<>("(loading...)"));
                            }
                        });

                        parent.getChildren().add(child);
                    }

                    // If no children found, tree item has no expand arrow (it's a leaf by default)
                });
            } catch (Exception e) {
                // ignore
            }
        }).start();
    }

    @FXML
    private void refreshDirTree() {
        if (rootTreeItem != null) {
            loadTreeChildren(rootTreeItem, "/");
        }
    }

    private void initFileContextMenu() {
        ContextMenu contextMenu = new ContextMenu();

        MenuItem refreshItem = new MenuItem("刷新");
        refreshItem.setOnAction(e -> refreshFiles());
        refreshItem.setStyle("-fx-font-size: 12px;");

        MenuItem openItem = new MenuItem("打开");
        openItem.setOnAction(e -> {
            FileItem selected = fileTable.getSelectionModel().getSelectedItem();
            if (selected != null && selected.isDirectory()) {
                loadRemoteDirectory(selected.getFullPath());
            }
        });
        openItem.setStyle("-fx-font-size: 12px;");

        MenuItem openWithItem = new MenuItem("打开方式...");
        openWithItem.setOnAction(e -> {
            FileItem selected = fileTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                openWithDialog(selected);
            }
        });
        openWithItem.setStyle("-fx-font-size: 12px;");

        MenuItem copyPathItem = new MenuItem("复制路径");
        copyPathItem.setOnAction(e -> {
            FileItem selected = fileTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                ClipboardContent content = new ClipboardContent();
                content.putString(selected.getFullPath());
                Clipboard.getSystemClipboard().setContent(content);
                updateStatus("路径已复制: " + selected.getFullPath());
            }
        });
        copyPathItem.setStyle("-fx-font-size: 12px;");

        MenuItem copyNameItem = new MenuItem("复制文件名");
        copyNameItem.setOnAction(e -> {
            FileItem selected = fileTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                ClipboardContent content = new ClipboardContent();
                content.putString(selected.getName());
                Clipboard.getSystemClipboard().setContent(content);
                updateStatus("文件名已复制: " + selected.getName());
            }
        });
        copyNameItem.setStyle("-fx-font-size: 12px;");

        MenuItem copyContentItem = new MenuItem("复制内容");
        copyContentItem.setOnAction(e -> {
            FileItem selected = fileTable.getSelectionModel().getSelectedItem();
            if (selected != null && !selected.isDirectory()) {
                new Thread(() -> {
                    try {
                        String connKey = getCurrentConnectionKey();
                        if (connKey != null) {
                            SshConnectionManager.SftpChannel sftp = SshConnectionManager.getInstance().openSftp(connKey);
                            String content = sftp.readTextFile(selected.getFullPath());
                            sftp.close();
                            Platform.runLater(() -> {
                                ClipboardContent cc = new ClipboardContent();
                                cc.putString(content);
                                Clipboard.getSystemClipboard().setContent(cc);
                                updateStatus("内容已复制: " + selected.getName());
                            });
                        }
                    } catch (Exception ex) {
                        Platform.runLater(() -> showAlert("复制失败: " + ex.getMessage()));
                    }
                }).start();
            }
        });
        copyContentItem.setStyle("-fx-font-size: 12px;");

        MenuItem referenceToAiItem = new MenuItem("引用到AI");
        referenceToAiItem.setOnAction(e -> {
            FileItem selected = fileTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                addAiFileReference(selected.getFullPath());
            }
        });
        referenceToAiItem.setStyle("-fx-font-size: 12px; -fx-text-fill: #4a9eff;");

        SeparatorMenuItem sep1 = new SeparatorMenuItem();

        MenuItem downloadItem = new MenuItem("下载");
        downloadItem.setOnAction(e -> downloadFile());
        downloadItem.setStyle("-fx-font-size: 12px;");

        MenuItem uploadItem = new MenuItem("上传...");
        uploadItem.setOnAction(e -> uploadFile());
        uploadItem.setStyle("-fx-font-size: 12px;");

        SeparatorMenuItem sep2 = new SeparatorMenuItem();

        Menu newMenu = new Menu("新建");
        newMenu.setStyle("-fx-font-size: 12px;");

        MenuItem newFolderItem = new MenuItem("新建文件夹");
        newFolderItem.setOnAction(e -> createFolder());
        newFolderItem.setStyle("-fx-font-size: 12px;");

        MenuItem newFileItem = new MenuItem("新建文件");
        newFileItem.setOnAction(e -> createFile());
        newFileItem.setStyle("-fx-font-size: 12px;");

        newMenu.getItems().addAll(newFolderItem, newFileItem);

        MenuItem renameItem = new MenuItem("重命名");
        renameItem.setOnAction(e -> renameSelectedFile());
        renameItem.setStyle("-fx-font-size: 12px;");

        SeparatorMenuItem sep3 = new SeparatorMenuItem();

        MenuItem deleteItem = new MenuItem("删除");
        deleteItem.setOnAction(e -> deleteFile());
        deleteItem.setStyle("-fx-font-size: 12px; -fx-text-fill: #cd3131;");

        MenuItem quickDeleteItem = new MenuItem("快速删除 (rm)");
        quickDeleteItem.setOnAction(e -> quickDeleteFile());
        quickDeleteItem.setStyle("-fx-font-size: 12px; -fx-text-fill: #cd3131;");

        SeparatorMenuItem sep4 = new SeparatorMenuItem();

        MenuItem permissionsItem = new MenuItem("文件权限...");
        permissionsItem.setOnAction(e -> changeFilePermissions());
        permissionsItem.setStyle("-fx-font-size: 12px;");

        MenuItem chmodItem = new MenuItem("快速修改权限...");
        chmodItem.setOnAction(e -> quickChmod());
        chmodItem.setStyle("-fx-font-size: 12px;");

        MenuItem chownItem = new MenuItem("修改所有者...");
        chownItem.setOnAction(e -> changeOwner());
        chownItem.setStyle("-fx-font-size: 12px;");

        SeparatorMenuItem sep5 = new SeparatorMenuItem();

        MenuItem propertiesItem = new MenuItem("属性");
        propertiesItem.setOnAction(e -> showFileProperties());
        propertiesItem.setStyle("-fx-font-size: 12px;");

        contextMenu.getItems().addAll(
                refreshItem, openItem, openWithItem,
                new SeparatorMenuItem(),
                copyPathItem, copyNameItem, copyContentItem, referenceToAiItem,
                sep1,
                downloadItem, uploadItem,
                sep2,
                newMenu,
                renameItem,
                sep3,
                deleteItem, quickDeleteItem,
                sep4,
                permissionsItem, chmodItem, chownItem,
                sep5,
                propertiesItem
        );

        fileTable.setContextMenu(contextMenu);
    }

    /**
     * 添加一个文件/文件夹引用到 AI 对话输入框上方。
     * 会去重；若新添加，显示一个带 × 按钮的蓝色标签。
     */
    private void addAiFileReference(String remotePath) {
        if (remotePath == null || remotePath.isEmpty()) return;
        boolean added = aiReferencedPaths.add(remotePath);
        if (!added) {
            updateStatus("已引用: " + remotePath);
            return;
        }
        renderReferenceTags();
        updateStatus("已引用到AI: " + remotePath);
        // 如果 AI 面板是折叠的，展开方便用户看到
        if (rightCollapsed) {
            toggleRightPanel();
        }
    }

    private void removeAiFileReference(String remotePath) {
        aiReferencedPaths.remove(remotePath);
        renderReferenceTags();
    }

    /**
     * 清空所有引用标签（发送后可保留，供下次对话继续引用，无需自动清空）。
     */
    private void clearAiFileReferences() {
        aiReferencedPaths.clear();
        renderReferenceTags();
    }

    /**
     * 重新渲染引用标签栏：根据 aiReferencedPaths 重新构建所有标签节点。
     */
    private void renderReferenceTags() {
        if (fileReferences == null) return;
        fileReferences.getChildren().clear();
        if (aiReferencedPaths.isEmpty()) {
            fileReferences.setVisible(false);
            fileReferences.setManaged(false);
            return;
        }
        fileReferences.setVisible(true);
        fileReferences.setManaged(true);
        for (String path : aiReferencedPaths) {
            fileReferences.getChildren().add(buildReferenceChip(path));
        }
    }

    /**
     * 构造单个引用标签：文件夹图标 + 路径短名 + × 删除按钮。
     */
    private HBox buildReferenceChip(String remotePath) {
        HBox chip = new HBox();
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.setSpacing(4);
        String chipStyle =
                "-fx-background-color: #1a3a5c;" +
                "-fx-border-color: #2d6fb8;" +
                "-fx-border-radius: 4;" +
                "-fx-background-radius: 4;" +
                "-fx-padding: 3 6 3 8;" +
                "-fx-cursor: hand;";
        chip.setStyle(chipStyle);

        // 文件/目录图标（用 emoji 简化，避免额外资源）
        Label icon = new Label("📎");
        icon.setStyle("-fx-font-size: 12px;");

        // 路径显示：太长时仅显示最后两段
        String display = remotePath;
        int slash1 = remotePath.lastIndexOf('/');
        if (slash1 > 0) {
            int slash2 = remotePath.lastIndexOf('/', slash1 - 1);
            if (slash2 >= 0 && remotePath.length() > 50) {
                display = "…" + remotePath.substring(slash2);
            }
        }
        Label pathLabel = new Label(display);
        pathLabel.setStyle("-fx-text-fill: #9cc7ff; -fx-font-size: 12px;");
        pathLabel.setTooltip(new Tooltip(remotePath));

        // × 删除按钮
        Button closeBtn = new Button("×");
        closeBtn.setStyle(
                "-fx-background-color: transparent;" +
                "-fx-text-fill: #9cc7ff;" +
                "-fx-font-size: 14px;" +
                "-fx-font-weight: bold;" +
                "-fx-padding: 0 2 0 2;" +
                "-fx-cursor: hand;" +
                "-fx-border-color: transparent;");
        closeBtn.setOnAction(e -> removeAiFileReference(remotePath));

        // 点击标签主体：直接把路径插入输入框当前光标位置
        chip.setOnMouseClicked(e -> {
            if (aiInput != null) {
                int pos = aiInput.getCaretPosition();
                String text = aiInput.getText();
                String insert = " \"" + remotePath + "\" ";
                aiInput.setText(text.substring(0, pos) + insert + text.substring(pos));
                aiInput.positionCaret(pos + insert.length());
                aiInput.requestFocus();
            }
        });

        chip.getChildren().addAll(icon, pathLabel, closeBtn);
        return chip;
    }

    /**
     * 构造引用上下文文本：发送消息时拼接到用户消息末尾，让 AI 知道当前引用了哪些路径。
     */
    private String buildReferenceContextSnippet() {
        if (aiReferencedPaths.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n[引用的文件/文件夹路径]\n");
        int i = 1;
        for (String p : aiReferencedPaths) {
            sb.append(i++).append(". ").append(p).append("\n");
        }
        sb.append("请针对以上引用的路径进行操作（如读取、分析、修改等）。");
        return sb.toString();
    }

    /**
     * 更新终端底部的 AI 操作状态指示器。
     * TYPING    → 🤖 蓝色"AI 正在输入命令..."（带闪烁效果）
     * EXECUTING → ⏳ 蓝色"命令执行中..."
     * IDLE      → 隐藏指示器
     *
     * @param state   AI 当前操作状态
     * @param command 正在输入/执行的命令文本（可截断显示）
     */
    public void updateAiTerminalStatus(TerminalController.AiBusyState state, String command) {
        if (aiTerminalStatus == null && aiActivityIndicator == null) return;
        switch (state) {
            case TYPING:
                String cmdShort = command;
                if (cmdShort != null && cmdShort.length() > 40) {
                    cmdShort = cmdShort.substring(0, 37) + "...";
                }
                if (aiTerminalStatus != null) {
                    aiTerminalStatus.setText("✦ AI 正在输入命令" + (cmdShort != null ? ": " + cmdShort : ""));
                    aiTerminalStatus.setStyle(
                            "-fx-text-fill: #58a6ff;" +
                            "-fx-font-size: 12px;" +
                            "-fx-padding: 0 0 0 10;" +
                            "-fx-font-family: 'Microsoft YaHei', 'Segoe UI Emoji', sans-serif;");
                    aiTerminalStatus.setVisible(true);
                    aiTerminalStatus.setManaged(true);
                }
                setAiActivity("● TYPING", "ai-activity-typing");
                break;
            case EXECUTING:
                if (aiTerminalStatus != null) {
                    aiTerminalStatus.setText("↻ 命令执行中 · 输出实时回传 AI");
                    aiTerminalStatus.setStyle(
                            "-fx-text-fill: #d29922;" +
                            "-fx-font-size: 12px;" +
                            "-fx-padding: 0 0 0 10;" +
                            "-fx-font-family: 'Microsoft YaHei', 'Segoe UI Emoji', sans-serif;");
                    aiTerminalStatus.setVisible(true);
                    aiTerminalStatus.setManaged(true);
                }
                setAiActivity("● RUNNING", "ai-activity-running");
                break;
            case IDLE:
            default:
                if (aiTerminalStatus != null) {
                    aiTerminalStatus.setVisible(false);
                    aiTerminalStatus.setManaged(false);
                }
                boolean ready = terminalController != null && terminalController.isConnected();
                setAiActivity(ready ? "● READY" : "● OFFLINE",
                        ready ? "ai-activity-ready" : "ai-activity-offline");
                break;
        }
    }

    private void setAiActivity(String text, String stateClass) {
        if (aiActivityIndicator == null) return;
        aiActivityIndicator.setText(text);
        aiActivityIndicator.getStyleClass().removeAll(
                "ai-activity-ready", "ai-activity-typing",
                "ai-activity-running", "ai-activity-offline");
        if (!aiActivityIndicator.getStyleClass().contains(stateClass)) {
            aiActivityIndicator.getStyleClass().add(stateClass);
        }
    }

    private void openWithDialog(FileItem item) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("打开方式 - " + item.getName());
        dialog.setHeaderText("选择查看/编辑方式");

        ButtonType okBtn = new ButtonType("确定", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okBtn, ButtonType.CANCEL);

        ListView<String> listView = new ListView<>();
        listView.getItems().addAll(
                "在终端中查看 (cat)",
                "在终端中编辑 (vi)",
                "在终端中编辑 (nano)",
                "下载到本地查看",
                "使用内置文本编辑器"
        );
        listView.setPrefHeight(180);
        listView.getSelectionModel().select(0);

        dialog.getDialogPane().setContent(listView);

        dialog.setResultConverter(btn -> {
            if (btn == okBtn) {
                return listView.getSelectionModel().getSelectedItem();
            }
            return null;
        });

        dialog.showAndWait().ifPresent(choice -> {
            if (choice == null) return;
            switch (choice) {
                case "在终端中查看 (cat)":
                    executeInTerminal("cat " + item.getFullPath());
                    break;
                case "在终端中编辑 (vi)":
                    executeInTerminal("vi " + item.getFullPath());
                    break;
                case "在终端中编辑 (nano)":
                    executeInTerminal("nano " + item.getFullPath());
                    break;
                case "下载到本地查看":
                    downloadFile();
                    break;
                case "使用内置文本编辑器":
                    openInInternalEditor(item);
                    break;
            }
        });
    }

    private void openInInternalEditor(FileItem item) {
        if (item.isDirectory()) return;
        new Thread(() -> {
            try {
                String connKey = getCurrentConnectionKey();
                if (connKey == null) return;
                SshConnectionManager.SftpChannel sftp = SshConnectionManager.getInstance().openSftp(connKey);
                String content = sftp.readTextFile(item.getFullPath());
                sftp.close();

                Platform.runLater(() -> {
                    Dialog<Boolean> editorDialog = new Dialog<>();
                    editorDialog.setTitle("编辑 - " + item.getFullPath());
                    editorDialog.setHeaderText(null);

                    ButtonType saveBtn = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
                    editorDialog.getDialogPane().getButtonTypes().addAll(saveBtn, ButtonType.CANCEL);

                    TextArea editor = new TextArea(content);
                    editor.setWrapText(true);
                    editor.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 13px;");
                    editor.setPrefWidth(800);
                    editor.setPrefHeight(600);
                    editorDialog.getDialogPane().setContent(editor);

                    editorDialog.setResultConverter(btn -> {
                        if (btn == saveBtn) {
                            return true;
                        }
                        return null;
                    });

                    editorDialog.showAndWait().ifPresent(save -> {
                        if (save != null && save) {
                            String newContent = editor.getText();
                            new Thread(() -> {
                                try {
                                    SshConnectionManager.SftpChannel sftp2 = SshConnectionManager.getInstance().openSftp(getCurrentConnectionKey());
                                    sftp2.writeTextFile(item.getFullPath(), newContent);
                                    sftp2.close();
                                    Platform.runLater(() -> {
                                        updateStatus("保存成功: " + item.getName());
                                        refreshFiles();
                                    });
                                } catch (Exception ex) {
                                    Platform.runLater(() -> showAlert("保存失败: " + ex.getMessage()));
                                }
                            }).start();
                        }
                    });
                });
            } catch (Exception ex) {
                Platform.runLater(() -> showAlert("读取文件失败: " + ex.getMessage()));
            }
        }).start();
    }

    private void renameSelectedFile() {
        FileItem selected = fileTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        TextInputDialog dialog = new TextInputDialog(selected.getName());
        dialog.setTitle("重命名");
        dialog.setHeaderText("重命名: " + selected.getName());
        dialog.setContentText("新名称:");

        dialog.showAndWait().ifPresent(newName -> {
            if (newName != null && !newName.isEmpty() && !newName.equals(selected.getName())) {
                String oldPath = selected.getFullPath();
                String parentPath = oldPath.substring(0, oldPath.lastIndexOf('/'));
                String newPath = parentPath + "/" + newName;

                new Thread(() -> {
                    try {
                        SshConnectionManager.SftpChannel sftp = SshConnectionManager.getInstance().openSftp(getCurrentConnectionKey());
                        sftp.rename(oldPath, newPath);
                        sftp.close();
                        Platform.runLater(() -> {
                            updateStatus("重命名成功: " + newName);
                            refreshFiles();
                        });
                    } catch (Exception e) {
                        Platform.runLater(() -> showAlert("重命名失败: " + e.getMessage()));
                    }
                }).start();
            }
        });
    }

    @FXML
    private void createFile() {
        if (selectedServer == null) return;
        TextInputDialog dialog = new TextInputDialog("new_file.txt");
        dialog.setTitle("新建文件");
        dialog.setHeaderText("请输入文件名:");
        dialog.showAndWait().ifPresent(name -> {
            String path = getCurrentRemotePath();
            String fullPath = path.endsWith("/") ? path + name : path + "/" + name;
            new Thread(() -> {
                try {
                    SshConnectionManager.SftpChannel sftp = SshConnectionManager.getInstance().openSftp(getCurrentConnectionKey());
                    sftp.writeTextFile(fullPath, "");
                    sftp.close();
                    Platform.runLater(() -> loadRemoteDirectory(path));
                } catch (Exception e) {
                    Platform.runLater(() -> showAlert("创建失败: " + e.getMessage()));
                }
            }).start();
        });
    }

    private void quickDeleteFile() {
        FileItem selected = fileTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("快速删除");
        confirm.setHeaderText(null);
        confirm.setContentText("确定快速删除 \"" + selected.getName() + "\" ?\n此操作不可恢复！");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                // 安全修复：单引号转义路径防止命令注入
                String safePath = SecurityUtils.sanitizeInput(selected.getFullPath());
                String cmd = selected.isDirectory()
                        ? "rm -rf " + safePath
                        : "rm -f " + safePath;
                new Thread(() -> {
                    try {
                        SshConnectionManager.getInstance().executeCommand(getCurrentConnectionKey(), cmd);
                        Platform.runLater(() -> {
                            updateStatus("已删除: " + selected.getName());
                            refreshFiles();
                        });
                    } catch (Exception e) {
                        Platform.runLater(() -> showAlert("删除失败: " + e.getMessage()));
                    }
                }).start();
            }
        });
    }

    private void changeFilePermissions() {
        FileItem selected = fileTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("文件权限");
        dialog.setHeaderText(selected.getName() + " - 当前权限: " + selected.getPermissions());

        ButtonType okBtn = new ButtonType("确定", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okBtn, ButtonType.CANCEL);

        VBox content = new VBox(8);
        content.setPadding(new Insets(10));

        TextField permField = new TextField(selected.getPermissions());
        permField.setPromptText("如 755, 644, 777");

        CheckBox readOwner = new CheckBox("所有者-读取");
        CheckBox writeOwner = new CheckBox("所有者-写入");
        CheckBox execOwner = new CheckBox("所有者-执行");
        CheckBox readGroup = new CheckBox("用户组-读取");
        CheckBox writeGroup = new CheckBox("用户组-写入");
        CheckBox execGroup = new CheckBox("用户组-执行");
        CheckBox readOther = new CheckBox("其他-读取");
        CheckBox writeOther = new CheckBox("其他-写入");
        CheckBox execOther = new CheckBox("其他-执行");

        // Parse current permissions to set checkboxes
        String perms = selected.getPermissions();
        if (perms.length() >= 9) {
            readOwner.setSelected(perms.charAt(1) == 'r');
            writeOwner.setSelected(perms.charAt(2) == 'w');
            execOwner.setSelected(perms.charAt(3) == 'x');
            readGroup.setSelected(perms.charAt(4) == 'r');
            writeGroup.setSelected(perms.charAt(5) == 'w');
            execGroup.setSelected(perms.charAt(6) == 'x');
            readOther.setSelected(perms.charAt(7) == 'r');
            writeOther.setSelected(perms.charAt(8) == 'w');
            execOther.setSelected(perms.charAt(9) == 'x');
        }

        content.getChildren().addAll(
                new Label("权限码:"), permField,
                new Separator(),
                readOwner, writeOwner, execOwner,
                readGroup, writeGroup, execGroup,
                readOther, writeOther, execOther
        );

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(320);

        dialog.setResultConverter(btn -> {
            if (btn == okBtn) return permField.getText();
            return null;
        });

        dialog.showAndWait().ifPresent(perm -> {
            if (perm != null && !perm.isEmpty()) {
                // 安全修复：权限码仅允许八进制数字，路径单引号转义防止命令注入
                String safePerm = perm.replaceAll("[^0-7]", "");
                if (safePerm.isEmpty()) {
                    showAlert("权限码非法: " + perm);
                    return;
                }
                String cmd = "chmod " + safePerm + " " + SecurityUtils.sanitizeInput(selected.getFullPath());
                new Thread(() -> {
                    try {
                        SshConnectionManager.getInstance().executeCommand(getCurrentConnectionKey(), cmd);
                        Platform.runLater(() -> {
                            updateStatus("权限已更改: " + perm);
                            refreshFiles();
                        });
                    } catch (Exception e) {
                        Platform.runLater(() -> showAlert("更改权限失败: " + e.getMessage()));
                    }
                }).start();
            }
        });
    }

    private void quickChmod() {
        FileItem selected = fileTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("快速修改权限");
        dialog.setHeaderText(selected.getName());

        ButtonType okBtn = new ButtonType("应用", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okBtn, ButtonType.CANCEL);

        VBox content = new VBox(8);
        content.setPadding(new Insets(10));

        TextField permField = new TextField("755");
        permField.setPromptText("权限码 (如 755)");

        ToggleGroup group = new ToggleGroup();
        RadioButton rb755 = new RadioButton("755 (rwxr-xr-x)");
        rb755.setToggleGroup(group); rb755.setUserData("755");
        RadioButton rb777 = new RadioButton("777 (rwxrwxrwx)");
        rb777.setToggleGroup(group); rb777.setUserData("777");
        RadioButton rb644 = new RadioButton("644 (rw-r--r--)");
        rb644.setToggleGroup(group); rb644.setUserData("644");
        RadioButton rb600 = new RadioButton("600 (rw-------)");
        rb600.setToggleGroup(group); rb600.setUserData("600");
        RadioButton rb700 = new RadioButton("700 (rwx------)");
        rb700.setToggleGroup(group); rb700.setUserData("700");

        rb755.setSelected(true);

        content.getChildren().addAll(
                new Label("常用权限:"), permField,
                new Separator(),
                rb755, rb777, rb644, rb600, rb700
        );

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(280);

        dialog.setResultConverter(btn -> {
            if (btn == okBtn) {
                if (group.getSelectedToggle() != null) {
                    return (String) group.getSelectedToggle().getUserData();
                }
                return permField.getText();
            }
            return null;
        });

        dialog.showAndWait().ifPresent(perm -> {
            if (perm != null && !perm.isEmpty()) {
                // 安全修复：权限码仅允许八进制数字，路径单引号转义防止命令注入
                String safePerm = perm.replaceAll("[^0-7]", "");
                if (safePerm.isEmpty()) {
                    showAlert("权限码非法: " + perm);
                    return;
                }
                String cmd = "chmod " + safePerm + " " + SecurityUtils.sanitizeInput(selected.getFullPath());
                new Thread(() -> {
                    try {
                        SshConnectionManager.getInstance().executeCommand(getCurrentConnectionKey(), cmd);
                        Platform.runLater(() -> {
                            updateStatus("权限已更改: " + perm);
                            refreshFiles();
                        });
                    } catch (Exception e) {
                        Platform.runLater(() -> showAlert("更改权限失败: " + e.getMessage()));
                    }
                }).start();
            }
        });
    }

    private void changeOwner() {
        FileItem selected = fileTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("修改所有者");
        dialog.setHeaderText(selected.getName());

        ButtonType okBtn = new ButtonType("确定", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okBtn, ButtonType.CANCEL);

        VBox content = new VBox(8);
        content.setPadding(new Insets(10));

        TextField userField = new TextField("root");
        userField.setPromptText("所有者 (如 root)");
        TextField groupField = new TextField("root");
        groupField.setPromptText("用户组 (如 root)");

        content.getChildren().addAll(
                new Label("所有者:"), userField,
                new Label("用户组:"), groupField
        );

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(300);

        dialog.setResultConverter(btn -> {
            if (btn == okBtn) return userField.getText() + ":" + groupField.getText();
            return null;
        });

        dialog.showAndWait().ifPresent(owner -> {
            if (owner != null && !owner.isEmpty()) {
                // 安全修复：owner 仅允许 user:group 安全字符，路径单引号转义防止命令注入
                String safeOwner = owner.replaceAll("[^a-zA-Z0-9._:-]", "");
                if (safeOwner.isEmpty()) {
                    showAlert("所有者格式非法: " + owner);
                    return;
                }
                String cmd = "chown " + safeOwner + " " + SecurityUtils.sanitizeInput(selected.getFullPath());
                new Thread(() -> {
                    try {
                        SshConnectionManager.getInstance().executeCommand(getCurrentConnectionKey(), cmd);
                        Platform.runLater(() -> {
                            updateStatus("所有者已更改: " + owner);
                            refreshFiles();
                        });
                    } catch (Exception e) {
                        Platform.runLater(() -> showAlert("更改所有者失败: " + e.getMessage()));
                    }
                }).start();
            }
        });
    }

    private void showFileProperties() {
        FileItem selected = fileTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        new Thread(() -> {
            try {
                String connKey = getCurrentConnectionKey();
                if (connKey == null) return;
                String cmd = "stat -c 'Name: %n\\nSize: %s bytes\\nType: %F\\nPermission: %a (%A)\\nOwner: %U:%G\\nAccess: %a\\nModify: %y\\nChange: %z' \"" + selected.getFullPath() + "\" 2>/dev/null || ls -la \"" + selected.getFullPath() + "\"";
                String result = SshConnectionManager.getInstance().executeCommand(connKey, cmd);

                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("属性 - " + selected.getName());
                    alert.setHeaderText(null);

                    TextArea textArea = new TextArea(result);
                    textArea.setEditable(false);
                    textArea.setWrapText(true);
                    textArea.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px;");
                    textArea.setPrefWidth(450);
                    textArea.setPrefHeight(300);
                    alert.getDialogPane().setContent(textArea);
                    alert.setResizable(true);
                    alert.showAndWait();
                });
            } catch (Exception e) {
                Platform.runLater(() -> showAlert("获取属性失败: " + e.getMessage()));
            }
        }).start();
    }

    private void executeInTerminal(String cmd) {
        if (terminalController != null && terminalController.isConnected()) {
            terminalController.sendCommand(cmd);
        } else {
            showAlert("请先连接到服务器");
        }
    }

    private void initFileDragDrop() {
        // 整个文件管理区（fileSplitPane）均可接受拖放，而非仅 fileTable
        fileSplitPane.setOnDragOver(event -> {
            if (event.getGestureSource() != fileSplitPane && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });

        fileSplitPane.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
                // 连接检查：未连接服务器时提示并返回
                if (selectedServer == null || getCurrentConnectionKey() == null) {
                    showAlert("请先连接服务器");
                    event.setDropCompleted(false);
                    event.consume();
                    return;
                }
                success = true;
                String remoteDir = getCurrentRemotePath();

                for (File file : db.getFiles()) {
                    String remotePath = remoteDir.endsWith("/") ? remoteDir + file.getName() : remoteDir + "/" + file.getName();
                    if (file.isFile()) {
                        uploadFileToServer(file.getAbsolutePath(), remotePath);
                    } else if (file.isDirectory()) {
                        uploadDirectoryToServer(file, remotePath);
                    }
                }
            }
            event.setDropCompleted(success);
            event.consume();
        });
    }

    /**
     * 递归上传本地文件夹到远程服务器。
     * 先用 mkdir -p 创建远程目录，再逐个上传文件、递归子目录。
     */
    private void uploadDirectoryToServer(File localDir, String remoteDir) {
        new Thread(() -> {
            String connKey = getCurrentConnectionKey();
            try {
                // 创建远程目录
                SshConnectionManager.getInstance().executeCommand(connKey, "mkdir -p " + remoteDir);
                // 递归上传内容
                File[] children = localDir.listFiles();
                if (children != null) {
                    for (File child : children) {
                        String childRemote = remoteDir.endsWith("/") ? remoteDir + child.getName() : remoteDir + "/" + child.getName();
                        if (child.isFile()) {
                            uploadFileToServer(child.getAbsolutePath(), childRemote);
                        } else if (child.isDirectory()) {
                            uploadDirectoryToServer(child, childRemote);
                        }
                    }
                }
                Platform.runLater(() -> {
                    showAlert("文件夹上传完成: " + localDir.getName());
                    refreshFiles();
                });
            } catch (Exception e) {
                Platform.runLater(() -> showAlert("文件夹上传失败: " + e.getMessage()));
            }
        }, "UploadDir-" + localDir.getName()).start();
    }

    @FXML
    private void navigateToPath() {
        String connKey = getCurrentConnectionKey();
        if (selectedServer == null || connKey == null || !SshConnectionManager.getInstance().isConnected(connKey)) {
            showAlert("Please connect to a server first");
            return;
        }
        String path = getCurrentRemotePath();
        loadRemoteDirectory(path);
    }

    @FXML
    private void navigateToPathRoot() {
        loadRemoteDirectory("/");
    }

    @FXML
    private void navigateToParent() {
        String current = getCurrentRemotePath();
        if (current.equals("/")) return;
        int lastSlash = current.lastIndexOf('/');
        String parent = lastSlash <= 0 ? "/" : current.substring(0, lastSlash);
        loadRemoteDirectory(parent);
    }

    private void loadRemoteDirectory(String path) {
        if (selectedServer == null) return;
        String connKey = getCurrentConnectionKey();
        if (connKey == null) return;
        new Thread(() -> {
            try {
                String result = SshConnectionManager.getInstance().executeCommand(
                        connKey,
                        "ls -la " + path + " 2>/dev/null || echo 'LS_FAILED'"
                );

                Platform.runLater(() -> {
                    fileTable.getItems().clear();
                    if (result.contains("LS_FAILED")) {
                        showAlert("Cannot access directory: " + path);
                        return;
                    }

                    String[] lines = result.split("\n");
                    for (String line : lines) {
                        if (line.startsWith("total") || line.isEmpty()) continue;
                        FileItem item = parseLsLine(line, path);
                        if (item != null) {
                            fileTable.getItems().add(item);
                        }
                    }
                    setCurrentRemotePath(path);
                    updateBreadcrumb(path);
                });
            } catch (Exception e) {
                Platform.runLater(() -> showAlert("Failed to load directory: " + e.getMessage()));
            }
        }).start();
    }

    private void updateBreadcrumb(String path) {
        breadcrumbBar.getChildren().clear();

        Button rootBtn = new Button("/");
        rootBtn.getStyleClass().add("breadcrumb-btn");
        rootBtn.setOnAction(e -> loadRemoteDirectory("/"));
        breadcrumbBar.getChildren().add(rootBtn);

        if (path.equals("/")) return;

        String[] parts = path.split("/");
        StringBuilder currentPath = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            currentPath.append("/").append(part);

            Label sep = new Label(" > ");
            sep.getStyleClass().add("breadcrumb-sep");
            breadcrumbBar.getChildren().add(sep);

            String segmentPath = currentPath.toString();
            Button btn = new Button(part);
            btn.getStyleClass().add("breadcrumb-btn");
            btn.setOnAction(e -> loadRemoteDirectory(segmentPath));
            breadcrumbBar.getChildren().add(btn);
        }
    }

    private FileItem parseLsLine(String line, String parentPath) {
        String[] parts = line.split("\\s+", 9);
        if (parts.length < 9) return null;

        String permissions = parts[0];
        boolean isDir = permissions.startsWith("d");
        String name = parts[8];
        if (name.equals(".") || name.equals("..")) return null;

        long size = 0;
        try { size = Long.parseLong(parts[4]); } catch (NumberFormatException e) {}

        String time = parts[5] + " " + parts[6] + " " + parts[7];
        String fullPath = parentPath.endsWith("/") ? parentPath + name : parentPath + "/" + name;
        String ownerGroup = parts[2] + "/" + parts[3];

        FileItem item = new FileItem(name, size, permissions, time, isDir);
        item.setFullPath(fullPath);
        item.setOwnerGroup(ownerGroup);
        return item;
    }

    @FXML
    private void uploadFile() {
        if (selectedServer == null) {
            showAlert("Please connect to a server first");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select file to upload");
        List<File> files = chooser.showOpenMultipleDialog(primaryStage);
        if (files != null) {
            String remoteDir = getCurrentRemotePath();
            for (File file : files) {
                String remotePath = remoteDir.endsWith("/") ? remoteDir + file.getName() : remoteDir + "/" + file.getName();
                uploadFileToServer(file.getAbsolutePath(), remotePath);
            }
        }
    }

    private void uploadFileToServer(String localPath, String remotePath) {
        new Thread(() -> {
            try {
                SshConnectionManager.SftpChannel sftp = SshConnectionManager.getInstance().openSftp(getCurrentConnectionKey());
                sftp.upload(localPath, remotePath);
                sftp.close();
                Platform.runLater(() -> {
                    showAlert("Upload complete: " + new File(localPath).getName());
                    refreshFiles();
                });
            } catch (Exception e) {
                Platform.runLater(() -> showAlert("Upload failed: " + e.getMessage()));
            }
        }).start();
    }

    @FXML
    private void downloadFile() {
        FileItem selected = fileTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert("请选择文件或文件夹");
            return;
        }

        String connKey = getCurrentConnectionKey();
        if (connKey == null) {
            showAlert("请先连接服务器");
            return;
        }

        String downloadDir = DownloadManager.getInstance().getDefaultDownloadPath();

        if (selected.isDirectory()) {
            String localPath = downloadDir + File.separator + selected.getName();
            DownloadManager.getInstance().downloadFolder(connKey, selected.getFullPath(), localPath);
            showAlert("开始下载文件夹: " + selected.getName() + "\n保存到: " + localPath);
        } else {
            String localPath = downloadDir + File.separator + selected.getName();
            DownloadManager.getInstance().downloadFile(connKey, selected.getFullPath(), localPath);
            showAlert("开始下载: " + selected.getName() + "\n保存到: " + localPath);
        }
    }

    @FXML
    private void showTransferManager() {
        new DownloadManagerDialog().show(serverConfigMap);
    }

    @FXML
    private void createFolder() {
        if (selectedServer == null) return;
        TextInputDialog dialog = new TextInputDialog("new_folder");
        dialog.setTitle("New Folder");
        dialog.setHeaderText("Enter folder name:");
        dialog.showAndWait().ifPresent(name -> {
            String path = getCurrentRemotePath();
            String fullPath = path.endsWith("/") ? path + name : path + "/" + name;
            new Thread(() -> {
                try {
                    SshConnectionManager.SftpChannel sftp = SshConnectionManager.getInstance().openSftp(getCurrentConnectionKey());
                    sftp.mkdir(fullPath);
                    sftp.close();
                    Platform.runLater(() -> loadRemoteDirectory(path));
                } catch (Exception e) {
                    Platform.runLater(() -> showAlert("Create failed: " + e.getMessage()));
                }
            }).start();
        });
    }

    @FXML
    private void refreshFiles() {
        String path = getCurrentRemotePath();
        if (path != null) loadRemoteDirectory(path);
    }

    @FXML
    private void deleteFile() {
        FileItem selected = fileTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setContentText("Delete " + selected.getName() + "?");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                new Thread(() -> {
                    try {
                        SshConnectionManager.SftpChannel sftp = SshConnectionManager.getInstance().openSftp(getCurrentConnectionKey());
                        if (selected.isDirectory()) {
                            sftp.rmdir(selected.getFullPath());
                        } else {
                            sftp.rm(selected.getFullPath());
                        }
                        sftp.close();
                        Platform.runLater(() -> refreshFiles());
                    } catch (Exception e) {
                        Platform.runLater(() -> showAlert("Delete failed: " + e.getMessage()));
                    }
                }).start();
            }
        });
    }

    // ========== AI Assistant (TRAE Style) ==========

    /**
     * Add a user message bubble to the chat.
     */
    public void addChatMessage(String role, String content) {
        // 隐藏欢迎界面，显示聊天区域
        aiWelcomeScreen.setVisible(false);
        aiWelcomeScreen.setManaged(false);
        aiChatScroll.setVisible(true);
        aiChatScroll.setManaged(true);

        VBox bubble;
        if ("user".equals(role)) {
            // 用户消息：蓝色背景，右对齐
            bubble = ChatBubbleFactory.createUserBubble(content);
        } else {
            // AI消息：深灰背景，左对齐，Markdown渲染
            lastAssistantFullText.setLength(0);
            lastAssistantFullText.append(content);
            bubble = ChatBubbleFactory.createAssistantBubble(content, this::sendCodeToTerminal);
            // 非流式消息（如加载历史）直接添加复制/重试按钮
            ChatBubbleFactory.addActionButtons(bubble, content, this::regenerateLastResponse);
        }

        aiChatMessages.getChildren().add(bubble);

        // 滚动到底部
        Platform.runLater(() -> aiChatScroll.setVvalue(1.0));
    }

    /** 当前流式AI气泡 */
    private VBox lastAssistantBubble = null;
    /** 当前流式AI气泡的累积文本 */
    private final StringBuilder lastAssistantFullText = new StringBuilder();

    public void startAssistantBubble() {
        // 隐藏欢迎界面，显示聊天区域
        aiWelcomeScreen.setVisible(false);
        aiWelcomeScreen.setManaged(false);
        aiChatScroll.setVisible(true);
        aiChatScroll.setManaged(true);

        // 重置累积文本
        lastAssistantFullText.setLength(0);
        // 创建空气泡（使用ChatBubbleFactory）
        lastAssistantBubble = ChatBubbleFactory.createAssistantBubble(
                "", this::sendCodeToTerminal);
        aiChatMessages.getChildren().add(lastAssistantBubble);
    }

    public void appendToAssistantBubble(String token) {
        if (lastAssistantBubble != null) {
            // 累积文本，节流渲染（120ms）。本方法已在 FX 线程（onToken 经 Platform.runLater 包裹），
            // 故直接同步滚动到底，避免每 token 再排队一个 runLater 任务造成抖动。
            lastAssistantFullText.append(token);
            ChatBubbleFactory.updateAssistantBubble(
                    lastAssistantBubble,
                    lastAssistantFullText.toString(),
                    this::sendCodeToTerminal
            );
            aiChatScroll.setVvalue(1.0);
        }
    }

    /**
     * 流式结束时最终Markdown渲染
     */
    public void finalizeAssistantBubble() {
        if (lastAssistantBubble != null) {
            String fullText = lastAssistantFullText.toString();
            ChatBubbleFactory.finalizeBubble(
                    lastAssistantBubble,
                    fullText,
                    this::sendCodeToTerminal);
            // 流式结束后添加复制/重试操作按钮
            ChatBubbleFactory.addActionButtons(lastAssistantBubble, fullText, this::regenerateLastResponse);
            Platform.runLater(() -> aiChatScroll.setVvalue(1.0));
        }
    }

    /**
     * 将指定代码块内容发送到终端执行，并给出操作反馈。
     * 每个代码块的"发送到终端"按钮直接传入本块代码（Consumer<String>），不再全局提取"最后一个块"。
     */
    private void sendCodeToTerminal(String code) {
        if (code == null || code.isBlank()) {
            showStatusFeedback("⚠ 未找到可发送的代码");
            return;
        }
        if (terminalController == null || !terminalController.isConnected()) {
            showStatusFeedback("✗ 未连接服务器，无法发送到终端");
            return;
        }
        try {
            terminalController.sendCommand(code.trim());
            showStatusFeedback("✓ 已发送到终端");
        } catch (Exception ex) {
            logger.error("发送代码到终端失败", ex);
            showStatusFeedback("✗ 发送失败: " + ex.getMessage());
        }
    }

    /**
     * 在底部状态栏临时显示操作反馈（2秒后恢复原文本），非阻塞。
     */
    private void showStatusFeedback(String msg) {
        if (statusText == null) return;
        final String previous = statusText.getText();
        statusText.setText(msg);
        javafx.animation.PauseTransition pt = new javafx.animation.PauseTransition(javafx.util.Duration.millis(2000));
        pt.setOnFinished(e -> statusText.setText(previous));
        pt.play();
    }

    /**
     * 发送/停止按钮统一入口：生成中点击则停止，否则发送当前输入。
     */
    @FXML
    private void handleSendOrStop() {
        if (aiGenerating) {
            stopAiGeneration();
        } else {
            sendAiMessage();
        }
    }

    private void sendAiMessage() {
        String msg = aiInput.getText();
        if (msg == null || msg.trim().isEmpty()) {
            // 若只有引用没有文字，不发送
            if (aiReferencedPaths.isEmpty()) return;
            msg = "请查看我引用的文件/文件夹并分析";
        }
        if (aiGenerating) return; // 防止重复发送
        aiInput.clear();
        // 拼接引用上下文到用户消息末尾，AI 即可看到当前引用了哪些路径
        String refSnippet = buildReferenceContextSnippet();
        String fullMsg = msg.trim() + refSnippet;
        lastUserMessage = fullMsg;
        executeAiSend(fullMsg);
    }

    /**
     * 重新生成最后一条 AI 回复：移除最后的 AI 气泡并重发上一条用户消息。
     */
    @FXML
    private void regenerateLastResponse() {
        if (aiGenerating) return;
        if (lastUserMessage == null || lastUserMessage.isEmpty()) {
            showAlert("没有可重新生成的消息");
            return;
        }
        // 移除最后一个 AI 气泡
        if (lastAssistantBubble != null && aiChatMessages.getChildren().contains(lastAssistantBubble)) {
            aiChatMessages.getChildren().remove(lastAssistantBubble);
            ChatBubbleFactory.disposeBubble(lastAssistantBubble);
            lastAssistantBubble = null;
        } else if (!aiChatMessages.getChildren().isEmpty()) {
            int n = aiChatMessages.getChildren().size();
            javafx.scene.Node last = aiChatMessages.getChildren().get(n - 1);
            if (last instanceof VBox) {
                aiChatMessages.getChildren().remove(last);
                ChatBubbleFactory.disposeBubble((VBox) last);
            }
        }
        lastAssistantFullText.setLength(0);
        executeAiSend(lastUserMessage);
    }

    /**
     * 新建对话：停止当前生成、清空聊天区、创建新会话。
     */
    @FXML
    private void newAiChat() {
        if (aiGenerating) stopAiGeneration();
        String name = "Chat " + java.time.LocalTime.now().toString().substring(0, 5);
        if (selectedServer != null && currentConnectionKey != null
                && SshConnectionManager.getInstance().isConnected(currentConnectionKey)) {
            openServerSessionAndConnect(selectedServer, name, true);
        } else {
            createBlankSessionAndActivate(name);
        }
        lastUserMessage = "";
    }

    /**
     * 停止当前 AI 生成：中断后台线程并定稿当前气泡。
     */
    private void stopAiGeneration() {
        // 先让当前请求失效，再中断线程；即使 provider 的异步回调稍后到达也会被请求 ID 隔离。
        activeAiRequestId = aiRequestSequence.incrementAndGet();
        if (aiGenerationThread != null) {
            aiGenerationThread.interrupt();
            aiGenerationThread = null;
        }
        // 会话至少保留用户的请求，并明确记录该轮被停止，避免 UI 有消息但历史记录丢失。
        Session stoppedSession = activeAiSession;
        String stoppedMessage = activeAiUserMessage;
        if (stoppedSession != null && stoppedMessage != null && !stoppedMessage.isBlank()) {
            SessionManager.getInstance().addMessage(stoppedSession.getId(),
                    new Message("user", stoppedMessage));
            SessionManager.getInstance().addMessage(stoppedSession.getId(),
                    new Message("assistant", "本轮生成已由用户停止。"));
        }
        activeAiSession = null;
        activeAiUserMessage = null;
        setAiGenerating(false);
        if (lastAssistantFullText.length() > 0) {
            lastAssistantFullText.append("\n\n_已停止生成_\n");
        }
        finalizeAssistantBubble();
        lastAssistantBubble = null;
        lastAssistantFullText.setLength(0);
    }

    private boolean isActiveAiRequest(long requestId) {
        return aiGenerating && activeAiRequestId == requestId;
    }

    /**
     * 切换发送按钮的生成/就绪状态（文本与样式）。
     */
    private void setAiGenerating(boolean generating) {
        aiGenerating = generating;
        Platform.runLater(() -> {
            if (aiSendBtn == null) return;
            if (generating) {
                aiSendBtn.setText("⏹ 停止");
                if (!aiSendBtn.getStyleClass().contains("ai-stop-btn")) {
                    aiSendBtn.getStyleClass().add("ai-stop-btn");
                }
            } else {
                aiSendBtn.setText("发送");
                aiSendBtn.getStyleClass().removeAll("ai-stop-btn");
            }
        });
    }

    /**
     * 执行 AI 发送的核心逻辑（构建上下文、流式调用 ToolAgent）。
     * 由 sendAiMessage / regenerateLastResponse 共用。
     */
    private void executeAiSend(String msg) {
        Session session = SessionManager.getInstance().getCurrentSession();
        if (session == null) {
            String name = "Chat " + java.time.LocalTime.now().toString().substring(0, 5);
            session = createBlankSessionAndActivate(name);
            if (session == null) return;
        }
        addChatMessage("user", msg);

        Session finalSession = session;
        SessionWorkspaceState.Data requestWorkspace = workspaceState.get(finalSession.getId());
        // The per-session workspace is the only authority for AI routing. Never
        // fall back to mutable global UI fields, which may still describe a tab
        // that was visible immediately before this request.
        final ServerConfig requestServer = requestWorkspace.serverConfig;
        final String requestConnectionKey = requestWorkspace.connectionKey;
        startAssistantBubble();
        setAiGenerating(true);
        final long requestId = aiRequestSequence.incrementAndGet();
        activeAiRequestId = requestId;
        activeAiSession = finalSession;
        activeAiUserMessage = msg;

        // 捕获拖入的文件（JavaFX 线程安全快照），发送后清空
        List<File> attachments = new ArrayList<>(pendingAttachments);
        pendingAttachments.clear();

        StringBuilder fullResponse = new StringBuilder();
        Thread genThread = new Thread(() -> {
            // 构建上下文：优先使用ServerContextHelper
            StringBuilder ctx = new StringBuilder();
            if (requestServer != null) {
                String serverCtx = ServerContextHelper.getPromptContext(finalSession.getId());
                if (serverCtx != null && !serverCtx.isEmpty()) {
                    ctx.append(serverCtx);
                } else {
                    // 首次对话：触发完整上下文拉取
                    if (requestConnectionKey != null) {
                        ServerContextHelper.fetchFullContextIfNeeded(
                            requestConnectionKey, requestServer.getId(), finalSession.getId());
                    }
                    // 回退到基本上下文
                    ctx.append("Connected server: ").append(requestServer.getName())
                       .append(" (").append(requestServer.getUsername()).append("@")
                       .append(requestServer.getHost()).append(")\n");
                }
            }

            // 注入拖入的本地文件上下文：告知 AI 文件路径和上传/部署指引
            if (!attachments.isEmpty()) {
                ctx.append("\n[用户拖入的本地文件]\n");
                for (File f : attachments) {
                    ctx.append("- ").append(f.getAbsolutePath()).append("\n");
                }
                ctx.append("这些文件已在本机就绪，可通过 upload_file 工具上传到服务器。")
                   .append("如用户指定了目标路径则上传到该位置（必要时用 execute_shell 执行 mkdir -p 创建目录）；")
                   .append("如用户表示随意或仅说\"部署\"，可在合适位置创建 /starshell/ 目录，分类上传后协助部署。\n");
            }

            // SSH 连接状态提示：若未连接，明确告知 AI 不要调用需要 SSH 的工具，
            // 引导用户先点击工具栏"连接"按钮。避免 AI 盲目调工具后收到技术性错误。
            boolean sshConnected = requestConnectionKey != null
                    && SshConnectionManager.getInstance().isConnected(requestConnectionKey);
            if (!sshConnected) {
                ctx.append("\n[重要] 当前未连接到服务器。")
                   .append("请勿调用 execute_shell/run_in_terminal/check_port 等需要 SSH 连接的工具。")
                   .append("如果用户的请求需要操作服务器，请提示用户先点击工具栏「连接」按钮连接服务器后再重试。\n");
            }

            // 使用ToolAgent替代直接chatStream
            ToolAgent agent = new ToolAgent();
            ToolContext toolCtx = new ToolContext(
                String.valueOf(finalSession.getId()),
                requestServer != null ? String.valueOf(requestServer.getId()) : "0",
                requestServer != null ? requestServer.getHost() : "",
                requestServer != null ? requestServer.getUsername() : "",
                requestConnectionKey
            );
            String systemPrompt = OpsPromptTemplates.buildOpsAssistantPrompt(ctx.toString());

            // 对话记忆：构建完整历史轮次。当前 msg 尚未存入 session（在 onComplete 才保存），
            // 故 finalSession.getMessages() 全为前序轮次，可直接作为 history 注入。
            // 长对话压缩由 ToolAgent 内部调用 ContextCompactor（按 model contextWindow）处理，
            // 不再在 MainController 硬截断，避免丢上下文。
            List<ChatMessage> history = new ArrayList<>();
            List<Message> priorMsgs = finalSession.getMessages();
            if (priorMsgs != null) {
                for (Message m : priorMsgs) {
                    if (m != null && m.getContent() != null) {
                        history.add(new ChatMessage(m.getRole(), m.getContent()));
                    }
                }
            }
            // M-P1-2/M-P1-3: 跨线程接收 onPersistedText 回调（纯自然语言回复，不含工具状态行），
            // onComplete 中优先用它持久化，未触发时回退到 fullResponse 保证不丢消息。
            AtomicReference<String> persistedTextRef = new AtomicReference<>();

            if (!isActiveAiRequest(requestId) || Thread.currentThread().isInterrupted()) {
                return;
            }

            Thread worker = agent.executeStream(msg, systemPrompt, toolCtx,
                token -> Platform.runLater(() -> {
                    if (isActiveAiRequest(requestId)) {
                        fullResponse.append(token);
                        appendToAssistantBubble(token);
                    }
                }),
                () -> Platform.runLater(() -> {
                    if (!isActiveAiRequest(requestId)) return;
                    // 用户消息照常保存
                    SessionManager.getInstance().addMessage(finalSession.getId(),
                        new Message("user", msg));
                    // M-P1-2：assistant 消息保存用 onPersistedText 回调收到的纯回复，
                    // 而非 fullResponse（后者混入了 [Executing tool]/[Result] 等状态行）。
                    // 若 onPersistedText 未触发（如异常路径），回退到 fullResponse 保证不丢消息。
                    String persisted = persistedTextRef.get();
                    if (persisted == null || persisted.isEmpty()) {
                        persisted = fullResponse.toString();
                    }
                    SessionManager.getInstance().addMessage(finalSession.getId(),
                        new Message("assistant", persisted));
                    finalizeAssistantBubble();
                    lastAssistantBubble = null;
                    lastAssistantFullText.setLength(0);
                    setAiGenerating(false);
                    aiGenerationThread = null;
                    activeAiSession = null;
                    activeAiUserMessage = null;
                }),
                error -> Platform.runLater(() -> {
                    if (!isActiveAiRequest(requestId)) return;
                    // P0-6: 流式失败也要保存用户消息
                    SessionManager.getInstance().addMessage(finalSession.getId(),
                        new Message("user", msg));
                    String errorText = "**Error**: " +
                            (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                    SessionManager.getInstance().addMessage(finalSession.getId(),
                        new Message("assistant", errorText));
                    appendToAssistantBubble("\n\n" + errorText);
                    finalizeAssistantBubble();
                    lastAssistantBubble = null;
                    lastAssistantFullText.setLength(0);
                    setAiGenerating(false);
                    aiGenerationThread = null;
                    activeAiSession = null;
                    activeAiUserMessage = null;
                }),
                request -> Platform.runLater(() -> {
                    if (isActiveAiRequest(requestId)) showToolPermissionDialog(request);
                    else request.deny();
                }),
                history,
                text -> Platform.runLater(() -> {
                    if (isActiveAiRequest(requestId)) persistedTextRef.set(text);
                })  // onPersistedText: 仅纯回复
            );
            if (isActiveAiRequest(requestId)) {
                aiGenerationThread = worker;
            } else {
                worker.interrupt();
            }
        });
        aiGenerationThread = genThread;
        genThread.start();
    }

    /**
     * 获取当前选中的服务器配置（供Helper类调用）。
     */
    public ServerConfig getSelectedServer() {
        return selectedServer;
    }

    /**
     * 显示工具权限确认对话框。
     * 当AI请求执行需要确认的工具时弹出，用户可选择允许/总是允许/拒绝。
     */
    private void showToolPermissionDialog(ToolPermission.PermissionRequest request) {
        // 改用自定义 Dialog 而非 Alert：Alert.setContentText 在 args 较大时
        //（如 write_file content）会无限撑高，按钮被挤出屏幕外用户点不到。
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("工具执行确认");
        dialog.setHeaderText("AI 请求执行: " + request.getToolName());
        dialog.initOwner(primaryStage);

        // 警告文案（粗体红色，自动换行）
        Label warningLabel = new Label("⚠️ " + request.getWarning());
        warningLabel.setWrapText(true);
        warningLabel.setMaxWidth(440);
        warningLabel.setStyle("-fx-text-fill: #f85149; -fx-font-weight: bold; -fx-font-size: 13px;");

        // 参数区：用可滚动 TextArea 限制最大高度，避免长 args 撑爆弹窗
        String argsStr = request.getArgs().toString();
        // 截断超长参数，避免 TextArea 渲染卡顿
        if (argsStr.length() > 2000) {
            argsStr = argsStr.substring(0, 2000) + "\n...(参数过长已截断，完整内容见日志)";
        }
        TextArea argsArea = new TextArea(argsStr);
        argsArea.setEditable(false);
        argsArea.setWrapText(true);
        argsArea.setPrefHeight(120);
        argsArea.setMaxHeight(160);
        argsArea.setStyle("-fx-font-size: 12px; -fx-background-color: #1e1e1e; -fx-text-fill: #d4d4d4;");

        Label argsCaption = new Label("参数:");
        argsCaption.setStyle("-fx-text-fill: #888; -fx-font-size: 11px;");

        VBox content = new VBox(8, warningLabel, argsCaption, argsArea);
        content.setPrefWidth(440);
        content.setPadding(new Insets(8));
        dialog.getDialogPane().setContent(content);
        // 固定 dialog 大小，防止 args 文本撑大窗口
        dialog.getDialogPane().setPrefSize(480, 320);
        dialog.getDialogPane().setMinSize(480, 320);
        dialog.getDialogPane().setMaxSize(480, 320);
        dialog.setResizable(false);

        // 应用深色主题
        dialog.getDialogPane().getStylesheets().add(
                getClass().getResource("/css/dark-theme.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add("dialog-pane");

        ButtonType allowBtn = new ButtonType("允许", ButtonBar.ButtonData.YES);
        ButtonType alwaysAllowBtn = new ButtonType("总是允许", ButtonBar.ButtonData.APPLY);
        ButtonType denyBtn = new ButtonType("拒绝", ButtonBar.ButtonData.NO);
        dialog.getDialogPane().getButtonTypes().setAll(allowBtn, alwaysAllowBtn, denyBtn);

        // 默认焦点在"允许"按钮（最常用），按 Enter 即允许。
        // lookupButton 必须在 dialog 显示后才能取到节点，用 setOnShown 回调设置焦点。
        dialog.setOnShown(e -> {
            javafx.scene.Node allowNode = dialog.getDialogPane().lookupButton(allowBtn);
            if (allowNode != null) allowNode.requestFocus();
        });

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent()) {
            ButtonBar.ButtonData data = result.get().getButtonData();
            if (data == ButtonBar.ButtonData.YES) request.approve();
            else if (data == ButtonBar.ButtonData.APPLY) request.approveAlways();
            else request.deny();
        } else {
            request.deny();
        }
    }

    /**
     * 终端错误通知：在AI聊天面板显示错误气泡，提供AI分析入口。
     */
    public void showTerminalErrorNotification(TerminalEvent event) {
        VBox bubble = new VBox(4);
        bubble.setPadding(new Insets(8, 14, 8, 14));
        bubble.setStyle("-fx-background-color: #3d1f1f; -fx-background-radius: 8; -fx-border-color: #f85149; -fx-border-width: 1; -fx-border-radius: 8;");

        Label title = new Label("⚠️ 终端检测到错误");
        title.setStyle("-fx-text-fill: #f85149; -fx-font-weight: bold;");

        String output = event.getRawOutput();
        Label detail = new Label(output.length() > 200 ? output.substring(0, 200) + "..." : output);
        detail.setStyle("-fx-text-fill: #e5e5e5; -fx-font-size: 12px; -fx-wrap-text: true;");
        detail.setMaxWidth(340);

        Button analyzeBtn = new Button("AI分析");
        analyzeBtn.setStyle("-fx-background-color: #1f6feb; -fx-text-fill: white; -fx-cursor: hand;");
        analyzeBtn.setOnAction(e -> {
            aiInput.setText("终端出现错误，请分析原因并提供修复建议:\n```\n" + output + "\n```");
            sendAiMessage();
        });

        bubble.getChildren().addAll(title, detail, analyzeBtn);
        aiChatMessages.getChildren().add(bubble);
        Platform.runLater(() -> aiChatScroll.setVvalue(1.0));
    }

    /**
     * 终端输入提示：在状态栏显示等待输入提示。
     */
    public void showTerminalInputHint(TerminalEvent event) {
        statusText.setText("终端等待输入: " + event.getDetectedPattern());
    }

    /**
     * 终端成功通知：在状态栏显示成功事件。
     */
    public void showTerminalSuccessNotification(TerminalEvent event) {
        statusText.setText("✓ " + event.getEventType().toString());
    }

    @FXML
    private void aiAnalyzeServer() {
        if (selectedServer == null) {
            showAlert("Please select a server first");
            return;
        }
        aiInput.setText("Please analyze the current server status, including CPU, memory, disk, network, and key services");
        sendAiMessage();
    }

    @FXML
    private void aiCheckServices() {
        if (selectedServer == null) {
            showAlert("Please select a server first");
            return;
        }
        aiInput.setText("Check the running status of all key services on the server");
        sendAiMessage();
    }

    @FXML
    private void generateDeployScript() {
        if (selectedServer == null) {
            showAlert("Please select a server first");
            return;
        }
        aiInput.setText("Help me deploy a new service on this server");
        sendAiMessage();
    }

    @FXML
    private void clearAiChat() {
        // P0-8: 移除气泡前先释放资源，避免 ChatBubbleFactory 静态 Map 持有已移除气泡引用导致内存泄漏
        for (javafx.scene.Node node : aiChatMessages.getChildren()) {
            if (node instanceof VBox) {
                ChatBubbleFactory.disposeBubble((VBox) node);
            }
        }
        aiChatMessages.getChildren().clear();
        lastAssistantBubble = null;
        lastAssistantFullText.setLength(0);
        // Show welcome screen again
        aiWelcomeScreen.setVisible(true);
        aiWelcomeScreen.setManaged(true);
        aiChatScroll.setVisible(false);
        aiChatScroll.setManaged(false);
    }

    @FXML
    private void showAiSettings() {
        AiSettingsDialog dialog = new AiSettingsDialog(primaryStage);
        dialog.show();
    }

    /**
     * 显示AI会话历史列表（aiHistoryBtn 的处理方法）。
     * 交互式 ListView：双击或选中后点"切换"加载该会话的历史消息。
     */
    @FXML
    private void showAiHistory() {
        java.util.List<Session> sessions = SessionManager.getInstance().getAllSessions();
        if (sessions == null || sessions.isEmpty()) {
            showAlert("暂无历史会话");
            return;
        }
        Session current = SessionManager.getInstance().getCurrentSession();

        Dialog<Session> dialog = new Dialog<>();
        dialog.setTitle("AI 会话历史");
        dialog.setHeaderText("共 " + sessions.size() + " 个会话，双击切换");

        ButtonType switchBtn = new ButtonType("切换", ButtonBar.ButtonData.OK_DONE);
        ButtonType closeBtn = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(switchBtn, closeBtn);

        ListView<Session> list = new ListView<>();
        list.setPrefHeight(320);
        list.setPrefWidth(420);
        list.getItems().addAll(sessions);

        list.setCellFactory(lv -> new ListCell<Session>() {
            @Override
            protected void updateItem(Session item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    String date = item.getCreatedAt() != null ? item.getCreatedAt().toLocalDate().toString() : "";
                    boolean isCurrent = current != null && item.getId() != null && item.getId().equals(current.getId());
                    setText((isCurrent ? "▶ " : "  ") + item.getName() + "  (" + date + ")");
                    setStyle(isCurrent ? "-fx-text-fill: #0dbc79; -fx-font-weight: bold;" : "-fx-text-fill: #d4d4d4;");
                }
            }
        });

        // 双击切换
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                Session sel = list.getSelectionModel().getSelectedItem();
                if (sel != null) {
                    Button button = (Button) dialog.getDialogPane().lookupButton(switchBtn);
                    button.fire();
                }
            }
        });

        VBox content = new VBox(8, list);
        content.setStyle("-fx-padding: 12; -fx-background-color: #1e1e1e;");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setStyle("-fx-background-color: #1e1e1e;");
        dialog.initOwner(primaryStage);

        dialog.setResultConverter(btn -> btn == switchBtn ? list.getSelectionModel().getSelectedItem() : null);
        dialog.showAndWait().ifPresent(sel -> {
            if (sel != null) switchToSession(sel);
            lastUserMessage = "";
        });
    }

    // ========== Log Analysis ==========

    @FXML
    private void analyzeSelectedLog() {
        if (selectedServer == null) {
            showAlert("Please select a server first");
            return;
        }
        String logs = LogCollectorService.getInstance().getLogsAsText(selectedServer.getId(), 50);
        if (logs.isEmpty()) {
            showAlert("No logs to analyze");
            return;
        }

        addChatMessage("user", "Analyze server logs");
        startAssistantBubble();

        new Thread(() -> {
            String serverInfoStr = selectedServer.getUsername() + "@" + selectedServer.getHost();
            AiAnalysisResult result = AiServiceClient.getInstance().analyzeLog(logs, serverInfoStr);

            StringBuilder analysis = new StringBuilder();
            analysis.append("=== Log Analysis Result ===\n");
            analysis.append("Summary: ").append(result.getSummary()).append("\n");
            analysis.append("Severity: ").append(result.getSeverity()).append("\n");
            analysis.append("Root Cause: ").append(result.getRootCause()).append("\n");
            analysis.append("Fix Command: ").append(result.getFixCommand()).append("\n");
            analysis.append("Details: ").append(result.getExplanation()).append("\n");

            Platform.runLater(() -> appendToAssistantBubble(analysis.toString()));
        }).start();
    }

    // ========== Utilities ==========

    private double parseDouble(String str) {
        try {
            return Double.parseDouble(str.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private void updateStatus(String msg) {
        statusText.setText(msg);
    }

    private void showAlert(String msg) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setContentText(msg);
        alert.showAndWait();
    }

    private void checkAiService() {
        new Thread(() -> {
            boolean ok = AiServiceClient.getInstance().isServiceAvailable();
            Platform.runLater(() -> {
                // 工具栏状态
                aiConnectionStatus.setText(ok ? "Online" : "Offline");
                aiConnectionStatus.setStyle(ok ? "-fx-text-fill: #0dbc79;" : "-fx-text-fill: #cd3131;");
                // AI面板底部连接指示器（原为静态文本，现在实时同步）
                aiConnectionIndicator.setText(ok ? "● 已连接" : "● 未连接");
                aiConnectionIndicator.setStyle(ok ? "-fx-text-fill: #3fb950; -fx-font-size: 11px;" : "-fx-text-fill: #f85149; -fx-font-size: 11px;");
            });
        }).start();
    }
}
