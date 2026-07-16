package com.aifinalshell.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigDraftTest {
    @TempDir
    Path tempDir;

    @Test
    void editsAndResetStayIsolatedUntilApply() {
        AppConfig persisted = AppConfig.getInstance();
        Properties before = persisted.snapshot();

        AppConfig.Draft draft = persisted.createDraft();
        draft.setFontSize(persisted.getFontSize() == 72 ? 71 : persisted.getFontSize() + 1);
        draft.setMinimizeToTray(!persisted.isMinimizeToTray());
        draft.saveConfig();

        assertEquals(before, persisted.snapshot(), "draft edits/save must not mutate global config");

        draft.clearAndReloadDefaults();
        assertEquals(14, draft.getFontSize());
        assertEquals("Ctrl+Alt+V", draft.getShortcut("split_vertical"));
        assertEquals("Ctrl+Shift+Equals", draft.getShortcut("zoom_in"));
        assertEquals(before, persisted.snapshot(), "reset defaults must remain a cancellable draft operation");
    }

    @Test
    void snapshotsAreDefensiveCopies() {
        AppConfig persisted = AppConfig.getInstance();
        String original = persisted.getLanguage();
        Properties copy = persisted.snapshot();
        copy.setProperty("language", "snapshot-only");
        assertEquals(original, persisted.getLanguage());
    }

    @Test
    void applyCommitsExactlyTheDraftAndSupportsANewBaseline() {
        AppConfig persisted = AppConfig.getInstance();
        Properties before = persisted.snapshot();
        try {
            AppConfig.Draft draft = persisted.createDraft();
            int expected = persisted.getFontSize() == 72 ? 71 : persisted.getFontSize() + 1;
            draft.setFontSize(expected);
            persisted.applyDraft(draft);
            assertEquals(expected, persisted.getFontSize());

            AppConfig.Draft nextBaseline = persisted.createDraft();
            assertEquals(expected, nextBaseline.getFontSize(),
                    "Apply followed by Cancel must retain the last applied baseline");
        } finally {
            persisted.applyDraft(new AppConfig.Draft(before));
        }
    }

    @Test
    void legacySelectedBackgroundIsEnabledOnceButLaterDisableIsPreserved() throws IOException {
        Path image = Files.createFile(tempDir.resolve("background.png"));
        Properties legacy = new Properties();
        legacy.setProperty("background.image.path", image.toString());
        legacy.setProperty("background.image.enabled", "false");

        AppConfig.migrateBackgroundImageSelection(legacy);
        assertEquals("true", legacy.getProperty("background.image.enabled"));
        assertEquals("true", legacy.getProperty("background.image.auto_enable_migrated"));

        legacy.setProperty("background.image.enabled", "false");
        AppConfig.migrateBackgroundImageSelection(legacy);
        assertEquals("false", legacy.getProperty("background.image.enabled"),
                "an explicit later disable must not be undone");
    }
}
