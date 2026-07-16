package com.aifinalshell.controller;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShortcutValidatorTest {
    @Test
    void acceptsDistinctAndDisabledShortcuts() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("copy", "Ctrl+Shift+C");
        values.put("paste", "Ctrl+Shift+V");
        values.put("find", "");
        values.put("zoom_in", "Ctrl+Shift+Equals");
        assertTrue(ShortcutValidator.validate(values).isEmpty());
    }

    @Test
    void reportsCanonicalConflict() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("paste", "Ctrl+Shift+V");
        values.put("split_vertical", "Ctrl+Shift+V");
        ShortcutValidator.Issue issue = ShortcutValidator.validate(values).orElseThrow();
        assertEquals(ShortcutValidator.Type.CONFLICT, issue.type());
        assertEquals("paste", issue.firstAction());
        assertEquals("split_vertical", issue.secondAction());
    }

    @Test
    void reportsMalformedCombination() {
        ShortcutValidator.Issue issue = ShortcutValidator.validate(
                Map.of("copy", "Ctrl+Shift+")).orElseThrow();
        assertEquals(ShortcutValidator.Type.INVALID, issue.type());
        assertEquals("copy", issue.firstAction());
    }
}
