package com.n8nAndroidServer.core.config

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

class EnvironmentBuilderTest {

    private lateinit var context: Context
    private lateinit var pathResolver: PathResolver
    private lateinit var environmentBuilder: EnvironmentBuilder

    @Before
    fun setUp() {
        context = mockk()
        pathResolver = mockk()
        environmentBuilder = EnvironmentBuilder(context, pathResolver)

        // Mock Paths
        every { pathResolver.n8nUserDir } returns File("/data/user/0/com.n8n/files/userdata")
        every { pathResolver.libDir } returns File("/data/user/0/com.n8n/files/runtime/lib")
        every { pathResolver.nodeModulesDir } returns File("/data/user/0/com.n8n/files/runtime/lib/node_modules")
        every { pathResolver.getPathEnvValue() } returns "/data/user/0/com.n8n/files/runtime/bin:/system/bin"
        
        // Mock Context
        every { context.cacheDir } returns File("/data/user/0/com.n8n/cache")
    }

    @Test
    fun `build() should set critical Android workarounds`() {
        val env = environmentBuilder.build(encryptionKey = "test-key")

        assertEquals("/dev/null", env["OPENSSL_CONF"])
        assertEquals("true", env["N8N_BLOCK_JS_EXECUTION_PROCESS"])
        assertEquals("true", env["N8N_DISABLE_PYTHON_NODE"])
        assertEquals("main", env["N8N_TASKS_EVALUATOR_PROCESS"])
    }

    @Test
    fun `build() should set correct paths`() {
        val env = environmentBuilder.build(encryptionKey = "test-key")

        assertEquals("/data/user/0/com.n8n/files/userdata", env["HOME"])
        assertEquals("/data/user/0/com.n8n/files/runtime/lib", env["LD_LIBRARY_PATH"])
        assertEquals("/data/user/0/com.n8n/files/runtime/bin:/system/bin", env["PATH"])
    }

    @Test
    fun `build() should set encryption key`() {
        val key = "super-secret-key"
        val env = environmentBuilder.build(encryptionKey = key)

        assertEquals(key, env["N8N_ENCRYPTION_KEY"])
    }

    @Test
    fun `build() should set server port`() {
        val env = environmentBuilder.build(encryptionKey = "key")
        
        // ServerConfig.N8N_PORT is 5681
        assertEquals("5681", env["N8N_PORT"])
    }
}
