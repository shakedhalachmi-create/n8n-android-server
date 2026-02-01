package com.n8nAndroidServer.core

import android.content.Context
import android.util.Log
// import androidx.security.crypto.EncryptedSharedPreferences
// import androidx.security.crypto.MasterKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

enum class ServerState {
    STOPPED, STARTING, RUNNING, RETRYING, FATAL_ERROR, STOPPING, 
    NOT_INSTALLED, DOWNLOADING, INSTALLING, VERIFYING_CACHE
}

class ServerManager private constructor(
    private val context: Context,
    private val processRunner: ProcessRunner = RealProcessRunner(),
    private val keyProvider: KeyProvider = AndroidKeyProvider(context)
) {
    private val _state = MutableStateFlow(ServerState.STOPPED)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    companion object {
        private const val TAG = "ServerManager"
        private const val PORT = "5679"

        @Volatile
        private var INSTANCE: ServerManager? = null

        fun getInstance(context: Context): ServerManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ServerManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // Hidden Debug Flag
    val isSmartUpdateEnabled = MutableStateFlow(false)

    private var currentProcess: RunningProcess? = null
    private var processJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // File Paths
    private val rootDir = context.filesDir
    private val runtimeRoot = File(rootDir, "runtime")
    private val nodeBin = File(runtimeRoot, "bin/node")
    private val userDataDir = File(rootDir, "userdata/n8n")
    private val logDir = File(rootDir, "userdata/logs")
    private val logFile = File(logDir, "n8n.log")
    private val archiveLogFile = File(logDir, "n8n-archive.log")
    private val pidFile = File(userDataDir, "n8n.pid")

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    /**
     * Smart Update Flow:
     * 1. Check Metadata
     * 2. Check Installed Version (Skip if match)
     * 3. Check Cache (Install if match)
     * 4. Download (Last resort)
     */
    suspend fun checkAndInstallRuntime(trustCache: Boolean = false): Boolean {
        // 1. Fetch Metadata
        val metadata = RuntimeDownloader.getLatestMetadata()
        if (metadata == null) {
            Log.e(TAG, "Failed to fetch metadata")
            return RuntimeInstaller.isRuntimeAvailable(context)
        }
        val remoteHash = metadata.sha256

        // 2. Check Installed Version
        _state.value = ServerState.VERIFYING_CACHE
        val installedHash = RuntimeInstaller.getInstalledHash(context)
        if (installedHash != null && installedHash.equals(remoteHash, ignoreCase = true)) {
            Log.i(TAG, "System is up to date.")
            if (!trustCache) {
                 if (_state.value != ServerState.RUNNING) _state.value = ServerState.STOPPED
                 return true
            }
        }

        // 3. Check Local Cache
        val cacheDir = context.externalCacheDir ?: context.cacheDir
        val archiveFile = File(cacheDir, "n8n-android-arm64.tar.gz")

        if (archiveFile.exists()) {
             // ... (Keep existing cache logic, just ensure state transitions are correct) ...
             // Simplified for brevity in this replacement, assuming inner logic is mostly same but ensuring INSTALLING state
             val cachedHash = RuntimeDownloader.calculateSha256(archiveFile)
             if (cachedHash.equals(remoteHash, ignoreCase = true) || trustCache) {
                 _state.value = ServerState.INSTALLING
                 _downloadProgress.value = 1.0f
                 val installed = RuntimeInstaller.installFromArchive(context, archiveFile, remoteHash, verifyIntegrity = false)
                 if (installed) _state.value = ServerState.STOPPED else _state.value = ServerState.NOT_INSTALLED
                 return installed
             } else {
                 archiveFile.delete()
             }
        }
        
        // 4. Download
        _state.value = ServerState.DOWNLOADING
        logToUi("Downloading runtime...")
        try {
            _downloadProgress.value = 0.01f
            val downloaded = RuntimeDownloader.downloadRuntime(
                metadata.downloadUrl,
                archiveFile,
                remoteHash
            ) { progress ->
                _downloadProgress.value = progress
            }
            
            if (!downloaded) {
                logToUi("Download failed.")
                _state.value = ServerState.NOT_INSTALLED
                return false
            }
            
            _state.value = ServerState.INSTALLING
            logToUi("Installing runtime...")
            _downloadProgress.value = 1.0f 
            
            val installed = RuntimeInstaller.installFromArchive(context, archiveFile, remoteHash, verifyIntegrity = false)
            if (installed) {
                logToUi("Installation complete.")
                _state.value = ServerState.STOPPED
            } else {
                logToUi("Installation failed.")
                _state.value = ServerState.NOT_INSTALLED
            }
            return installed
            
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
             _state.value = ServerState.FATAL_ERROR
            return false
        }
    }

    private fun logToUi(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val formatted = "[$timestamp] $message"
        try {
            logFile.parentFile?.mkdirs()
            logFile.appendText("$formatted\n")
            archiveLogFile.appendText("$formatted\n")
            checkAndRotateLogs()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to UI log", e)
        }
    }

    fun startServer() {
        if (_state.value == ServerState.RUNNING || _state.value == ServerState.STARTING) return
        
        processJob = scope.launch {
            try {
                _state.value = ServerState.STARTING
                logToUi("Initializing Server Startup...")

                // 0. Update/Install Check
                if (!ensureBinariesExist()) {
                    logToUi("Runtime not found. Initiating auto-install...")
                    val success = checkAndInstallRuntime(trustCache = isSmartUpdateEnabled.value)
                    if (!success) {
                        logToUi("Auto-install failed. Aborting.")
                        _state.value = ServerState.NOT_INSTALLED
                        return@launch
                    }
                    // If install success, state is STOPPED. Set back to STARTING to proceed.
                    _state.value = ServerState.STARTING
                }
                
                // 2. Prepare Environment
                logToUi("Preparing environment...")
                val encryptionKey = getOrGenerateEncryptionKey()
                if (encryptionKey == null) {
                    logToUi("FATAL: Encryption key generation failed.")
                    _state.value = ServerState.FATAL_ERROR
                    return@launch
                }
                
                val env = buildEnvironment(encryptionKey)
                
                // 3. Command construction
                val n8nEntry = File(runtimeRoot, "lib/node_modules/n8n/bin/n8n")
                val bootstrapScript = File(runtimeRoot, "bin/n8n-start.sh")
                val command = if (bootstrapScript.exists()) {
                    listOf("/system/bin/sh", bootstrapScript.absolutePath)
                } else {
                    listOf(nodeBin.absolutePath, n8nEntry.absolutePath, "start")
                }

                // 4. Start Process
                userDataDir.mkdirs()
                logDir.mkdirs()

                logToUi("Launching n8n process...")
                currentProcess = processRunner.start(command, env, userDataDir)
                logToUi("Process started (PID: ${currentProcess?.pid()}). Waiting for port $PORT...")
                
                // 5. Stream Consumption
                launch(Dispatchers.IO) { consumeStream(currentProcess!!.inputStream, "STDOUT") }
                launch(Dispatchers.IO) { consumeStream(currentProcess!!.errorStream, "STDERR") }
                
                // 6. PID Tracking
                val pid = currentProcess!!.pid()
                if (pid > 0) {
                   pidFile.parentFile?.mkdirs()
                   pidFile.writeText(pid.toString())
                }
                
                // 7. Port Polling (Max 45s)
                var portReady = false
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < 45000) { // 45s timeout
                    if (currentProcess?.isAlive() != true) {
                        logToUi("Process died unexpectedly.")
                        break 
                    }
                    try {
                        java.net.Socket("127.0.0.1", PORT.toInt()).use { portReady = true }
                    } catch (e: Exception) {
                        // Retry
                    }
                    
                    if (portReady) {
                        logToUi("Port $PORT is OPEN. Server is RUNNING.")
                        break
                    }
                    delay(1000)
                }

                if (!portReady) {
                     logToUi("FATAL: Timed out waiting for port $PORT.")
                     stopServer() // Cleanup
                     _state.value = ServerState.FATAL_ERROR
                     return@launch
                }
                
                _state.value = ServerState.RUNNING
                
                // 8. Wait for exit
                val exitCode = currentProcess!!.waitFor()
                logToUi("Server process exited with code $exitCode")
                handleExit(exitCode)

            } catch (e: CancellationException) {
                logToUi("Server process cancelled.")
                _state.value = ServerState.STOPPED
            } catch (e: Exception) {
                logToUi("FATAL EXCEPTION: ${e.message}")
                Log.e(TAG, "Error starting server", e)
                _state.value = ServerState.FATAL_ERROR
            }
        }
    }

    private suspend fun ensureBinariesExist(): Boolean {
        // Just check if we are runnable. If not, trigger install flow logic if needed
        // But typically ensureBinaries just returns false if missing, prompting UI to show "Download"
        // If we want auto-update, we call checkAndInstallRuntime()
        if (RuntimeInstaller.isRuntimeAvailable(context)) return true
        return false // Let UI handle "Missing Runtime" state
    }
    
    private suspend fun consumeStream(stream: java.io.InputStream, type: String) {
        try {
            logDir.mkdirs()
            stream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (type == "STDERR") Log.e("n8n-proc", line) else Log.i("n8n-proc", line)
                    try {
                        // Write to UI Log
                        logFile.appendText("$line\n")
                        
                        // Write to Archive Log
                        archiveLogFile.appendText("$line\n")
                        
                        // Check Rotation
                        checkAndRotateLogs()
                    } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stream consumer failed for $type", e)
        }
    }
    
    private fun checkAndRotateLogs() {
        // 1. UI Log Rotation (Max 5KB) - Truncate
        try {
            if (logFile.exists() && logFile.length() > 5 * 1024) { // 5KB
                // Delete existing content, effectively truncating to show only latest
                // In a real scrolling UI, we might want to keep the last N lines, but for simplicity
                // and to avoid memory issues, we just clear it or keep tail. 
                // Requirement: "Delete old logs if exceeds"
                logFile.writeText("") 
            }
        } catch (e: Exception) {
             Log.e(TAG, "Failed to rotate UI log", e)
        }
        
        // 2. Archive Log Rotation (Max 1MB) - Roll over
        try {
            if (archiveLogFile.exists() && archiveLogFile.length() > 1 * 1024 * 1024) { // 1MB
                val backup = File(logDir, "n8n-archive.log.1")
                if (backup.exists()) backup.delete()
                archiveLogFile.renameTo(backup)
                // New archive file will be created on next write
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate Archive log", e)
        }
    }
    
    fun clearUiLog() {
        try {
            if (logFile.exists()) {
                logFile.writeText("")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear UI log", e)
        }
    }

    private fun getOrGenerateEncryptionKey(): String? {
        return keyProvider.getKey() ?: keyProvider.generateKey()
    }

    private fun buildEnvironment(encryptionKey: String): Map<String, String> {
        val env = HashMap<String, String>()
        
        // Universal Contract v1.5
        env["HOME"] = userDataDir.absolutePath
        env["N8N_USER_FOLDER"] = userDataDir.absolutePath
        env["LD_LIBRARY_PATH"] = File(runtimeRoot, "lib").absolutePath
        env["PATH"] = "${File(runtimeRoot, "bin").absolutePath}:${System.getenv("PATH")}"
        env["OPENSSL_CONF"] = File(runtimeRoot, "etc/tls/openssl.cnf").absolutePath
        env["N8N_PORT"] = PORT
        env["N8N_HOST"] = "127.0.0.1"
        env["N8N_LISTEN_ADDRESS"] = "127.0.0.1"
        env["N8N_ENCRYPTION_KEY"] = encryptionKey
        env["DB_TYPE"] = "sqlite"
        env["NODE_OPTIONS"] = "--max-old-space-size=512" 
        env["NODE_PATH"] = File(runtimeRoot, "lib/node_modules").absolutePath
        env["TMPDIR"] = context.cacheDir.absolutePath
        env["N8N_SECURE_COOKIE"] = "false"
        
        // Timezone Sync
        val timeZone = java.util.TimeZone.getDefault().id
        env["GENERIC_TIMEZONE"] = timeZone
        
        // Dynamic Dev Mode
        env["NODE_ENV"] = if (isSmartUpdateEnabled.value) "development" else "production"

        return env
    }
    
    private suspend fun handleExit(exitCode: Int) {
        Log.w(TAG, "Process exited with code $exitCode")
        
        if (_state.value == ServerState.STOPPING) {
            logToUi("Process terminated. Cool-down (3s)...")
            delay(3000)
        }
        
        if (exitCode == 101) { 
             _state.value = ServerState.FATAL_ERROR
             return
        }
        
        if (exitCode == 0 || exitCode == 143 || _state.value == ServerState.STOPPING) { 
             _state.value = ServerState.STOPPED
             logToUi("Server is now fully STOPPED.")
             currentProcess = null
             return
        }
        
        _state.value = ServerState.RETRYING
    }

    fun stopServer() {
        if (_state.value == ServerState.STOPPED || _state.value == ServerState.STOPPING) return
        _state.value = ServerState.STOPPING
        logToUi("Stopping n8n server...")
        
        scope.launch {
            try {
                // 1. Try graceful termination
                currentProcess?.destroy()
                
                // 2. Monitor exit for up to 5 seconds
                // handleExit() handles the standard 3s cool-down.
                // We only invoke force kill if the process acts stuck.
                var attempts = 0
                while (attempts < 50) { // 5 seconds (100ms intervals)
                    if (_state.value == ServerState.STOPPED) return@launch // Success
                    
                    if (attempts == 20) { // After 2s, try force kill
                        if (currentProcess?.isAlive() == true) {
                            logToUi("Process unresponsive. Forcing kill...")
                            currentProcess?.destroyForcibly()
                        }
                    }
                    
                    delay(100)
                    attempts++
                }
                
                // 3. Failsafe
                if (_state.value != ServerState.STOPPED) {
                    logToUi("Shutdown timed out. Forcing state reset.")
                    processJob?.cancel()
                    currentProcess = null
                    _state.value = ServerState.STOPPED
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in stop sequence", e)
                _state.value = ServerState.STOPPED
            }
        }
    }
    
    fun performHardRestart() {
        scope.launch {
            logToUi("Hard restart requested...")
            stopServer()
            delay(2000)
            startServer()
        }
    }
}
