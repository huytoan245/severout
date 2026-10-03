package com.family.child

import android.content.Context
import android.os.Bundle
import com.family.core.*
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CleanBaselineTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase("location.db")
        context.getSharedPreferences("tracking_diag",Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun inboxIsDurableIdempotentAndOrderedAcrossReopen() {
        PendingStore(context).useStore { assertTrue(it.acceptWake(100)); assertFalse(it.acceptWake(100)); assertFalse(it.acceptWake(99)) }
        PendingStore(context).useStore { assertEquals("100",it.meta("refresh_pending")); assertFalse(it.acceptWake(100)); assertTrue(it.acceptWake(101)) }
    }
    @Test fun oldTerminalCannotClearNewerPendingOrReplaceResult() {
        PendingStore(context).useStore {
            it.acceptWake(200); it.finishRefresh(100,"{\"request\":100}")
            assertEquals("200",it.meta("refresh_pending"))
            it.finishRefresh(200,"{\"request\":200}"); it.finishRefresh(100,"{\"request\":100}")
            assertEquals(200L,JSONObject(it.meta("refresh_result")!!).getLong("request")); assertEquals("0",it.meta("refresh_pending"))
        }
    }
    @Test fun fiveThousandAndOneSamplesPersistOfflineAndDrainInOrderAfterRestart() {
        var db = PendingStore(context); var engine = VisitEngine(); engine.restoreTrip(Trip("clean-trip",1))
        repeat(5001) { i ->
            val sample = Sample(GeoPoint(20.0+i*0.0006,105.0,8.0),1_000L+i*60_000L,true)
            engine.accept(sample); assertTrue(engine.currentTrip()!!.points.size <= 1)
            db.commitSample(listOf(JSONObject().put("type","location_sample").put("id",EventIdentity.sampleId(sample.timeMs,sample.point.lat,105.0)).put("time",sample.timeMs).toString()),JournalCheckpoint.encode(engine,sample))
            if (i==2500) { db.close(); db=PendingStore(context); val restored=VisitEngine(); JournalCheckpoint.restore(restored,db.meta("engine_checkpoint")!!); assertEquals("clean-trip",restored.currentTrip()!!.id); engine=restored }
        }
        db.close(); db=PendingStore(context); assertEquals(5001,db.count())
        var previous = 0L; var drained = 0
        while (db.count()>0) {
            val batch=db.batch(128); assertTrue(batch.size<=128)
            batch.forEach { val t=JSONObject(it.json).getLong("time"); assertTrue(t>previous); previous=t; db.delete(it.localId); drained++ }
        }
        assertEquals(5001,drained); db.close()
    }
    @Test fun rebaseAbovePreviousInstallDoesNotOverwriteConcurrentRotation() {
        val first=TokenState.observe(context,"new-install")
        val rebased=TokenState.rebase(context,first,first.generation+500)
        assertEquals(first.generation+501,rebased.generation)
        val rotation=TokenState.observe(context,"rotated")
        assertEquals(rotation,TokenState.rebase(context,rebased,rotation.generation+1000))
        assertFalse(TokenState.confirm(context,rebased)); assertTrue(TokenState.confirm(context,rotation))
    }
    private fun data(id: Long)=mapOf("type" to "location_refresh","deviceId" to "child-01","requestId" to "$id","requestedAt" to "$id","expiresAt" to "${id+WakeRequestPolicy.TTL}")
    @Test fun malformedExpiredFutureAndWrongDeviceFcmAreRejected() {
        val now=2_000_000L
        assertEquals(now,WakeRequestPolicy.parse(data(now),now))
        assertNull(WakeRequestPolicy.parse(data(now)+( "deviceId" to "child-02"),now))
        assertNull(WakeRequestPolicy.parse(data(now)+( "requestId" to "garbage"),now))
        assertNull(WakeRequestPolicy.parse(data(now)+( "expiresAt" to "1"),now))
        assertNull(WakeRequestPolicy.parse(data(now-WakeRequestPolicy.TTL),now))
        assertNull(WakeRequestPolicy.parse(data(now+30_001),now))
    }
    @Test fun actualMessagingServiceRejectsMalformedBeforeInboxOrSideEffects() {
        val service=Robolectric.buildService(ChildWakeMessagingService::class.java).create().get()
        service.onMessageReceived(RemoteMessage(Bundle().apply { putString("type","location_refresh"); putString("requestId","invalid") }))
        assertEquals("invalid_or_expired",service.getSharedPreferences("tracking_diag",Context.MODE_PRIVATE).getString("fcm_wake_start_result",null))
        PendingStore(context).useStore { assertNull(it.meta("refresh_pending")); assertEquals(0,it.count()) }
    }
    @Test fun staleRequestAndLocationCannotRegressNewerEvidence() {
        val remote=mapOf<String,Any?>("refreshRequestedAt" to 200L,"refreshCompletedFor" to 200L,"locationTime" to 300L)
        val old=mapOf<String,Any?>("refreshCompletedFor" to 100L,"refreshResult" to "ok","lastError" to "old","locationTime" to 250L,"lastLat" to 20.0)
        val selected=DeviceMutationPolicy.select(remote,old)
        assertFalse(selected.containsKey("refreshCompletedFor")); assertFalse(selected.containsKey("lastLat")); assertFalse(selected.containsKey("lastError"))
        assertFalse(DeviceMutationPolicy.select(remote,mapOf("refreshAckFor" to 200L,"refreshResult" to "locating")).containsKey("refreshResult"))
    }
    @Test fun matchingNewResultPassesGuard() {
        val next=mapOf<String,Any?>("refreshCompletedFor" to 200L,"refreshResult" to "ok","locationTime" to 300L,"lastLat" to 20.0)
        assertEquals(next,DeviceMutationPolicy.select(mapOf("refreshRequestedAt" to 200L,"locationTime" to 299L),next))
    }
    @Test fun locationOffAndMissingPermissionStopRecovery() {
        assertEquals(false,SetupDiagnostics.fields(context)["fineLocationGranted"])
        assertEquals("location_off",RecoveryPolicy.blockedReason(true,true,false,false))
        assertEquals("permission_missing",RecoveryPolicy.blockedReason(false,true,true,false))
        assertEquals("background_permission_missing",RecoveryPolicy.blockedReason(true,false,true,false))
        assertEquals("background_restricted",RecoveryPolicy.blockedReason(true,true,true,true))
        assertNull(RecoveryPolicy.blockedReason(true,true,true,false))
        assertFalse(RecoveryStarter.startLocationService(context,"test_permission_missing"))
        assertEquals("blocked:permission_missing",context.getSharedPreferences("tracking_diag",Context.MODE_PRIVATE).getString("recovery_start_result_v229",null))
    }
    @Test fun tokenUploadDiagnosticsRetainFirestoreTypes() {
        val fields=SetupDiagnostics.fields(context)+mapOf("fcmTokenGeneration" to 123L,"fcmTokenOwnerUid" to "fixture-child")
        val encoded=BoundedRest.encode(fields)
        assertEquals(false,encoded.getJSONObject("fields").getJSONObject("fineLocationGranted").getBoolean("booleanValue"))
        assertEquals("123",encoded.getJSONObject("fields").getJSONObject("fcmTokenGeneration").getString("integerValue"))
        assertEquals(fields,BoundedRest.decode(encoded))
    }
}
