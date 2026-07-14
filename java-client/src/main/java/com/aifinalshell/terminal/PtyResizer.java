package com.aifinalshell.terminal;

import javafx.animation.PauseTransition;
import javafx.beans.value.ChangeListener;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * 终端 PTY 尺寸调整器：监听目标 Region（终端 TextArea）的宽高变化，debounce 200ms 后
 * 估算 PTY 列数/行数并通过回调通知调用方执行 setPtySize。
 *
 * <p>setup/teardown 应在 FX 线程调用。computeSize 仅读 Region 宽高，可在任意线程调用。</p>
 */
public class PtyResizer {
    private PauseTransition resizePause;
    private ChangeListener<Number> widthListener;
    private ChangeListener<Number> heightListener;
    private Region target;
    private Consumer<int[]> onResize;

    /**
     * 注册尺寸监听并立即用实际尺寸校正一次（替代固定 120×40）。
     *
     * @param target   被监听的 Region（终端 TextArea）
     * @param onResize 尺寸变化回调，参数为 [cols, rows]
     */
    public void setup(Region target, Consumer<int[]> onResize) {
        this.target = target;
        this.onResize = onResize;
        resizePause = new PauseTransition(Duration.millis(200));
        resizePause.setOnFinished(e -> onResize.accept(computeSize()));
        widthListener = (obs, o, n) -> resizePause.playFromStart();
        heightListener = (obs, o, n) -> resizePause.playFromStart();
        target.widthProperty().addListener(widthListener);
        target.heightProperty().addListener(heightListener);
        // 连接后立即用实际尺寸校正 PTY
        onResize.accept(computeSize());
    }

    /**
     * 移除监听并停止 debounce 定时器（断开连接时调用）。
     */
    public void teardown() {
        if (widthListener != null && target != null) {
            target.widthProperty().removeListener(widthListener);
            widthListener = null;
        }
        if (heightListener != null && target != null) {
            target.heightProperty().removeListener(heightListener);
            heightListener = null;
        }
        if (resizePause != null) {
            resizePause.stop();
            resizePause = null;
        }
        target = null;
        onResize = null;
    }

    /**
     * 根据 Region 宽高估算 PTY 列数/行数（等宽字体近似 7px/列、14px/行），
     * 减去约 30px 容纳滚动条与内边距。
     *
     * @return [cols, rows]
     */
    public int[] computeSize() {
        if (target == null) return new int[]{80, 24};
        double w = target.getWidth();
        double h = target.getHeight();
        int cols = Math.max(20, (int) ((w - 30) / 7));
        int rows = Math.max(5, (int) ((h - 30) / 14));
        return new int[]{cols, rows};
    }

    /**
     * 立即触发一次尺寸校正（不经 debounce）。
     */
    public void resizeNow() {
        if (onResize != null) {
            onResize.accept(computeSize());
        }
    }
}
