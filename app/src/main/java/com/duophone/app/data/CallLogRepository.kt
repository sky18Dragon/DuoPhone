package com.duophone.app.data

import android.content.ContentValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

interface CallLogStore {
    val logs: StateFlow<List<CallLogRecord>>
    suspend fun reload()
    suspend fun insert(record: CallLogRecord): Long
    suspend fun deleteById(id: Long)
    suspend fun clearAll()
}

class CallLogRepository(private val dbHelper: DuoDatabaseHelper) : CallLogStore {
    private val _logs = MutableStateFlow<List<CallLogRecord>>(emptyList())
    override val logs: StateFlow<List<CallLogRecord>> = _logs.asStateFlow()

    override suspend fun reload() = withContext(Dispatchers.IO) {
        _logs.value = queryAll()
    }

    override suspend fun insert(record: CallLogRecord): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("number", record.number)
            put("direction", record.direction.name)
            put("outcome", record.outcome.name)
            put("started_at_ms", record.startedAtMs)
            put("duration_sec", record.durationSec)
            put("end_reason", record.endReason)
        }
        val id = db.insert(DuoDatabaseHelper.TABLE_CALLS, null, values)
        _logs.value = queryAll()
        id
    }

    override suspend fun deleteById(id: Long) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.delete(DuoDatabaseHelper.TABLE_CALLS, "id = ?", arrayOf(id.toString()))
        _logs.value = queryAll()
    }

    override suspend fun clearAll() = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.delete(DuoDatabaseHelper.TABLE_CALLS, null, null)
        _logs.value = emptyList()
    }

    private fun queryAll(): List<CallLogRecord> {
        val db = dbHelper.readableDatabase
        val out = mutableListOf<CallLogRecord>()
        db.query(
            DuoDatabaseHelper.TABLE_CALLS,
            null,
            null,
            null,
            null,
            null,
            "started_at_ms DESC, id DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(
                    CallLogRecord(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        number = cursor.getString(cursor.getColumnIndexOrThrow("number")),
                        direction = runCatching {
                            CallDirection.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("direction")))
                        }.getOrDefault(CallDirection.OUTGOING),
                        outcome = runCatching {
                            CallRecordOutcome.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("outcome")))
                        }.getOrDefault(CallRecordOutcome.OUTGOING_FAILED),
                        startedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("started_at_ms")),
                        durationSec = cursor.getLong(cursor.getColumnIndexOrThrow("duration_sec")),
                        endReason = cursor.getString(cursor.getColumnIndexOrThrow("end_reason")).orEmpty(),
                    ),
                )
            }
        }
        return out
    }
}
