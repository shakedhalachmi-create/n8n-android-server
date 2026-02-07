package com.n8nAndroidServer.core.runtime

import android.util.Log
import com.n8nAndroidServer.core.config.PathResolver
import java.io.File

/**
 * Validates the extracted runtime to ensure it's functional.
 * 
 * Checks performed:
 * - Node binary exists and is executable
 * - Bootstrap script exists
 * - n8n entry point exists
 * - Library directory contains .so files
 * 
 * @param paths PathResolver for file locations
 */
class RuntimeValidator(
    private val paths: PathResolver
) {
    companion object {
        private const val TAG = "RuntimeValidator"
    }
    
    /**
     * Validate the runtime in the specified directory.
     * 
     * @param runtimeDir The directory to validate (default: standard runtime location)
     * @return ValidationResult with details
     */
    fun validate(runtimeDir: File = paths.runtimeRoot): ValidationResult {
        val issues = mutableListOf<String>()
        
        // Check node binary
        val nodeBin = File(runtimeDir, "bin/node")
        if (!nodeBin.exists()) {
            issues.add("Node binary missing: ${nodeBin.absolutePath}")
        } else if (!nodeBin.canExecute()) {
            issues.add("Node binary not executable: ${nodeBin.absolutePath}")
        }
        
        // Check real node binary (the actual binary, not the wrapper)
        val nodeRealBin = File(runtimeDir, "bin/node_bin")
        if (!nodeRealBin.exists()) {
            // This might be first extraction where node hasn't been renamed yet
            Log.d(TAG, "node_bin not found, might be pre-rename state")
        }
        
        // Check bootstrap script
        val bootstrapScript = File(runtimeDir, "bin/n8n-start.sh")
        if (!bootstrapScript.exists()) {
            issues.add("Bootstrap script missing: ${bootstrapScript.absolutePath}")
        }
        
        // Check n8n entry point
        val n8nEntry = File(runtimeDir, "lib/node_modules/n8n/bin/n8n")
        if (!n8nEntry.exists()) {
            issues.add("n8n entry point missing: ${n8nEntry.absolutePath}")
        }
        
        // Check lib directory has .so files
        val libDir = File(runtimeDir, "lib")
        if (!libDir.exists()) {
            issues.add("Library directory missing: ${libDir.absolutePath}")
        } else {
            val soFiles = libDir.listFiles { file -> 
                file.isFile && file.name.endsWith(".so") 
            }
            if (soFiles.isNullOrEmpty()) {
                issues.add("No .so files found in library directory")
            } else {
                Log.d(TAG, "Found ${soFiles.size} .so files in lib/")
            }
        }
        
        // Check node_modules exists
        val nodeModules = File(runtimeDir, "lib/node_modules")
        if (!nodeModules.exists()) {
            issues.add("node_modules directory missing")
        }
        
        return if (issues.isEmpty()) {
            Log.i(TAG, "Runtime validation passed")
            ValidationResult.Valid
        } else {
            Log.e(TAG, "Runtime validation failed: ${issues.joinToString(", ")}")
            ValidationResult.Invalid(issues)
        }
    }
    
    /**
     * Check if runtime is available and valid.
     */
    fun isRuntimeAvailable(): Boolean {
        return validate() is ValidationResult.Valid
    }
    
    /**
     * Get a summary of the installed runtime for debugging.
     */
    fun getRuntimeSummary(): RuntimeSummary {
        val runtimeDir = paths.runtimeRoot
        
        return RuntimeSummary(
            exists = runtimeDir.exists(),
            nodeExists = paths.nodeBin.exists(),
            nodeExecutable = paths.nodeBin.canExecute(),
            bootstrapExists = paths.bootstrapScript.exists(),
            libCount = countSharedLibraries(),
            totalSizeMb = calculateRuntimeSize() / (1024 * 1024)
        )
    }
    
    private fun countSharedLibraries(): Int {
        return try {
            paths.libDir.listFiles { file -> 
                file.isFile && file.name.endsWith(".so") 
            }?.size ?: 0
        } catch (e: Exception) {
            0
        }
    }
    
    private fun calculateRuntimeSize(): Long {
        return try {
            paths.runtimeRoot.walkTopDown().sumOf { 
                if (it.isFile) it.length() else 0 
            }
        } catch (e: Exception) {
            0
        }
    }
}

/**
 * Result of runtime validation.
 */
sealed class ValidationResult {
    /** Runtime is valid and ready to use */
    data object Valid : ValidationResult()
    
    /** Runtime has issues that prevent it from working */
    data class Invalid(val issues: List<String>) : ValidationResult()
}

/**
 * Summary of installed runtime for debugging.
 */
data class RuntimeSummary(
    val exists: Boolean,
    val nodeExists: Boolean,
    val nodeExecutable: Boolean,
    val bootstrapExists: Boolean,
    val libCount: Int,
    val totalSizeMb: Long
)
