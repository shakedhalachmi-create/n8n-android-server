package com.n8nAndroidServer.core.logging

import android.util.Log
import com.n8nAndroidServer.core.config.PathResolver
import com.n8nAndroidServer.core.config.ServerConfig

/**
 * Handles log file rotation to prevent unbounded growth.
 * 
 * ## Rotation Policies
 * 
 * 1. **UI Log**: Truncated when exceeds [ServerConfig.UI_LOG_MAX_SIZE_BYTES].
 *    This keeps the log display responsive in the app UI.
 * 
 * 2. **Archive Log**: Rotated when exceeds [ServerConfig.ARCHIVE_LOG_MAX_SIZE_BYTES].
 *    Old archive is renamed to .log.1, current file is cleared.
 * 
 * @param paths PathResolver for log file locations
 */
class LogRotator(
    private val paths: PathResolver
) {
    companion object {
        private const val TAG = "LogRotator"
    }
    
    /**
     * Check and rotate both logs if needed.
     */
    fun rotateIfNeeded() {
        rotateUiLogIfNeeded()
        rotateArchiveLogIfNeeded()
    }
    
    /**
     * Truncate UI log if it exceeds the maximum size.
     * 
     * The UI log is kept small so it can be displayed in the app
     * without consuming too much memory or making scrolling laggy.
     */
    fun rotateUiLogIfNeeded() {
        try {
            val logFile = paths.uiLogFile
            if (logFile.exists() && logFile.length() > ServerConfig.UI_LOG_MAX_SIZE_BYTES) {
                // Simple truncation - just clear the file
                // In a more sophisticated version, we could keep the last N lines
                logFile.writeText("")
                Log.d(TAG, "UI log truncated")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate UI log", e)
        }
    }
    
    /**
     * Rotate archive log if it exceeds the maximum size.
     * 
     * The current log is renamed to .log.1 and a new empty log is created.
     * Only one backup is kept to save storage space.
     */
    fun rotateArchiveLogIfNeeded() {
        try {
            val archiveLog = paths.archiveLogFile
            val backupLog = paths.archiveLogBackupFile
            
            if (archiveLog.exists() && archiveLog.length() > ServerConfig.ARCHIVE_LOG_MAX_SIZE_BYTES) {
                // Delete old backup
                if (backupLog.exists()) {
                    backupLog.delete()
                }
                
                // Rename current to backup
                archiveLog.renameTo(backupLog)
                
                Log.i(TAG, "Archive log rotated")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate archive log", e)
        }
    }
    
    /**
     * Get total size of all log files.
     */
    fun getTotalLogSize(): Long {
        var total = 0L
        
        try {
            if (paths.uiLogFile.exists()) {
                total += paths.uiLogFile.length()
            }
            if (paths.archiveLogFile.exists()) {
                total += paths.archiveLogFile.length()
            }
            if (paths.archiveLogBackupFile.exists()) {
                total += paths.archiveLogBackupFile.length()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate log size", e)
        }
        
        return total
    }
    
    /**
     * Delete all log files.
     */
    fun deleteAllLogs() {
        try {
            paths.uiLogFile.delete()
            paths.archiveLogFile.delete()
            paths.archiveLogBackupFile.delete()
            Log.i(TAG, "All logs deleted")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete logs", e)
        }
    }
}
