package com.n8nAndroidServer.gatekeeper

import android.content.Context
import android.util.Log
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.websocket.*
import io.ktor.server.routing.*
import java.time.Duration

/**
 * Ktor module configuration for the Gatekeeper server.
 * Binds to 0.0.0.0:5678 for LAN-wide access.
 * Uses CIO engine for better stability on Android.
 */
object GatekeeperModule {
    private const val TAG = "GatekeeperModule"
    private const val PORT = 5678
    
    private var server: ApplicationEngine? = null
    
    fun start(context: Context) {
        if (server != null) {
            Log.w(TAG, "Gatekeeper already running")
            return
        }
        
        Log.i(TAG, "Starting Gatekeeper on 0.0.0.0:$PORT")
        
        try {
            server = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
                install(WebSockets) {
                    pingPeriod = Duration.ofSeconds(60)
                    timeout = Duration.ofSeconds(15)
                    maxFrameSize = Long.MAX_VALUE
                    masking = false
                }
                
                // Configure proxy routing
                val proxy = GatekeeperProxy(context)
                proxy.configureRouting(this)
            }
            
            server?.start(wait = false)
            Log.i(TAG, "Gatekeeper started successfully on port $PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Gatekeeper", e)
            server = null
        }
    }
    
    fun stop() {
        server?.let {
            Log.i(TAG, "Stopping Gatekeeper")
            try {
                it.stop(1000, 2000)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping Gatekeeper", e)
            }
            server = null
        }
    }
    
    fun isRunning(): Boolean {
        return server != null
    }
}
