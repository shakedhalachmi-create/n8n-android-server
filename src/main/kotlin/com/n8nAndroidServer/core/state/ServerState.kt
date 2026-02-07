package com.n8nAndroidServer.core.state

/**
 * Represents the complete lifecycle states of the n8n server.
 * 
 * This sealed class provides type-safe state representation with
 * associated data where relevant, enabling pattern matching and
 * compile-time exhaustiveness checks.
 * 
 * ## State Machine Diagram
 * ```
 *                    ┌─────────────────────────────────────┐
 *                    │                                     │
 *                    ▼                                     │
 *  ┌──────┐    ┌──────────┐    ┌──────────┐    ┌─────────┐│
 *  │ Idle │───▶│Extracting│───▶│ Starting │───▶│ Running ││
 *  └──────┘    └──────────┘    └──────────┘    └─────────┘│
 *      ▲            │               │              │      │
 *      │            │               │              │      │
 *      │            ▼               ▼              ▼      │
 *      │       ┌─────────┐    ┌─────────┐    ┌──────────┐ │
 *      │       │  Error  │    │  Error  │    │ShuttingDn│─┘
 *      │       └─────────┘    └─────────┘    └──────────┘
 *      │            │               │              │
 *      └────────────┴───────────────┴──────────────┘
 * ```
 * 
 * ## State Descriptions
 * 
 * - **Idle**: Server is not running, ready to start
 * - **Extracting**: Runtime assets are being extracted from APK
 * - **Starting**: Node.js process launched, waiting for port
 * - **Running**: Server is active and accepting connections
 * - **Error**: An error occurred (may be recoverable)
 * - **ShuttingDown**: Graceful shutdown in progress
 */
sealed class ServerState {
    
    /**
     * Server is idle, not running.
     * This is the initial state and the state after clean shutdown.
     */
    data object Idle : ServerState()
    
    /**
     * Runtime assets are being extracted from APK.
     * This happens on first launch or after app update.
     * 
     * @property progress Extraction progress 0.0 to 1.0
     */
    data class Extracting(val progress: Float = 0f) : ServerState()
    
    /**
     * Node.js process has been launched, waiting for n8n to become ready.
     * The port is being polled until it becomes available.
     * 
     * @property pid Process ID of the launched node process
     * @property elapsedMs Time elapsed since start was initiated
     */
    data class Starting(
        val pid: Int = -1,
        val elapsedMs: Long = 0
    ) : ServerState()
    
    /**
     * Server is running and accepting connections.
     * 
     * @property pid Process ID of the running node process
     * @property startTime System.currentTimeMillis() when server started
     * @property port Port the server is listening on
     */
    data class Running(
        val pid: Int,
        val startTime: Long,
        val port: Int
    ) : ServerState()
    
    /**
     * An error has occurred.
     * 
     * @property message Human-readable error message
     * @property cause Optional underlying exception
     * @property recoverable True if the error might be resolved by retry
     * @property exitCode Process exit code if applicable
     */
    data class Error(
        val message: String,
        val cause: Throwable? = null,
        val recoverable: Boolean = false,
        val exitCode: Int? = null
    ) : ServerState()
    
    /**
     * Server is shutting down gracefully.
     * 
     * @property reason Why the shutdown was initiated
     */
    data class ShuttingDown(
        val reason: ShutdownReason = ShutdownReason.USER_REQUESTED
    ) : ServerState()
    
    /**
     * Check if a transition to another state is valid.
     * This enforces the state machine rules.
     */
    fun canTransitionTo(next: ServerState): Boolean {
        return when (this) {
            is Idle -> next is Extracting || next is Starting || next is Error
            is Extracting -> next is Starting || next is Error || next is Idle
            is Starting -> next is Running || next is Error || next is ShuttingDown
            is Running -> next is ShuttingDown || next is Error
            is Error -> next is Idle || next is Starting || next is Extracting
            is ShuttingDown -> next is Idle || next is Error
        }
    }
    
    /**
     * Check if server is in an active state (starting or running).
     */
    fun isActive(): Boolean = this is Starting || this is Running
    
    /**
     * Check if server is in a terminal error state.
     */
    fun isFatalError(): Boolean = this is Error && !(this as Error).recoverable
    
    /**
     * Get a human-readable status string for UI display.
     */
    fun toDisplayString(): String = when (this) {
        is Idle -> "Stopped"
        is Extracting -> "Installing Runtime..."
        is Starting -> "Starting..."
        is Running -> "Running"
        is Error -> if (recoverable) "Retrying..." else "Error"
        is ShuttingDown -> "Stopping..."
    }
}

/**
 * Reasons for server shutdown.
 */
enum class ShutdownReason {
    /** User explicitly requested stop */
    USER_REQUESTED,
    
    /** App is being destroyed/closed */
    APP_CLOSING,
    
    /** Service received stop command */
    SERVICE_STOPPED,
    
    /** Process crashed/died unexpectedly */
    PROCESS_DIED,
    
    /** Startup timeout exceeded */
    STARTUP_TIMEOUT
}
