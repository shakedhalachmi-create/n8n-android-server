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
    STOPPED, STARTING, RUNNING, RETRYING, FATAL_ERROR, STOPPING, ERROR_MISSING_RUNTIME,
    // New states for Smart Update
    VERIFYING_CACHE, INSTALLING
}

class ServerManager private constructor(
    private val context: Context,
    private val processRunner: ProcessRunner = RealProcessRunner(),
    private val keyProvider: KeyProvider = AndroidKeyProvider(context)
) {
    private val _state = MutableStateFlow(ServerState.STOPPED)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    // Hidden Debug Flag
    val isSmartUpdateEnabled = MutableStateFlow(true)

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

    fun startServer() {
        if (_state.value == ServerState.RUNNING || _state.value == ServerState.STARTING) return
        
        processJob = scope.launch {
            try {
                _state.value = ServerState.STARTING
                println("DEBUG: ServerState set to STARTING")

                // 0. Smart Update Check (Hidden Flag)
                if (isSmartUpdateEnabled.value) {
                    Log.i(TAG, "Smart Update Flag Active. Checking for updates...")
                    checkAndInstallRuntime(trustCache = true)
                }
                
                // 1. Pre-Flight Checks
                println("DEBUG: Checking nodeBin at: " + nodeBin.absolutePath)
                if (!ensureBinariesExist()) {
                    _state.value = ServerState.ERROR_MISSING_RUNTIME
                    Log.e(TAG, "Runtime binaries missing. Use OTA to install.")
                    return@launch
                }
                
                // 2. Prepare Environment
                val encryptionKey = getOrGenerateEncryptionKey()
                if (encryptionKey == null) {
                    _state.value = ServerState.FATAL_ERROR
                    Log.e(TAG, "Encryption Key issue. Aborting.")
                    return@launch
                }
                
                val env = buildEnvironment(encryptionKey)
                
                // 3. Command construction
                val n8nEntry = File(runtimeRoot, "lib/node_modules/n8n/bin/n8n")
                val bootstrapScript = File(runtimeRoot, "bin/n8n-start.sh")
                val command = if (bootstrapScript.exists()) {
                    // Explicitly use sh to avoid Shebang execution issues on some Android ROMs
                    listOf("/system/bin/sh", bootstrapScript.absolutePath)
                } else {
                    listOf(nodeBin.absolutePath, n8nEntry.absolutePath, "start")
                }

                // 4. Start Process
                userDataDir.mkdirs()
                logDir.mkdirs()

                Log.i(TAG, "Starting n8n process: $command")
                currentProcess = processRunner.start(command, env, userDataDir)
                
                // 5. Stream Consumption (Critical)
                launch(Dispatchers.IO) { consumeStream(currentProcess!!.inputStream, "STDOUT") }
                launch(Dispatchers.IO) { consumeStream(currentProcess!!.errorStream, "STDERR") }
                
                // 6. PID Tracking
                val pid = currentProcess!!.pid()
                if (pid > 0) {
                   pidFile.parentFile?.mkdirs()
                   pidFile.writeText(pid.toString())
                }
                
                _state.value = ServerState.RUNNING
                
                // 7. Wait for exit
                val exitCode = currentProcess!!.waitFor()
                handleExit(exitCode)

            } catch (e: Exception) {
                Log.e(TAG, "Error starting server", e)
                _state.value = ServerState.FATAL_ERROR
            }
        }
    }
    
    fun stopServer() {
        scope.launch {
            _state.value = ServerState.STOPPING
            currentProcess?.let { proc ->
                proc.destroy() 
                delay(5000)
                if (proc.isAlive()) {
                    Log.w(TAG, "Process ignored SIGTERM. Sending SIGKILL.")
                    proc.destroyForcibly()
                }
            }
            currentProcess = null
            _state.value = ServerState.STOPPED
        }
    }
    
    fun performHardRestart() {
        scope.launch {
            Log.w(TAG, "Performing Hard Restart...")
            stopServer()
            delay(6000) // 5s wait in stop + buffer
            startServer()
        }
    }
    
    fun isAlive(): Boolean {
        return currentProcess?.isAlive() == true
    }

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    /**
     * Smart Update Flow:
     * 1. Check Metadata
     * 2. Check Installed Version (Skip if match)
     * 3. Check Cache (Install if match)
     * 4. Download (Last resort)
     * @param trustCache If true, installs from cache even if checksum doesn't match remote (Dev Mode)
     */
    suspend fun checkAndInstallRuntime(trustCache: Boolean = false): Boolean {
        // 1. Fetch Metadata
        val metadata = RuntimeDownloader.getLatestMetadata()
        if (metadata == null) {
            Log.e(TAG, "Failed to fetch metadata")
            // Fallback: If we have ANY runtime installed, just use it?
            // For now, fail safe.
            return RuntimeInstaller.isRuntimeAvailable(context)
        }
        val remoteHash = metadata.sha256

        // 2. Check Installed Version
        _state.value = ServerState.VERIFYING_CACHE
        val installedHash = RuntimeInstaller.getInstalledHash(context)
        if (installedHash != null && installedHash.equals(remoteHash, ignoreCase = true)) {
            Log.i(TAG, "System is up to date (Hash: $installedHash).")
            if (!trustCache) {
                Log.i(TAG, "Skipping update.")
                if (_state.value != ServerState.RUNNING) _state.value = ServerState.STOPPED
                return true
            }
            Log.i(TAG, "Trust Cache is ON. Checking for local override...")
        }

        // 3. Check Local Cache
        val cacheDir = context.externalCacheDir ?: context.cacheDir
        val archiveFile = File(cacheDir, "n8n-android-arm64.tar.gz")

        if (archiveFile.exists()) {
             Log.i(TAG, "Found archived runtime. Verifying integrity...")
             val cachedHash = RuntimeDownloader.calculateSha256(archiveFile)
                 val matches = cachedHash.equals(remoteHash, ignoreCase = true)
                 
                 if (matches || trustCache) {
                     if (matches) {
                         Log.i(TAG, "Cache hit! Installing from local archive...")
                     } else {
                         Log.w(TAG, "Cache mismatch. Trust Cache is ON. Installing Dev Build...")
                     }

                     _state.value = ServerState.INSTALLING
                     _downloadProgress.value = 1.0f // Show full bar
                     
                     val installed = RuntimeInstaller.installFromArchive(context, archiveFile, remoteHash, verifyIntegrity = false)
                     if (installed) {
                         _state.value = ServerState.STOPPED
                     } else {
                         _state.value = ServerState.ERROR_MISSING_RUNTIME
                     }
                     return installed
                 } else {
                     Log.w(TAG, "Cache mismatch (Got $cachedHash, Expected $remoteHash). Will re-download.")
                     archiveFile.delete()
                 }
        }
        
        // 4. Download as Last Resort
        _state.value = ServerState.ERROR_MISSING_RUNTIME // Or some "DOWNLOADING" state? 
        // We stay in ERROR_MISSING_RUNTIME or introduce DOWNLOADING.
        // The UI uses 'downloadProgress > 0' to show bar.
        // Let's stick to ERROR_MISSING_RUNTIME (which shows the download button/bar) or better,
        // Since we are *actively* downloading now, maybe we should indicate that?
        // Actually the UI triggers this method via button click usually.
        // But if called automatically, we want the UI to reflect progress.
        // Let's use ERROR_MISSING_RUNTIME state (which in UI enables the download view) 
        // OR add DOWNLOADING. 
        // For now, 'ServerControlCard' shows progress bar if progress > 0.
        // So we can keep ERROR_MISSING_RUNTIME or STOPPED while downloading, ensuring progress updates.
        // However, we want to block Start button.
        
        // Let's proceed with download.
        try {
            _downloadProgress.value = 0.01f // Started
            val downloaded = RuntimeDownloader.downloadRuntime(
                metadata.downloadUrl,
                archiveFile,
                remoteHash
            ) { progress ->
                _downloadProgress.value = progress
            }
            
            if (!downloaded) {
                Log.e(TAG, "Download failed or checksum mismatch")
                _downloadProgress.value = 0f
                return false
            }
            
            _state.value = ServerState.INSTALLING
            _downloadProgress.value = 1.0f 
            
            // 5. Extract
            // 5. Extract
            val installed = RuntimeInstaller.installFromArchive(context, archiveFile, remoteHash, verifyIntegrity = false)
            if (installed) {
                _state.value = ServerState.STOPPED
            } else {
                _state.value = ServerState.ERROR_MISSING_RUNTIME
            }
            return installed
            
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            _downloadProgress.value = 0f
             _state.value = ServerState.ERROR_MISSING_RUNTIME
            return false
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
        
        return env
    }
    
    private fun handleExit(exitCode: Int) {
        Log.w(TAG, "Process exited with code $exitCode")
        
        if (exitCode == 101) { 
             _state.value = ServerState.FATAL_ERROR
             return
        }
        
        if (exitCode == 0 || exitCode == 143) { 
             _state.value = ServerState.STOPPED
             return
        }
        
        _state.value = ServerState.RETRYING
    }
}
