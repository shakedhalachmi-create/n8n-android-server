package com.n8nAndroidServer.core.config

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

class PathResolverTest {

    private lateinit var context: Context
    private lateinit var pathResolver: PathResolver
    
    // Mock files
    private val mockFilesDir = File("/data/user/0/com.n8n/files")
    private val mockCacheDir = File("/data/user/0/com.n8n/cache")

    @Before
    fun setUp() {
        context = mockk()
        every { context.filesDir } returns mockFilesDir
        every { context.cacheDir } returns mockCacheDir
        
        pathResolver = PathResolver(context)
    }

    @Test
    fun `runtimeRoot should be under filesDir`() {
        val expected = File(mockFilesDir, "runtime")
        assertEquals(expected.path, pathResolver.runtimeRoot.path)
    }

    @Test
    fun `libDir should be under runtimeRoot`() {
        val expected = File(mockFilesDir, "runtime/lib")
        assertEquals(expected.path, pathResolver.libDir.path)
    }
    
    @Test
    fun `nodeBin should be under runtimeRoot`() {
        val expected = File(mockFilesDir, "runtime/bin/node")
        assertEquals(expected.path, pathResolver.nodeBin.path)
    }

    @Test
    fun `n8nUserDir should be under userdata`() {
        val expected = File(mockFilesDir, "userdata/n8n")
        assertEquals(expected.path, pathResolver.n8nUserDir.path)
    }
    
    @Test
    fun `tempRuntimeTarball should be in cacheDir`() {
        val expected = File(mockCacheDir, "temp_runtime.tar.gz")
        assertEquals(expected.path, pathResolver.tempRuntimeTarball.path)
    }
}
