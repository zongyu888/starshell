package com.aifinalshell.monitor;

import com.aifinalshell.model.ServerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MonitorServiceLifecycleTest {
    @Test
    void startsWithTheSessionConnectionKeyAndStopsCleanly() {
        MonitorService service = MonitorService.getInstance();
        ServerConfig server = new ServerConfig("test-monitor", "127.0.0.1", 22, "test", "");
        server.setId(9_900_001L);
        String connectionKey = "44_9900001";

        try {
            service.startMonitoring(server, connectionKey);
            assertTrue(service.isMonitoring(server.getId()));
            assertEquals(connectionKey, service.getMonitoringConnectionKey(server.getId()));
        } finally {
            service.stopMonitoring(server.getId());
        }

        assertFalse(service.isMonitoring(server.getId()));
        assertNull(service.getMonitoringConnectionKey(server.getId()));
    }

    @Test
    void releasingAStaleTabDoesNotStopTheCurrentServerMonitor() {
        MonitorService service = MonitorService.getInstance();
        ServerConfig server = new ServerConfig("shared-monitor", "127.0.0.1", 22, "test", "");
        server.setId(9_900_002L);
        String oldKey = "45_9900002";
        String currentKey = "46_9900002";

        try {
            service.startMonitoring(server, oldKey);
            service.startMonitoring(server, currentKey);
            service.releaseConnection(server.getId(), oldKey);

            assertTrue(service.isMonitoring(server.getId()));
            assertEquals(currentKey, service.getMonitoringConnectionKey(server.getId()));
        } finally {
            service.stopMonitoring(server.getId());
        }
    }

    @Test
    void releasingTheOnlyOwnerStopsTheMonitor() {
        MonitorService service = MonitorService.getInstance();
        ServerConfig server = new ServerConfig("single-monitor", "127.0.0.1", 22, "test", "");
        server.setId(9_900_003L);
        String onlyKey = "47_9900003";

        service.startMonitoring(server, onlyKey);
        service.releaseConnection(server.getId(), onlyKey);

        assertFalse(service.isMonitoring(server.getId()));
        assertNull(service.getMonitoringConnectionKey(server.getId()));
    }
}
