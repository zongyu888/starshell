package com.aifinalshell.terminal;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * 终端输出节流器：读者线程将原始数据累积到内部缓冲，由 Timeline 周期性（默认 50ms）
 * 在 FX 线程批量 flush 给消费回调，避免高吞吐输出（如 cat 大文件）时每块都
 * Platform.runLater 导致 UI 卡顿。
 *
 * <p>线程模型：feed() 在读者线程调用（内部同步，线程安全）；
 * start/stop/flushNow 应在 FX 线程调用。</p>
 */
public class OutputThrottler {
    private final StringBuilder pendingOutput = new StringBuilder();
    private Timeline flushTimeline;
    private Consumer<String> onFlush;

    /**
     * 启动周期性 flush（FX 线程调用）。
     *
     * @param onFlush    flush 回调（在 FX 线程执行），传入本周期累积的批量原始文本
     * @param intervalMs flush 周期（毫秒）
     */
    public void start(Consumer<String> onFlush, long intervalMs) {
        this.onFlush = onFlush;
        synchronized (pendingOutput) {
            pendingOutput.setLength(0);
        }
        if (flushTimeline != null) {
            flushTimeline.stop();
        }
        flushTimeline = new Timeline(new KeyFrame(Duration.millis(intervalMs), e -> flushNow()));
        flushTimeline.setCycleCount(Timeline.INDEFINITE);
        flushTimeline.play();
    }

    /**
     * 累积原始输出（读者线程调用，线程安全）。
     */
    public void feed(String text) {
        if (text == null || text.isEmpty()) return;
        synchronized (pendingOutput) {
            pendingOutput.append(text);
        }
    }

    /**
     * 立即 flush 累积的输出给回调（FX 线程调用）。无累积时直接返回。
     */
    public void flushNow() {
        String chunk;
        synchronized (pendingOutput) {
            if (pendingOutput.length() == 0) {
                return;
            }
            chunk = pendingOutput.toString();
            pendingOutput.setLength(0);
        }
        if (onFlush != null) {
            onFlush.accept(chunk);
        }
    }

    /**
     * 停止周期 flush（FX 线程调用）。不 flush 残余——调用方需先调用 flushNow()。
     */
    public void stop() {
        if (flushTimeline != null) {
            flushTimeline.stop();
            flushTimeline = null;
        }
    }
}
