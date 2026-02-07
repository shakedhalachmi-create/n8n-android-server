package com.n8nAndroidServer.core.process

import android.util.Log
import com.n8nAndroidServer.core.config.ServerConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.net.HttpURLConnection
import java.net.URL

/**
 * Monitors the health of the n8n server process.
 * 
 * This watchdog service periodically pings the n8n API to detect:
 * - Server completely dead (process not running)
 * - Zombie state (process alive but unresponsive)
 * - Normal operation
 * 
 * ## Health Check Strategy
 * 1. Check if process is alive (via ProcessSupervisor)
 * 2. HTTP GET to n8n health endpoint
 * 3. If process alive but HTTP fails repeatedly → Zombie detected
 * 
 * ## Events
 * Subscribe to [healthEvents] flow to receive health status updates.
 * 
 * @param supervisor ProcessSupervisor to check process liveness
 */
class ProcessHealthMonitor(
    private val supervisor: ProcessSupervisor
) {
    companion object {
        private const val TAG = "ProcessHealthMonitor"
        
        /** Default interval between health checks */
        private const val DEFAULT_CHECK_INTERVAL_MS = 30_000L // 30 seconds
        
        /** Timeout for HTTP health check */
        private const val HTTP_TIMEOUT_MS = 5_000
        
        /** Number of consecutive failures before declaring zombie */
        private const val ZOMBIE_THRESHOLD = 3
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null
    
    private val _healthEvents = MutableSharedFlow<HealthEvent>(replay = 1)
    
    /**
     * Observable flow of health events.
     */
    val healthEvents: SharedFlow<HealthEvent> = _healthEvents.asSharedFlow()
    
    private var consecutiveFailures = 0
    
    /**
     * Start health monitoring with the specified interval.
     * 
     * @param intervalMs Milliseconds between health checks
     */
    fun start(intervalMs: Long = DEFAULT_CHECK_INTERVAL_MS) {
        if (monitorJob?.isActive == true) {
            Log.d(TAG, "Monitor already running")
            return
        }
        
        Log.i(TAG, "Starting health monitor (interval: ${intervalMs}ms)")
        consecutiveFailures = 0
        
        monitorJob = scope.launch {
            while (isActive) {
                try {
                    val result = performHealthCheck()
                    _healthEvents.emit(result)
                    
                    when (result) {
                        is HealthEvent.Healthy -> consecutiveFailures = 0
                        is HealthEvent.Unhealthy -> {
                            consecutiveFailures++
                            if (consecutiveFailures >= ZOMBIE_THRESHOLD) {
                                _healthEvents.emit(HealthEvent.Zombie(
                                    message = "Process unresponsive after $consecutiveFailures checks"
                                ))
                            }
                        }
                        is HealthEvent.ProcessDead -> consecutiveFailures = 0
                        is HealthEvent.Zombie -> {} // Already emitted
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Health check exception", e)
                }
                
                delay(intervalMs)
            }
        }
    }
    
    /**
     * Stop health monitoring.
     */
    fun stop() {
        Log.i(TAG, "Stopping health monitor")
        monitorJob?.cancel()
        monitorJob = null
        consecutiveFailures = 0
    }
    
    /**
     * Perform a single health check.
     */
    suspend fun performHealthCheck(): HealthEvent {
        // First check: Is the process alive?
        if (!supervisor.isRunning) {
            return HealthEvent.ProcessDead
        }
        
        // Second check: Can we reach the HTTP endpoint?
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("http://127.0.0.1:${ServerConfig.N8N_PORT}/healthz")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = HTTP_TIMEOUT_MS
                connection.readTimeout = HTTP_TIMEOUT_MS
                connection.requestMethod = "GET"
                
                try {
                    val responseCode = connection.responseCode
                    if (responseCode in 200..299) {
                        HealthEvent.Healthy(
                            pid = supervisor.pid,
                            responseTimeMs = 0 // Could measure this
                        )
                    } else {
                        HealthEvent.Unhealthy(
                            message = "HTTP $responseCode from health endpoint",
                            httpCode = responseCode
                        )
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                // Connection failed - could be zombie or just slow
                HealthEvent.Unhealthy(
                    message = "Health check failed: ${e.message}",
                    httpCode = null
                )
            }
        }
    }
    
    /**
     * Check if monitoring is currently active.
     */
    val isMonitoring: Boolean get() = monitorJob?.isActive == true
}

/**
 * Events emitted by the health monitor.
 */
sealed class HealthEvent {
    /**
     * Server is healthy and responding.
     */
    data class Healthy(
        val pid: Int,
        val responseTimeMs: Long
    ) : HealthEvent()
    
    /**
     * Server is running but not responding properly.
     * May recover on its own.
     */
    data class Unhealthy(
        val message: String,
        val httpCode: Int?
    ) : HealthEvent()
    
    /**
     * Process is not running.
     */
    data object ProcessDead : HealthEvent()
    
    /**
     * Process is alive but completely unresponsive (zombie state).
     * Requires restart.
     */
    data class Zombie(
        val message: String
    ) : HealthEvent()
}
