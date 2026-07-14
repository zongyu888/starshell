package com.aifinalshell.ui;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.Optional;

/**
 * 密码重输对话框。
 *
 * 当 SSH 连接因认证失败（密码错误）而被拒绝时弹出，引导用户重新输入正确密码并重试连接。
 * 含：清晰错误提示 + 密码输入框（带眼睛可见性切换）+ 确认重试 / 取消按钮。
 *
 * 返回值：用户点「确认重试」返回新密码字符串；点「取消」或关闭返回 empty。
 */
public class PasswordRetryDialog extends Dialog<String> {

    private final ButtonType retryBtn = new ButtonType("确认重试", ButtonBar.ButtonData.OK_DONE);
    private final PasswordToggleField pwd = new PasswordToggleField();

    /**
     * @param host     服务器主机
     * @param user     登录用户名
     * @param errorMsg 原始认证失败信息（去掉 AUTH_FAILED: 前缀后）
     */
    public PasswordRetryDialog(String host, String user, String errorMsg) {
        setTitle("密码错误 — 重新输入");
        setHeaderText("连接服务器 " + (host == null ? "" : host) + " 失败：密码或密钥认证被拒绝");

        getDialogPane().getButtonTypes().addAll(retryBtn, ButtonType.CANCEL);
        getDialogPane().setPrefWidth(440);

        // 错误提示
        Label errLabel = new Label("❌ " + (errorMsg == null || errorMsg.isEmpty() ? "认证失败" : errorMsg));
        errLabel.setStyle("-fx-text-fill: #cd3131; -fx-wrap-text: true; -fx-font-size: 12px;");

        Label pwdLabel = new Label("密码:");
        pwd.setStyle("-fx-font-size: 13px;");
        pwd.setPromptText("请输入 " + (user == null ? "" : user) + " 的密码");

        Label tipLabel = new Label("请重新输入密码后点「确认重试」，或点「取消」终止连接。");
        tipLabel.setStyle("-fx-text-fill: #888; -fx-font-size: 11px; -fx-wrap-text: true;");

        VBox box = new VBox(10, errLabel, pwdLabel, pwd, tipLabel);
        box.setPadding(new Insets(15));
        box.setPrefWidth(420);
        getDialogPane().setContent(box);

        // 初始禁用「确认重试」按钮，输入非空后启用
        getDialogPane().lookupButton(retryBtn).setDisable(true);
        pwd.textProperty().addListener((obs, oldV, newV) ->
                getDialogPane().lookupButton(retryBtn).setDisable(newV == null || newV.isEmpty()));

        // 对话框显示后自动聚焦到密码框
        getDialogPane().sceneProperty().addListener((obs, o, n) -> {
            if (n != null) {
                n.windowProperty().addListener((wobs, wo, wn) -> {
                    if (wn != null) {
                        wn.showingProperty().addListener((sobs, so, sn) -> {
                            if (sn) {
                                javafx.application.Platform.runLater(pwd::requestFocus);
                            }
                        });
                    }
                });
            }
        });

        setResultConverter(btn -> btn == retryBtn ? pwd.getText() : null);
    }

    /**
     * 便捷显示方法，返回 Optional<String>。
     */
    public Optional<String> showAndReturn() {
        return showAndWait();
    }
}
