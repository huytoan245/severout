package com.family.child

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.family.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class JournalReliabilityTest {
    private lateinit var context: Context
    @Before fun reset() { context = RuntimeEnvironment.getApplication(); context.deleteDatabase("location.db") }
    private fun event(index: Int, time: Long = 1_800_000_000_000L + index * 60_000L): String = JSONObject()
        .put("type", "location_sample").put("id", EventIdentity.sampleId(time, 20.0 + index * 0.001, 105.0))
        .put("time", time).put("lat", 20.0 + index * 0.001).put("lon", 105.0).toString()

    @Test fun offlineRouteSurvivesReopenAndRetriesWithoutDuplicate() {
        var db = PendingStore(context)
        repeat(150) { db.commitSample(listOf(event(it)), "checkpoint-$it") }
        assertEquals(150, db.count()); db.close()
        db = PendingStore(context)
        assertEquals("checkpoint-149", db.meta("engine_checkpoint"))
        repeat(150) { db.insert(event(it)) }
        assertEquals(150, db.count())
        val ids = db.batch(200).map { EventIdentity.documentId(JSONObject(it.json)) }
        assertEquals(150, ids.toSet().size)
        db.batch(200).forEach { db.delete(it.localId) } // authoritative success in uploader
        db.close(); db = PendingStore(context)
        repeat(150) { db.insert(event(it)) } // retry/replayed callback after success
        assertEquals(0, db.count()); db.close()
    }
    @Test fun failedBatchRollsBackEventsAndCheckpointTogether() {
        val db = PendingStore(context)
        db.commitSample(listOf(event(0)), "before")
        try { db.commitSample(listOf(event(1), "broken-json"), "after"); fail("expected failure") } catch (_: org.json.JSONException) { }
        assertEquals(1, db.count()); assertEquals("before", db.meta("engine_checkpoint"))
        db.insert(event(1)); assertEquals(2, db.count()); db.close()
    }
    @Test fun sameTimestampDifferentCoordinatesRemainDistinct() {
        val db = PendingStore(context)
        db.commitSample(listOf(event(0, 1000), event(1, 1000)), "same-time")
        assertEquals(2, db.count()); assertNotEquals(EventIdentity.documentId(JSONObject(event(0, 1000))), EventIdentity.documentId(JSONObject(event(1, 1000))))
        db.close()
    }
    @Test fun v1MigrationPreservesRowsAndLegacyRemoteIdentity() {
        context.getDatabasePath("location.db").parentFile!!.mkdirs()
        val legacy = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("location.db"), null)
        legacy.execSQL("CREATE TABLE pending_events(id INTEGER PRIMARY KEY AUTOINCREMENT,json TEXT NOT NULL,created_at INTEGER NOT NULL)")
        val json = JSONObject().put("type", "visit_start").put("id", "old-visit").put("time", 42).toString()
        legacy.execSQL("INSERT INTO pending_events(json,created_at) VALUES(?,?)", arrayOf<Any>(json, 42)); legacy.version = 1; legacy.close()
        val db = PendingStore(context); assertEquals(1, db.count())
        assertEquals("visit_start-old-visit-42", EventIdentity.documentId(JSONObject(db.batch().single().json)))
        db.insert(json); assertEquals(1, db.count()); db.close()
    }
    @Test fun departureCandidateAndTripAnchorSurviveRestart() {
        val engine = VisitEngine()
        fun s(lat: Double, t: Long, moving: Boolean = true) = Sample(GeoPoint(lat, 105.0, 8.0), t, moving)
        engine.seedVisit(s(20.0, 1000, false))
        val departure = s(20.003, 61_000); engine.accept(departure)
        val restarted = VisitEngine(); JournalCheckpoint.restore(restarted, JournalCheckpoint.encode(engine, departure))
        val events = restarted.accept(s(20.006, 151_000))
        assertEquals(61_000L, events.filterIsInstance<Event.VisitEnded>().single().visit.departureMs)
        assertEquals(1, events.filterIsInstance<Event.TripPointAdded>().size)
        val afterTrip = VisitEngine(); JournalCheckpoint.restore(afterTrip, JournalCheckpoint.encode(restarted, s(20.006, 151_000)))
        assertEquals(restarted.currentTrip()?.id, afterTrip.currentTrip()?.id)
        assertTrue(afterTrip.accept(s(20.006, 181_000)).filterIsInstance<Event.TripPointAdded>().isEmpty())
    }
    @Test fun arrivalConfirmationSurvivesRestart() {
        val engine = VisitEngine(); engine.restoreTrip(Trip("trip", 1000))
        val stopped = Sample(GeoPoint(20.0, 105.0, 8.0), 60_000, false); engine.accept(stopped)
        val restarted = VisitEngine(); JournalCheckpoint.restore(restarted, JournalCheckpoint.encode(engine, stopped))
        val events = restarted.accept(stopped.copy(timeMs = 180_000))
        assertEquals(60_000L, events.filterIsInstance<Event.TripEnded>().single().trip.endMs)
        assertEquals(60_000L, events.filterIsInstance<Event.VisitStarted>().single().visit.arrivalMs)
    }
    @Test fun pendingCommandAndTerminalPayloadSurviveReopen() {
        val db = PendingStore(context); db.putMeta("refresh_pending", "123")
        db.putMeta("refresh_result", JSONObject().put("request", 123).put("fields", JSONObject().put("refreshCompletedFor", 123).put("lastLat", 20.0)).toString()); db.close()
        val reopened = PendingStore(context); assertEquals("123", reopened.meta("refresh_pending"))
        assertEquals(123L, JSONObject(reopened.meta("refresh_result")!!).getJSONObject("fields").getLong("refreshCompletedFor")); reopened.close()
    }
    @Test fun badAccuracyStaleOutOfOrderAndImpossibleJumpAreRejected() {
        fun fix(lat: Double = 20.0, time: Long = 1_000_000, accuracy: Float = 8f, elapsed: Long = 200_000_000_000) = android.location.Location("test").apply {
            latitude = lat; longitude = 105.0; this.time = time; this.accuracy = accuracy; elapsedRealtimeNanos = elapsed
        }
        val original = fix()
        assertNull(LocationPolicy.problem(original, now = 1_000_000, elapsedNow = 200_000_000_000))
        assertNotNull(LocationPolicy.problem(fix(accuracy = Float.NaN), now = 1_000_000, elapsedNow = 200_000_000_000))
        assertNotNull(LocationPolicy.problem(fix(accuracy = 151f), now = 1_000_000, elapsedNow = 200_000_000_000))
        assertNotNull(LocationPolicy.problem(fix(elapsed = 1_000_000_000), now = 1_000_000, elapsedNow = 200_000_000_000))
        assertNotNull(LocationPolicy.problem(fix(time = 900_000), lastGpsTime = 1_000_000, now = 1_000_000, elapsedNow = 200_000_000_000))
        assertNotNull(LocationPolicy.problem(fix(lat = Double.NaN), now = 1_000_000, elapsedNow = 200_000_000_000))
        assertNotNull(LocationPolicy.problem(fix(lat = 21.0, time = 1_030_000), original, 1_000_000, 1_030_000, 200_000_000_000))
    }
}
