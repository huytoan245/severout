package com.family.child

import android.content.Context
import com.family.core.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SurvivalReliabilityTest {
    private lateinit var context: Context
    @Before fun setup() { context = RuntimeEnvironment.getApplication(); context.getSharedPreferences("tracking_diag", Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun oldWorkerCannotReplaceOrConfirmRotatedToken() {
        val old = TokenState.observe(context, "old")
        val rotated = TokenState.observe(context, "new")
        assertEquals(rotated, TokenState.observe(context, "delayed-sdk-old", old.generation))
        assertFalse(TokenState.confirm(context, old)); assertTrue(TokenState.confirm(context, rotated))
        assertEquals(rotated, TokenState.current(context))
    }
    @Test fun generationPersistsAndDuplicateDoesNotAdvanceIt() {
        val first = TokenState.observe(context, "token")
        assertEquals(first, TokenState.observe(context, "token")); assertEquals(first, TokenState.current(context))
        val next = TokenState.observe(context, "rotated"); assertTrue(next.generation > first.generation)
        assertEquals(0L, context.getSharedPreferences("tracking_diag", Context.MODE_PRIVATE).getLong("fcm_token_cloud_at", -1L))
    }
    @Test fun rapidConcurrentRotationRetainsOneCurrentGeneration() {
        val threads = (1..50).map { id -> Thread { TokenState.observe(context, "token-$id") }.apply { start() } }
        threads.forEach { it.join() }
        val latest = TokenState.current(context); assertTrue(latest.generation > 0); assertTrue(latest.token.startsWith("token-"))
        assertTrue(TokenState.confirm(context, latest))
    }
    @Test fun longTripKeepsHistoryEventsWithBoundedEngineMemory() {
        val engine = VisitEngine(); engine.restoreTrip(Trip("long-trip", 1000))
        val all = mutableListOf<Event.TripPointAdded>()
        repeat(5000) { index ->
            val sample = Sample(GeoPoint(20.0 + index * 0.002, 105.0, 8.0), 1000L + index * 60_000L, true)
            all += engine.accept(sample).filterIsInstance<Event.TripPointAdded>()
            assertTrue(engine.currentTrip()!!.points.size <= 1)
        }
        assertEquals(5000, all.size); assertEquals(5000, all.map { it.sample.timeMs }.toSet().size)
        val restored = VisitEngine(); JournalCheckpoint.restore(restored, JournalCheckpoint.encode(engine, all.last().sample))
        assertEquals("long-trip", restored.currentTrip()!!.id)
        assertTrue(restored.accept(all.last().sample).filterIsInstance<Event.TripPointAdded>().isEmpty())
    }
}
