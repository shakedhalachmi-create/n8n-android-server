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
        private const val PORT = "5681"

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
     * Installs or updates the runtime from assets.
     */
    suspend fun installRuntime(): Boolean {
        _state.value = ServerState.INSTALLING
        logToUi("Verifying Runtime Assets...")
        
        return withContext(Dispatchers.IO) {
            val success = RuntimeInstaller.installFromAssets(context)
            if (success) {
                logToUi("Runtime ready.")
                _state.value = ServerState.STOPPED
            } else {
                logToUi("FATAL: Runtime installation failed.")
                _state.value = ServerState.FATAL_ERROR
            }
            success
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

    private val isStarting = java.util.concurrent.atomic.AtomicBoolean(false)

    @Synchronized
    fun startServer() {
        if (!isStarting.compareAndSet(false, true)) {
             Log.d(TAG, "startServer ignored: Race condition or already starting")
             return
        }

        if (_state.value == ServerState.RUNNING || _state.value == ServerState.STARTING) {
            Log.d(TAG, "startServer ignored: already ${state.value}")
            isStarting.set(false)
            return
        }
        
        _state.value = ServerState.STARTING
        
        processJob = scope.launch {
            try {
                logToUi("Initializing Server Startup...")
                
                // 0. Cleanup Previous Processes
                killExistingNodeProcesses()

                // 1. Install / Verify Runtime
                if (!installRuntime()) {
                    isStarting.set(false)
                    return@launch
                }
                
                // If install success, state might be STOPPED. Set back to STARTING.
                _state.value = ServerState.STARTING
                
                // 2. Prepare Environment
                logToUi("Preparing environment...")
                val encryptionKey = getOrGenerateEncryptionKey()
                if (encryptionKey == null) {
                    logToUi("FATAL: Encryption key generation failed.")
                    _state.value = ServerState.FATAL_ERROR
                    isStarting.set(false)
                    return@launch
                }
                
                val env = buildEnvironment(encryptionKey)
                
                // 3. Command construction
                val n8nEntry = File(runtimeRoot, "lib/node_modules/n8n/bin/n8n")
                val bootstrapScript = File(runtimeRoot, "bin/n8n-start.sh")
                
                // Use bootstrap script which handles LD_LIBRARY_PATH and execution
                val command = if (bootstrapScript.exists()) {
                    listOf("/system/bin/sh", bootstrapScript.absolutePath)
                } else {
                    // Fallback (Should typically not happen with new installer)
                    listOf(nodeBin.absolutePath, n8nEntry.absolutePath, "start")
                }

                // 4. Start Process
                userDataDir.mkdirs()
                logDir.mkdirs()

                logToUi("Launching n8n process...")
                currentProcess = processRunner.start(command, env, userDataDir)
                // Reset startup lock once process is actually launched
                isStarting.set(false)

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
                while (System.currentTimeMillis() - startTime < 90000) { // 45s timeout
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
                isStarting.set(false)
            } catch (e: Exception) {
                logToUi("FATAL EXCEPTION: ${e.message}")
                Log.e(TAG, "Error starting server", e)
                _state.value = ServerState.FATAL_ERROR
                isStarting.set(false)
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
        env["OPENSSL_CONF"] = "/dev/null" // Force ignore system OpenSSL
        env["N8N_PORT"] = PORT
        env["N8N_HOST"] = "127.0.0.1"
        env["N8N_LISTEN_ADDRESS"] = "0.0.0.0"
        env["N8N_ENCRYPTION_KEY"] = encryptionKey
        env["DB_TYPE"] = "sqlite"
        env["NODE_OPTIONS"] = "--max-old-space-size=512" 
        env["NODE_PATH"] = File(runtimeRoot, "lib/node_modules").absolutePath
        env["TMPDIR"] = context.cacheDir.absolutePath
        env["N8N_SECURE_COOKIE"] = "false"
        
        // Timezone Sync
        val timeZone = java.util.TimeZone.getDefault().id
        env["GENERIC_TIMEZONE"] = timeZone
        
        // Android Optimization / Singularity
        // Disable Task Runners (separate processes) to prevent port conflicts
        
        // New Method (v1.7 fix)
        env["N8N_BLOCK_JS_EXECUTION_PROCESS"] = "true"
        env["N8N_DISABLE_PYTHON_NODE"] = "true"
        env["N8N_TASKS_EVALUATOR_PROCESS"] = "main"
        
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

    fun killExistingNodeProcesses() {
        logToUi("Process Cleanup: Starting...")
        try {
            val runtime = Runtime.getRuntime()
            var killedCount = 0

            // Method A: Built-in shell commands (simple try)
            val commands = listOf("pkill -9 node", "killall -9 node")
            for (cmd in commands) {
                try {
                    val p = runtime.exec(arrayOf("/system/bin/sh", "-c", cmd))
                    p.waitFor()
                } catch (e: Exception) {
                    // Ignore, move to next
                }
            }

            // Method B: Robust PS Parsing (The real fix)
            // ps -A outputs: USER PID PPID VSIZE RSS WCHAN PC NAME
            try {
                val p = runtime.exec(arrayOf("/system/bin/ps", "-A"))
                val output = p.inputStream.bufferedReader().use { it.readText() }
                p.waitFor()

                // DEBUG: Log all node-like lines to see what we're missing
                val nodeLines = output.lines().filter { it.contains("node") || it.contains("n8n") }
                if (nodeLines.isNotEmpty()) {
                     Log.d(TAG, "PS DEBUG: Found potential node processes:\n${nodeLines.joinToString("\n")}")
                }

                output.lines().forEach { line ->
                    if (line.contains("runtime/bin/node") || (line.contains("node") && line.contains("n8n"))) {
                        // Extract PID (2nd column usually, but split by whitespace handles it)
                        val parts = line.trim().split("\\s+".toRegex())
                        if (parts.size > 1) {
                            val pidStr = parts[1]
                            // Verify it's a number
                            if (pidStr.all { it.isDigit() }) {
                                Log.i(TAG, "Found zombie node process: $pidStr. Killing...")
                                runtime.exec(arrayOf("kill", "-9", pidStr)).waitFor()
                                killedCount++
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "PS parsing failed", e)
            }
            
            logToUi("Process Cleanup: Killed $killedCount zombie processes.")
            if (killedCount > 0) Thread.sleep(500) // Wait for OS to release ports

        } catch (e: Exception) {
            Log.e(TAG, "General cleanup failure", e)
            logToUi("Process Cleanup: Failed (${e.message})")
        }
    }
}
