package com.n8nAndroidServer.core.config

import android.content.Context
import java.io.File

/**
 * Centralized file path resolution for the n8n Android Server.
 * 
 * This class provides a single source of truth for all file and directory
 * paths used throughout the application, eliminating scattered path construction.
 * 
 * ## Directory Structure (on device)
 * ```
 * /data/data/com.n8nAndroidServer/files/
 * ├── runtime/                    # Extracted n8n runtime
 * │   ├── bin/
 * │   │   ├── node               # Node.js wrapper script
 * │   │   ├── node_bin           # Actual Node.js binary
 * │   │   └── n8n-start.sh       # Bootstrap script
 * │   ├── lib/
 * │   │   ├── *.so               # Shared libraries (patched)
 * │   │   └── node_modules/      # n8n and dependencies
 * │   └── etc/                   # Configuration files
 * └── userdata/
 *     ├── n8n/                   # n8n user data (HOME)
 *     │   ├── n8n.pid            # PID file
 *     │   └── .n8n/              # n8n internal data
 *     └── logs/
 *         ├── n8n.log            # UI log (truncated)
 *         └── n8n-archive.log    # Full archive log
 * ```
 * 
 * @param context Application context for accessing filesDir/cacheDir
 */
class PathResolver(private val context: Context) {
    
    // ============================
    // Root Directories
    // ============================
    
    /** Application's private files directory */
    val filesDir: File get() = context.filesDir
    
    /** Application's cache directory (for temporary files) */
    val cacheDir: File get() = context.cacheDir
    
    /** Root directory containing the extracted n8n runtime */
    val runtimeRoot: File 
        get() = File(filesDir, ServerConfig.RUNTIME_DIR_NAME)
    
    /** Temporary directory for atomic extraction */
    val runtimeTempDir: File 
        get() = File(filesDir, "${ServerConfig.RUNTIME_DIR_NAME}_tmp")
    
    /** Root directory for user data (logs, n8n config, etc.) */
    val userDataRoot: File 
        get() = File(filesDir, ServerConfig.USERDATA_DIR_NAME)
    
    // ============================
    // n8n User Data Directories
    // ============================
    
    /** 
     * n8n user data directory. 
     * This is set as HOME and N8N_USER_FOLDER for the n8n process.
     */
    val n8nUserDir: File 
        get() = File(userDataRoot, ServerConfig.N8N_USERDATA_SUBDIR)
    
    /** Directory containing application logs */
    val logDir: File 
        get() = File(userDataRoot, ServerConfig.LOGS_SUBDIR)
    
    // ============================
    // Log Files
    // ============================
    
    /** 
     * UI log file (truncated frequently to keep UI responsive).
     * Max size: [ServerConfig.UI_LOG_MAX_SIZE_BYTES]
     */
    val uiLogFile: File 
        get() = File(logDir, "n8n.log")
    
    /** 
     * Archive log file (rotated when exceeds max size).
     * Max size: [ServerConfig.ARCHIVE_LOG_MAX_SIZE_BYTES]
     */
    val archiveLogFile: File 
        get() = File(logDir, "n8n-archive.log")
    
    /** Backup archive log file (after rotation) */
    val archiveLogBackupFile: File 
        get() = File(logDir, "n8n-archive.log.1")
    
    // ============================
    // Runtime Binaries
    // ============================
    
    /** 
     * Node.js wrapper script.
     * This is a shell script that sets LD_LIBRARY_PATH and invokes [nodeRealBin].
     */
    val nodeBin: File 
        get() = File(runtimeRoot, ServerConfig.NODE_BIN_PATH)
    
    /** 
     * Actual Node.js binary (renamed from 'node' during build).
     * Invoked by the node wrapper script.
     */
    val nodeRealBin: File 
        get() = File(runtimeRoot, ServerConfig.NODE_REAL_BIN_PATH)
    
    /** 
     * Bootstrap script that launches n8n with proper environment.
     * Preferred entry point as it handles LD_LIBRARY_PATH setup.
     */
    val bootstrapScript: File 
        get() = File(runtimeRoot, ServerConfig.BOOTSTRAP_SCRIPT_PATH)
    
    /** n8n CLI entry point (JavaScript) */
    val n8nEntry: File 
        get() = File(runtimeRoot, ServerConfig.N8N_ENTRY_PATH)
    
    // ============================
    // Runtime Library Directories
    // ============================
    
    /** 
     * Directory containing patched shared libraries (.so files).
     * Used in LD_LIBRARY_PATH.
     * 
     * **Critical**: Libraries are patched with patchelf to:
     * - Rename versioned libs (libz.so.1 → libz.so)
     * - Fix DT_NEEDED entries
     */
    val libDir: File 
        get() = File(runtimeRoot, ServerConfig.LIB_PATH)
    
    /** Directory containing Node.js modules (n8n and dependencies) */
    val nodeModulesDir: File 
        get() = File(runtimeRoot, ServerConfig.NODE_MODULES_PATH)
    
    /** Binary directory (node, n8n-start.sh) */
    val binDir: File 
        get() = File(runtimeRoot, ServerConfig.BIN_PATH)
    
    // ============================
    // Process Management
    // ============================
    
    /** PID file for tracking the running n8n process */
    val pidFile: File 
        get() = File(n8nUserDir, "n8n.pid")
    
    // ============================
    // Temporary/Cache Files
    // ============================
    
    /** Temporary location for runtime tarball during extraction */
    val tempRuntimeTarball: File 
        get() = File(cacheDir, "temp_runtime.tar.gz")
    
    // ============================
    // Utility Methods
    // ============================
    
    /**
     * Ensure all required directories exist.
     * Call during initialization.
     */
    fun ensureDirectoriesExist() {
        n8nUserDir.mkdirs()
        logDir.mkdirs()
    }
    
    /**
     * Check if the runtime appears to be properly installed.
     * Validates that critical binaries exist and are executable.
     */
    fun isRuntimeInstalled(): Boolean {
        return nodeBin.exists() && 
               nodeBin.canExecute() && 
               bootstrapScript.exists()
    }
    
    /**
     * Get the PATH environment variable value including our bin directory.
     */
    fun getPathEnvValue(): String {
        val systemPath = System.getenv("PATH") ?: "/system/bin:/system/xbin"
        return "${binDir.absolutePath}:$systemPath"
    }
}
