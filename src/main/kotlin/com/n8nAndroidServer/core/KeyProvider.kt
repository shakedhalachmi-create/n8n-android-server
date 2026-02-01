package com.n8nAndroidServer.core

/**
 * Abstraction for encryption key management.
 * Used for N8N_ENCRYPTION_KEY persistence and retrieval.
 */
interface KeyProvider {
    /**
     * Retrieve the encryption key if it exists.
     * @return The encryption key, or null if not yet generated.
     */
    fun getKey(): String?
    
    /**
     * Generate a new encryption key and persist it.
     * Should only be called once during initial setup.
     * @return The newly generated key.
     */
    fun generateKey(): String
}
