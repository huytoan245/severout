package com.family.child

import com.family.core.*
import org.junit.Assert.*
import org.junit.Test

class ReliableGpsTest {
    private val id=1_790_000_000_000L
    @Test fun fcmReceiptIsNotServiceOrAckAndOldOkIsIgnored(){
        assertEquals(RefreshStage.FCM_RECEIVED,RefreshTimeline.stage(id,mapOf("refreshReceivedFor" to id,"refreshServiceFor" to id-1,"refreshCompletedFor" to id-1,"refreshResult" to "ok")))
        assertEquals(RefreshStage.SERVICE_STARTED,RefreshTimeline.stage(id,mapOf("refreshServiceFor" to id,"refreshAckFor" to id)))
    }
    @Test fun lateServerConfirmationAfter45SecondsCanStillFinish(){
        assertFalse(RefreshTimeline.waitingTimeout(id,id+46_000,RefreshStage.GPS_ACQUIRING))
        assertTrue(RefreshTimeline.waitingTimeout(id,id+91_000,RefreshStage.LOCAL_PERSISTED))
        val fields=mapOf("refreshCompletedFor" to id,"locationTime" to id+60_000,"lastLat" to 20.0,"lastLon" to 105.0)
        assertEquals(RefreshStage.SERVER_CONFIRMED,RefreshTimeline.stage(id,fields))
        assertNotEquals(RefreshStage.SERVER_CONFIRMED,RefreshTimeline.stage(id,fields,false))
        assertNotEquals(RefreshStage.SERVER_CONFIRMED,RefreshTimeline.stage(id,fields+mapOf("locationTime" to id-1)))
    }
    @Test fun noCallbackDeadlineCancelsOnceAndLateSuccessCannotWin(){
        lateinit var pending:Runnable;var timeouts=0
        val clock=GpsDeadline({r,delay->assertEquals(30_000L,delay);pending=r},{},{timeouts++})
        clock.arm();pending.run();pending.run();assertEquals(1,timeouts);assertFalse(clock.finish())
    }
    @Test fun callbackBeforeDeadlineRemovesTimerAndCannotBeFollowedByFailure(){
        lateinit var pending:Runnable;var removed=false;var timeouts=0
        val clock=GpsDeadline({r,_->pending=r},{removed=true},{timeouts++})
        clock.arm();assertTrue(clock.finish());pending.run();assertTrue(removed);assertEquals(0,timeouts)
    }
    @Test fun supersededAttemptCannotCompleteNewCommand(){
        val lease=AcquisitionLease();val old=lease.next();val fresh=lease.next()
        assertFalse(lease.finish(old));assertTrue(lease.current(fresh));assertTrue(lease.finish(fresh));assertFalse(lease.finish(fresh))
    }
    @Test fun firestoreFailureUsesOneHttpsFallbackAndReportsOnce(){
        var fallbacks=0;var completions=0;var success=false
        val write=ConfirmedWrite({cb->fallbacks++;cb(true);cb(false)}){completions++;success=it}
        write.primary(false);write.timeout();write.primary(true)
        assertEquals(1,fallbacks);assertEquals(1,completions);assertTrue(success)
    }
    @Test fun timeoutFallbackFailureDoesNotPretendLatePrimaryWasConfirmed(){
        val result=mutableListOf<Boolean>();val write=ConfirmedWrite({it(false)}){result+=it}
        write.timeout();write.primary(true);assertEquals(listOf(false),result)
    }
    @Test fun primarySuccessSkipsFallback(){
        var fallbacks=0;val result=mutableListOf<Boolean>();val write=ConfirmedWrite({fallbacks++}){result+=it}
        write.primary(true);write.primary(false);write.timeout();assertEquals(0,fallbacks);assertEquals(listOf(true),result)
    }
    @Test fun obsoleteCommandOrOlderGpsCannotProduceCompletion(){
        val remote=mapOf("refreshRequestedAt" to id+10,"locationTime" to id+20)
        val candidate=mapOf("refreshCompletedFor" to id,"refreshResult" to "ok","locationTime" to id+5,"lastLat" to 20.0,"lastLon" to 105.0)
        assertTrue(DeviceMutationPolicy.select(remote,candidate).isEmpty())
        assertTrue(DeviceMutationPolicy.select(remote+mapOf("refreshRequestedAt" to id),candidate).isEmpty())
        assertFalse(DeviceMutationPolicy.confirmed(remote,candidate))
    }
    @Test fun lateErrorCannotOverwriteConfirmedSuccess(){
        val remote=mapOf("refreshRequestedAt" to id,"refreshCompletedFor" to id)
        assertTrue(DeviceMutationPolicy.select(remote,mapOf("refreshFailedFor" to id,"lastError" to "timeout")).isEmpty())
    }
    @Test fun sameAuthoritativePayloadIsIdempotentButUnrelatedStateIsNot(){
        val fields=mapOf("refreshRequestedAt" to id,"refreshCompletedFor" to id,"locationTime" to id+100,"lastLat" to 20.0,"lastLon" to 105.0,"refreshResult" to "ok")
        assertTrue(DeviceMutationPolicy.confirmed(fields,fields.filterKeys{it!="refreshRequestedAt"}))
        assertFalse(DeviceMutationPolicy.confirmed(fields,mapOf("refreshCompletedFor" to id+1)))
    }
    @Test fun lostResponseThenNewerBackgroundGpsStillAcknowledgesCommittedRequestWithoutOverwritingCoordinates(){
        val candidate=mapOf("refreshCompletedFor" to id,"locationTime" to id+100,"lastLat" to 20.0,"lastLon" to 105.0)
        val remote=mapOf("refreshRequestedAt" to id,"refreshCompletedFor" to id,"refreshFixTime" to id+100,"locationTime" to id+200,"lastLat" to 20.1,"lastLon" to 105.1)
        assertTrue(DeviceMutationPolicy.confirmed(remote,candidate))
        assertFalse(DeviceMutationPolicy.confirmed(remote+mapOf("refreshRequestedAt" to id+1),candidate))
    }
    @Test fun newerAuthoritativeBackgroundFixCanFinishRefreshWithoutOverwritingItsCoordinates(){
        val remote=mapOf("refreshRequestedAt" to id,"locationTime" to id+200,"lastLat" to 20.1,"lastLon" to 105.1)
        val candidate=mapOf("refreshCompletedFor" to id,"locationTime" to id+100,"lastLat" to 20.0,"lastLon" to 105.0)
        val selected=DeviceMutationPolicy.select(remote,candidate)
        assertFalse(selected.containsKey("lastLat"));assertFalse(selected.containsKey("locationTime"));assertEquals(id,selected["refreshCompletedFor"]);assertEquals(id+200,selected["refreshFixTime"])
    }
    @Test fun samplingSwitchesMovementAndStopsWithoutDroppingRawFixes(){
        assertTrue(JourneyPolicy.moving(1.2f,0f));assertTrue(JourneyPolicy.moving(null,26f));assertFalse(JourneyPolicy.moving(0f,1f))
        assertEquals(30_000L,JourneyPolicy.interval(true));assertEquals(120_000L,JourneyPolicy.interval(false))
        val engine=VisitEngine(EngineConfig(sameAreaM=60.0,leaveConfirmMs=30_000))
        engine.seedVisit(Sample(GeoPoint(20.0,105.0,5.0),id))
        engine.accept(Sample(GeoPoint(20.0001,105.0,5.0),id+30_000,true))
        assertTrue(engine.accept(Sample(GeoPoint(20.0004,105.0,5.0),id+60_000,true)).any{it is Event.TripStarted})
    }
    @Test fun jitterGapsAndImpossibleJumpsDoNotCreateDistance(){
        val points=listOf(HistoryPoint("a",id,20.0,105.0,20.0),HistoryPoint("b",id+30_000,20.00001,105.0,20.0),HistoryPoint("c",id+600_000,21.0,105.0,20.0),HistoryPoint("d",id+630_000,23.0,105.0,20.0))
        val metrics=JourneyPolicy.metrics(points+points);assertEquals(0.0,metrics.distance,0.01);assertEquals(2,metrics.gaps)
        assertEquals(3,JourneyPolicy.segments(points).size)
    }
    @Test fun recoveryDoesNotPretendBackgroundPermissionWasGranted(){
        assertEquals("background_permission_missing",RecoveryPolicy.blockedReason(true,false,true,false))
        assertNull(RecoveryPolicy.blockedReason(true,false,true,false,true))
    }
}
