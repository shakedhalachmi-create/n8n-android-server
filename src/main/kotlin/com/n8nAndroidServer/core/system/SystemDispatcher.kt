package com.n8nAndroidServer.core.system

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat

import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.provider.Settings
import android.widget.Toast
import android.content.ClipboardManager
import android.content.ClipData
import com.n8nAndroidServer.services.N8nAccessibilityService
import android.bluetooth.BluetoothProfile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SystemDispatcher(private val context: Context) : BluetoothProfile.ServiceListener {

    private var a2dpProxy: BluetoothProfile? = null
    private val proxyLatch = CountDownLatch(1)

    init {
        // Initialize A2DP Proxy (Safeguarded)
        if (hasBluetoothPermissions()) {
            try {
                val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = bluetoothManager?.adapter
                adapter?.getProfileProxy(context, this, BluetoothProfile.A2DP)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init Bluetooth Proxy", e)
            }
        } else {
             Log.w(TAG, "Skipping Bluetooth Proxy init: Missing Permissions")
        }
    }

    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
        if (profile == BluetoothProfile.A2DP) {
            a2dpProxy = proxy
            Log.d(TAG, "A2DP Proxy connected")
            proxyLatch.countDown()
        }
    }

    override fun onServiceDisconnected(profile: Int) {
        if (profile == BluetoothProfile.A2DP) {
            a2dpProxy = null
            Log.d(TAG, "A2DP Proxy disconnected")
        }
    }

    companion object {
        private const val TAG = "SystemDispatcher"
        
        // Target Device for "Plugin" 1
        private const val TARGET_DEVICE_NAME = "RX-V381 YamahX"
        // MAC fragment from discovery: 00:1F:47
    }

    fun execute(req: SystemRequest): SystemResult {
        Log.d(TAG, "Dispatching: ${req.category} -> ${req.action}")
        
        return try {
            when (req.category.lowercase()) {
                "bluetooth" -> handleBluetooth(req)
                "app" -> handleApp(req)
                "battery" -> handleBattery(req)
                "flashlight" -> handleFlashlight(req)
                "volume" -> handleVolume(req)
                "brightness" -> handleBrightness(req)
                "screen" -> handleScreen(req)
                "display" -> handleBrightness(req) // Legacy alias for brightness mostly
                "connectivity" -> handleConnectivity(req)
                "wifi" -> handleConnectivity(req)
                "src" -> handleUi(req) // Typos/Legacy
                "ui" -> handleUi(req)
                "clipboard" -> handleClipboard(req)
                "feedback" -> handleFeedback(req)
                else -> SystemResult(false, "Unknown category: ${req.category}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Execution failed", e)
            SystemResult(false, "Error: ${e.message}")
        }
    }

    // ... (handleBluetooth, etc.)

    private fun handleBrightness(req: SystemRequest): SystemResult {
        if (!Settings.System.canWrite(context)) {
            return SystemResult(false, "Missing WRITE_SETTINGS permission")
        }

        // 1. Simplified Protocol: Action IS the level (e.g. "200")
        val levelFromAction = req.action.toIntOrNull()
        if (levelFromAction != null) {
            return setBrightness(levelFromAction)
        }

        return try {
            when (req.action.lowercase()) {
                "set", "set_brightness" -> {
                    val levelStr = req.params?.get("level") ?: return SystemResult(false, "Missing parameter: level")
                    val level = levelStr.toIntOrNull() ?: return SystemResult(false, "Invalid level")
                    setBrightness(level)
                }
                "auto", "auto_brightness" -> {
                     Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                     SystemResult(true, "Auto-Brightness enabled")
                }
                "status", "get" -> {
                    val mode = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
                    val level = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
                    val modeStr = if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) "Auto" else "Manual"
                    SystemResult(true, "Level: $level, Mode: $modeStr")
                }
                else -> SystemResult(false, "Unknown Brightness action: ${req.action}")
            }
        } catch (e: Exception) {
            SystemResult(false, "Brightness Error: ${e.message}")
        }
    }

    private fun setBrightness(level: Int): SystemResult {
        val finalLevel = level.coerceIn(0, 255)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, finalLevel)
        return SystemResult(true, "Brightness set to $finalLevel")
    }

    private fun handleScreen(req: SystemRequest): SystemResult {
        return try {
             when (req.action.lowercase()) {
                "on" -> {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    val wakeLock = pm.newWakeLock(
                        android.os.PowerManager.FULL_WAKE_LOCK or
                                android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                                android.os.PowerManager.ON_AFTER_RELEASE,
                        "n8n:WakeLock"
                    )
                    wakeLock.acquire(1000)
                    SystemResult(true, "Screen turned ON")
                }
                "off" -> {
                    handleUi(SystemRequest("ui", "lock"))
                }
                "status" -> {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    val isInteractive = pm.isInteractive
                    SystemResult(true, if (isInteractive) "ON" else "OFF")
                }
                else -> SystemResult(false, "Unknown Screen action: ${req.action}")
             }.also {
                 Log.d(TAG, "Screen Result: ${it.success} - ${it.message}")
             }
        } catch (e: Exception) {
            Log.e(TAG, "Screen Error", e)
            SystemResult(false, "Screen Error: ${e.message}")
        }
    }

    private fun handleBluetooth(req: SystemRequest): SystemResult {
        // Permission Check
        if (!hasBluetoothPermissions()) {
            return SystemResult(false, "Missing Bluetooth Permissions. Please grant BLUETOOTH_CONNECT/SCAN.")
        }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: return SystemResult(false, "Bluetooth not available on device")

        // Check enabled state only for actions that require it
        return when (req.action.lowercase()) {
            "enable", "on" -> {
                 if (adapter.enable()) SystemResult(true, "Bluetooth enabling...") else SystemResult(false, "Failed to enable Bluetooth (System restriction?)")
            }
            "connect" -> {
                if (!adapter.isEnabled) return SystemResult(false, "Bluetooth is disabled")
                connectToTarget(adapter, req.params)
            }
            "disable", "off" -> {
                 if (adapter.isEnabled && adapter.disable()) SystemResult(true, "Bluetooth disabling...") else SystemResult(false, "Failed to disable Bluetooth (System restriction or already disabled?)")
            }
            "scan" -> {
                if (!adapter.isEnabled) return SystemResult(false, "Bluetooth is disabled")
                val devices = adapter.bondedDevices.joinToString(separator = ", ") { "${it.name} (${it.address})" }
                if (devices.isNotEmpty()) {
                    SystemResult(true, "Paired Devices: $devices")
                } else {
                    SystemResult(true, "No paired devices found")
                }
            }
            else -> SystemResult(false, "Unknown Bluetooth action: ${req.action}")
        }
    }

    @SuppressLint("MissingPermission") // Checked in hasBluetoothPermissions
    private fun connectToTarget(adapter: BluetoothAdapter, params: Map<String, String>?): SystemResult {
        // Resolve target: explicit parameter > default constant
        val targetNameOrAddress = params?.get("target")
        
        val bondedDevices = adapter.bondedDevices
        
        val target = if (targetNameOrAddress != null) {
            bondedDevices.find { it.name == targetNameOrAddress || it.address == targetNameOrAddress }
        } else {
            // Fallback to default if no parameter provided
            bondedDevices.find { it.name == TARGET_DEVICE_NAME || it.address.startsWith("00:1F:47") }
        }

        return if (target != null) {
            Log.i(TAG, "Found target device: ${target.name} (${target.address})")
            
            // Try to force connection via A2DP reflection
            if (forceA2dpConnect(target)) {
                 SystemResult(true, "Connection initiated to ${target.name} via A2DP.")
            } else {
                 SystemResult(true, "Device found (${target.name}) but active connection failed. Ensure device is in range.")
            }
        } else {
             val msg = if (targetNameOrAddress != null) "Device '$targetNameOrAddress' not found." else "Default device '$TARGET_DEVICE_NAME' not found."
             SystemResult(false, "$msg Please pair it first.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun forceA2dpConnect(device: BluetoothDevice): Boolean {
        // Wait for proxy if needed (rare case if called immediately on startup)
        try {
            if (a2dpProxy == null) {
                proxyLatch.await(2, TimeUnit.SECONDS)
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for proxy")
        }

        val proxy = a2dpProxy ?: return false
        
        return try {
            // Reflection: connect(BluetoothDevice)
            val connectMethod = (proxy as Any).javaClass.getDeclaredMethod("connect", BluetoothDevice::class.java)
            connectMethod.isAccessible = true
            val success = connectMethod.invoke(proxy, device) as Boolean
            Log.i(TAG, "Reflection connect() result: $success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to invoke connect via reflection", e)
            false
        }
    }

    private fun handleApp(req: SystemRequest): SystemResult {
        return when (req.action.lowercase()) {
            "launch" -> {
                val packageName = req.params?.get("package_name") 
                    ?: return SystemResult(false, "Missing parameter: package_name")
                
                // Use launch intent from package manager
                val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    SystemResult(true, "Launched app: $packageName")
                } else {
                    SystemResult(false, "App not installed or not launchable: $packageName")
                }
            }
            else -> SystemResult(false, "Unknown App action: ${req.action}")
        }
    }

    private fun handleBattery(req: SystemRequest): SystemResult {
        if (req.action == "get_status") {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val pct = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else -1
                
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                val pluggedStr = when (plugged) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                    BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                    else -> "Battery"
                }

                val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
                val healthStr = when (health) {
                   BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                   BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
                   BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                   BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
                   else -> "Unknown"
                }
                
                SystemResult(true, "Level: $pct%, Power: $pluggedStr, Health: $healthStr")
            } else {
                SystemResult(false, "Failed to get battery status")
            }
        }
        return SystemResult(false, "Unknown Battery action: ${req.action}")
    }

    private fun handleFlashlight(req: SystemRequest): SystemResult {
        val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            val cameraId = camManager.cameraIdList[0] // Assume first camera has flash
            // Check if user wants toggle vs specific state
            val desiredState = when (req.action.lowercase()) {
                 "on" -> true
                 "off" -> false
                 "toggle" -> {
                     // We can't easily get current torch state without a callback listener.
                     // But for toggle, we might need to track state or users should explicitly use on/off.
                     // Let's assume 'toggle' is tricky without state.
                     // Actually, we can try to guess or just return error that 'toggle' needs state tracking.
                     // Or, we can just say 'on'/'off' are supported. 
                     // Let's implement ON/OFF first.
                     return SystemResult(false, "Toggle not supported directly. Use 'on' or 'off'.")
                 }
                 else -> return SystemResult(false, "Unknown action")
            }
            
            camManager.setTorchMode(cameraId, desiredState)
            SystemResult(true, "Flashlight turned ${if (desiredState) "ON" else "OFF"}")
            
        } catch (e: Exception) {
            SystemResult(false, "Flashlight error: ${e.message}")
        }
    }

    private fun handleVolume(req: SystemRequest): SystemResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streamStr = req.params?.get("stream") ?: "music"
        val levelStr = req.params?.get("level") ?: return SystemResult(false, "Missing parameter: level")

        val streamType = when (streamStr.lowercase()) {
            "music" -> AudioManager.STREAM_MUSIC
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }

        return try {
            val maxVol = audioManager.getStreamMaxVolume(streamType)
            val requestedVol = levelStr.toIntOrNull() ?: return SystemResult(false, "Invalid level: $levelStr")
            val finalVol = requestedVol.coerceIn(0, maxVol)
            
            audioManager.setStreamVolume(streamType, finalVol, AudioManager.FLAG_SHOW_UI)
            SystemResult(true, "Volume set to $finalVol/$maxVol for $streamStr")
        } catch (e: Exception) {
            SystemResult(false, "Volume Error: ${e.message}")
        }
    }

    // handleDisplay removed (Split into handleBrightness and handleScreen)

    @Suppress("DEPRECATION")
    private fun handleConnectivity(req: SystemRequest): SystemResult {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        
        return try {
            when (req.action.lowercase()) {
                "wifi_on", "on" -> {
                    if (!wifiManager.isWifiEnabled) {
                        wifiManager.isWifiEnabled = true
                        SystemResult(true, "Wifi enabling...")
                    } else {
                        SystemResult(true, "Wifi already enabled")
                    }
                }
                "wifi_off", "off" -> {
                    if (wifiManager.isWifiEnabled) {
                        wifiManager.isWifiEnabled = false
                        SystemResult(true, "Wifi disabling...")
                    } else {
                        SystemResult(true, "Wifi already disabled")
                    }
                }
                "get_wifi_info", "info", "status" -> {
                    val info = wifiManager.connectionInfo
                    val ssid = info.ssid
                    val rssi = info.rssi
                    val state = if (wifiManager.isWifiEnabled) "Enabled" else "Disabled"
                    SystemResult(true, "State: $state, SSID: $ssid, Signal: $rssi dBm")
                }
                "scan" -> {
                    if (!wifiManager.isWifiEnabled) return SystemResult(false, "Wifi is disabled")
                    
                    // Permission Check for Android 8.1+
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        return SystemResult(false, "Missing Location Permission (Required for Wi-Fi Scan). Grant in App Settings.")
                    }

                    // Latch to wait for results
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var success = false
                    
                    val receiver = object : android.content.BroadcastReceiver() {
                        override fun onReceive(c: Context, intent: Intent) {
                            success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                            latch.countDown()
                        }
                    }

                    try {
                        context.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
                        val started = wifiManager.startScan()
                        
                        if (!started) {
                            // If throttled, don't wait, just return cached if any
                            // But usually, we want to try to wait a bit or just proceed.
                            // If throttled, onReceive might not be called? 
                            // Actually, if startScan returns false, we should probably just read cache immediately.
                        } else {
                            // Wait for up to 10 seconds
                            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Scan Error", e)
                    } finally {
                        try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
                    }
                    
                    // Return results (Fresh or Cached)
                    val results = wifiManager.scanResults
                    if (results.isEmpty()) {
                         SystemResult(true, "Scan completed but no networks found (or Location Disabled).")
                    } else {
                         val visible = results.take(15).joinToString(", ") { "${it.SSID} (${it.level}dBm)" }
                         SystemResult(true, "Networks: $visible")
                    }
                }
                "connect" -> {
                    if (!wifiManager.isWifiEnabled) return SystemResult(false, "Wifi is disabled")
                    
                    val ssid = req.params?.get("ssid") ?: return SystemResult(false, "Missing parameter: ssid")
                    val password = req.params?.get("password")
                    
                    // Legacy Connection Logic (Valid for targetSdk 28)
                    val conf = android.net.wifi.WifiConfiguration()
                    conf.SSID = "\"$ssid\""
                    
                    if (password != null) {
                        conf.preSharedKey = "\"$password\""
                    } else {
                        conf.allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
                    }
                    
                    val netId = wifiManager.addNetwork(conf)
                    if (netId == -1) {
                         return SystemResult(false, "Failed to add network configuration")
                    }
                    
                    val disconnected = wifiManager.disconnect()
                    val enabled = wifiManager.enableNetwork(netId, true)
                    val reconnected = wifiManager.reconnect()
                    
                    if (enabled) {
                         SystemResult(true, "Connecting to $ssid...")
                    } else {
                         SystemResult(false, "Failed to enable network (System restriction?)")
                    }
                }
                else -> SystemResult(false, "Unknown Wifi action: ${req.action}")
            }
        } catch (e: Exception) {
             SystemResult(false, "Wifi Error: ${e.message} (Permission issue?)")
        }
    }

    private fun handleUi(req: SystemRequest): SystemResult {
        val service = N8nAccessibilityService.instance
        if (service == null) {
            return SystemResult(false, "Accessibility Service not Running")
        }

        return when (req.action.lowercase()) {
            "home" -> {
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                SystemResult(true, "Home pressed")
            }
            "back" -> {
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                SystemResult(true, "Back pressed")
            }
            "recents" -> {
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                SystemResult(true, "Recents opened")
            }
            "notifications" -> {
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                SystemResult(true, "Notifications opened")
            }
            "lock" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val success = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                    if (success) {
                        SystemResult(true, "Screen locked")
                    } else {
                        SystemResult(false, "Screen lock command failed (Accessibility Service rejected action)")
                    }
                } else {
                    SystemResult(false, "Lock screen requires Android P+ (Current SDK: ${Build.VERSION.SDK_INT})")
                }
            }
            else -> SystemResult(false, "Unknown UI action")
        }
    }

    private fun handleClipboard(req: SystemRequest): SystemResult {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        
        return when (req.action.lowercase()) {
            "set" -> {
                val text = req.params?.get("text") ?: return SystemResult(false, "Missing parameter: text")
                val clip = ClipData.newPlainText("n8n", text)
                clipboard.setPrimaryClip(clip)
                SystemResult(true, "Text copied to clipboard")
            }
            "get" -> {
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                     val text = clip.getItemAt(0).text.toString()
                     SystemResult(true, text)
                } else {
                     SystemResult(true, "") // Empty
                }
            }
            else -> SystemResult(false, "Unknown Clipboard action")
        }
    }

    private fun handleFeedback(req: SystemRequest): SystemResult {
         return when (req.action.lowercase()) {
            "toast" -> {
                 val text = req.params?.get("text") ?: return SystemResult(false, "Missing parameter: text")
                 // Toast must run on UI thread
                 android.os.Handler(android.os.Looper.getMainLooper()).post {
                     Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                 }
                 SystemResult(true, "Toast shown: $text")
            }
            "speak" -> {
                 // Simple TTS implementation could go here, but omitted for simplicity unless strictly required.
                 // Implementing proper TTS requires initialization listener.
                 // For now, returning not supported or simple implementation if vital.
                 SystemResult(false, "TTS not fully implemented yet in this iteration")
            }
            else -> SystemResult(false, "Unknown Feedback action")
         }
    }

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }
}
