package com.n8nAndroidServer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Maintenance Card - Encryption key visibility and log console.
 */
@Composable
fun MaintenanceCard(viewModel: DashboardViewModel) {
    val encryptionKey by viewModel.encryptionKey.collectAsState()
    val logContent by viewModel.logContent.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "MAINTENANCE & DATA",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Encryption Key Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Encryption Key",
                    style = MaterialTheme.typography.titleMedium
                )
                
                TextButton(onClick = { viewModel.toggleEncryptionKeyVisibility() }) {
                    Text(if (encryptionKey == null) "Show" else "Hide")
                }
            }
            
            if (encryptionKey != null) {
                SelectionContainer {
                    Text(
                        text = encryptionKey ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                Button(
                    onClick = {
                        encryptionKey?.let {
                            clipboardManager.setText(AnnotatedString(it))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Copy Key")
                }
            }
            
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            
            // Log Console
            Text(
                text = "Log Console (Last 5KB)",
                style = MaterialTheme.typography.titleMedium
            )
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                ) {
                    item {
                        SelectionContainer {
                            Text(
                                text = logContent,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(logContent))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Copy Logs")
                }
                
                OutlinedButton(
                    onClick = { viewModel.clearLogs() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear Logs")
                }
            }
        }
    }
}
