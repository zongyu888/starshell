package com.aifinalshell.controller;

import javafx.scene.input.KeyCombination;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Pure validation for user-configurable JavaFX accelerators. */
final class ShortcutValidator {
    enum Type { INVALID, CONFLICT }

    record Issue(Type type, String shortcut, String firstAction, String secondAction) { }

    private ShortcutValidator() { }

    static Optional<Issue> validate(Map<String, String> shortcuts) {
        Map<KeyCombination, String> assigned = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : shortcuts.entrySet()) {
            String action = entry.getKey();
            String value = entry.getValue();
            if (value == null || value.isBlank()) continue;
            final KeyCombination combination;
            try {
                combination = KeyCombination.keyCombination(value.trim());
            } catch (IllegalArgumentException ex) {
                return Optional.of(new Issue(Type.INVALID, value, action, null));
            }
            String previous = assigned.putIfAbsent(combination, action);
            if (previous != null) {
                return Optional.of(new Issue(Type.CONFLICT, value, previous, action));
            }
        }
        return Optional.empty();
    }
}
