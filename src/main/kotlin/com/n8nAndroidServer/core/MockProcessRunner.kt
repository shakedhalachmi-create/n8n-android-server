package com.n8nAndroidServer.core

import java.io.File
import java.io.InputStream
import java.io.ByteArrayInputStream

/**
 * Mock implementation of ProcessRunner for testing.
 * Allows simulating different exit codes, PIDs, and stream outputs.
 */
class MockProcessRunner(
    private val exitCode: Int = 0,
    private val pid: Int = 12345,
    private val stdoutContent: String = "",
    private val stderrContent: String = "",
    private val simulateAlive: Boolean = true
) : ProcessRunner {
    
    override fun start(command: List<String>, env: Map<String, String>, workingDir: File): RunningProcess {
        return MockRunningProcess(exitCode, pid, stdoutContent, stderrContent, simulateAlive)
    }
}

/**
 * Mock running process for testing.
 */
private class MockRunningProcess(
    private val exitCode: Int,
    private val mockPid: Int,
    stdoutContent: String,
    stderrContent: String,
    private var alive: Boolean
) : RunningProcess {
    
    override fun pid(): Int = mockPid
    
    override val inputStream: InputStream = ByteArrayInputStream(stdoutContent.toByteArray())
    override val errorStream: InputStream = ByteArrayInputStream(stderrContent.toByteArray())
    
    override fun waitFor(): Int {
        alive = false
        return exitCode
    }
    
    override fun isAlive(): Boolean = alive
    
    override fun destroy() {
        alive = false
    }
    
    override fun destroyForcibly() {
        alive = false
    }
}
