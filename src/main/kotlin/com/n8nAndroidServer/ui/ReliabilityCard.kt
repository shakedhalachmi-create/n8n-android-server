package com.n8nAndroidServer.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Reliability Card - Battery optimization status and phantom process fix.
 */
@Composable
fun ReliabilityCard(
    viewModel: DashboardViewModel,
    onRequestBluetoothPermission: () -> Unit = {}
) {
    val isBatteryOptimized by viewModel.isBatteryOptimized.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isBatteryOptimized) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "RELIABILITY WIZARD",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Bluetooth Permission Check
            val hasBluetoothPermission by viewModel.hasBluetoothPermission.collectAsState()
            
            if (!hasBluetoothPermission) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "🔐",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Column {
                        Text(
                            text = "Permission Missing",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "App needs Bluetooth Connect/Scan to control stereo.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                
                Button(
                    onClick = onRequestBluetoothPermission,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Bluetooth Permission")
                }
                
                Divider(modifier = Modifier.padding(vertical = 8.dp))
            }
            
            // Battery Optimization Status
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (isBatteryOptimized) "✅" else "⚠️",
                    style = MaterialTheme.typography.headlineMedium
                )
                Column {
                    Text(
                        text = if (isBatteryOptimized) {
                            "High Reliability (Battery Opt Disabled)"
                        } else {
                            "Warning: Battery Optimization Enabled"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isBatteryOptimized) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    
                    if (!isBatteryOptimized) {
                        Text(
                            text = "Server may be killed during sleep",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            
            // Battery Optimization Button
            if (!isBatteryOptimized) {
                Button(
                    onClick = { viewModel.requestBatteryOptimization() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Disable Battery Optimization")
                }
            }
            
            // Overlay Permission Check
            val hasOverlayPermission by viewModel.hasOverlayPermission.collectAsState()
            
            if (!hasOverlayPermission) {
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "⚠️",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Column {
                        Text(
                            text = "Warning: Background Starts Restricted",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "App needs 'Display over other apps' to start Execute Command reliably from background.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                
                Button(
                    onClick = { viewModel.requestOverlayPermission() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Overlay Permission")
                }
            }
            
            // Phantom Process Fix (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                
                Text(
                    text = "PHANTOM PROCESS KILLER FIX (ADB)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
                
                val adbCommand = "adb shell device_config put activity_manager max_phantom_processes 2147483647"
                
                SelectionContainer {
                    Text(
                        text = adbCommand,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(adbCommand))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Copy ADB Command")
                }
                
                Text(
                    text = "Run this on your computer while phone is connected via USB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
             // Accessibility Service Check
            val isAccessibilityEnabled by viewModel.isAccessibilityEnabled.collectAsState()
            
            if (!isAccessibilityEnabled) {
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "🛠️",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Column {
                        Text(
                            text = "Native Control Disabled",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Enable n8n android server Accessibility Service to allow taps and gestures.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                
                Button(
                    onClick = { viewModel.openAccessibilitySettings() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Enable Native Control")
                }
            }
        }
    }
}
