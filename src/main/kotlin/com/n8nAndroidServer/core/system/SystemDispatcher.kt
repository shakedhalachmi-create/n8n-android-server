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
                else -> SystemResult(false, "Unknown category: ${req.category}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Execution failed", e)
            SystemResult(false, "Error: ${e.message}")
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
            "enable" -> {
                 if (adapter.enable()) SystemResult(true, "Bluetooth enabling...") else SystemResult(false, "Failed to enable Bluetooth (System restriction?)")
            }
            "connect" -> {
                if (!adapter.isEnabled) return SystemResult(false, "Bluetooth is disabled")
                connectToTarget(adapter, req.params)
            }
            "disable" -> {
                 if (adapter.isEnabled && adapter.disable()) SystemResult(true, "Bluetooth disabling...") else SystemResult(false, "Failed to disable Bluetooth (System restriction or already disabled?)")
            }
            "scan" -> {
                if (!adapter.isEnabled) return SystemResult(false, "Bluetooth is disabled")
                SystemResult(false, "Scan not implemented yet (Targeting specific device only)")
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
            val connectMethod = proxy.javaClass.getDeclaredMethod("connect", BluetoothDevice::class.java)
            connectMethod.isAccessible = true
            val success = connectMethod.invoke(proxy, device) as Boolean
            Log.i(TAG, "Reflection connect() result: $success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to invoke connect via reflection", e)
            false
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
