package com.n8nAndroidServer.core

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RuntimeMetadata(
    val version: String,
    val n8nVersion: String,
    val sha256: String,
    val downloadUrl: String
)

object RuntimeDownloader {
    private const val TAG = "RuntimeDownloader"
    private const val RELEASE_API_URL = "https://api.github.com/repos/shakedhalachmi-create/n8n-android-server/releases/latest" 
    // For V1.6 we might hardcode or fetch from a known endpoint.
    // For V1.6 we might hardcode or fetch from a known endpoint. 
    // The user requirement says "Use the GitHub Releases API".
    
    private val client = OkHttpClient()

    suspend fun getLatestMetadata(): RuntimeMetadata? = withContext(Dispatchers.IO) {
        // First try to get the Release JSON
        val request = Request.Builder().url(RELEASE_API_URL).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val json = JSONObject(body)
                val assets = json.getJSONArray("assets")
                
                var downloadUrl: String? = null
                var metadataUrl: String? = null
                
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    if (name == "n8n-android-arm64.tar.gz") {
                        downloadUrl = asset.getString("browser_download_url")
                    } else if (name == "metadata.json") {
                        metadataUrl = asset.getString("browser_download_url")
                    }
                }

                if (metadataUrl != null && downloadUrl != null) {
                    // Fetch metadata content
                    fetchMetadataContent(metadataUrl, downloadUrl)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch release info", e)
            null
        }
    }
    
    private fun fetchMetadataContent(url: String, fallbackBlobUrl: String): RuntimeMetadata? {
        val request = Request.Builder().url(url).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                RuntimeMetadata(
                    version = json.getString("version"),
                    n8nVersion = json.getString("n8n_version"),
                    sha256 = json.getString("sha256"),
                    downloadUrl = json.optString("download_url", fallbackBlobUrl) 
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch metadata content", e)
            null
        }
    }
    
    suspend fun downloadRuntime(
        url: String, 
        destinationFile: File, 
        expectedSha256: String, 
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val body = response.body ?: return@use false
                val contentLength = body.contentLength()
                
                body.source().use { source ->
                    FileOutputStream(destinationFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        var totalRead = 0L
                        
                        while (source.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (contentLength > 0) {
                                onProgress(totalRead.toFloat() / contentLength.toFloat())
                            }
                        }
                    }
                }
                
                // Verify Checksum
                val actualSha = calculateSha256(destinationFile)
                if (actualSha.equals(expectedSha256, ignoreCase = true)) {
                    Log.i(TAG, "Checksum verified")
                    true
                } else {
                    Log.e(TAG, "Checksum mismatch! Expected $expectedSha256, got $actualSha")
                    destinationFile.delete() // Security: Delete tainted file
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            false
        }
    }
    
    fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
