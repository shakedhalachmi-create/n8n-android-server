package com.n8nAndroidServer.core

import android.util.Log
import kotlinx.coroutines.delay

/**
 * Resilience module for exponential backoff and crash recovery.
 * Implements retry logic with exponential delays capped at 60 seconds.
 */
object Resilience {
    private const val TAG = "Resilience"
    private const val INITIAL_DELAY_MS = 1000L // 1 second
    private const val MAX_DELAY_MS = 60000L // 60 seconds
    
    /**
     * Calculate the backoff delay for a given retry attempt.
     * Sequence: 1s, 2s, 4s, 8s, 16s, 32s, 60s (capped)
     * 
     * @param retryCount The number of retry attempts (0-indexed)
     * @return Delay in milliseconds
     */
    fun calculateBackoff(retryCount: Int): Long {
        if (retryCount < 0) return INITIAL_DELAY_MS
        
        val exponentialDelay = INITIAL_DELAY_MS * (1 shl retryCount) // 2^retryCount
        return exponentialDelay.coerceAtMost(MAX_DELAY_MS)
    }
    
    /**
     * Suspend and wait for the calculated backoff period.
     * 
     * @param retryCount The current retry attempt number
     */
    suspend fun waitBackoff(retryCount: Int) {
        val delayMs = calculateBackoff(retryCount)
        Log.i(TAG, "Retry attempt $retryCount: waiting ${delayMs}ms before restart")
        delay(delayMs)
    }
    
    /**
     * Execute a block with exponential backoff retry logic.
     * Stops retrying if the block returns true (success).
     * 
     * @param maxRetries Maximum number of retry attempts (default 10)
     * @param block The suspending block to execute, returns true on success
     */
    suspend fun retryWithBackoff(
        maxRetries: Int = 10,
        block: suspend (attemptNumber: Int) -> Boolean
    ): Boolean {
        repeat(maxRetries) { attempt ->
            try {
                if (block(attempt)) {
                    Log.i(TAG, "Operation succeeded on attempt $attempt")
                    return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Operation failed on attempt $attempt", e)
            }
            
            if (attempt < maxRetries - 1) {
                waitBackoff(attempt)
            }
        }
        
        Log.e(TAG, "Operation failed after $maxRetries attempts")
        return false
    }
}
