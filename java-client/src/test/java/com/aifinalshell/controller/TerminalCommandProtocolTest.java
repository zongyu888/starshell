package com.aifinalshell.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalCommandProtocolTest {

    @Test
    void echoedProbeCannotLookLikeCompletion() {
        String id = "abc123";
        String probe = TerminalCommandProtocol.buildCompletionProbe(id);

        assertFalse(probe.contains(TerminalCommandProtocol.markerPrefix(id)),
                "PTY 回显的探针命令不得包含最终完成标记");
        assertTrue(TerminalCommandProtocol.findCompletion("$ " + probe + "\r\n", id).isEmpty(),
                "仅回显探针时不能误判命令已完成");
    }

    @Test
    void parsesCompletionAndExitCodeAcrossCapturedOutput() {
        String id = "deadbeef";
        String output = "command output\r\n__STAR_CMD_DONE_deadbeef_17__\r\n$ ";

        TerminalCommandProtocol.Completion completion =
                TerminalCommandProtocol.findCompletion(output, id).orElseThrow();

        assertEquals(17, completion.exitCode());
        assertEquals("command output\r\n", output.substring(0, completion.markerStart()));
    }

    @Test
    void ignoresMarkerForAnotherCommand() {
        assertTrue(TerminalCommandProtocol.findCompletion(
                "__STAR_CMD_DONE_other_0__", "abc123").isEmpty());
    }
}
