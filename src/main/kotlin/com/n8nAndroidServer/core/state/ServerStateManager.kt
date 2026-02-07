package com.n8nAndroidServer.core.state

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages server state transitions with validation and logging.
 * 
 * This class is the single source of truth for server state, providing:
 * - Thread-safe state updates via StateFlow
 * - Transition validation (invalid transitions are logged and rejected)
 * - State history for debugging
 * 
 * ## Usage
 * ```kotlin
 * val stateManager = ServerStateManager()
 * 
 * // Observe state changes
 * stateManager.state.collect { state ->
 *     when (state) {
 *         is ServerState.Running -> showRunningUI()
 *         is ServerState.Error -> showError(state.message)
 *         // ...
 *     }
 * }
 * 
 * // Transition to new state
 * stateManager.transitionTo(ServerState.Starting())
 * ```
 * 
 * @param initialState The initial state (defaults to Idle)
 */
class ServerStateManager(
    initialState: ServerState = ServerState.Idle
) {
    companion object {
        private const val TAG = "ServerStateManager"
        private const val MAX_HISTORY_SIZE = 20
    }
    
    private val _state = MutableStateFlow(initialState)
    
    /**
     * Observable state flow for UI binding.
     */
    val state: StateFlow<ServerState> = _state.asStateFlow()
    
    /**
     * Current state value (snapshot).
     * Prefer observing [state] flow in most cases.
     */
    val currentState: ServerState get() = _state.value
    
    /**
     * History of state transitions for debugging.
     * Most recent transition is last.
     */
    private val _history = mutableListOf<StateTransition>()
    val history: List<StateTransition> get() = _history.toList()
    
    /**
     * Attempt to transition to a new state.
     * 
     * If the transition is invalid according to the state machine rules,
     * it will be rejected and logged as a warning.
     * 
     * @param newState The target state
     * @param force If true, skip validation (use sparingly for error recovery)
     * @return True if transition was successful, false if rejected
     */
    @Synchronized
    fun transitionTo(newState: ServerState, force: Boolean = false): Boolean {
        val oldState = _state.value
        
        // Same state, no-op
        if (oldState == newState) {
            Log.d(TAG, "State unchanged: ${oldState::class.simpleName}")
            return true
        }
        
        // Validate transition
        if (!force && !oldState.canTransitionTo(newState)) {
            Log.w(TAG, "Invalid transition rejected: ${oldState::class.simpleName} → ${newState::class.simpleName}")
            return false
        }
        
        // Record transition
        val transition = StateTransition(
            from = oldState,
            to = newState,
            timestamp = System.currentTimeMillis(),
            forced = force
        )
        recordTransition(transition)
        
        // Apply transition
        _state.value = newState
        
        Log.i(TAG, "State transition: ${oldState::class.simpleName} → ${newState::class.simpleName}")
        
        return true
    }
    
    /**
     * Force transition to error state.
     * This always succeeds as errors can occur from any state.
     * 
     * @param message Error message
     * @param cause Optional exception
     * @param recoverable Whether retry might succeed
     */
    fun transitionToError(
        message: String,
        cause: Throwable? = null,
        recoverable: Boolean = false,
        exitCode: Int? = null
    ) {
        transitionTo(
            ServerState.Error(
                message = message,
                cause = cause,
                recoverable = recoverable,
                exitCode = exitCode
            ),
            force = true // Errors can occur from any state
        )
    }
    
    /**
     * Reset to idle state.
     * Use after clean shutdown or to recover from errors.
     */
    fun reset() {
        transitionTo(ServerState.Idle, force = true)
    }
    
    /**
     * Check if currently in a specific state type.
     */
    fun isInState(stateClass: kotlin.reflect.KClass<out ServerState>): Boolean {
        return stateClass.isInstance(_state.value)
    }
    
    /**
     * Get state if it matches the expected type.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : ServerState> getStateAs(stateClass: kotlin.reflect.KClass<T>): T? {
        return if (stateClass.isInstance(_state.value)) _state.value as T else null
    }
    
    /**
     * Update Running state with new values (e.g., updated uptime).
     * Only works if currently in Running state.
     */
    fun updateRunningState(update: (ServerState.Running) -> ServerState.Running): Boolean {
        val current = _state.value
        if (current is ServerState.Running) {
            _state.value = update(current)
            return true
        }
        return false
    }
    
    private fun recordTransition(transition: StateTransition) {
        _history.add(transition)
        // Keep history bounded
        while (_history.size > MAX_HISTORY_SIZE) {
            _history.removeAt(0)
        }
    }
    
    /**
     * Get a formatted log of recent state transitions for debugging.
     */
    fun getTransitionLog(): String {
        return _history.joinToString("\n") { transition ->
            val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                .format(java.util.Date(transition.timestamp))
            val forced = if (transition.forced) " [FORCED]" else ""
            "[$time] ${transition.from::class.simpleName} → ${transition.to::class.simpleName}$forced"
        }
    }
}

/**
 * Records a state transition for debugging/auditing.
 */
data class StateTransition(
    val from: ServerState,
    val to: ServerState,
    val timestamp: Long,
    val forced: Boolean = false
)
