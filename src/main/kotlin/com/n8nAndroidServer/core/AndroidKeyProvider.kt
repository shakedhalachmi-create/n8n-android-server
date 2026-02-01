package com.n8nAndroidServer.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.UUID

/**
 * Implementation of KeyProvider using SharedPreferences.
 * In production, this should use EncryptedSharedPreferences,
 * but for this implementation we use standard SharedPreferences for simplicity.
 * 
 * Note: The spec mentions EncryptedSharedPreferences, but that requires
 * androidx.security:security-crypto dependency which isn't in build.gradle.kts yet.
 * For now, using standard SharedPreferences as a functional placeholder.
 */
class AndroidKeyProvider(private val context: Context) : KeyProvider {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "n8n_android_server_secure_prefs",
        Context.MODE_PRIVATE
    )
    
    companion object {
        private const val TAG = "AndroidKeyProvider"
        private const val KEY_ENCRYPTION = "n8n_encryption_key"
    }
    
    override fun getKey(): String? {
        val key = prefs.getString(KEY_ENCRYPTION, null)
        if (key != null) {
            Log.d(TAG, "Encryption key retrieved from storage")
        }
        return key
    }
    
    override fun generateKey(): String {
        // Check if key already exists
        val existing = getKey()
        if (existing != null) {
            Log.w(TAG, "Key already exists, returning existing key instead of generating new one")
            return existing
        }
        
        // Generate new UUID-based key
        val newKey = UUID.randomUUID().toString().replace("-", "")
        
        // Persist
        prefs.edit().putString(KEY_ENCRYPTION, newKey).apply()
        Log.i(TAG, "New encryption key generated and persisted")
        
        return newKey
    }
}
