package com.aifinalshell.controller;

import com.aifinalshell.model.ServerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionWorkspaceStateTest {
    @Test
    void keepsRemoteDirectoriesIndependentForTabsOnTheSameServer() {
        SessionWorkspaceState state = new SessionWorkspaceState();
        state.setRemotePath(11L, "/srv/app-a");
        state.setRemotePath(12L, "/srv/app-b");

        assertEquals("/srv/app-a", state.remotePath(11L));
        assertEquals("/srv/app-b", state.remotePath(12L));
        assertEquals("/", state.remotePath(13L));
    }

    @Test
    void storesConnectionMetadataPerSessionAndCleansItAtomically() {
        SessionWorkspaceState state = new SessionWorkspaceState();
        ServerConfig server = new ServerConfig("prod", "10.0.0.8", 22, "ops", "secret");
        server.setId(7L);

        SessionWorkspaceState.Data data = state.get(21L);
        data.serverId = server.getId();
        data.serverConfig = server;
        data.connectionKey = "21_7";
        data.terminalText = "$ uptime\n";

        SessionWorkspaceState.Data removed = state.remove(21L);
        assertSame(data, removed);
        assertEquals("21_7", removed.connectionKey);
        assertEquals(7L, removed.serverId);
        assertEquals("/", state.remotePath(21L), "a reopened tab starts from root");
    }

    @Test
    void normalizesMissingPathsToRoot() {
        SessionWorkspaceState state = new SessionWorkspaceState();
        state.setRemotePath(1L, " ");
        assertEquals("/", state.remotePath(1L));
        assertEquals("/", state.remotePath(null));
        assertThrows(IllegalArgumentException.class, () -> state.get(null));
    }

    @Test
    void blankTabStartsWithoutAnyServerRoutingMetadata() {
        SessionWorkspaceState.Data blank = new SessionWorkspaceState().get(99L);
        assertNull(blank.serverId);
        assertNull(blank.serverConfig);
        assertNull(blank.connectionKey);
        assertEquals("", blank.terminalText);
        assertEquals("/", blank.remotePath);
    }
}
