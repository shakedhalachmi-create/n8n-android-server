package com.n8nAndroidServer.gatekeeper

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * SQLite database for IP whitelist management.
 * Schema v2: Supports PENDING, ALLOWED, and BLOCKED statuses.
 */
class WhitelistDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    
    companion object {
        private const val TAG = "WhitelistDatabase"
        private const val DATABASE_NAME = "whitelist.db"
        private const val DATABASE_VERSION = 2
        
        private const val TABLE_WHITELIST = "whitelist"
        private const val COLUMN_IP = "ip"
        private const val COLUMN_STATUS = "status"
        private const val COLUMN_LAST_UPDATED = "last_updated"
        
        @Volatile
        private var INSTANCE: WhitelistDatabase? = null
        
        fun getInstance(context: Context): WhitelistDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WhitelistDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        // Static methods for convenience (delegate to singleton)
        fun checkIp(ip: String): Status? = INSTANCE?.checkIpInternal(ip)
        fun addPending(ip: String) = INSTANCE?.addPendingInternal(ip)
        fun allowIp(ip: String) = INSTANCE?.updateStatusInternal(ip, Status.ALLOWED)
        fun blockIp(ip: String) = INSTANCE?.updateStatusInternal(ip, Status.BLOCKED)
        fun getAllPending(): List<Entry> = INSTANCE?.getAllPendingInternal() ?: emptyList()
    }
    
    enum class Status(val value: String) {
        PENDING("PENDING"),
        ALLOWED("ALLOWED"),
        BLOCKED("BLOCKED");
        
        companion object {
            fun fromString(value: String): Status? = values().find { it.value == value }
        }
    }
    
    data class Entry(
        val ip: String,
        val status: Status,
        val lastUpdated: Long
    )
    
    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE IF NOT EXISTS $TABLE_WHITELIST (
                $COLUMN_IP TEXT PRIMARY KEY,
                $COLUMN_STATUS TEXT NOT NULL CHECK ($COLUMN_STATUS IN ('PENDING', 'ALLOWED', 'BLOCKED')),
                $COLUMN_LAST_UPDATED INTEGER NOT NULL
            )
        """.trimIndent()
        
        db.execSQL(createTable)
        Log.i(TAG, "Database created with schema v$DATABASE_VERSION")
    }
    
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Migration from v1 (if it existed) to v2
            db.execSQL("DROP TABLE IF EXISTS $TABLE_WHITELIST")
            onCreate(db)
            Log.i(TAG, "Database upgraded from v$oldVersion to v$newVersion")
        }
    }
    
    private fun checkIpInternal(ip: String): Status? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_WHITELIST,
            arrayOf(COLUMN_STATUS),
            "$COLUMN_IP = ?",
            arrayOf(ip),
            null, null, null
        )
        
        return cursor.use {
            if (it.moveToFirst()) {
                val statusStr = it.getString(0)
                Status.fromString(statusStr)
            } else {
                null
            }
        }
    }
    
    private fun addPendingInternal(ip: String) {
        val existing = checkIpInternal(ip)
        if (existing != null) {
            Log.d(TAG, "IP $ip already exists with status $existing, not adding as PENDING")
            return
        }
        
        val db = writableDatabase
        val now = System.currentTimeMillis()
        
        db.execSQL(
            "INSERT OR REPLACE INTO $TABLE_WHITELIST ($COLUMN_IP, $COLUMN_STATUS, $COLUMN_LAST_UPDATED) VALUES (?, ?, ?)",
            arrayOf(ip, Status.PENDING.value, now)
        )
        
        Log.i(TAG, "Added IP $ip as PENDING")
    }
    
    private fun updateStatusInternal(ip: String, status: Status) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        
        val rowsAffected = db.update(
            TABLE_WHITELIST,
            android.content.ContentValues().apply {
                put(COLUMN_STATUS, status.value)
                put(COLUMN_LAST_UPDATED, now)
            },
            "$COLUMN_IP = ?",
            arrayOf(ip)
        )
        
        if (rowsAffected > 0) {
            Log.i(TAG, "Updated IP $ip to status $status")
        } else {
            Log.w(TAG, "IP $ip not found for status update")
        }
    }
    
    private fun getAllPendingInternal(): List<Entry> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_WHITELIST,
            arrayOf(COLUMN_IP, COLUMN_STATUS, COLUMN_LAST_UPDATED),
            "$COLUMN_STATUS = ?",
            arrayOf(Status.PENDING.value),
            null, null,
            "$COLUMN_LAST_UPDATED DESC"
        )
        
        val entries = mutableListOf<Entry>()
        cursor.use {
            while (it.moveToNext()) {
                entries.add(
                    Entry(
                        ip = it.getString(0),
                        status = Status.fromString(it.getString(1)) ?: Status.PENDING,
                        lastUpdated = it.getLong(2)
                    )
                )
            }
        }
        
        return entries
    }
}
