package com.duophone.app.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class ContactSource(val label: String) {
    LOCAL("本地"),
    ANDROID_SYSTEM("系统"),
}

data class ContactRecord(
    val id: Long = 0L,
    val name: String,
    val phone: String,
    val normalizedPhone: String,
    val note: String = "",
    val source: ContactSource = ContactSource.LOCAL,
    val updatedAtMs: Long = 0L,
)

/**
 * Pure phone-number normalization, suffix matching, and contact list merge helpers (JVM-testable).
 */
object ContactPhoneMatcher {
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val sb = StringBuilder(trimmed.length)
        for (i in trimmed.indices) {
            val ch = trimmed[i]
            if (ch == '+' && sb.isEmpty()) {
                sb.append('+')
            } else if (ch in '0'..'9') {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun matchKey(raw: String): String {
        val digitsOnly = normalize(raw).removePrefix("+")
        if (digitsOnly.isEmpty()) return ""
        val withoutIntl = when {
            digitsOnly.startsWith("0086") && digitsOnly.length == 15 -> digitsOnly.substring(4)
            digitsOnly.startsWith("86") && digitsOnly.length == 13 -> digitsOnly.substring(2)
            else -> digitsOnly
        }
        return if (withoutIntl.length >= 11) withoutIntl.takeLast(11) else withoutIntl
    }

    fun resolveDisplayName(number: String, contacts: List<ContactRecord>): String? {
        val norm = normalize(number)
        if (norm.isEmpty()) return null
        val exact = contacts.firstOrNull {
            it.normalizedPhone == norm || normalize(it.phone) == norm
        }
        if (exact != null) return exact.name

        val key = matchKey(number)
        if (key.length < 4) return null
        return contacts.firstOrNull { contact ->
            val candidateKey = matchKey(contact.normalizedPhone.ifBlank { contact.phone })
            candidateKey.length >= 4 && candidateKey == key
        }?.name
    }

    fun formatCallerLabel(number: String, contacts: List<ContactRecord>): String {
        val trimmed = number.trim()
        if (trimmed.isEmpty()) return "未知号码"
        val name = resolveDisplayName(trimmed, contacts)
        return if (name.isNullOrBlank()) trimmed else "$name ($trimmed)"
    }

    fun mergeContacts(
        local: List<ContactRecord>,
        system: List<ContactRecord>,
    ): List<ContactRecord> {
        val seenKeys = HashSet<String>()
        val seenExact = HashSet<String>()
        val merged = ArrayList<ContactRecord>(local.size + system.size)

        for (item in local) {
            merged.add(item)
            val norm = normalize(item.normalizedPhone.ifBlank { item.phone })
            if (norm.isNotEmpty()) seenExact.add(norm)
            val key = matchKey(item.phone)
            if (key.length >= 4) seenKeys.add(key)
        }

        for (item in system) {
            val norm = normalize(item.normalizedPhone.ifBlank { item.phone })
            val key = matchKey(item.phone)
            if (norm.isEmpty()) continue
            if (norm in seenExact) continue
            if (key.length >= 4 && key in seenKeys) continue
            merged.add(item)
            seenExact.add(norm)
            if (key.length >= 4) seenKeys.add(key)
        }

        return merged.sortedWith(
            compareBy<ContactRecord> { it.name.lowercase() }
                .thenBy { it.source.ordinal }
                .thenBy { it.phone },
        )
    }
}

interface ContactStore {
    val contacts: StateFlow<List<ContactRecord>>
    fun hasSystemContactsPermission(): Boolean
    suspend fun reload()
    suspend fun upsertLocal(id: Long?, name: String, phone: String, note: String = ""): Long
    suspend fun deleteLocal(id: Long)
}

class ContactRepository(
    private val context: Context,
    private val dbHelper: DuoDatabaseHelper,
) : ContactStore {
    private val _contacts = MutableStateFlow<List<ContactRecord>>(emptyList())
    override val contacts: StateFlow<List<ContactRecord>> = _contacts.asStateFlow()

    override fun hasSystemContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun reload() = withContext(Dispatchers.IO) {
        val local = queryLocalContacts()
        val system = if (hasSystemContactsPermission()) querySystemContacts() else emptyList()
        val merged = ContactPhoneMatcher.mergeContacts(local, system)
        _contacts.value = merged
        DuoLogger.i(
            LogCategory.SERVICE,
            "contacts_reload",
            "local=${local.size} system=${system.size} merged=${merged.size}",
        )
    }

    override suspend fun upsertLocal(
        id: Long?,
        name: String,
        phone: String,
        note: String,
    ): Long = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        val cleanPhone = phone.trim()
        val normalized = ContactPhoneMatcher.normalize(cleanPhone)
        require(cleanName.isNotEmpty()) { "联系人姓名不能为空" }
        require(normalized.isNotEmpty()) { "联系人电话号码无效" }

        val db = dbHelper.writableDatabase
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("name", cleanName)
            put("phone", cleanPhone)
            put("normalized_phone", normalized)
            put("note", note.trim())
            put("updated_at_ms", now)
        }
        val rowId = if (id != null && id > 0L) {
            val updated = db.update(
                DuoDatabaseHelper.TABLE_CONTACTS,
                values,
                "id = ?",
                arrayOf(id.toString()),
            )
            if (updated > 0) id else db.insert(DuoDatabaseHelper.TABLE_CONTACTS, null, values)
        } else {
            db.insert(DuoDatabaseHelper.TABLE_CONTACTS, null, values)
        }
        reload()
        rowId
    }

    override suspend fun deleteLocal(id: Long) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.delete(DuoDatabaseHelper.TABLE_CONTACTS, "id = ?", arrayOf(id.toString()))
        reload()
    }

    fun resolveDisplayName(number: String): String? =
        ContactPhoneMatcher.resolveDisplayName(number, _contacts.value)

    private fun queryLocalContacts(): List<ContactRecord> {
        val db = dbHelper.readableDatabase
        val out = mutableListOf<ContactRecord>()
        db.query(
            DuoDatabaseHelper.TABLE_CONTACTS,
            null,
            null,
            null,
            null,
            null,
            "name COLLATE NOCASE ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(
                    ContactRecord(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        phone = cursor.getString(cursor.getColumnIndexOrThrow("phone")),
                        normalizedPhone = cursor.getString(cursor.getColumnIndexOrThrow("normalized_phone")),
                        note = cursor.getString(cursor.getColumnIndexOrThrow("note")).orEmpty(),
                        source = ContactSource.LOCAL,
                        updatedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at_ms")),
                    ),
                )
            }
        }
        return out
    }

    private fun querySystemContacts(): List<ContactRecord> {
        val out = mutableListOf<ContactRecord>()
        runCatching {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                var syntheticId = -1L
                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim().orEmpty() else ""
                    val rawNum = if (numIdx >= 0) cursor.getString(numIdx)?.trim().orEmpty() else ""
                    val norm = ContactPhoneMatcher.normalize(rawNum)
                    if (name.isEmpty() || norm.isEmpty()) continue
                    val contactId = if (idIdx >= 0) -cursor.getLong(idIdx) - 1000L else syntheticId--
                    out.add(
                        ContactRecord(
                            id = contactId,
                            name = name,
                            phone = rawNum,
                            normalizedPhone = norm,
                            note = "",
                            source = ContactSource.ANDROID_SYSTEM,
                            updatedAtMs = 0L,
                        ),
                    )
                }
            }
        }.onFailure { err ->
            DuoLogger.w(LogCategory.SERVICE, "system_contacts_read_fail", err.message.orEmpty())
        }
        return out
    }
}
