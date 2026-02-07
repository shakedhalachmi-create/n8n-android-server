package com.n8nAndroidServer.core.runtime

import android.content.Context
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.n8nAndroidServer.core.config.PathResolver
import com.n8nAndroidServer.core.config.ServerConfig

/**
 * Orchestrates the n8n runtime installation and updates.
 * 
 * This class coordinates the full runtime lifecycle:
 * - Check if update is needed
 * - Extract assets
 * - Validate extraction
 * - Atomic swap (temp → production)
 * - Set permissions
 * - Update version tracking
 * 
 * ## Update Detection
 * The app's version code is tracked in SharedPreferences. When the app
 * is updated, the new version code triggers a re-extraction of assets.
 * 
 * ## Atomic Installation
 * Extraction happens to a temp directory. Only after validation does
 * the temp directory get renamed to the production location. This
 * prevents partial/corrupt installations.
 * 
 * @param context Application context
 * @param paths PathResolver for file locations
 */
class RuntimeOrchestrator(
    private val context: Context,
    private val paths: PathResolver = PathResolver(context)
) {
    companion object {
        private const val TAG = "RuntimeOrchestrator"
    }
    
    private val extractor = AssetExtractor(context, paths)
    private val validator = RuntimeValidator(paths)
    private val prefs = context.getSharedPreferences(ServerConfig.PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * Install or update the runtime if needed.
     * 
     * This is the main entry point for runtime management. It checks
     * if an update is needed and performs the installation atomically.
     * 
     * @return InstallResult indicating success or failure
     */
    @Synchronized
    fun installIfNeeded(): InstallResult {
        val currentVersionCode = getCurrentVersionCode()
        val lastVersionCode = prefs.getLong(ServerConfig.PREF_KEY_ASSET_VERSION, -1)
        
        val needsUpdate = currentVersionCode > lastVersionCode
        val isMissing = !validator.isRuntimeAvailable()
        
        Log.i(TAG, "Install check: current=$currentVersionCode, last=$lastVersionCode, " +
                   "needsUpdate=$needsUpdate, isMissing=$isMissing")
        
        // Already up-to-date?
        if (!needsUpdate && !isMissing) {
            Log.i(TAG, "Runtime up-to-date (v$lastVersionCode). Skipping extraction.")
            return InstallResult.AlreadyUpToDate(lastVersionCode)
        }
        
        Log.i(TAG, "Runtime update required...")
        
        // 1. Extract to temp
        val extractResult = extractor.extractToTemp()
        if (extractResult !is ExtractionResult.Success) {
            return when (extractResult) {
                is ExtractionResult.AssetMissing -> 
                    InstallResult.Failed("Asset missing: ${extractResult.assetName}")
                is ExtractionResult.ExtractionFailed -> 
                    InstallResult.Failed("Extraction failed: ${extractResult.error}")
                is ExtractionResult.Error -> 
                    InstallResult.Failed(extractResult.message)
                is ExtractionResult.Success -> 
                    throw IllegalStateException("Unexpected success in failure branch")
            }
        }
        
        val tempDir = extractResult.extractedDir
        
        // 2. Validate temp extraction
        val validationResult = validator.validate(tempDir)
        if (validationResult is ValidationResult.Invalid) {
            Log.e(TAG, "Validation failed: ${validationResult.issues}")
            tempDir.deleteRecursively()
            return InstallResult.Failed("Validation failed: ${validationResult.issues.first()}")
        }
        
        // 3. Atomic swap
        val swapResult = performAtomicSwap(tempDir)
        if (!swapResult) {
            return InstallResult.Failed("Atomic swap failed")
        }
        
        // 4. Set permissions
        setExecutablePermissions()
        
        // 5. Record version
        prefs.edit().putLong(ServerConfig.PREF_KEY_ASSET_VERSION, currentVersionCode).apply()
        
        Log.i(TAG, "Runtime v$currentVersionCode installed successfully")
        return InstallResult.Installed(currentVersionCode)
    }
    
    /**
     * Force reinstall even if version hasn't changed.
     */
    fun forceReinstall(): InstallResult {
        // Clear the version to force reinstall
        prefs.edit().putLong(ServerConfig.PREF_KEY_ASSET_VERSION, -1).apply()
        return installIfNeeded()
    }
    
    /**
     * Check if first-time installation is needed.
     */
    fun isFirstTimeInstall(): Boolean {
        return prefs.getLong(ServerConfig.PREF_KEY_ASSET_VERSION, -1) < 0
    }
    
    /**
     * Get the currently installed version code, or -1 if not installed.
     */
    fun getInstalledVersion(): Long {
        return prefs.getLong(ServerConfig.PREF_KEY_ASSET_VERSION, -1)
    }
    
    /**
     * Check if runtime is currently available.
     */
    fun isRuntimeAvailable(): Boolean {
        return validator.isRuntimeAvailable()
    }
    
    /**
     * Get runtime summary for debugging.
     */
    fun getRuntimeSummary(): RuntimeSummary {
        return validator.getRuntimeSummary()
    }
    
    private fun getCurrentVersionCode(): Long {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            PackageInfoCompat.getLongVersionCode(info)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get version code", e)
            1
        }
    }
    
    private fun performAtomicSwap(tempDir: java.io.File): Boolean {
        val runtimeDir = paths.runtimeRoot
        
        try {
            // Remove old runtime
            if (runtimeDir.exists()) {
                Log.i(TAG, "Removing old runtime...")
                runtimeDir.deleteRecursively()
            }
            
            // Atomic rename
            if (tempDir.renameTo(runtimeDir)) {
                Log.i(TAG, "Atomic rename successful")
                return true
            } else {
                Log.e(TAG, "Atomic rename failed!")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Atomic swap exception", e)
            return false
        }
    }
    
    private fun setExecutablePermissions() {
        try {
            // Bulk chmod (might not work on all devices, but try)
            Runtime.getRuntime()
                .exec("chmod -R 755 ${paths.runtimeRoot.absolutePath}")
                .waitFor()
            
            // Explicit permissions for critical executables
            paths.nodeBin.setExecutable(true, false)
            paths.bootstrapScript.setExecutable(true, false)
            
            // Also make node_bin executable if it exists
            paths.nodeRealBin.let { if (it.exists()) it.setExecutable(true, false) }
            
            Log.d(TAG, "Permissions set successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set some permissions: ${e.message}")
        }
    }
}

/**
 * Result of runtime installation.
 */
sealed class InstallResult {
    /** Runtime is already up-to-date, no action taken */
    data class AlreadyUpToDate(val versionCode: Long) : InstallResult()
    
    /** Runtime was freshly installed/updated */
    data class Installed(val versionCode: Long) : InstallResult()
    
    /** Installation failed */
    data class Failed(val reason: String) : InstallResult()
    
    /** Check if result indicates runtime is usable */
    fun isSuccess(): Boolean = this is AlreadyUpToDate || this is Installed
}
