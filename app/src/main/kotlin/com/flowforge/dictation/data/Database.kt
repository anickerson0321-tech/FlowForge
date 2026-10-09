package com.flowforge.dictation.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.flowforge.core.cleanup.CleanupMode
import com.flowforge.core.dictionary.DictionaryEntry
import com.flowforge.core.dictionary.EntryCategory
import com.flowforge.core.dictionary.PersonalDictionary
import com.flowforge.core.privacy.RetentionPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Local database. Every row's content is an AES-GCM encrypted JSON payload ([SecureBox]); only
 * ids and timestamps are stored in the clear so retention can be enforced without decrypting.
 */
class FlowForgeDatabase(context: Context) : SQLiteOpenHelper(context, "flowforge.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE dictionary (id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, payload BLOB NOT NULL)")
        db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, payload BLOB NOT NULL)")
        db.execSQL("CREATE INDEX history_created ON history(created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // Deleted rows are overwritten, not left in free pages.
        // (PRAGMA returns a row, so it must go through rawQuery rather than execSQL.)
        db.rawQuery("PRAGMA secure_delete = ON", null).use { it.moveToFirst() }
    }
}

class DictionaryRepository(private val db: FlowForgeDatabase, private val box: SecureBox) {
    @Volatile private var cached: PersonalDictionary? = null

    /** Current dictionary for cleanup; cached until the next change. Call off the main thread. */
    fun current(): PersonalDictionary = cached ?: PersonalDictionary(list()).also { cached = it }

    /** Terms from the cache only (never touches the disk); empty until [current] has run once. */
    fun cachedTerms(): List<String> = cached?.entries?.map { it.term } ?: emptyList()

    fun list(): List<DictionaryEntry> {
        val out = ArrayList<DictionaryEntry>()
        db.readableDatabase.query("dictionary", arrayOf("id", "payload"), null, null, null, null, "created_at").use { c ->
            while (c.moveToNext()) {
                val entry = runCatching { decode(c.getString(0), box.decryptString(c.getBlob(1))) }.getOrNull()
                if (entry != null) out += entry
            }
        }
        return out.sortedBy { it.term.lowercase() }
    }

    fun upsert(entry: DictionaryEntry) {
        val json = JSONObject()
            .put("term", entry.term)
            .put("spoken", JSONArray(entry.spokenForms))
            .put("category", entry.category.name)
        val values = ContentValues().apply {
            put("id", entry.id)
            put("created_at", System.currentTimeMillis())
            put("payload", box.encryptString(json.toString()))
        }
        db.writableDatabase.insertWithOnConflict("dictionary", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        cached = null
    }

    fun delete(id: String) {
        db.writableDatabase.delete("dictionary", "id = ?", arrayOf(id))
        cached = null
    }

    fun clear() {
        db.writableDatabase.delete("dictionary", null, null)
        cached = null
    }

    fun newId(): String = UUID.randomUUID().toString()

    private fun decode(id: String, json: String): DictionaryEntry {
        val o = JSONObject(json)
        val arr = o.optJSONArray("spoken") ?: JSONArray()
        val spoken = (0 until arr.length()).map { arr.getString(it) }
        val category = runCatching { EntryCategory.valueOf(o.optString("category")) }.getOrDefault(EntryCategory.OTHER)
        return DictionaryEntry(id, o.getString("term"), spoken, category)
    }
}

data class HistoryEntry(
    val id: Long,
    val createdAtMillis: Long,
    val original: String,
    val cleaned: String,
    val mode: CleanupMode,
    val engine: String,
    val outcome: String,
    val targetApp: String,
)

class HistoryRepository(
    private val db: FlowForgeDatabase,
    private val box: SecureBox,
    private val settings: SettingsStore,
) {
    /** Saves an entry if the user has history enabled. Returns false when nothing was stored. */
    fun add(original: String, cleaned: String, mode: CleanupMode, engine: String, outcome: String, targetApp: String): Boolean {
        val privacy = settings.privacy
        if (!privacy.historyEnabled) return false
        val json = JSONObject()
            .put("original", if (privacy.keepOriginalTranscript) original else "")
            .put("cleaned", cleaned)
            .put("mode", mode.name)
            .put("engine", engine)
            .put("outcome", outcome)
            .put("app", targetApp)
        val values = ContentValues().apply {
            put("created_at", System.currentTimeMillis())
            put("payload", box.encryptString(json.toString()))
        }
        db.writableDatabase.insert("history", null, values)
        purgeExpired()
        return true
    }

    fun list(limit: Int = 200): List<HistoryEntry> {
        val out = ArrayList<HistoryEntry>()
        db.readableDatabase.query("history", arrayOf("id", "created_at", "payload"), null, null, null, null, "created_at DESC", limit.toString()).use { c ->
            while (c.moveToNext()) {
                val e = runCatching {
                    val o = JSONObject(box.decryptString(c.getBlob(2)))
                    HistoryEntry(
                        c.getLong(0), c.getLong(1), o.optString("original"), o.optString("cleaned"),
                        runCatching { CleanupMode.valueOf(o.optString("mode")) }.getOrDefault(CleanupMode.LIGHT),
                        o.optString("engine"), o.optString("outcome"), o.optString("app"),
                    )
                }.getOrNull()
                if (e != null) out += e
            }
        }
        return out
    }

    fun delete(id: Long) {
        db.writableDatabase.delete("history", "id = ?", arrayOf(id.toString()))
    }

    fun clear() {
        db.writableDatabase.delete("history", null, null)
    }

    fun purgeExpired(now: Long = System.currentTimeMillis()): Int {
        val days = settings.privacy.historyRetentionDays
        return db.writableDatabase.delete("history", "created_at < ?", arrayOf(RetentionPolicy.cutoffMillis(now, days).toString()))
    }

    fun count(): Long = android.database.DatabaseUtils.queryNumEntries(db.readableDatabase, "history")
}
