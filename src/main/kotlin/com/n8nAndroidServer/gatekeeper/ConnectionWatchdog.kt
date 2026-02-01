package com.n8nAndroidServer.gatekeeper

import android.util.Log
import com.n8nAndroidServer.core.ServerManager
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Connection Watchdog - Monitors backend health and triggers restart if needed.
 * 
 * Logic:
 * - Tracks consecutive 5xx responses or connection failures
 * - After 3 consecutive failures, enters 60s grace period
 * - If failures continue after grace period, triggers ServerManager.performHardRestart()
 * 
 * This prevents flapping during temporary backend hiccups.
 */
class ConnectionWatchdog(private val serverManager: ServerManager) {
    
    companion object {
        private const val TAG = "ConnectionWatchdog"
        private const val FAILURE_THRESHOLD = 3
        private const val GRACE_PERIOD_MS = 60_000L // 60 seconds
    }
    
    private val consecutiveFailures = AtomicInteger(0)
    private var gracePeriodJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Report a successful backend response.
     * Resets the failure counter.
     */
    fun reportSuccess() {
        val previousFailures = consecutiveFailures.getAndSet(0)
        if (previousFailures > 0) {
            Log.i(TAG, "✅ Backend recovered (was $previousFailures consecutive failures)")
            gracePeriodJob?.cancel()
            gracePeriodJob = null
        }
    }
    
    /**
     * Report a backend failure (5xx response or connection error).
     * Increments failure counter and may trigger restart logic.
     */
    fun reportFailure(reason: String) {
        val failures = consecutiveFailures.incrementAndGet()
        Log.w(TAG, "❌ Backend failure #$failures: $reason")
        
        if (failures == FAILURE_THRESHOLD && gracePeriodJob == null) {
            Log.w(TAG, "⚠️ Threshold reached ($FAILURE_THRESHOLD failures). Starting 60s grace period...")
            gracePeriodJob = scope.launch {
                delay(GRACE_PERIOD_MS)
                
                // Check if still failing after grace period
                val currentFailures = consecutiveFailures.get()
                if (currentFailures >= FAILURE_THRESHOLD) {
                    Log.e(TAG, "🔁 Grace period expired with $currentFailures failures. Triggering HARD RESTART")
                    serverManager.performHardRestart()
                    consecutiveFailures.set(0)
                } else {
                    Log.i(TAG, "✅ Backend recovered during grace period")
                }
                
                gracePeriodJob = null
            }
        }
    }
    
    /**
     * Check if currently in grace period (waiting before restart).
     */
    fun isInGracePeriod(): Boolean {
        return gracePeriodJob != null
    }
    
    fun shutdown() {
        gracePeriodJob?.cancel()
        scope.cancel()
    }
}
