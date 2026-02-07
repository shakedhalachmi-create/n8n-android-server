package com.n8nAndroidServer.core.config

/**
 * Centralized configuration constants for the n8n Android Server.
 * 
 * This object eliminates magic strings and numbers scattered across the codebase,
 * providing a single source of truth for all configuration values.
 * 
 * ## Port Allocation Strategy
 * - **5680**: Gatekeeper public-facing port (receives external traffic)
 * - **5681**: Internal n8n port (only accessible via localhost)
 * - **5679**: Reserved for n8n internal task runners (disabled on Android)
 * 
 * ## Architecture Notes
 * The n8n server runs on an internal port, with Gatekeeper acting as a
 * reverse proxy that handles IP whitelisting and request routing.
 */
object ServerConfig {
    
    // ============================
    // Port Configuration
    // ============================
    
    /** 
     * Internal n8n server port. Only accessible via localhost.
     * External access must go through Gatekeeper on [GATEKEEPER_PORT].
     */
    const val N8N_PORT = 5681
    
    /**
     * Public-facing Gatekeeper proxy port.
     * This is the port users connect to from external devices.
     */
    const val GATEKEEPER_PORT = 5680
    
    /**
     * Command Bridge port for internal Kotlin↔Node communication.
     */
    const val COMMAND_BRIDGE_PORT = 5682
    
    // ============================
    // Timeouts & Intervals
    // ============================
    
    /**
     * Maximum time to wait for n8n to start and port to become available.
     * n8n on Android can take 60-90 seconds to fully initialize.
     */
    const val STARTUP_TIMEOUT_MS = 90_000L
    
    /**
     * Maximum time to wait for graceful shutdown before force-killing.
     */
    const val SHUTDOWN_TIMEOUT_MS = 5_000L
    
    /**
     * Port polling interval during startup.
     */
    const val PORT_POLL_INTERVAL_MS = 1_000L
    
    /**
     * Delay after killing zombie processes to allow OS to release ports.
     */
    const val POST_KILL_DELAY_MS = 500L
    
    /**
     * Cool-down period after process termination before state reset.
     */
    const val SHUTDOWN_COOLDOWN_MS = 3_000L
    
    /**
     * AlarmManager heartbeat interval for preventing CPU deep sleep.
     */
    const val HEARTBEAT_INTERVAL_MS = 300_000L // 5 minutes
    
    // ============================
    // Logging Configuration
    // ============================
    
    /**
     * Maximum size of the UI log file before truncation.
     * Keeps the log window responsive by limiting displayed content.
     */
    const val UI_LOG_MAX_SIZE_BYTES = 5 * 1024 // 5KB
    
    /**
     * Maximum size of the archive log before rotation.
     */
    const val ARCHIVE_LOG_MAX_SIZE_BYTES = 1024 * 1024 // 1MB
    
    // ============================
    // Node.js Configuration
    // ============================
    
    /**
     * Maximum V8 heap size in MB.
     * Tuned for Android memory constraints.
     */
    const val NODE_MAX_OLD_SPACE_SIZE_MB = 512
    
    // ============================
    // Runtime Paths (relative)
    // ============================
    // Note: These are relative paths used for building full paths via PathResolver
    
    const val RUNTIME_DIR_NAME = "runtime"
    const val USERDATA_DIR_NAME = "userdata"
    const val N8N_USERDATA_SUBDIR = "n8n"
    const val LOGS_SUBDIR = "logs"
    
    // Asset filename (with custom extension to prevent compression)
    const val RUNTIME_ASSET_FILENAME = "core_runtime.n8n"
    
    // Binary locations (relative to runtime root)
    const val NODE_BIN_PATH = "bin/node"
    const val NODE_REAL_BIN_PATH = "bin/node_bin" // Actual binary (node is a wrapper)
    const val BOOTSTRAP_SCRIPT_PATH = "bin/n8n-start.sh"
    const val N8N_ENTRY_PATH = "lib/node_modules/n8n/bin/n8n"
    const val NODE_MODULES_PATH = "lib/node_modules"
    const val LIB_PATH = "lib"
    const val BIN_PATH = "bin"
    
    // ============================
    // Log Tags
    // ============================
    
    const val TAG_SERVER_MANAGER = "ServerManager"
    const val TAG_RUNTIME_INSTALLER = "RuntimeInstaller"
    const val TAG_PROCESS_SUPERVISOR = "ProcessSupervisor"
    const val TAG_N8N_PROCESS = "n8n-proc"
    const val TAG_FOREGROUND_SERVICE = "N8nForegroundService"
    
    // ============================
    // Notification Configuration
    // ============================
    
    const val NOTIFICATION_ID = 1001
    const val NOTIFICATION_CHANNEL_ID = "n8nAndroidServer_service"
    
    // ============================
    // SharedPreferences Keys
    // ============================
    
    const val PREFS_NAME = "n8n_runtime_prefs"
    const val PREF_KEY_ASSET_VERSION = "last_asset_version_code"
    const val PREF_KEY_ENCRYPTION_KEY = "n8n_encryption_key"
}
