package com.aifinalshell.controller;

import org.junit.jupiter.api.Test;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerminalBackgroundStyleTest {
    @Test
    void convertsConfiguredColorIntoTransparentOverlay() {
        assertEquals("rgba(30,30,30,0.700)", TerminalController.toRgba("#1e1e1e", 0.7));
        assertEquals("rgba(255,0,128,0.250)", TerminalController.toRgba("#ff0080", 0.25));
    }

    @Test
    void clampsOverlayAlpha() {
        assertEquals("rgba(0,0,0,0.000)", TerminalController.toRgba("black", -2));
        assertEquals("rgba(255,255,255,1.000)", TerminalController.toRgba("white", 2));
    }

    @Test
    void preblendsImageWithoutFadingTerminalText() {
        WritableImage source = new WritableImage(1, 1);
        source.getPixelWriter().setColor(0, 0, Color.RED);
        Color blended = TerminalController.blendImage(source, Color.BLACK, 0.5)
                .getPixelReader().getColor(0, 0);
        assertEquals(0.5, blended.getRed(), 0.01);
        assertEquals(0.0, blended.getGreen(), 0.01);
        assertEquals(0.0, blended.getBlue(), 0.01);
        assertEquals(1.0, blended.getOpacity(), 0.01);
    }

    @Test
    void defaultsToContainSoTheWholeBackgroundImageRemainsVisible() {
        assertEquals("contain", TerminalController.backgroundSizeCss(null));
        assertEquals("contain", TerminalController.backgroundSizeCss("contain"));
        assertEquals("cover", TerminalController.backgroundSizeCss("cover"));
        assertEquals("100% 100%", TerminalController.backgroundSizeCss("stretch"));
    }
}
