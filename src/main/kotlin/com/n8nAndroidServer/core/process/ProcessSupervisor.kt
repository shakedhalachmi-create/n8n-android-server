package com.n8nAndroidServer.core.process

import android.util.Log
import com.n8nAndroidServer.core.ProcessRunner
import com.n8nAndroidServer.core.RunningProcess
import com.n8nAndroidServer.core.config.PathResolver
import com.n8nAndroidServer.core.config.ServerConfig
import kotlinx.coroutines.delay
import java.io.File
import java.net.Socket

/**
 * Supervises the n8n Node.js process lifecycle.
 * 
 * This class provides single-responsibility process management:
 * - Start process with environment
 * - Track PID and write PID file
 * - Wait for port availability
 * - Monitor process liveness
 * - Handle graceful and forced termination
 * 
 * ## Usage
 * ```kotlin
 * val supervisor = ProcessSupervisor(paths, processRunner)
 * 
 * val result = supervisor.startAndWaitForPort(env)
 * when (result) {
 *     is StartResult.Success -> // Process running, port open
 *     is StartResult.Timeout -> // Port never became available
 *     is StartResult.ProcessDied -> // Process died during startup
 * }
 * ```
 * 
 * @param paths PathResolver for file locations
 * @param processRunner ProcessRunner implementation
 */
class ProcessSupervisor(
    private val paths: PathResolver,
    private val processRunner: ProcessRunner
) {
    companion object {
        private const val TAG = "ProcessSupervisor"
    }
    
    private var currentProcess: RunningProcess? = null
    
    /**
     * Current running process, or null if not running.
     */
    val process: RunningProcess? get() = currentProcess
    
    /**
     * Check if a process is currently running.
     */
    val isRunning: Boolean get() = currentProcess?.isAlive() == true
    
    /**
     * Get the PID of the current process, or -1 if not running.
     */
    val pid: Int get() = currentProcess?.pid() ?: -1
    
    /**
     * Start the n8n process and wait for the port to become available.
     * 
     * @param environment Environment variables for the process
     * @param timeoutMs Maximum time to wait for port availability
     * @return StartResult indicating success, timeout, or failure
     */
    suspend fun startAndWaitForPort(
        environment: Map<String, String>,
        timeoutMs: Long = ServerConfig.STARTUP_TIMEOUT_MS
    ): StartResult {
        // Determine command: prefer bootstrap script, fallback to direct node
        val command = buildStartCommand()
        
        Log.i(TAG, "Starting process with command: ${command.joinToString(" ")}")
        Log.d(TAG, "Working directory: ${paths.n8nUserDir.absolutePath}")
        
        // Ensure directories exist
        paths.ensureDirectoriesExist()
        
        try {
            // Start the process
            currentProcess = processRunner.start(command, environment, paths.n8nUserDir)
            
            val processPid = currentProcess?.pid() ?: -1
            Log.i(TAG, "Process started with PID: $processPid")
            
            // Write PID file
            writePidFile(processPid)
            
            // Wait for port
            val portResult = waitForPort(
                port = ServerConfig.N8N_PORT,
                timeoutMs = timeoutMs,
                pollIntervalMs = ServerConfig.PORT_POLL_INTERVAL_MS
            )
            
            return when (portResult) {
                PortWaitResult.READY -> StartResult.Success(
                    pid = processPid,
                    port = ServerConfig.N8N_PORT
                )
                PortWaitResult.TIMEOUT -> StartResult.Timeout(
                    elapsedMs = timeoutMs,
                    port = ServerConfig.N8N_PORT
                )
                PortWaitResult.PROCESS_DIED -> {
                    val exitCode = try { currentProcess?.waitFor() } catch (e: Exception) { null }
                    StartResult.ProcessDied(
                        exitCode = exitCode,
                        pid = processPid
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start process", e)
            return StartResult.Error(
                message = e.message ?: "Unknown error",
                cause = e
            )
        }
    }
    
    /**
     * Stop the running process gracefully, with force fallback.
     * 
     * @param gracefulTimeoutMs Time to wait for graceful shutdown before force kill
     * @return True if process is stopped, false if still running (shouldn't happen)
     */
    suspend fun stop(gracefulTimeoutMs: Long = ServerConfig.SHUTDOWN_TIMEOUT_MS): Boolean {
        val proc = currentProcess ?: return true
        
        if (!proc.isAlive()) {
            Log.d(TAG, "Process already dead")
            cleanup()
            return true
        }
        
        Log.i(TAG, "Stopping process (PID: ${proc.pid()})...")
        
        // Try graceful termination (SIGTERM)
        proc.destroy()
        
        // Wait for graceful shutdown
        val gracefulMs = gracefulTimeoutMs * 2 / 3 // Give 2/3 of timeout for graceful
        var waited = 0L
        while (proc.isAlive() && waited < gracefulMs) {
            delay(100)
            waited += 100
        }
        
        if (proc.isAlive()) {
            Log.w(TAG, "Process unresponsive after ${waited}ms, force killing...")
            proc.destroyForcibly()
            
            // Wait a bit more
            delay(500)
        }
        
        if (proc.isAlive()) {
            Log.e(TAG, "Process still alive after force kill!")
            return false
        }
        
        cleanup()
        return true
    }
    
    /**
     * Force kill the process immediately.
     */
    fun forceKill() {
        currentProcess?.destroyForcibly()
        cleanup()
    }
    
    /**
     * Wait for the process to exit and return the exit code.
     */
    fun waitForExit(): Int? {
        return currentProcess?.waitFor()
    }
    
    /**
     * Get input stream for stdout consumption.
     */
    fun getInputStream() = currentProcess?.inputStream
    
    /**
     * Get input stream for stderr consumption.
     */
    fun getErrorStream() = currentProcess?.errorStream
    
    private fun buildStartCommand(): List<String> {
        return if (paths.bootstrapScript.exists()) {
            // Preferred: Use bootstrap script which handles LD_LIBRARY_PATH
            listOf("/system/bin/sh", paths.bootstrapScript.absolutePath)
        } else {
            // Fallback: Direct node execution
            listOf(
                paths.nodeBin.absolutePath,
                paths.n8nEntry.absolutePath,
                "start"
            )
        }
    }
    
    private fun writePidFile(pid: Int) {
        if (pid > 0) {
            try {
                paths.pidFile.parentFile?.mkdirs()
                paths.pidFile.writeText(pid.toString())
                Log.d(TAG, "Wrote PID file: ${paths.pidFile.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write PID file", e)
            }
        }
    }
    
    private fun cleanup() {
        currentProcess = null
        try {
            if (paths.pidFile.exists()) {
                paths.pidFile.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete PID file", e)
        }
    }
    
    private suspend fun waitForPort(
        port: Int,
        timeoutMs: Long,
        pollIntervalMs: Long
    ): PortWaitResult {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            // Check if process died
            if (currentProcess?.isAlive() != true) {
                return PortWaitResult.PROCESS_DIED
            }
            
            // Try to connect to port
            try {
                Socket("127.0.0.1", port).use {
                    Log.i(TAG, "Port $port is open!")
                    return PortWaitResult.READY
                }
            } catch (e: Exception) {
                // Port not ready yet, continue polling
            }
            
            delay(pollIntervalMs)
        }
        
        return PortWaitResult.TIMEOUT
    }
}

/**
 * Result of port wait operation.
 */
private enum class PortWaitResult {
    READY,
    TIMEOUT,
    PROCESS_DIED
}

/**
 * Result of starting the n8n process.
 */
sealed class StartResult {
    /**
     * Process started successfully and port is open.
     */
    data class Success(
        val pid: Int,
        val port: Int
    ) : StartResult()
    
    /**
     * Port never became available within timeout.
     */
    data class Timeout(
        val elapsedMs: Long,
        val port: Int
    ) : StartResult()
    
    /**
     * Process died during startup.
     */
    data class ProcessDied(
        val exitCode: Int?,
        val pid: Int
    ) : StartResult()
    
    /**
     * An exception occurred during startup.
     */
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : StartResult()
}
