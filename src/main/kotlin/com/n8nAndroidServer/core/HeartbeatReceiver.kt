package com.n8nAndroidServer.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver for AlarmManager heartbeat.
 * Fires every 5 minutes to prevent clock drift during deep sleep.
 * 
 * Purpose: Android's deep sleep can cause JavaScript timers (setTimeout/setInterval)
 * to drift or stall. This heartbeat "nudges" the CPU awake periodically to ensure
 * the n8n process remains responsive.
 */
class HeartbeatReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "HeartbeatReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "❤️ Heartbeat Pulse - CPU nudged to prevent clock drift")
        
        // The mere fact of executing this code wakes up the CPU briefly.
        // This prevents Node.js timers from drifting during deep sleep.
        // No additional action needed unless we want to check service health.
        
        // Optional: Could ping the ServerManager to verify n8n is still alive
        // and trigger restart if needed, but ConnectionWatchdog already handles that.
    }
}
