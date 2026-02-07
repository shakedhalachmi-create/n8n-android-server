package com.n8nAndroidServer.core.logging

import android.util.Log
import com.n8nAndroidServer.core.config.PathResolver
import com.n8nAndroidServer.core.config.ServerConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Unified logging interface bridging Node.js stdout/stderr and Android Logcat.
 * 
 * This class provides centralized logging with:
 * - Android Logcat output with consistent tags
 * - File logging for UI display
 * - File logging for archival
 * - Timestamp formatting
 * 
 * ## Log Destinations
 * 1. **Logcat**: Android's system log (viewable via adb logcat)
 * 2. **UI Log**: Small file for displaying in app UI (frequently truncated)
 * 3. **Archive Log**: Larger file for debugging (rotated when full)
 * 
 * ## Usage
 * ```kotlin
 * val logBridge = LogBridge(paths)
 * 
 * // Log system messages
 * logBridge.system("Server starting...")
 * 
 * // Log Node.js output
 * logBridge.fromNodeProcess(line, isError = false)
 * ```
 * 
 * @param paths PathResolver for log file locations
 */
class LogBridge(
    private val paths: PathResolver
) {
    companion object {
        // Tags for Logcat
        const val TAG_N8N_PROCESS = "n8n-proc"
        const val TAG_SYSTEM = "N8nServer"
        
        private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }
    
    private val rotator = LogRotator(paths)
    
    /**
     * Log a message from the Node.js process stdout/stderr.
     * 
     * @param line The log line from the process
     * @param isError True if from stderr, false if from stdout
     */
    fun fromNodeProcess(line: String, isError: Boolean) {
        // Log to Logcat
        if (isError) {
            Log.e(TAG_N8N_PROCESS, line)
        } else {
            Log.i(TAG_N8N_PROCESS, line)
        }
        
        // Write to files (without additional timestamp, Node.js may include its own)
        writeToFiles(line)
    }
    
    /**
     * Log a system message (from Kotlin code).
     * These are formatted with a timestamp for UI display.
     * 
     * @param message The message to log
     * @param level Log level
     */
    fun system(message: String, level: LogLevel = LogLevel.INFO) {
        val tag = TAG_SYSTEM
        
        // Log to Logcat
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.WARN -> Log.w(tag, message)
            LogLevel.ERROR -> Log.e(tag, message)
        }
        
        // Write to files with timestamp
        val formatted = formatWithTimestamp(message)
        writeToFiles(formatted)
    }
    
    /**
     * Log a message to the UI log only.
     * Use for user-facing status messages.
     */
    fun toUiLog(message: String) {
        val formatted = formatWithTimestamp(message)
        writeToUiLog(formatted)
    }
    
    /**
     * Log an error with exception details.
     */
    fun error(message: String, throwable: Throwable? = null) {
        Log.e(TAG_SYSTEM, message, throwable)
        
        val errorMessage = if (throwable != null) {
            "$message: ${throwable.message}"
        } else {
            message
        }
        writeToFiles(formatWithTimestamp(errorMessage))
    }
    
    /**
     * Read the current UI log contents.
     */
    fun readUiLog(): String {
        return try {
            if (paths.uiLogFile.exists()) {
                paths.uiLogFile.readText()
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG_SYSTEM, "Failed to read UI log", e)
            ""
        }
    }
    
    /**
     * Clear the UI log.
     */
    fun clearUiLog() {
        try {
            if (paths.uiLogFile.exists()) {
                paths.uiLogFile.writeText("")
            }
        } catch (e: Exception) {
            Log.e(TAG_SYSTEM, "Failed to clear UI log", e)
        }
    }
    
    private fun formatWithTimestamp(message: String): String {
        val timestamp = dateFormat.format(Date())
        return "[$timestamp] $message"
    }
    
    private fun writeToFiles(line: String) {
        try {
            ensureLogDirExists()
            
            // Write to UI log
            paths.uiLogFile.appendText("$line\n")
            
            // Write to archive log
            paths.archiveLogFile.appendText("$line\n")
            
            // Check rotation
            rotator.rotateIfNeeded()
            
        } catch (e: Exception) {
            Log.e(TAG_SYSTEM, "Failed to write log", e)
        }
    }
    
    private fun writeToUiLog(line: String) {
        try {
            ensureLogDirExists()
            paths.uiLogFile.appendText("$line\n")
            rotator.rotateUiLogIfNeeded()
        } catch (e: Exception) {
            Log.e(TAG_SYSTEM, "Failed to write UI log", e)
        }
    }
    
    private fun ensureLogDirExists() {
        val logDir = paths.uiLogFile.parentFile
        if (logDir != null && !logDir.exists()) {
            logDir.mkdirs()
        }
    }
}

/**
 * Log severity levels.
 */
enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}
