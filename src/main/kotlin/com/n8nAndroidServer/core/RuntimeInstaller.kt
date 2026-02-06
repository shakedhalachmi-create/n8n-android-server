package com.n8nAndroidServer.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File
import androidx.core.content.pm.PackageInfoCompat

/**
 * Handles extraction and installation of the n8n runtime from APK Assets.
 * Implements atomic updates: Check Version -> Extract to Temp -> Atomic Rename.
 */
object RuntimeInstaller {
    private const val TAG = "RuntimeInstaller"
    private const val PREFS_NAME = "n8n_runtime_prefs"
    private const val KEY_ASSET_VERSION = "last_asset_version_code"
    private const val ASSET_FILENAME = "core_runtime.tar.gz"

    /**
     * Installs the runtime from assets if not present or if app version changed.
     * Thread-safe / Synchronized to prevent parallel extractions.
     */
    @Synchronized
    fun installFromAssets(context: Context): Boolean {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val currentVersionCode = PackageInfoCompat.getLongVersionCode(info)
            val lastVersionCode = prefs.getLong(KEY_ASSET_VERSION, -1)
            
            val runtimeDir = File(context.filesDir, "runtime")
            val nodeBin = File(runtimeDir, "bin/node")
            
            // Check if update implies a re-install
            val needsUpdate = (currentVersionCode > lastVersionCode)
            val isMissing = !runtimeDir.exists() || !nodeBin.exists()
            
            if (!needsUpdate && !isMissing) {
                Log.i(TAG, "Runtime up-to-date (v$lastVersionCode). Skipping extraction.")
                return true
            }
            
            Log.i(TAG, "Runtime update required. Current: $currentVersionCode, Last: $lastVersionCode, Missing: $isMissing")
            
            // 1. Prepare Paths
            val cacheTar = File(context.cacheDir, "temp_runtime.tar.gz")
            val runtimeTmp = File(context.filesDir, "runtime_tmp")
            
            // Cleanup previous attempts
            if (runtimeTmp.exists()) {
                runtimeTmp.deleteRecursively()
            }
            runtimeTmp.mkdirs()
            
            // 2. Stream Asset to Cache (Native tar needs a file path)
            Log.i(TAG, "Streaming $ASSET_FILENAME to cache...")
            context.assets.open(ASSET_FILENAME).use { input ->
                cacheTar.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            // 3. Native Tar Extraction (Preserves Symlinks)
            Log.i(TAG, "Extracting to ${runtimeTmp.absolutePath}...")
            val tarCmd = listOf(
                "tar",
                "-xzf", cacheTar.absolutePath,
                "--no-same-owner",
                "-C", runtimeTmp.absolutePath
            )
            
            val process = Runtime.getRuntime().exec(tarCmd.toTypedArray())
            val exitCode = process.waitFor()
            
            // Clean cache file immediately to save space
            cacheTar.delete()
            
            if (exitCode != 0) {
                val error = process.errorStream.bufferedReader().readText()
                Log.e(TAG, "Tar extraction failed! Code: $exitCode, Error: $error")
                runtimeTmp.deleteRecursively()
                return false
            }
            
            // 4. Validation
            val tmpNode = File(runtimeTmp, "bin/node")
            if (!tmpNode.exists()) {
                Log.e(TAG, "Extraction validation failed: bin/node missing!")
                runtimeTmp.deleteRecursively()
                return false
            }
            
            // 5. Atomic Rename (The Swap)
            if (runtimeDir.exists()) {
                Log.i(TAG, "Removing old runtime...")
                runtimeDir.deleteRecursively()
            }
            
            if (runtimeTmp.renameTo(runtimeDir)) {
                Log.i(TAG, "Atomic rename successful.")
            } else {
                Log.e(TAG, "Atomic rename failed! Attempting manual move...")
                // Fallback if renaming across mount points fails (unlikely inside filesDir)
                // But just in case:
                // runtimeTmp.copyRecursively(runtimeDir, true)
                // runtimeTmp.deleteRecursively()
                return false
            }
            
            // 6. Permissions (W^X Check)
            setExecutablePermissions(runtimeDir)
            
            // 7. Update State
            prefs.edit().putLong(KEY_ASSET_VERSION, currentVersionCode).apply()
            Log.i(TAG, "Runtime v$currentVersionCode installed successfully.")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Critical installation failure", e)
            return false
        }
    }
    
    private fun setExecutablePermissions(runtimeDir: File) {
        try {
            // Bulk chmod
            Runtime.getRuntime().exec("chmod -R 755 ${runtimeDir.absolutePath}").waitFor()
            
            // Explicit +x for binaries
            val binDir = File(runtimeDir, "bin")
            File(binDir, "node").setExecutable(true, false)
            File(binDir, "n8n-start.sh").setExecutable(true, false)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set explicit permissions: ${e.message}")
        }
    }
    
    fun getRuntimePath(context: Context): File {
        return File(context.filesDir, "runtime")
    }
    
    fun isRuntimeAvailable(context: Context): Boolean {
         val node = File(getRuntimePath(context), "bin/node")
         return node.exists() && node.canExecute()
    }
}

