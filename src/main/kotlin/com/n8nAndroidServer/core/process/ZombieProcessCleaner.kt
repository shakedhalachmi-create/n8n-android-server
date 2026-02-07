package com.n8nAndroidServer.core.process

import android.util.Log
import com.n8nAndroidServer.core.config.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Cleans up zombie Node.js processes.
 * 
 * On Android, orphaned node processes can persist after app crashes or
 * force-stops, holding onto ports and preventing the server from restarting.
 * This class provides methods to find and kill such zombie processes.
 * 
 * ## Cleanup Strategy
 * 1. Try `pkill`/`killall` commands (may not work without root)
 * 2. Parse `ps -A` output and kill matching processes manually
 * 
 * ## Process Detection Patterns
 * - Processes with "runtime/bin/node" in command line
 * - Processes containing both "node" and "n8n"
 * 
 * @param logCallback Optional callback for logging to UI
 */
class ZombieProcessCleaner(
    private val logCallback: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "ZombieProcessCleaner"
    }
    
    /**
     * Kill all existing Node.js processes related to n8n.
     * 
     * @return Number of processes killed
     */
    suspend fun cleanupZombieProcesses(): Int = withContext(Dispatchers.IO) {
        log("Process Cleanup: Starting...")
        
        var killedCount = 0
        
        try {
            val runtime = Runtime.getRuntime()
            
            // Method A: Simple commands (may fail without root, but try anyway)
            trySimpleKillCommands(runtime)
            
            // Method B: Parse ps output and kill individually
            killedCount = parseAndKillNodeProcesses(runtime)
            
            log("Process Cleanup: Killed $killedCount zombie processes.")
            
            // Wait for OS to release ports
            if (killedCount > 0) {
                delay(ServerConfig.POST_KILL_DELAY_MS)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "General cleanup failure", e)
            log("Process Cleanup: Failed (${e.message})")
        }
        
        killedCount
    }
    
    /**
     * Try simple kill commands. These may fail without root.
     */
    private fun trySimpleKillCommands(runtime: Runtime) {
        val commands = listOf("pkill -9 node", "killall -9 node")
        
        for (cmd in commands) {
            try {
                val process = runtime.exec(arrayOf("/system/bin/sh", "-c", cmd))
                process.waitFor()
            } catch (e: Exception) {
                // Expected to fail without root, continue
            }
        }
    }
    
    /**
     * Parse ps output and kill node processes related to n8n.
     * 
     * PS output format:
     * USER      PID   PPID  VSIZE  RSS     WCHAN    PC         NAME
     * 
     * We look for processes containing "runtime/bin/node" or both "node" and "n8n".
     * 
     * @return Number of processes killed
     */
    private fun parseAndKillNodeProcesses(runtime: Runtime): Int {
        var killedCount = 0
        
        try {
            val process = runtime.exec(arrayOf("/system/bin/ps", "-A"))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            
            // Debug: Log all node-related lines
            val nodeLines = output.lines().filter { 
                it.contains("node") || it.contains("n8n") 
            }
            
            if (nodeLines.isNotEmpty()) {
                Log.d(TAG, "PS DEBUG: Found potential node processes:\n${nodeLines.joinToString("\n")}")
            }
            
            // Parse and kill matching processes
            output.lines().forEach { line ->
                if (shouldKillProcess(line)) {
                    val pid = extractPid(line)
                    if (pid != null) {
                        Log.i(TAG, "Found zombie node process: $pid. Killing...")
                        try {
                            runtime.exec(arrayOf("kill", "-9", pid)).waitFor()
                            killedCount++
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to kill PID $pid: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "PS parsing failed", e)
        }
        
        return killedCount
    }
    
    /**
     * Determine if a ps line represents a process we should kill.
     */
    private fun shouldKillProcess(line: String): Boolean {
        // Kill if line contains our runtime node path
        if (line.contains("runtime/bin/node")) return true
        
        // Kill if line contains both "node" and "n8n" 
        // (likely our n8n process, not some other node app)
        if (line.contains("node") && line.contains("n8n")) return true
        
        return false
    }
    
    /**
     * Extract PID from a ps output line.
     * Expected format: columns separated by whitespace, PID is second column.
     */
    private fun extractPid(line: String): String? {
        val parts = line.trim().split("\\s+".toRegex())
        if (parts.size > 1) {
            val pidStr = parts[1]
            // Verify it's numeric
            if (pidStr.all { it.isDigit() }) {
                return pidStr
            }
        }
        return null
    }
    
    /**
     * Read PID from a PID file if it exists.
     */
    fun readPidFromFile(pidFile: java.io.File): Int? {
        return try {
            if (pidFile.exists()) {
                pidFile.readText().trim().toIntOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read PID file", e)
            null
        }
    }
    
    /**
     * Kill a specific process by PID.
     * 
     * @param pid Process ID to kill
     * @param force Use SIGKILL if true, SIGTERM if false
     * @return True if kill command was executed (not necessarily successful)
     */
    fun killByPid(pid: Int, force: Boolean = true): Boolean {
        if (pid <= 0) return false
        
        return try {
            val signal = if (force) "-9" else "-15"
            Runtime.getRuntime().exec(arrayOf("kill", signal, pid.toString())).waitFor()
            Log.i(TAG, "Sent kill signal to PID $pid (force=$force)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to kill PID $pid", e)
            false
        }
    }
    
    private fun log(message: String) {
        Log.i(TAG, message)
        logCallback?.invoke(message)
    }
}
