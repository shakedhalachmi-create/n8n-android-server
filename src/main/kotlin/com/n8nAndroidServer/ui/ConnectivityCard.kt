package com.n8nAndroidServer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/**
 * Connectivity Card - Display local IP and pending whitelist approvals.
 */
@Composable
fun ConnectivityCard(viewModel: DashboardViewModel) {
    val ipAddress by viewModel.ipAddress.collectAsState()
    val pendingRequests by viewModel.pendingRequests.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "CONNECTIVITY",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Local IP Display
            SelectionContainer {
                Text(
                    text = "http://$ipAddress:5678",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            Button(
                onClick = {
                    clipboardManager.setText(AnnotatedString("http://$ipAddress:5678"))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Copy Address")
            }
            
            // Command Bridge Status
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("⚡ Command Bridge:", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.width(8.dp))
                Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                    Text("active @ 5680", modifier = Modifier.padding(4.dp))
                }
            }
            
            // Pending Approvals List
            if (pendingRequests.isNotEmpty()) {
                Divider( modifier = Modifier.padding(vertical = 8.dp))
                
                Text(
                    text = "Pending Approvals (${pendingRequests.size})",
                    style = MaterialTheme.typography.titleMedium
                )
                
                pendingRequests.forEach { entry ->
                    PendingApprovalItem(
                        ip = entry.ip,
                        onApprove = { viewModel.approveIp(entry.ip) },
                        onReject = { viewModel.blockIp(entry.ip) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingApprovalItem(
    ip: String,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = ip,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Approve")
                }
                
                OutlinedButton(onClick = onReject) {
                    Text("Reject")
                }
            }
        }
    }
}
