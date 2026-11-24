package com.duophone.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

interface SmsStore {
    val messages: StateFlow<List<SmsRecord>>
    suspend fun reload()
    suspend fun insertIfAbsent(record: SmsRecord): Boolean
    suspend fun insertAndGetId(record: SmsRecord): Long
    suspend fun updateStatus(id: Long, status: SmsDeliveryStatus)
    suspend fun markThreadRead(address: String)
    suspend fun deleteById(id: Long)
}

class SmsRepository(private val dbHelper: DuoDatabaseHelper) : SmsStore {
    private val _messages = MutableStateFlow<List<SmsRecord>>(emptyList())
    override val messages: StateFlow<List<SmsRecord>> = _messages.asStateFlow()

    override suspend fun reload() = withContext(Dispatchers.IO) {
        _messages.value = queryAll()
    }

    override suspend fun insertIfAbsent(record: SmsRecord): Boolean = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = toContentValues(record)
        val rowId = db.insertWithOnConflict(
            DuoDatabaseHelper.TABLE_SMS,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        if (rowId != -1L) {
            _messages.value = queryAll()
            true
        } else {
            false
        }
    }

    override suspend fun insertAndGetId(record: SmsRecord): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val rowId = db.insertWithOnConflict(
            DuoDatabaseHelper.TABLE_SMS,
            null,
            toContentValues(record),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        _messages.value = queryAll()
        rowId
    }

    override suspend fun updateStatus(id: Long, status: SmsDeliveryStatus) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply { put("status", status.name) }
        db.update(DuoDatabaseHelper.TABLE_SMS, values, "id = ?", arrayOf(id.toString()))
        _messages.value = queryAll()
    }

    override suspend fun markThreadRead(address: String) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply { put("is_read", 1) }
        db.update(DuoDatabaseHelper.TABLE_SMS, values, "address = ? AND is_read = 0", arrayOf(address))
        _messages.value = queryAll()
    }

    override suspend fun deleteById(id: Long) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.delete(DuoDatabaseHelper.TABLE_SMS, "id = ?", arrayOf(id.toString()))
        _messages.value = queryAll()
    }

    fun buildThreads(all: List<SmsRecord>): List<SmsThreadSummary> {
        return all.groupBy { it.address }.map { (address, msgs) ->
            val sorted = msgs.sortedByDescending { it.timestampMs }
            val latest = sorted.first()
            SmsThreadSummary(
                address = address,
                latestBody = latest.body,
                latestTimestampMs = latest.timestampMs,
                unreadCount = msgs.count { !it.isRead && it.direction == SmsDirection.INCOMING },
                latestOtp = sorted.firstOrNull { !it.otpCode.isNullOrBlank() }?.otpCode,
                messageCount = msgs.size,
            )
        }.sortedByDescending { it.latestTimestampMs }
    }

    private fun queryAll(): List<SmsRecord> {
        val db = dbHelper.readableDatabase
        val out = mutableListOf<SmsRecord>()
        db.query(
            DuoDatabaseHelper.TABLE_SMS,
            null,
            null,
            null,
            null,
            null,
            "timestamp_ms DESC, id DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val indicesStr = cursor.getString(cursor.getColumnIndexOrThrow("modem_indices")).orEmpty()
                val indices = indicesStr.split(",").mapNotNull { it.trim().toIntOrNull() }
                out.add(
                    SmsRecord(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        dedupKey = cursor.getString(cursor.getColumnIndexOrThrow("dedup_key")),
                        address = cursor.getString(cursor.getColumnIndexOrThrow("address")),
                        direction = runCatching {
                            SmsDirection.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("direction")))
                        }.getOrDefault(SmsDirection.INCOMING),
                        body = cursor.getString(cursor.getColumnIndexOrThrow("body")),
                        timestampMs = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp_ms")),
                        isRead = cursor.getInt(cursor.getColumnIndexOrThrow("is_read")) != 0,
                        status = runCatching {
                            SmsDeliveryStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status")))
                        }.getOrDefault(SmsDeliveryStatus.RECEIVED),
                        otpCode = cursor.getString(cursor.getColumnIndexOrThrow("otp_code")),
                        modemIndices = indices,
                    ),
                )
            }
        }
        return out
    }

    private fun toContentValues(record: SmsRecord) = ContentValues().apply {
        put("dedup_key", record.dedupKey)
        put("address", record.address)
        put("direction", record.direction.name)
        put("body", record.body)
        put("timestamp_ms", record.timestampMs)
        put("is_read", if (record.isRead) 1 else 0)
        put("status", record.status.name)
        put("otp_code", record.otpCode)
        put("modem_indices", record.modemIndices.joinToString(","))
    }
}
