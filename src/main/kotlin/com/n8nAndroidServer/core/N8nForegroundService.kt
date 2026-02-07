package com.n8nAndroidServer.core

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import com.n8nAndroidServer.gatekeeper.GatekeeperModule
import com.n8nAndroidServer.bridge.CommandBridge
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first

/**
 * Foreground Service for n8n server management.
 * 
 * CRITICAL IMPLEMENTATION NOTES:
 * 1. startForeground() MUST be called FIRST in onStartCommand() to prevent ANR
 * 2. Acquires PARTIAL_WAKE_LOCK to prevent deep sleep
 * 3. Acquires HIGH_PERF WiFi lock for stable networking
 * 4. Schedules AlarmManager heartbeat every 5 minutes for clock drift mitigation
 * 5. Returns START_STICKY for automatic restart after OOM kill
 */
class N8nForegroundService : Service() {
    
    private lateinit var serverManager: ServerManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var monitorJob: Job? = null
    
    companion object {
        private const val TAG = "N8nForegroundService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "n8nAndroidServer_service"
        private const val HEARTBEAT_INTERVAL_MS = 300_000L // 5 minutes
        
        const val ACTION_START = "com.n8nAndroidServer.action.START_SERVER"
        const val ACTION_STOP = "com.n8nAndroidServer.action.STOP_SERVER"
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate()")
        
        serverManager = ServerManager.getInstance(applicationContext)
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // CRITICAL: Call startForeground IMMEDIATELY to prevent ANR
        Log.d(TAG, "Service startForeground() called")
        startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
        
        // Acquire WakeLocks
        acquireWakeLocks()
        
        // Schedule heartbeat
        scheduleHeartbeat()
        
        // Handle action
        when (intent?.action) {
            ACTION_START -> {
                Log.i(TAG, "Starting n8n server...")
                serverManager.startServer()
                GatekeeperModule.start(applicationContext)
                CommandBridge.start(applicationContext)
                startMonitoring()
            }
            ACTION_STOP -> {
                Log.i(TAG, "Stopping n8n server...")
                GatekeeperModule.stop()
                CommandBridge.stop()
                serverManager.stopServer()
                
                // Wait for fully STOPPED state before stopSelf()
                scope.launch {
                    serverManager.state.first { it == ServerState.STOPPED }
                    Log.i(TAG, "Server fully stopped. Stopping service...")
                    stopSelf()
                }
            }
        }
        
        // START_STICKY ensures service is restarted after OOM kill
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy()")
        
        GatekeeperModule.stop()
        CommandBridge.stop()
        releaseWakeLocks()
        cancelHeartbeat()
        monitorJob?.cancel()
        scope.cancel()
        
        // Ensure zombie processes are killed
        serverManager.killExistingNodeProcesses()
    }
    
    private fun acquireWakeLocks() {
        // PARTIAL_WAKE_LOCK prevents CPU deep sleep
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "n8nAndroidServer::ServerWakeLock"
        ).apply {
            acquire()
            Log.d(TAG, "PARTIAL_WAKE_LOCK acquired")
        }
        
        // HIGH_PERF WiFi lock for stable networking
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "n8nAndroidServer::WifiLock"
        ).apply {
            acquire()
            Log.d(TAG, "WIFI_MODE_FULL_HIGH_PERF acquired")
        }
    }
    
    private fun releaseWakeLocks() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wifiLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WifiLock released")
            }
        }
    }
    
    private fun scheduleHeartbeat() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, HeartbeatReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Fire every 5 minutes to nudge CPU and prevent clock drift
        alarmManager.setRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS,
            HEARTBEAT_INTERVAL_MS,
            pendingIntent
        )
        Log.d(TAG, "AlarmManager heartbeat scheduled (5min intervals)")
    }
    
    private fun cancelHeartbeat() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, HeartbeatReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )
        pendingIntent?.let {
            alarmManager.cancel(it)
            Log.d(TAG, "AlarmManager heartbeat cancelled")
        }
    }
    
    private fun startMonitoring() {
        monitorJob?.cancel() // Fix: Prevent multiple concurrent monitoring jobs
        monitorJob = scope.launch {
            serverManager.state.collect { state ->
                val statusText = when (state) {
                    ServerState.RUNNING -> "Server Running"
                    ServerState.STARTING -> "Starting..."
                    ServerState.STOPPING -> "Stopping..."
                    ServerState.STOPPED -> "Server Stopped"
                    ServerState.RETRYING -> "Retrying..."
                    ServerState.FATAL_ERROR -> "Fatal Error"
                    ServerState.NOT_INSTALLED -> "Not Installed"
                    ServerState.DOWNLOADING -> "Downloading Runtime..."
                    ServerState.VERIFYING_CACHE -> "Verifying Update..."
                    ServerState.INSTALLING -> "Installing Runtime..."
                }
                
                // Update notification
                val notification = createNotification(statusText)
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "n8n android server Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps n8n server running in background"
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("n8n android server")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
