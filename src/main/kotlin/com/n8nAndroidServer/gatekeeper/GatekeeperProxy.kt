package com.n8nAndroidServer.gatekeeper

import android.content.Context
import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*

/**
 * Gatekeeper Proxy - Reverse proxy from 0.0.0.0:5678 to 127.0.0.1:5681.
 * 
 * Key Features:
 * - IP-based access control via WhitelistDatabase
 * - Comprehensive request logging (remote IP, path, decision)
 * - WebSocket tunneling support
 * - Header injection (X-Forwarded-For, X-Forwarded-Proto)
 * 
 * Security Model:
 * 1. Unknown IP → 403 + Add to PENDING
 * 2. BLOCKED IP → 403
 * 3. ALLOWED IP → Proxy to backend
 * 4. PENDING IP → 403 (waiting for manual approval)
 */
class GatekeeperProxy(private val context: Context) {
    
    companion object {
        private const val TAG = "GatekeeperProxy"
        private const val BACKEND_HOST = "127.0.0.1"
        private const val BACKEND_PORT = 5681
    }
    
    private val httpClient = HttpClient(CIO) {
        install(io.ktor.client.plugins.websocket.WebSockets)
        
        engine {
            // Disable native transport to avoid io.netty.channel.epoll errors on Android
            // Force Java NIO instead of native Netty epoll
            threadsCount = 4
        }
    }
    
    fun configureRouting(application: Application) {
        application.routing {
            // WebSocket support
            webSocket("{...}") {
                handleWebSocket(this)
            }
            
            // HTTP/HTTPS proxy (catch-all)
            route("{...}") {
                handle {
                    handleHttpRequest(call)
                }
            }
        }
    }
    
    private suspend fun handleHttpRequest(call: ApplicationCall) {
        val remoteIp = getClientIp(call)
        val requestPath = call.request.uri
        val method = call.request.httpMethod.value
        
        // COMPREHENSIVE LOGGING (per debug spec)
        Log.d(TAG, "📥 Incoming Request: $method $requestPath from $remoteIp")
        
        // Check whitelist
        val status = WhitelistDatabase.checkIp(remoteIp)
        
        when (status) {
            null -> {
                // Unknown IP - add to pending and reject
                Log.w(TAG, "🚫 UNKNOWN IP: $remoteIp → Adding to PENDING & returning 403")
                WhitelistDatabase.addPending(remoteIp)
                call.respondText(
                    "Access Denied. Your IP ($remoteIp) has been added to the pending approval list.",
                    status = HttpStatusCode.Forbidden
                )
            }
            
            WhitelistDatabase.Status.BLOCKED -> {
                Log.w(TAG, "🚫 BLOCKED IP: $remoteIp → Returning 403")
                call.respondText(
                    "Access Denied. Your IP is blocked.",
                    status = HttpStatusCode.Forbidden
                )
            }
            
            WhitelistDatabase.Status.PENDING -> {
                Log.w(TAG, "⏳ PENDING IP: $remoteIp → Returning 403 (awaiting approval)")
                call.respondText(
                    "Access Pending. Your IP ($remoteIp) is awaiting approval.",
                    status = HttpStatusCode.Forbidden
                )
            }
            
            WhitelistDatabase.Status.ALLOWED -> {
                Log.i(TAG, "✅ ALLOWED IP: $remoteIp → Forwarding to backend")
                proxyRequest(call, remoteIp)
            }
        }
    }
    
    private suspend fun proxyRequest(call: ApplicationCall, remoteIp: String) {
        try {
            val requestPath = call.request.uri
            val backendUrl = "http://$BACKEND_HOST:$BACKEND_PORT$requestPath"
            
            Log.d(TAG, "🔀 Proxying ${call.request.httpMethod.value} to $backendUrl")
            
            // Forward request to backend
            val response: HttpResponse = httpClient.request(backendUrl) {
                method = call.request.httpMethod
                
                // Copy headers (Case-insensitive filtering)
                call.request.headers.forEach { name, values ->
                    if (name.equals("Host", ignoreCase = true) || 
                        name.equals("Connection", ignoreCase = true) || 
                        name.equals("Origin", ignoreCase = true)) {
                        return@forEach
                    }
                    values.forEach { value ->
                        header(name, value)
                    }
                }
                
                // Inject forwarding headers (Spoofed to Localhost)
                header("X-Forwarded-For", remoteIp) 
                header("X-Forwarded-Proto", "http") // Force http
                header("X-Forwarded-Host", "$BACKEND_HOST:$BACKEND_PORT") // Spoof Host
                header("Host", "$BACKEND_HOST:$BACKEND_PORT")
                
                // Rewrite Origin
                val newOrigin = "http://$BACKEND_HOST:$BACKEND_PORT"
                Log.d(TAG, "🔄 Rewriting Origin: ${call.request.headers["Origin"]} -> $newOrigin")
                header("Origin", newOrigin)
                
                // Copy body if present
                val requestBody = call.receiveNullable<ByteArray>()
                requestBody?.let {
                    setBody(it)
                }
            }
            
            // Forward response back to client
            Log.d(TAG, "📤 Backend response: ${response.status.value} for $requestPath")
            
            call.response.status(response.status)
            
            // Copy response headers
            response.headers.forEach { name, values ->
                if (name !in listOf("Transfer-Encoding", "Connection")) {
                    values.forEach { value ->
                        call.response.header(name, value)
                    }
                }
            }
            
            // Stream response body
            call.respondBytes(response.readBytes())
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Proxy error for $remoteIp: ${e.message}", e)
            call.respondText(
                "Backend Error: ${e.message}",
                status = HttpStatusCode.BadGateway
            )
        }
    }
    
    private suspend fun handleWebSocket(session: WebSocketServerSession) {
        val call = session.call
        val remoteIp = getClientIp(call)
        val requestPath = call.request.uri
        
        Log.d(TAG, "🔌 WebSocket Request: $requestPath from $remoteIp")
        
        // Check whitelist
        val status = WhitelistDatabase.checkIp(remoteIp)
        
        if (status != WhitelistDatabase.Status.ALLOWED) {
            Log.w(TAG, "🚫 WebSocket DENIED for $remoteIp (status: $status)")
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "WebSocket Access Denied"))
            return
        }
        
        Log.i(TAG, "✅ WebSocket ALLOWED for $remoteIp → Tunneling to backend")
        
        try {
            val backendUrl = "ws://$BACKEND_HOST:$BACKEND_PORT$requestPath"
            
            // Connect to Backend using the client
            httpClient.webSocket(backendUrl, request = {
                // Copy headers from client request
                val clientHeaders = session.call.request.headers
                clientHeaders.names().forEach { name ->
                    val ignored = listOf(
                        "Host", "Connection", "Upgrade", 
                        "Sec-WebSocket-Key", "Sec-WebSocket-Version", 
                        "Sec-WebSocket-Extensions", "Sec-WebSocket-Accept", 
                        "Origin"
                    )
                    
                    if (ignored.none { it.equals(name, ignoreCase = true) }) {
                        clientHeaders.getAll(name)?.forEach { value ->
                            header(name, value)
                        }
                    }
                }
                
                // Add Forwarding Headers (Spoofed)
                header("X-Forwarded-For", remoteIp)
                header("X-Forwarded-Proto", "http")
                header("X-Forwarded-Host", "$BACKEND_HOST:$BACKEND_PORT")
                header("Host", "$BACKEND_HOST:$BACKEND_PORT")
                
                // Rewrite Origin
                val newOrigin = "http://$BACKEND_HOST:$BACKEND_PORT"
                Log.d(TAG, "🔄 WS Origin Rewrite: $newOrigin")
                header("Origin", newOrigin)
            }) {
                val backendSession = this
                Log.i(TAG, "🔌 Tunnel Established: Client <-> Backend ($requestPath)")
                
                // Proxy: Client -> Backend
                val clientToBackend = launch {
                    try {
                        for (frame in session.incoming) {
                            // Forward the frame directly
                            backendSession.send(frame)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error C -> B", e)
                    }
                }
                
                // Proxy: Backend -> Client
                val backendToClient = launch {
                    try {
                        for (frame in backendSession.incoming) {
                            session.send(frame)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error B -> C", e)
                    }
                }
                
                // Wait for either execution to finish/error
                try {
                    // We join both? Or race?
                    // Typically if one closes, we close the other.
                    // For now, simpler: just wait.
                    joinAll(clientToBackend, backendToClient)
                } finally {
                     clientToBackend.cancel()
                     backendToClient.cancel()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "WebSocket Tunnel Failed", e)
            session.close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Backend Failure"))
        }
    }
    
    private fun getClientIp(call: ApplicationCall): String {
        // Try X-Forwarded-For first (if behind another proxy)
        val forwardedFor = call.request.headers["X-Forwarded-For"]
        if (forwardedFor != null) {
            return forwardedFor.split(",").first().trim()
        }
        
        // Fall back to direct connection IP
        return call.request.local.remoteHost
    }
}
