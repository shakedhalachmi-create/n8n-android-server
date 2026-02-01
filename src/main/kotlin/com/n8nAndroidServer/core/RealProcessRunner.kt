package com.n8nAndroidServer.core

import java.io.File
import java.io.InputStream

/**
 * Production implementation of ProcessRunner using java.lang.ProcessBuilder.
 */
class RealProcessRunner : ProcessRunner {
    override fun start(command: List<String>, env: Map<String, String>, workingDir: File): RunningProcess {
        val processBuilder = ProcessBuilder(command)
        processBuilder.directory(workingDir)
        
        // Merge system environment and override with provided env
        val environment = processBuilder.environment()
        environment.putAll(env)
        
        val process = processBuilder.start()
        return RealRunningProcess(process)
    }
}

/**
 * Wrapper around java.lang.Process to implement RunningProcess interface.
 */
private class RealRunningProcess(private val process: Process) : RunningProcess {
    override fun pid(): Int {
        return try {
            // Use reflection to get PID for compatibility
            val pidField = process.javaClass.getDeclaredField("pid")
            pidField.isAccessible = true
            pidField.getInt(process)
        } catch (e: Exception) {
            -1
        }
    }
    
    override val inputStream: InputStream
        get() = process.inputStream
    
    override val errorStream: InputStream
        get() = process.errorStream
    
    override fun waitFor(): Int {
        return process.waitFor()
    }
    
    override fun isAlive(): Boolean {
        return process.isAlive
    }
    
    override fun destroy() {
        process.destroy()
    }
    
    override fun destroyForcibly() {
        process.destroyForcibly()
    }
}
