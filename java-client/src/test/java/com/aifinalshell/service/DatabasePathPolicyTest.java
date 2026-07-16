package com.aifinalshell.service;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DatabasePathPolicyTest {

    @Test
    void developmentUsesLegacyDevelopmentProfile() {
        File actual = DatabaseManager.defaultDatabaseBaseFile("C:\\Users\\tester", false);

        assertEquals(new File("C:\\Users\\tester", ".aifinalshell/data/aifinalshell")
                .getAbsoluteFile(), actual);
    }

    @Test
    void packagedRuntimeUsesCleanBrandedProfile() {
        File actual = DatabaseManager.defaultDatabaseBaseFile("C:\\Users\\tester", true);

        assertEquals(new File("C:\\Users\\tester", ".starshell/data/starshell")
                .getAbsoluteFile(), actual);
    }

    @Test
    void developmentAndPackagedProfilesCannotShareSavedServers() {
        File development = DatabaseManager.defaultDatabaseBaseFile("C:\\Users\\tester", false);
        File packaged = DatabaseManager.defaultDatabaseBaseFile("C:\\Users\\tester", true);

        assertNotEquals(development, packaged);
    }
}
