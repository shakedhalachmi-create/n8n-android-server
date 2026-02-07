package com.n8nAndroidServer.core.config

import android.content.Context
import java.util.TimeZone

/**
 * Builds environment variables for the n8n Node.js process.
 * 
 * This class centralizes all environment variable configuration, providing
 * documentation for each variable's purpose and eliminating scattered env
 * setup logic.
 * 
 * ## Environment Variable Categories
 * 
 * 1. **System Paths**: HOME, PATH, LD_LIBRARY_PATH, NODE_PATH
 * 2. **n8n Configuration**: N8N_PORT, N8N_HOST, N8N_ENCRYPTION_KEY, etc.
 * 3. **Android Workarounds**: OPENSSL_CONF, N8N_BLOCK_JS_EXECUTION_PROCESS
 * 4. **Performance**: NODE_OPTIONS, DB_TYPE
 * 
 * ## Critical Android Workarounds
 * 
 * - **OPENSSL_CONF=/dev/null**: Prevents OpenSSL from trying to read
 *   system config files that don't exist or have wrong permissions on Android.
 * 
 * - **N8N_BLOCK_JS_EXECUTION_PROCESS=true**: Disables n8n's task runner
 *   subprocess to prevent port conflicts (task runners would compete for ports).
 * 
 * - **N8N_DISABLE_PYTHON_NODE=true**: Python is not available on Android.
 * 
 * @param context Application context
 * @param paths PathResolver for directory paths
 */
class EnvironmentBuilder(
    private val context: Context,
    private val paths: PathResolver
) {
    
    /**
     * Build the complete environment map for the n8n process.
     * 
     * @param encryptionKey The n8n encryption key (retrieved from secure storage)
     * @param devMode Whether to run in development mode (enables hot reload)
     * @return Map of environment variable name to value
     */
    fun build(encryptionKey: String, devMode: Boolean = false): Map<String, String> {
        return buildMap {
            // ============================
            // System Paths
            // ============================
            
            // HOME directory for Node.js and n8n
            put("HOME", paths.n8nUserDir.absolutePath)
            
            // n8n-specific user folder
            put("N8N_USER_FOLDER", paths.n8nUserDir.absolutePath)
            
            // Library path for loading patched .so files
            // Critical: Must point to runtime/lib where patchelf'd libraries live
            put("LD_LIBRARY_PATH", paths.libDir.absolutePath)
            
            // Executable search path
            put("PATH", paths.getPathEnvValue())
            
            // Node.js module search path
            put("NODE_PATH", paths.nodeModulesDir.absolutePath)
            
            // Temporary directory (use app's cache dir)
            put("TMPDIR", context.cacheDir.absolutePath)
            
            // ============================
            // OpenSSL Workaround
            // ============================
            
            /**
             * CRITICAL: Force OpenSSL to ignore system config.
             * 
             * On Android, OpenSSL may try to load /system/etc/ssl/openssl.cnf
             * which either doesn't exist or has incompatible format, causing:
             * "error:2500006F:DSO support routines:dso_new:unknown error"
             * 
             * Setting to /dev/null makes OpenSSL skip config loading entirely.
             */
            put("OPENSSL_CONF", "/dev/null")
            
            // ============================
            // n8n Server Configuration
            // ============================
            
            // Port n8n listens on (internal, proxied by Gatekeeper)
            put("N8N_PORT", ServerConfig.N8N_PORT.toString())
            
            // Bind to localhost only (Gatekeeper handles external access)
            put("N8N_HOST", "127.0.0.1")
            
            // Listen on all interfaces for internal binding
            // (Gatekeeper is the public-facing endpoint)
            put("N8N_LISTEN_ADDRESS", "0.0.0.0")
            
            // Encryption key for credentials and sensitive data
            put("N8N_ENCRYPTION_KEY", encryptionKey)
            
            // Use SQLite for simplicity on mobile
            put("DB_TYPE", "sqlite")
            
            // Disable secure cookies (we're on localhost/HTTP internally)
            put("N8N_SECURE_COOKIE", "false")
            
            // ============================
            // Node.js Performance
            // ============================
            
            // Limit V8 heap size for Android memory constraints
            put("NODE_OPTIONS", "--max-old-space-size=${ServerConfig.NODE_MAX_OLD_SPACE_SIZE_MB}")
            
            // ============================
            // Android-Specific Workarounds
            // ============================
            
            /**
             * CRITICAL: Disable JavaScript execution in subprocess.
             * 
             * n8n 1.7+ runs JavaScript nodes in a separate "task runner" process
             * that tries to bind to port 5679. On Android, this creates conflicts:
             * - Task runner and main n8n both try to use ports
             * - No proper process isolation like on server
             * 
             * Setting this to true runs JS execution in the main process.
             */
            put("N8N_BLOCK_JS_EXECUTION_PROCESS", "true")
            
            /**
             * Disable Python node (Python not available on Android).
             */
            put("N8N_DISABLE_PYTHON_NODE", "true")
            
            /**
             * Force evaluator to run in main process.
             * Companion to N8N_BLOCK_JS_EXECUTION_PROCESS.
             */
            put("N8N_TASKS_EVALUATOR_PROCESS", "main")
            
            // ============================
            // Timezone Synchronization
            // ============================
            
            // Sync Node.js timezone with Android device timezone
            put("GENERIC_TIMEZONE", TimeZone.getDefault().id)
            
            // ============================
            // Development Mode
            // ============================
            
            // NODE_ENV: production for stability, development for debugging
            put("NODE_ENV", if (devMode) "development" else "production")
        }
    }
    
    /**
     * Build environment for the bootstrap shell script.
     * This is a subset focused on what the shell script needs.
     */
    fun buildForBootstrap(encryptionKey: String, devMode: Boolean = false): Map<String, String> {
        // Bootstrap script needs the full environment as it launches node
        return build(encryptionKey, devMode)
    }
    
    companion object {
        /**
         * Create an EnvironmentBuilder with a new PathResolver.
         * Convenience factory for simple usage.
         */
        fun create(context: Context): EnvironmentBuilder {
            return EnvironmentBuilder(context, PathResolver(context))
        }
    }
}
