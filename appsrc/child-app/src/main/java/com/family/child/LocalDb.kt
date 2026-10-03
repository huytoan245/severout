package com.family.child

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.security.MessageDigest

data class PendingEvent(val localId: Long, val json: String, val createdAt: Long)

// SQLiteOpenHelper did not implement AutoCloseable on all supported Android APIs.
inline fun <T> PendingStore.useStore(block: (PendingStore) -> T): T = try { block(this) } finally { close() }

object EventIdentity {
    fun documentId(o: JSONObject): String = o.optString("eventId").takeIf { it.isNotBlank() }
        ?: "${o.getString("type")}-${o.getString("id")}-${o.getLong("time")}" // legacy retry identity

    fun sampleId(time: Long, lat: Double, lon: Double): String {
        val bytes = "$time:${java.lang.Double.doubleToLongBits(lat)}:${java.lang.Double.doubleToLongBits(lon)}"
        return "sample-" + MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/** v1 rows remain intact. A checkpoint and every event from a GPS fix commit together. */
class PendingStore(c: Context) : SQLiteOpenHelper(c.applicationContext, "location.db", null, 2) {
    override fun onConfigure(db: SQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE pending_events(id INTEGER PRIMARY KEY AUTOINCREMENT,json TEXT NOT NULL,created_at INTEGER NOT NULL)")
        createMetadata(db)
    }
    private fun createMetadata(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS journal_keys(event_key TEXT PRIMARY KEY NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS journal_meta(name TEXT PRIMARY KEY NOT NULL,value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        createMetadata(db)
        db.rawQuery("SELECT json FROM pending_events", null).use { c ->
            while (c.moveToNext()) {
                // Preserve even malformed legacy rows for diagnosis; never discard history.
                val key = try { EventIdentity.documentId(JSONObject(c.getString(0))) } catch (_: Exception) { continue }
                db.insertWithOnConflict("journal_keys", null, ContentValues().apply { put("event_key", key) }, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }
    private fun append(db: SQLiteDatabase, json: String) {
        val key = EventIdentity.documentId(JSONObject(json))
        if (db.insertWithOnConflict("journal_keys", null, ContentValues().apply { put("event_key", key) }, SQLiteDatabase.CONFLICT_IGNORE) == -1L) return
        db.insertOrThrow("pending_events", null, ContentValues().apply { put("json", json); put("created_at", System.currentTimeMillis()) })
    }
    @Synchronized fun acceptWake(request: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val latest = meta("wake_inbox_latest")?.toLongOrNull() ?: 0L
            if (request <= latest) return false
            writeMeta(db, "wake_inbox_latest", request.toString())
            writeMeta(db, "refresh_pending", request.toString())
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }
    @Synchronized fun finishRefresh(request: Long, envelope: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = JSONObject(meta("refresh_result") ?: "{}").optLong("request")
            if (request < existing) return
            writeMeta(db, "refresh_result", envelope)
            if (meta("refresh_pending")?.toLongOrNull() == request) writeMeta(db, "refresh_pending", "0")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun commitSample(events: List<String>, checkpoint: String) {
        commitWithMeta(events, "engine_checkpoint", checkpoint)
    }
    @Synchronized fun commitEventAndMeta(json: String, name: String, value: String) {
        commitWithMeta(listOf(json), name, value)
    }
    private fun commitWithMeta(events: List<String>, name: String, value: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            events.forEach { append(db, it) }
            writeMeta(db, name, value)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun insert(json: String) {
        val db = writableDatabase
        db.beginTransaction()
        try { append(db, json); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    private fun writeMeta(db: SQLiteDatabase, name: String, value: String) {
        db.insertWithOnConflict("journal_meta", null, ContentValues().apply { put("name", name); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }
    @Synchronized fun putMeta(name: String, value: String) = writeMeta(writableDatabase, name, value)
    @Synchronized fun meta(name: String): String? = readableDatabase.rawQuery("SELECT value FROM journal_meta WHERE name=?", arrayOf(name)).use { if (it.moveToFirst()) it.getString(0) else null }
    @Synchronized fun batch(limit: Int = 100): List<PendingEvent> {
        val out = mutableListOf<PendingEvent>()
        readableDatabase.rawQuery("SELECT id,json,created_at FROM pending_events ORDER BY id LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) out += PendingEvent(c.getLong(0), c.getString(1), c.getLong(2))
        }
        return out
    }
    @Synchronized fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM pending_events", null).use { it.moveToFirst(); it.getInt(0) }
    @Synchronized fun delete(id: Long) { writableDatabase.delete("pending_events", "id=?", arrayOf(id.toString())) }
}
