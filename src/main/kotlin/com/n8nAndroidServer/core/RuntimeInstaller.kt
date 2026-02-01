package com.n8nAndroidServer.core

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Handles extraction and installation of the n8n runtime.
 * Implements atomic updates: Verify -> Wipe -> Extract -> Version.
 */
object RuntimeInstaller {
    private const val TAG = "RuntimeInstaller"

    /**
     * Extracts the given archive to the context's files dir (runtime/ folder).
     * Performs SHA-256 verification and atomic cleanup.
     * 
     * @param archiveFile The tar.gz file to extract.
     * @param expectedSha256 The expected SHA-256 hash.
     * @param context App Context.
     * @return true if successful.
     */
    fun installFromArchive(context: Context, archiveFile: File, expectedSha256: String, verifyIntegrity: Boolean = true): Boolean {
        try {
            val runtimeDir = File(context.filesDir, "runtime")
        
        // 1. Verify Integrity (Optional)
        if (verifyIntegrity) {
            Log.i(TAG, "Verifying integrity of ${archiveFile.absolutePath}...")
            val calculatedSha = RuntimeDownloader.calculateSha256(archiveFile)
            if (!calculatedSha.equals(expectedSha256, ignoreCase = true)) {
                Log.e(TAG, "Integrity Check Failed! Expected: $expectedSha256, Got: $calculatedSha")
                return false
            }
            Log.i(TAG, "Integrity Verified.")
        } else {
             Log.i(TAG, "Integrity check skipped (trusted source).")
        }

        // 2. Atomic Wipe (Targeted Cleanup)
        if (runtimeDir.exists()) {
            Log.w(TAG, "Wiping old runtime at ${runtimeDir.absolutePath}...")
            try {
                val rmCmd = listOf("rm", "-rf", runtimeDir.absolutePath)
                val process = Runtime.getRuntime().exec(rmCmd.toTypedArray())
                val exitCode = process.waitFor()
                if (exitCode != 0) {
                     Log.e(TAG, "Shell wipe failed with code $exitCode. Attempting Kotlin fallback...")
                     runtimeDir.deleteRecursively() // Fallback
                }
            } catch (e: Exception) {
                Log.e(TAG, "Shell wipe failed", e)
                runtimeDir.deleteRecursively() // Fallback
            }
        }
        runtimeDir.mkdirs()
        
        // 3. Extract
        Log.i(TAG, "Extracting to ${runtimeDir.absolutePath} using tar...")
        try {
            // Use native tar for symlink preservation
            val command = listOf("tar", "-xzf", archiveFile.absolutePath, "--no-same-owner", "-C", runtimeDir.absolutePath)
            val process = Runtime.getRuntime().exec(command.toTypedArray())
            val exitCode = process.waitFor()
            
            if (exitCode != 0) {
                val error = process.errorStream.bufferedReader().readText()
                Log.e(TAG, "Tar extraction failed. Code: $exitCode, Error: $error")
                // Cleanup partial extraction
                runtimeDir.deleteRecursively()
                return false
            }
            
            Log.i(TAG, "Extraction successful.")
            
        } catch (e: Exception) {
            Log.e(TAG, "Shell execution failed", e)
            runtimeDir.deleteRecursively()
            return false
        }
        
        // 4. Set Permissions
        // chmod -R 755 runtimeDir
        try {
            // General recursive chmod
            val chmodCmd = listOf("chmod", "-R", "755", runtimeDir.absolutePath)
            Runtime.getRuntime().exec(chmodCmd.toTypedArray()).waitFor()
            
            // Critical: Explicitly set +x for binaries via Kotlin File API
            val binDir = File(runtimeDir, "bin")
            val nodeFile = File(binDir, "node")
            val startScript = File(binDir, "n8n-start.sh")

            if (nodeFile.exists()) {
                val success = nodeFile.setExecutable(true, false)
                Log.i(TAG, "Permission Fix: node (+x) = $success")
            }
            if (startScript.exists()) {
                val success = startScript.setExecutable(true, false)
                Log.i(TAG, "Permission Fix: n8n-start.sh (+x) = $success")
            }
            
            // Fallback: Targeted chmod for strict environments
            // Sometimes standard File.setExecutable fails on internal storage mounts
            Runtime.getRuntime().exec("chmod 755 ${nodeFile.absolutePath}").waitFor()
            Runtime.getRuntime().exec("chmod 755 ${startScript.absolutePath}").waitFor()
            
        } catch (e: Exception) {
            Log.w(TAG, "chmod warning: ${e.message}")
        }

        // 5. Verify & Stamp Version
        val nodeBin = File(runtimeDir, "bin/node")
        if (nodeBin.exists()) {
             Log.i(TAG, "Runtime verified. Stamping version...")
             val versionFile = File(runtimeDir, "version.txt")
             versionFile.writeText(expectedSha256)
             
             // NOTE: We DO NOT delete the archiveFile. Persistent Cache logic.
             Log.i(TAG, "Installation Complete. Archive preserved in cache.")
            return true
        } else {
            Log.e(TAG, "Node binary not found after extraction.")
            // Cleanup
            try {
                runtimeDir.deleteRecursively()
            } catch (ignore: Exception) {}
            return false
        }
    } catch (e: Throwable) {
        Log.e(TAG, "Critical installation failure", e)
        return false
    }
}

    /**
     * Checks if the runtime is currently installed and returns its version hash.
     * @return The installed SHA-256 hash or null if not installed/corrupt.
     */
    fun getInstalledHash(context: Context): String? {
        val runtimeDir = File(context.filesDir, "runtime")
        val nodeBin = File(runtimeDir, "bin/node")
        val versionFile = File(runtimeDir, "version.txt")
        
        if (!nodeBin.exists()) return null
        if (!versionFile.exists()) return null
        
        return try {
            versionFile.readText().trim()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Basic availability check.
     */
    fun isRuntimeAvailable(context: Context): Boolean {
        return getInstalledHash(context) != null
    }
}
