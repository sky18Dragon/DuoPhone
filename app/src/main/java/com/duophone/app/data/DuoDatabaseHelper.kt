package com.duophone.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local SQLite database storing SMS messages, call logs, and IMEI-keyed modem config backups.
 * All personal communication history stays strictly on-device.
 */
class DuoDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "duophone.db"
        const val DB_VERSION = 1

        const val TABLE_SMS = "sms_messages"
        const val TABLE_CALLS = "call_logs"
        const val TABLE_BACKUPS = "config_backups"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_SMS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                dedup_key TEXT NOT NULL UNIQUE,
                address TEXT NOT NULL,
                direction TEXT NOT NULL,
                body TEXT NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                is_read INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                otp_code TEXT,
                modem_indices TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_sms_address_ts ON $TABLE_SMS(address, timestamp_ms DESC)")

        db.execSQL(
            """
            CREATE TABLE $TABLE_CALLS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                number TEXT NOT NULL,
                direction TEXT NOT NULL,
                outcome TEXT NOT NULL,
                started_at_ms INTEGER NOT NULL,
                duration_sec INTEGER NOT NULL DEFAULT 0,
                end_reason TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_calls_started ON $TABLE_CALLS(started_at_ms DESC)")

        db.execSQL(
            """
            CREATE TABLE $TABLE_BACKUPS (
                imei TEXT PRIMARY KEY,
                model TEXT NOT NULL,
                usbnet_raw TEXT NOT NULL,
                usbcfg_raw TEXT NOT NULL,
                ims_raw TEXT NOT NULL,
                saved_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema migrations go here when DB_VERSION increments.
    }
}
