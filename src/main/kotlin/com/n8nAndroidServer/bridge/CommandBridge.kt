package com.n8nAndroidServer.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import androidx.core.content.ContextCompat.startActivity
import org.json.JSONObject
import com.n8nAndroidServer.core.system.SystemDispatcher
import com.n8nAndroidServer.core.system.SystemRequest
import java.nio.charset.Charset

/**
 * Command Bridge - Local HTTP Endpoint (127.0.0.1:5680)
 * Allows n8n (running locally) to trigger Android Intents safely.
 */
object CommandBridge {
    private const val TAG = "CommandBridge"
    private const val PORT = 5680
    private const val HOST = "127.0.0.1"
    
    private var server: ApplicationEngine? = null
    private var systemDispatcher: SystemDispatcher? = null
    
    fun start(context: Context) {
        if (server != null) return
        
        systemDispatcher = SystemDispatcher(context)
        Log.i(TAG, "Starting Command Bridge on $HOST:$PORT")
        
        try {
            server = embeddedServer(CIO, port = PORT, host = HOST) {
                routing {
                    post("/api/dispatch") {
                        handleDispatch(call, context)
                    }
                    post("/api/v1/system/execute") {
                        handleSystemExecute(call)
                    }
                    get("/status") {
                        call.respondText("Command Bridge Active", status = HttpStatusCode.OK)
                    }
                }
            }
            server?.start(wait = false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Command Bridge", e)
        }
    }
    
    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }
    
    fun isRunning(): Boolean = server != null
    
    private suspend fun handleDispatch(call: ApplicationCall, context: Context) {
        try {
            val bodyText = call.receiveText()
            Log.d(TAG, "Received Dispatch Request: $bodyText")
            
            val json = JSONObject(bodyText)
            
            // Branch 1: Specific Command (vibrate, etc.)
            val command = json.optString("command")
            if (command.isNotEmpty()) {
                handleCommand(command, json, call, context)
                return
            }

            // Branch 2: Generic Android Intent
            handleIntentRequest(json, call, context)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in dispatcher", e)
            call.respondText("Error: ${e.message}", status = HttpStatusCode.InternalServerError)
        }
    }

    private suspend fun handleCommand(command: String, json: JSONObject, call: ApplicationCall, context: Context) {
        when (command.lowercase()) {
            "vibrate" -> {
                val duration = json.optLong("duration", 500L)
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(duration, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    vibrator.vibrate(duration)
                }
                call.respondText("Vibrated for $duration ms", status = HttpStatusCode.OK)
            }
            "tap" -> {
                val xRaw = json.opt("x")
                val yRaw = json.opt("y")
                
                val displayMetrics = context.resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels

                fun resolveCoordinate(raw: Any?, max: Int): Float {
                    return when (raw) {
                        is String -> {
                            if (raw.endsWith("%")) {
                                val pct = raw.removeSuffix("%").toDoubleOrNull() ?: 0.0
                                (pct / 100.0 * max).toFloat()
                            } else {
                                raw.toFloatOrNull() ?: 0f
                            }
                        }
                        is Number -> raw.toFloat()
                        else -> 0f
                    }
                }

                val x = resolveCoordinate(xRaw, screenWidth)
                val y = resolveCoordinate(yRaw, screenHeight)

                val service = com.n8nAndroidServer.services.N8nAccessibilityService.instance
                if (service != null) {
                    val success = service.performTap(x, y)
                    if (success) {
                        call.respondText("Tap initiated at $x, $y (Screen: ${screenWidth}x${screenHeight})", status = HttpStatusCode.OK)
                    } else {
                        call.respondText("Tap failed (gesture dispatch error)", status = HttpStatusCode.InternalServerError)
                    }
                } else {
                    call.respondText("Accessibility Service not enabled", status = HttpStatusCode.ServiceUnavailable)
                }
            }
            else -> {
                call.respondText("Unknown command: $command", status = HttpStatusCode.BadRequest)
            }
        }
    }

    private suspend fun handleIntentRequest(json: JSONObject, call: ApplicationCall, context: Context) {
        val action = json.optString("action")
        val data = json.optString("data")
        val type = json.optString("type")
        val pkg = json.optString("package")
        val cls = json.optString("class")
        val extras = json.optJSONObject("extras")
        
        if (action.isEmpty() && pkg.isEmpty()) {
            call.respondText("Missing 'action', 'package', or 'command'", status = HttpStatusCode.BadRequest)
            return
        }
        
        val intent = Intent()
        
        if (action.isNotEmpty()) intent.action = action
        if (data.isNotEmpty()) intent.data = Uri.parse(data)
        if (type.isNotEmpty()) intent.type = type
        
        if (pkg.isNotEmpty()) {
           if (cls.isNotEmpty()) {
               intent.setClassName(pkg, cls)
           } else {
               intent.setPackage(pkg)
           }
        }
        
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        
        // Add Extras (Legacy support for top-level keys if extras object is missing)
        // We prioritize the 'extras' object but could enhance this.
        extras?.keys()?.forEach { key ->
            val value = extras.get(key)
            when (value) {
                is String -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is Boolean -> intent.putExtra(key, value)
                is Double -> intent.putExtra(key, value)
                else -> intent.putExtra(key, value.toString())
            }
        }
        
        Log.i(TAG, "Launching Intent: $intent")
        context.startActivity(intent)
        
        call.respondText("Intent Launched", status = HttpStatusCode.OK)
    }
    private suspend fun handleSystemExecute(call: ApplicationCall) {
        try {
            val bodyText = call.receiveText()
            Log.d(TAG, "Received System Request: $bodyText")
            
            val json = JSONObject(bodyText)
            val category = json.optString("category")
            val action = json.optString("action")
            
            // Parse params map
            val params = mutableMapOf<String, String>()
            val paramsJson = json.optJSONObject("params")
            paramsJson?.keys()?.forEach { key ->
                params[key] = paramsJson.optString(key)
            }
            
            val request = SystemRequest(category, action, if (params.isNotEmpty()) params else null)
            
            val dispatcher = systemDispatcher
            if (dispatcher != null) {
                val result = dispatcher.execute(request)
                Log.d(TAG, "System Execute Result: success=${result.success}, message=${result.message}")
                
                val statusCode = if (result.success) {
                    HttpStatusCode.OK
                } else if (result.message.contains("Permission", ignoreCase = true)) {
                    HttpStatusCode.Forbidden
                } else if (result.message.contains("Accessibility", ignoreCase = true)) {
                    HttpStatusCode.ServiceUnavailable
                } else {
                    HttpStatusCode.InternalServerError
                }

                call.respondText(
                    "{\"success\": ${result.success}, \"message\": \"${result.message.replace("\"", "\\\"")}\"}",
                    contentType = ContentType.Application.Json,
                    status = statusCode
                )
            } else {
                Log.e(TAG, "System Dispatcher not initialized")
                call.respondText("{\"success\": false, \"message\": \"System Dispatcher not initialized\"}", status = HttpStatusCode.InternalServerError)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "System Execute Error", e)
            call.respondText("{\"success\": false, \"message\": \"${e.message}\"}", status = HttpStatusCode.InternalServerError)
        }
    }
}
