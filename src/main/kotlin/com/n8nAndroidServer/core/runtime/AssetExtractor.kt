package com.n8nAndroidServer.core.runtime

import android.content.Context
import android.util.Log
import com.n8nAndroidServer.core.config.PathResolver
import com.n8nAndroidServer.core.config.ServerConfig
import java.io.File

/**
 * Extracts runtime assets from APK to the filesystem.
 * 
 * This class handles the low-level extraction of the n8n runtime tarball
 * from APK assets to the device's internal storage.
 * 
 * ## Extraction Process
 * 1. Check if asset exists in APK
 * 2. Stream asset to cache directory as temp file
 * 3. Use native `tar` command to extract (preserves symlinks)
 * 4. Validate extraction
 * 5. Clean up temp file
 * 
 * ## Why Native Tar?
 * Android's built-in archive APIs don't preserve symlinks, which are
 * critical for the Node.js runtime. Using `/system/bin/tar` ensures
 * proper symlink handling.
 * 
 * @param context Application context for accessing assets
 * @param paths PathResolver for file locations
 */
class AssetExtractor(
    private val context: Context,
    private val paths: PathResolver
) {
    companion object {
        private const val TAG = "AssetExtractor"
    }
    
    /**
     * Extract runtime assets to the temporary directory.
     * 
     * @return ExtractionResult indicating success or failure details
     */
    fun extractToTemp(): ExtractionResult {
        Log.i(TAG, "Starting asset extraction...")
        
        // 1. Verify asset exists
        val assetList = try {
            context.assets.list("") ?: emptyArray()
        } catch (e: Exception) {
            return ExtractionResult.Error("Failed to list assets: ${e.message}")
        }
        
        Log.d(TAG, "Assets found in APK: ${assetList.joinToString()}")
        
        if (!assetList.contains(ServerConfig.RUNTIME_ASSET_FILENAME)) {
            Log.e(TAG, "CRITICAL: ${ServerConfig.RUNTIME_ASSET_FILENAME} MISSING from APK assets!")
            return ExtractionResult.AssetMissing(ServerConfig.RUNTIME_ASSET_FILENAME)
        }
        
        // 2. Stream asset to cache
        val cacheTar = paths.tempRuntimeTarball
        try {
            Log.i(TAG, "Streaming ${ServerConfig.RUNTIME_ASSET_FILENAME} to cache...")
            context.assets.open(ServerConfig.RUNTIME_ASSET_FILENAME).use { input ->
                cacheTar.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stream asset to cache", e)
            return ExtractionResult.Error("Failed to stream asset: ${e.message}")
        }
        
        // 3. Prepare temp extraction directory
        val tempDir = paths.runtimeTempDir
        if (tempDir.exists()) {
            Log.d(TAG, "Cleaning up previous temp extraction")
            tempDir.deleteRecursively()
        }
        tempDir.mkdirs()
        
        // 4. Extract using native tar
        Log.i(TAG, "Extracting to ${tempDir.absolutePath}...")
        val extractResult = extractTarGz(cacheTar, tempDir)
        
        // 5. Clean up cache file
        try {
            cacheTar.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete temp tarball", e)
        }
        
        // 6. Check extraction result
        if (extractResult != null) {
            Log.e(TAG, "Tar extraction failed: $extractResult")
            tempDir.deleteRecursively()
            return ExtractionResult.ExtractionFailed(extractResult)
        }
        
        Log.i(TAG, "Extraction completed successfully")
        return ExtractionResult.Success(tempDir)
    }
    
    /**
     * Extract a tar.gz file using native tar command.
     * 
     * @param tarball Source tar.gz file
     * @param destDir Destination directory
     * @return Error message if failed, null if successful
     */
    private fun extractTarGz(tarball: File, destDir: File): String? {
        val command = listOf(
            "tar",
            "-xzf", tarball.absolutePath,
            "--no-same-owner",
            "-C", destDir.absolutePath
        )
        
        return try {
            val process = Runtime.getRuntime().exec(command.toTypedArray())
            val exitCode = process.waitFor()
            
            if (exitCode != 0) {
                val error = process.errorStream.bufferedReader().readText()
                "Exit code: $exitCode, Error: $error"
            } else {
                null // Success
            }
        } catch (e: Exception) {
            "Exception: ${e.message}"
        }
    }
}

/**
 * Result of asset extraction.
 */
sealed class ExtractionResult {
    /**
     * Extraction completed successfully.
     * @property extractedDir Directory containing extracted files
     */
    data class Success(val extractedDir: File) : ExtractionResult()
    
    /**
     * The runtime asset is missing from the APK.
     */
    data class AssetMissing(val assetName: String) : ExtractionResult()
    
    /**
     * Tar extraction command failed.
     */
    data class ExtractionFailed(val error: String) : ExtractionResult()
    
    /**
     * Other error occurred.
     */
    data class Error(val message: String) : ExtractionResult()
}
