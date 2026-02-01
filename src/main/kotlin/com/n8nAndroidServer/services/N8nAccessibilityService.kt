package com.n8nAndroidServer.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

class N8nAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "N8nAccessibilityService Connected")
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We can log events here if needed, but for now we just want to perform actions
    }

    override fun onInterrupt() {
        Log.i(TAG, "N8nAccessibilityService Interrupted")
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun performTap(x: Float, y: Float): Boolean {
        Log.i(TAG, "Performing tap at $x, $y")
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.i(TAG, "Gesture Completed")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.e(TAG, "Gesture Cancelled")
            }
        }, null)
    }

    companion object {
        private const val TAG = "N8nAccessibilityService"
        var instance: N8nAccessibilityService? = null
            private set
    }
}
