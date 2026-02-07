package com.n8nAndroidServer.core.config

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerConfigTest {

    @Test
    fun `ports should be correct`() {
        assertEquals(5681, ServerConfig.N8N_PORT)
        assertEquals(5680, ServerConfig.GATEKEEPER_PORT)
    }

    @Test
    fun `timeouts should be appropriate for mobile`() {
        // Startup timeout should be generous (90s)
        assertEquals(90_000L, ServerConfig.STARTUP_TIMEOUT_MS)
    }
    
    @Test
    fun `relative paths should be correct`() {
        assertEquals("runtime", ServerConfig.RUNTIME_DIR_NAME)
        assertEquals("dist/core_runtime.n8n", ServerConfig.RUNTIME_ASSET_FILENAME)
        assertEquals("bin/node", ServerConfig.NODE_BIN_PATH)
    }
}
