package com.n8nAndroidServer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.n8nAndroidServer.ui.DashboardScreen

/**
 * Main entry point for the n8n android server application.
 * Hosts the Dashboard UI in a Jetpack Compose container.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            MaterialTheme {
                Surface {
                    DashboardScreen()
                }
            }
        }
    }
}
