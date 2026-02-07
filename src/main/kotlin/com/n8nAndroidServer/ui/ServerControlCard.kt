package com.n8nAndroidServer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.n8nAndroidServer.core.LegacyServerState as ServerState

import androidx.compose.foundation.clickable

/**
 * Server Control Card - Smart state machine UI for server lifecycle.
 * Updated for Smart Update states (VERIFYING_CACHE, INSTALLING).
 */
@Composable
fun ServerControlCard(viewModel: DashboardViewModel) {
    val serverState by viewModel.serverState.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val isSmartUpdateEnabled by viewModel.isSmartUpdateEnabled.collectAsState()
    var titleClickCount by remember { mutableStateOf(0) }
    
    // Determine container color
    val containerColor = when (serverState) {
        ServerState.FATAL_ERROR -> MaterialTheme.colorScheme.errorContainer
        ServerState.RUNNING -> MaterialTheme.colorScheme.primaryContainer
        ServerState.VERIFYING_CACHE, ServerState.INSTALLING, ServerState.DOWNLOADING -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (isSmartUpdateEnabled) "N8N SERVER CONTROL (DEV)" else "N8N SERVER CONTROL",
                style = MaterialTheme.typography.labelLarge,
                color = if (isSmartUpdateEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable {
                     titleClickCount++
                     if (titleClickCount >= 5) {
                         viewModel.toggleSmartUpdate()
                         titleClickCount = 0
                     }
                }
            )
            
            // Status Indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusIndicator(state = serverState)
                Text(
                    text = getStatusText(serverState),
                    style = MaterialTheme.typography.headlineMedium
                )
            }
            
            // Download / Install Progress
            // Show if downloading OR installing
            val showProgress = (downloadProgress > 0f && downloadProgress < 1f) || 
                               serverState == ServerState.INSTALLING ||
                               serverState == ServerState.VERIFYING_CACHE ||
                               serverState == ServerState.DOWNLOADING
            
            AnimatedVisibility(visible = showProgress) {
                Column {
                    if (serverState == ServerState.VERIFYING_CACHE) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) // Indeterminate
                    } else if (serverState == ServerState.DOWNLOADING) {
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                         // INSTALLING or others
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) // Indeterminate for extract
                    }
                    
                    val progressText = when(serverState) {
                        ServerState.VERIFYING_CACHE -> "Checking Local Cache..."
                        ServerState.DOWNLOADING -> "Downloading Runtime: ${(downloadProgress * 100).toInt()}%"
                        ServerState.INSTALLING -> "Extracting & Installing..."
                        else -> "Processing..."
                    }
                    
                    Text(
                        text = progressText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            
            // Action Button
            Button(
                onClick = { handleButtonClick(viewModel, serverState) },
                enabled = isButtonEnabled(serverState),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = when (serverState) {
                        ServerState.RUNNING -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
            ) {
                if (isBusyState(serverState)) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                
                Text(
                    text = getButtonText(serverState),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            
            // Error Message
            if (serverState == ServerState.FATAL_ERROR) {
                Text(
                    text = "A fatal error occurred. Check logs for details.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun StatusIndicator(state: ServerState) {
    val color = when (state) {
        ServerState.RUNNING -> MaterialTheme.colorScheme.primary
        ServerState.FATAL_ERROR -> MaterialTheme.colorScheme.error
        ServerState.STARTING, ServerState.STOPPING, ServerState.RETRYING, 
        ServerState.VERIFYING_CACHE, ServerState.INSTALLING, ServerState.DOWNLOADING -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    Box(
        modifier = Modifier
            .size(12.dp)
            .padding(2.dp)
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = color,
            modifier = Modifier.fillMaxSize()
        ) {}
    }
}

private fun getStatusText(state: ServerState): String {
    return when (state) {
        ServerState.STOPPED -> "Stopped"
        ServerState.NOT_INSTALLED -> "Not Installed"
        ServerState.STARTING -> "Starting..."
        ServerState.RUNNING -> "Running" // Wait for port open
        ServerState.STOPPING -> "Stopping..."
        ServerState.RETRYING -> "Retrying..."
        ServerState.FATAL_ERROR -> "Fatal Error"
        ServerState.VERIFYING_CACHE -> "Checking Cache"
        ServerState.DOWNLOADING -> "Downloading..."
        ServerState.INSTALLING -> "Installing..."
    }
}

private fun getButtonText(state: ServerState): String {
    return when (state) {
        ServerState.STOPPED, ServerState.NOT_INSTALLED -> "START SERVER"
        ServerState.STARTING -> "STARTING..."
        ServerState.RUNNING -> "STOP SERVER"
        ServerState.STOPPING -> "STOPPING..."
        ServerState.RETRYING -> "RETRYING..."
        ServerState.FATAL_ERROR -> "RETRY"
        ServerState.VERIFYING_CACHE -> "CHECKING..."
        ServerState.DOWNLOADING -> "DOWNLOADING..."
        ServerState.INSTALLING -> "INSTALLING..."
    }
}

private fun isButtonEnabled(state: ServerState): Boolean {
    return state !in listOf(
        ServerState.STARTING,
        ServerState.STOPPING,
        ServerState.RETRYING,
        ServerState.VERIFYING_CACHE,
        ServerState.DOWNLOADING,
        ServerState.INSTALLING
    )
}

private fun isBusyState(state: ServerState): Boolean {
    return state in listOf(
        ServerState.STARTING,
        ServerState.STOPPING,
        ServerState.VERIFYING_CACHE,
        ServerState.DOWNLOADING,
        ServerState.INSTALLING
    )
}

private fun handleButtonClick(viewModel: DashboardViewModel, state: ServerState) {
    when (state) {
        ServerState.NOT_INSTALLED -> viewModel.toggleServer() // startServer handles logic
        ServerState.RUNNING -> viewModel.toggleServer()
        ServerState.STOPPED, ServerState.FATAL_ERROR -> viewModel.toggleServer()
        else -> { /* Do nothing for transitioning states */ }
    }
}
