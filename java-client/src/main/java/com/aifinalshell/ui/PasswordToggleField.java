package com.aifinalshell.ui;

import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/**
 * 密码输入控件，带可见性切换（眼睛图标）。
 *
 * 由 PasswordField（密文）+ TextField（明文）双向绑定 + 眼睛 ToggleButton 组成。
 * 默认密文显示；点击眼睛按钮切换为明文，再点切回密文。切换时文本不丢失、焦点平滑转移。
 *
 * 设计要点（跨 OS / 分辨率可靠性）：
 * - 无第三方图标库依赖，眼形用 Unicode 字符 👁/🙈，Win10/11（Segoe UI Emoji）与 Mac（Apple Color Emoji）均内置渲染。
 * - 按钮 min-width 固定，防止不同分辨率下宽度跳动。
 * - 配 tooltip 双保险，即使极端平台无字形也能传达功能。
 * - 明文/密文两个字段文本双向绑定，切换时内容一致。
 */
public class PasswordToggleField extends HBox {

    private final PasswordField secret = new PasswordField();
    private final TextField plain = new TextField();
    private final ToggleButton eye = new ToggleButton("👁");

    public PasswordToggleField() {
        setSpacing(6);
        setAlignment(Pos.CENTER_LEFT);

        HBox.setHgrow(secret, Priority.ALWAYS);
        HBox.setHgrow(plain, Priority.ALWAYS);

        // 两个字段文本双向绑定，切换显隐时内容保持一致
        plain.textProperty().bindBidirectional(secret.textProperty());
        // prompt 文本同步
        plain.promptTextProperty().bind(secret.promptTextProperty());

        // 默认密文：明文字段不占位、不可见
        plain.setManaged(false);
        plain.setVisible(false);

        eye.setTooltip(new Tooltip("显示/隐藏密码"));
        eye.getStyleClass().add("pwd-toggle-btn");
        eye.setOnAction(e -> {
            boolean show = eye.isSelected();
            secret.setManaged(!show);
            secret.setVisible(!show);
            plain.setManaged(show);
            plain.setVisible(show);
            eye.setText(show ? "🙈" : "👁");
            // 焦点转移到当前可见的字段，便于继续输入
            (show ? plain : secret).requestFocus();
            // 将光标移到末尾
            javafx.application.Platform.runLater(() -> {
                TextField focused = show ? plain : secret;
                focused.end();
            });
        });

        getChildren().addAll(secret, plain, eye);
    }

    /** 当前密码文本（明文/密文字段同步）。 */
    public String getText() {
        return secret.getText();
    }

    public void setText(String text) {
        secret.setText(text);
    }

    public void setPromptText(String prompt) {
        secret.setPromptText(prompt);
    }

    /** 暴露文本属性，供外部监听（如启用/禁用确认按钮）。 */
    public StringProperty textProperty() {
        return secret.textProperty();
    }

    @Override
    public void requestFocus() {
        secret.requestFocus();
    }
}
