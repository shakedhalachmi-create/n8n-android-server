package com.n8nAndroidServer.core

import android.content.Context
import android.util.Log
import com.n8nAndroidServer.core.config.EnvironmentBuilder
import com.n8nAndroidServer.core.config.PathResolver
import com.n8nAndroidServer.core.config.ServerConfig
import com.n8nAndroidServer.core.logging.LogBridge
import com.n8nAndroidServer.core.logging.LogLevel
import com.n8nAndroidServer.core.process.ProcessHealthMonitor
import com.n8nAndroidServer.core.process.ProcessSupervisor
import com.n8nAndroidServer.core.process.StartResult
import com.n8nAndroidServer.core.process.ZombieProcessCleaner
import com.n8nAndroidServer.core.runtime.InstallResult
import com.n8nAndroidServer.core.runtime.RuntimeOrchestrator
import com.n8nAndroidServer.core.state.ServerState
import com.n8nAndroidServer.core.state.ServerStateManager
import com.n8nAndroidServer.core.state.ShutdownReason
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates the n8n server lifecycle.
 * 
 * This class is a thin orchestration layer that coordinates between:
 * - [ServerStateManager]: State machine management
 * - [RuntimeOrchestrator]: Asset extraction and installation
 * - [ProcessSupervisor]: Process lifecycle management
 * - [ZombieProcessCleaner]: Cleanup of orphaned processes
 * - [LogBridge]: Unified logging
 * - [EnvironmentBuilder]: Environment variable construction
 * - [KeyProvider]: Encryption key management
 * 
 * ## Architecture
 * ```
 *                     ┌──────────────────┐
 *                     │  ServerManager   │
 *                     │  (Orchestrator)  │
 *                     └────────┬─────────┘
 *                              │
 *     ┌────────────────────────┼────────────────────────┐
 *     │                        │                        │
 *     ▼                        ▼                        ▼
 * ┌─────────────┐     ┌──────────────────┐     ┌──────────────┐
 * │ StateManager│     │ProcessSupervisor │     │RuntimeOrchestr│
 * └─────────────┘     └──────────────────┘     └──────────────┘
 * ```
 * 
 * ## Thread Safety
 * - Uses [AtomicBoolean] to prevent concurrent start operations
 * - State transitions are synchronized via [ServerStateManager]
 * 
 * @param context Application context
 * @param processRunner ProcessRunner implementation (for testing)
 * @param keyProvider KeyProvider implementation (for testing)
 */
class ServerManager private constructor(
    private val context: Context,
    private val processRunner: ProcessRunner = RealProcessRunner(),
    private val keyProvider: KeyProvider = AndroidKeyProvider(context)
) {
    companion object {
        private const val TAG = ServerConfig.TAG_SERVER_MANAGER
        
        @Volatile
        private var INSTANCE: ServerManager? = null
        
        /**
         * Get the singleton instance.
         */
        fun getInstance(context: Context): ServerManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ServerManager(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        /**
         * Create instance with custom dependencies (for testing).
         */
        fun createForTesting(
            context: Context,
            processRunner: ProcessRunner,
            keyProvider: KeyProvider
        ): ServerManager {
            return ServerManager(context, processRunner, keyProvider)
        }
    }
    
    // ============================
    // Dependencies
    // ============================
    
    private val paths = PathResolver(context)
    private val stateManager = ServerStateManager()
    private val environmentBuilder = EnvironmentBuilder(context, paths)
    private val runtimeOrchestrator = RuntimeOrchestrator(context, paths)
    private val processSupervisor = ProcessSupervisor(paths, processRunner)
    private val zombieCleaner = ZombieProcessCleaner { message -> logBridge.toUiLog(message) }
    private val logBridge = LogBridge(paths)
    private val healthMonitor = ProcessHealthMonitor(processSupervisor)
    
    // ============================
    // Public State
    // ============================
    
    /**
     * Observable server state.
     * 
     * Note: This provides backward compatibility with the old enum-based state.
     * New code should prefer [serverState] for the richer sealed class state.
     */
    private val _legacyState = MutableStateFlow(LegacyServerState.STOPPED)
    val state: StateFlow<LegacyServerState> = _legacyState.asStateFlow()
    
    /**
     * Rich server state with associated data.
     */
    val serverState: StateFlow<ServerState> = stateManager.state
    
    /**
     * Download progress (0.0 to 1.0) for runtime download.
     * Currently unused as we use asset-based installation.
     */
    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()
    
    /**
     * Debug flag for development mode.
     */
    val isSmartUpdateEnabled = MutableStateFlow(false)
    
    // ============================
    // Internal State
    // ============================
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var processJob: Job? = null
    private val isStarting = AtomicBoolean(false)
    
    // ============================
    // Public API
    // ============================
    
    /**
     * Install or update the runtime from assets.
     * 
     * @return True if runtime is ready, false if installation failed
     */
    suspend fun installRuntime(): Boolean {
        updateLegacyState(LegacyServerState.INSTALLING)
        logBridge.system("Verifying Runtime Assets...")
        
        return withContext(Dispatchers.IO) {
            val result = runtimeOrchestrator.installIfNeeded()
            
            when (result) {
                is InstallResult.AlreadyUpToDate,
                is InstallResult.Installed -> {
                    logBridge.system("Runtime ready.")
                    updateLegacyState(LegacyServerState.STOPPED)
                    true
                }
                is InstallResult.Failed -> {
                    logBridge.error("FATAL: Runtime installation failed: ${result.reason}")
                    updateLegacyState(LegacyServerState.FATAL_ERROR)
                    stateManager.transitionToError(result.reason, recoverable = false)
                    false
                }
            }
        }
    }
    
    /**
     * Start the n8n server.
     * 
     * This method is synchronized to prevent concurrent start operations.
     * If a start is already in progress, subsequent calls are ignored.
     */
    @Synchronized
    fun startServer() {
        // Prevent concurrent starts
        if (!isStarting.compareAndSet(false, true)) {
            Log.d(TAG, "startServer ignored: already starting")
            return
        }
        
        // Check current state
        val currentState = serverState.value
        if (currentState.isActive()) {
            Log.d(TAG, "startServer ignored: already ${currentState::class.simpleName}")
            isStarting.set(false)
            return
        }
        
        updateLegacyState(LegacyServerState.STARTING)
        stateManager.transitionTo(ServerState.Starting())
        
        processJob = scope.launch {
            try {
                executeStartSequence()
            } catch (e: CancellationException) {
                logBridge.system("Server start cancelled")
                updateLegacyState(LegacyServerState.STOPPED)
                stateManager.reset()
            } catch (e: Exception) {
                logBridge.error("FATAL EXCEPTION", e)
                updateLegacyState(LegacyServerState.FATAL_ERROR)
                stateManager.transitionToError(e.message ?: "Unknown error", e, false)
            } finally {
                isStarting.set(false)
            }
        }
    }
    
    /**
     * Stop the n8n server gracefully.
     */
    fun stopServer() {
        if (state.value == LegacyServerState.STOPPED || 
            state.value == LegacyServerState.STOPPING) return
        
        updateLegacyState(LegacyServerState.STOPPING)
        stateManager.transitionTo(ServerState.ShuttingDown(ShutdownReason.USER_REQUESTED))
        
        logBridge.system("Stopping n8n server...")
        healthMonitor.stop()
        
        scope.launch {
            try {
                val stopped = processSupervisor.stop()
                
                if (!stopped) {
                    logBridge.system("Process unresponsive, forcing kill...")
                    processSupervisor.forceKill()
                }
                
                delay(ServerConfig.SHUTDOWN_COOLDOWN_MS)
                
                logBridge.system("Server stopped.")
                updateLegacyState(LegacyServerState.STOPPED)
                stateManager.reset()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in stop sequence", e)
                updateLegacyState(LegacyServerState.STOPPED)
                stateManager.reset()
            }
        }
    }
    
    /**
     * Perform a hard restart (stop + start).
     */
    fun performHardRestart() {
        scope.launch {
            logBridge.system("Hard restart requested...")
            stopServer()
            delay(2000)
            startServer()
        }
    }
    
    /**
     * Kill any existing node processes (zombie cleanup).
     */
    fun killExistingNodeProcesses() {
        scope.launch {
            zombieCleaner.cleanupZombieProcesses()
        }
    }
    
    /**
     * Clear the UI log.
     */
    fun clearUiLog() {
        logBridge.clearUiLog()
    }
    
    /**
     * Read the current UI log contents.
     */
    fun readUiLog(): String {
        return logBridge.readUiLog()
    }
    
    // ============================
    // Private Implementation
    // ============================
    
    private suspend fun executeStartSequence() {
        logBridge.system("Initializing Server Startup...")
        
        // Step 1: Cleanup zombie processes
        zombieCleaner.cleanupZombieProcesses()
        
        // Step 2: Install/verify runtime
        if (!installRuntime()) {
            return
        }
        
        // Reset state after install (it changes to STOPPED on success)
        updateLegacyState(LegacyServerState.STARTING)
        stateManager.transitionTo(ServerState.Starting())
        
        // Step 3: Prepare environment
        logBridge.system("Preparing environment...")
        val encryptionKey = getOrGenerateEncryptionKey()
        if (encryptionKey == null) {
            logBridge.error("FATAL: Encryption key generation failed")
            updateLegacyState(LegacyServerState.FATAL_ERROR)
            stateManager.transitionToError("Encryption key generation failed", recoverable = false)
            return
        }
        
        val environment = environmentBuilder.build(
            encryptionKey = encryptionKey,
            devMode = isSmartUpdateEnabled.value
        )
        
        // Step 4: Start process and wait for port
        logBridge.system("Launching n8n process...")
        val result = processSupervisor.startAndWaitForPort(environment)
        
        when (result) {
            is StartResult.Success -> {
                logBridge.system("Port ${result.port} is OPEN. Server is RUNNING.")
                
                updateLegacyState(LegacyServerState.RUNNING)
                stateManager.transitionTo(ServerState.Running(
                    pid = result.pid,
                    startTime = System.currentTimeMillis(),
                    port = result.port
                ))
                
                // Start stream consumption
                startStreamConsumption()
                
                // Start health monitoring
                healthMonitor.start()
                
                // Wait for exit
                awaitProcessExit()
            }
            
            is StartResult.Timeout -> {
                logBridge.error("FATAL: Timed out waiting for port ${result.port}")
                processSupervisor.forceKill()
                updateLegacyState(LegacyServerState.FATAL_ERROR)
                stateManager.transitionToError("Startup timeout", recoverable = true)
            }
            
            is StartResult.ProcessDied -> {
                logBridge.error("Process died during startup (exit: ${result.exitCode})")
                updateLegacyState(LegacyServerState.FATAL_ERROR)
                stateManager.transitionToError(
                    "Process died during startup",
                    exitCode = result.exitCode,
                    recoverable = true
                )
            }
            
            is StartResult.Error -> {
                logBridge.error("Failed to start: ${result.message}")
                updateLegacyState(LegacyServerState.FATAL_ERROR)
                stateManager.transitionToError(result.message, result.cause, false)
            }
        }
    }
    
    private fun startStreamConsumption() {
        processSupervisor.getInputStream()?.let { stream ->
            scope.launch(Dispatchers.IO) {
                stream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        logBridge.fromNodeProcess(line, isError = false)
                    }
                }
            }
        }
        
        processSupervisor.getErrorStream()?.let { stream ->
            scope.launch(Dispatchers.IO) {
                stream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        logBridge.fromNodeProcess(line, isError = true)
                    }
                }
            }
        }
    }
    
    private suspend fun awaitProcessExit() {
        withContext(Dispatchers.IO) {
            val exitCode = processSupervisor.waitForExit() ?: 0
            logBridge.system("Server process exited with code $exitCode")
            healthMonitor.stop()
            handleExit(exitCode)
        }
    }
    
    private suspend fun handleExit(exitCode: Int) {
        Log.w(TAG, "Process exited with code $exitCode")
        
        when {
            // Fatal error code
            exitCode == 101 -> {
                updateLegacyState(LegacyServerState.FATAL_ERROR)
                stateManager.transitionToError("Fatal exit code 101", exitCode = exitCode)
            }
            
            // Clean exit or expected termination
            exitCode == 0 || exitCode == 143 || 
            state.value == LegacyServerState.STOPPING -> {
                delay(ServerConfig.SHUTDOWN_COOLDOWN_MS)
                logBridge.system("Server is now fully STOPPED.")
                updateLegacyState(LegacyServerState.STOPPED)
                stateManager.reset()
            }
            
            // Unexpected exit, might be recoverable
            else -> {
                updateLegacyState(LegacyServerState.RETRYING)
                stateManager.transitionToError(
                    "Unexpected exit",
                    exitCode = exitCode,
                    recoverable = true
                )
            }
        }
    }
    
    private fun getOrGenerateEncryptionKey(): String? {
        return keyProvider.getKey() ?: keyProvider.generateKey()
    }
    
    private fun updateLegacyState(newState: LegacyServerState) {
        _legacyState.value = newState
    }
}

/**
 * Legacy server state enum for backward compatibility with existing UI code.
 * 
 * New code should use [ServerState] sealed class instead.
 */
enum class LegacyServerState {
    STOPPED,
    STARTING,
    RUNNING,
    RETRYING,
    FATAL_ERROR,
    STOPPING,
    NOT_INSTALLED,
    DOWNLOADING,
    INSTALLING,
    VERIFYING_CACHE
}

/**
 * Type alias for backward compatibility.
 */
typealias ServerState_Legacy = LegacyServerState
