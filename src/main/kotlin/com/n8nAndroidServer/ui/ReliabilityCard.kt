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
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.graphics.Color

/**
 * Reliability Card - Battery optimization status and phantom process fix.
 */
@Composable
fun ReliabilityCard(
    viewModel: DashboardViewModel,
    onRequestBluetoothPermission: () -> Unit = {},
    onRequestLocationPermission: () -> Unit = {}
) {
    val isBatteryOptimized by viewModel.isBatteryOptimized.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    
    // Check Developer Mode status (Reactive)
    val isDevModeEnabled by viewModel.isDevModeEnabled.collectAsState()
    // Check App Developer Mode (Smart Update)
    val isAppDevMode by viewModel.isSmartUpdateEnabled.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "RELIABILITY & PERMISSIONS",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 1. Battery Optimization
            PermissionRequirementCard(
                title = "Battery Optimization",
                isGranted = isBatteryOptimized,
                iconGranted = "✅",
                iconMissing = "⚠️",
                description = "Prevents Android from killing the server during sleep.",
                commands = listOf("Run in background", "24/7 Availability"),
                onGrant = { viewModel.requestBatteryOptimization() },
                grantText = "Disable Optimization"
            )
            
            HorizontalDivider()

            // 2. Bluetooth Permission
            val hasBluetoothPermission by viewModel.hasBluetoothPermission.collectAsState()
            PermissionRequirementCard(
                title = "Bluetooth Access",
                isGranted = hasBluetoothPermission,
                iconGranted = "✅",
                iconMissing = "🔐",
                description = "Required to scan and connect to Bluetooth devices.",
                commands = listOf("bluetooth.scan", "bluetooth.connect"),
                onGrant = onRequestBluetoothPermission,
                grantText = "Grant Bluetooth"
            )

            HorizontalDivider()
            
            // 2.5 Location Permission (Wi-Fi Scan)
            val hasLocationPermission by viewModel.hasLocationPermission.collectAsState()
            PermissionRequirementCard(
                title = "Location Access",
                isGranted = hasLocationPermission,
                iconGranted = "✅",
                iconMissing = "📍",
                description = "Required for Wi-Fi Scanning (Android Requirement).",
                commands = listOf("wifi.scan", "connectivity.scan"),
                onGrant = onRequestLocationPermission,
                grantText = "Grant Location"
            )

            HorizontalDivider()
            
            // 3. Overlay Permission
            val hasOverlayPermission by viewModel.hasOverlayPermission.collectAsState()
            PermissionRequirementCard(
                title = "Display Over Apps",
                isGranted = hasOverlayPermission,
                iconGranted = "✅",
                iconMissing = "⚠️",
                description = "Allows starting activities from the background.",
                commands = listOf("app.launch", "reliability"),
                onGrant = { viewModel.requestOverlayPermission() },
                grantText = "Grant Overlay"
            )

            HorizontalDivider()

             // 4. Accessibility Service
            val isAccessibilityEnabled by viewModel.isAccessibilityEnabled.collectAsState()
            PermissionRequirementCard(
                title = "Accessibility Service",
                isGranted = isAccessibilityEnabled,
                iconGranted = "✅",
                iconMissing = "🛠️",
                description = "Enables UI automation and global actions.",
                commands = listOf("ui.home", "ui.back", "ui.recents", "ui.notifications"),
                onGrant = { viewModel.openAccessibilitySettings() },
                grantText = "Enable Service"
            )

            HorizontalDivider()
            
            // 5. Write Settings
            val hasWriteSettings = remember(context) { Settings.System.canWrite(context) }
            PermissionRequirementCard(
                title = "Modify System Settings",
                isGranted = hasWriteSettings,
                iconGranted = "✅",
                iconMissing = "⚙️",
                description = "Required to change system display settings.",
                commands = listOf("display.set_brightness", "display.auto_brightness"),
                onGrant = {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                       data = Uri.parse("package:${context.packageName}")
                       addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                },
                grantText = "Grant Write Settings"
            )

            // 6. Phantom Process Fix (ADB) - Only if BOTH Developer Modes are ON
            if (isDevModeEnabled && isAppDevMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                HorizontalDivider()
                
                Text(
                    text = "ADVANCED: PHANTOM PROCESS FIX",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
                
                Text(
                    text = "Android 12+ may kill child processes (like node) consuming too much CPU. Run this via ADB to disable it.",
                    style = MaterialTheme.typography.bodySmall
                )
                
                val adbCommand = "adb shell device_config put activity_manager max_phantom_processes 2147483647"
                
                SelectionContainer {
                    Text(
                        text = adbCommand,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    )
                }
                
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(adbCommand))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text("Copy ADB Command")
                }
            }
        }
    }
}

@Composable
fun PermissionRequirementCard(
    title: String,
    isGranted: Boolean,
    iconGranted: String,
    iconMissing: String,
    description: String,
    commands: List<String>,
    onGrant: () -> Unit,
    grantText: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Icon Column
        Text(
            text = if (isGranted) iconGranted else iconMissing,
            style = MaterialTheme.typography.headlineMedium
        )
        
        // Content Column
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Header
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isGranted) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.error
            )
            
            // Description
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            // Enabled Features / Commands
            if (commands.isNotEmpty()) {
                Text(
                    text = "Enables: ${commands.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            
            // Grant Button (Only if not granted)
            if (!isGranted) {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onGrant,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(grantText)
                }
            } else {
                 Text(
                    text = "Active & Ready",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                 )
            }
        }
    }
}
