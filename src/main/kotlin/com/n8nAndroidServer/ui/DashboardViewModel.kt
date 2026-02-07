package com.n8nAndroidServer.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.n8nAndroidServer.core.ServerManager
import com.n8nAndroidServer.core.LegacyServerState as ServerState
import com.n8nAndroidServer.gatekeeper.WhitelistDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    // Server State
    // Assuming ServerManager is a singleton or accessible via DI. 
    // For this context, we instantiate or retrieve it.
    // Server State
    // Using Singleton Instance
    private val serverManager = ServerManager.getInstance(application)
    val serverState: StateFlow<ServerState> = serverManager.state
    val downloadProgress: StateFlow<Float> = serverManager.downloadProgress

    // Connectivity State
    private val _ipAddress = MutableStateFlow("Determining...")
    val ipAddress: StateFlow<String> = _ipAddress.asStateFlow()

    // Security State (Pending Approvals)
    private val _pendingRequests = MutableStateFlow<List<WhitelistDatabase.Entry>>(emptyList())
    val pendingRequests: StateFlow<List<WhitelistDatabase.Entry>> = _pendingRequests.asStateFlow()

    // Maintenance State
    private val _encryptionKey = MutableStateFlow<String?>(null)
    val encryptionKey: StateFlow<String?> = _encryptionKey.asStateFlow()
    
    // Log State
    private val _logContent = MutableStateFlow("")
    val logContent: StateFlow<String> = _logContent.asStateFlow()
    
    // Reliability State
    private val _isBatteryOptimized = MutableStateFlow(true) // Default to true to avoid initial red flash
    val isBatteryOptimized: StateFlow<Boolean> = _isBatteryOptimized.asStateFlow()

    private var pollJob: Job? = null
    
    // Accessibility Service State
    private val _isAccessibilityEnabled = MutableStateFlow(false)
    val isAccessibilityEnabled: StateFlow<Boolean> = _isAccessibilityEnabled.asStateFlow()

    // Bluetooth Permission State
    private val _hasBluetoothPermission = MutableStateFlow(true)
    val hasBluetoothPermission: StateFlow<Boolean> = _hasBluetoothPermission.asStateFlow()

    // Location Permission State (Required for Wi-Fi Scan)
    private val _hasLocationPermission = MutableStateFlow(true)
    val hasLocationPermission: StateFlow<Boolean> = _hasLocationPermission.asStateFlow()

    // Developer Mode State
    private val _isDevModeEnabled = MutableStateFlow(false)
    val isDevModeEnabled: StateFlow<Boolean> = _isDevModeEnabled.asStateFlow()

    init {
        // Ensure Database Singleton is initialized early
        WhitelistDatabase.getInstance(application)
        
        refreshIp()
        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel() // Fix: Prevent multiple concurrent polling loops
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                // Poll Pending Requests
                val pending = WhitelistDatabase.getAllPending()
                if (_pendingRequests.value != pending) {
                    _pendingRequests.value = pending
                }
                
                // Poll Logs
                readLogs()
                
                // Poll Battery
                checkBatteryOptimization()
                checkOverlayPermission()
                checkAccessibilityService()
                checkBluetoothPermission()
                checkLocationPermission()
                checkDevMode()

                checkDevMode()

                // Adaptive Polling: Poll faster during critical transitions
                val state = serverState.value
                val delayMs = if (state == ServerState.STARTING || state == ServerState.INSTALLING || state == ServerState.STOPPING || state == ServerState.DOWNLOADING || state == ServerState.VERIFYING_CACHE) {
                    1000L 
                } else {
                    5000L
                }
                delay(delayMs)
            }
        }
    }

    // Actions
    fun toggleServer() {
        val currentState = serverState.value
        val context = getApplication<Application>()
        val intent = Intent(context, com.n8nAndroidServer.core.N8nForegroundService::class.java)

        if (currentState == ServerState.RUNNING) {
            intent.action = com.n8nAndroidServer.core.N8nForegroundService.ACTION_STOP
            context.startService(intent) // or startForegroundService if needed, but startService is fine for stop
        } else if (currentState == ServerState.STOPPED || currentState == ServerState.FATAL_ERROR || currentState == ServerState.NOT_INSTALLED) {
            intent.action = com.n8nAndroidServer.core.N8nForegroundService.ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    /**
     * Checks for runtime in cache and installs if available.
     * Called when user taps "Check for Runtime" button.
     */
    fun checkAndInstallRuntime() {
        viewModelScope.launch(Dispatchers.IO) {
            serverManager.installRuntime()
        }
    }

    // Smart Update Debug
    val isSmartUpdateEnabled = serverManager.isSmartUpdateEnabled.asStateFlow()
    
    fun toggleSmartUpdate() {
        val newState = !serverManager.isSmartUpdateEnabled.value
        serverManager.isSmartUpdateEnabled.value = newState
    }

    fun approveIp(ip: String) {
        viewModelScope.launch(Dispatchers.IO) {
            WhitelistDatabase.allowIp(ip)
            // Trigger immediate refresh or wait for poll
            _pendingRequests.value = WhitelistDatabase.getAllPending()
        }
    }

    fun blockIp(ip: String) {
        viewModelScope.launch(Dispatchers.IO) {
            WhitelistDatabase.blockIp(ip)
            _pendingRequests.value = WhitelistDatabase.getAllPending()
        }
    }

    fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${getApplication<Application>().packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            getApplication<Application>().startActivity(intent)
        }
    }
    
    fun toggleEncryptionKeyVisibility() {
        if (_encryptionKey.value == null) {
            // Reveal
           // In a real scenario, we might want to prompt auth. For "dumb view", we just read it.
           // We need to read it from the environment or a secure place.
           // ServerManager creates it. We can peek at EncryptedSharedPreferences or reuse ServerManager if it exposed it.
           // Since ServerManager keeps it internal, let's assume we can read it from the same source `AndroidKeyProvider`.
           // Or simplified: We just "show" a placeholder if we can't access it easily, or modify ServerManager to expose it.
           // Spec says: "Read N8N_ENCRYPTION_KEY from EncryptedSharedPreferences".
           // Let's implement helper to read it.
           _encryptionKey.value = com.n8nAndroidServer.core.AndroidKeyProvider(getApplication()).getKey() ?: "Key Not Found"
        } else {
            // Hide
            _encryptionKey.value = null
        }
    }

    fun clearLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            serverManager.clearUiLog()
            // Immediate read to reflect empty log
            readLogs()
        }
    }

    private fun refreshIp() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isLoopback || !iface.isUp) continue
                    
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr is Inet4Address) {
                            _ipAddress.value = addr.hostAddress ?: "Unknown"
                            return@launch
                        }
                    }
                }
                _ipAddress.value = "No Network"
            } catch (e: Exception) {
                _ipAddress.value = "Error"
            }
        }
    }
    
    private var lastLogOffset = 0L

    private fun readLogs() {
        val logFile = java.io.File(getApplication<Application>().filesDir, "userdata/logs/n8n.log")
        if (logFile.exists()) {
            try {
                val currentLength = logFile.length()

                // If file was truncated or cleared, reset
                if (currentLength < lastLogOffset) {
                    lastLogOffset = 0L
                    _logContent.value = ""
                }

                // Initial read or new data available
                if (currentLength > lastLogOffset) {
                    // On first read, we might want a tail of the last 5KB if offset is 0
                    val start = if (lastLogOffset == 0L && currentLength > 5120L) {
                        currentLength - 5120L
                    } else {
                        lastLogOffset
                    }

                    logFile.inputStream().use { stream ->
                        if (start > 0) stream.skip(start)
                        val bytes = stream.readBytes()
                        val newFragment = String(bytes)
                        
                        // Append and keep only reasonable tail in memory for UI (e.g., 20KB)
                        val combined = (_logContent.value + newFragment).takeLast(20480)
                        _logContent.value = combined
                        lastLogOffset = currentLength
                    }
                }
            } catch (e: Exception) {
                // ignore
            }
        } else {
            if (_logContent.value != "Log file not found.") {
                _logContent.value = "Log file not found."
                lastLogOffset = 0L
            }
        }
    }
    
    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
             val pm = getApplication<Application>().getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
             val pkg = getApplication<Application>().packageName
             _isBatteryOptimized.value = pm.isIgnoringBatteryOptimizations(pkg)
        } else {
             _isBatteryOptimized.value = true // Pre-M is safe
        }
    }
    
    // Overlay Permission (System Alert Window)
    private val _hasOverlayPermission = MutableStateFlow(true)
    val hasOverlayPermission: StateFlow<Boolean> = _hasOverlayPermission.asStateFlow()
    
    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            _hasOverlayPermission.value = Settings.canDrawOverlays(getApplication())
        } else {
            _hasOverlayPermission.value = true
        }
    }
        fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${getApplication<Application>().packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            getApplication<Application>().startActivity(intent)
        }
    }

    private fun checkAccessibilityService() {
        val context = getApplication<Application>()
        val serviceName = context.packageName + "/com.n8nAndroidServer.services.N8nAccessibilityService"
        val accessibilityEnabled = try {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        } catch (e: Settings.SettingNotFoundException) {
            0
        }

        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            _isAccessibilityEnabled.value = settingValue != null && settingValue.contains(serviceName)
        } else {
            _isAccessibilityEnabled.value = false
        }
    }

    private fun checkBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val context = getApplication<Application>()
            val connect = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT)
            val scan = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN)
            _hasBluetoothPermission.value = (connect == android.content.pm.PackageManager.PERMISSION_GRANTED && scan == android.content.pm.PackageManager.PERMISSION_GRANTED)
        } else {
             _hasBluetoothPermission.value = true
        }
    }
    
    fun onBluetoothPermissionResult(granted: Boolean) {
        _hasBluetoothPermission.value = granted
    }
    
    // Check Location Permission
    private fun checkLocationPermission() {
        val context = getApplication<Application>()
        val location = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
        _hasLocationPermission.value = (location == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    fun onLocationPermissionResult(granted: Boolean) {
        _hasLocationPermission.value = granted
    }
    
    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        getApplication<Application>().startActivity(intent)
    }

    private fun checkDevMode() {
        val context = getApplication<Application>()
        val devMode = try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
            )
        } catch (e: Exception) {
            0
        }
        _isDevModeEnabled.value = (devMode == 1)
    }



    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }
}
