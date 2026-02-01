package com.n8nAndroidServer.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Utility functions for power management and battery optimization checks.
 */
object PowerUtils {
    /**
     * Check if the app is ignoring battery optimizations.
     * On API 23+, returns true if battery optimization is disabled for this app.
     * On older APIs, returns true (no battery optimization exists).
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true // Battery optimization doesn't exist pre-M
        }
        
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
    
    /**
     * Create an Intent to request battery optimization exemption.
     * Opens the system settings page where the user can disable battery optimization.
     */
    fun createBatteryOptimizationIntent(context: Context): Intent {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        intent.data = Uri.parse("package:${context.packageName}")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }
}
