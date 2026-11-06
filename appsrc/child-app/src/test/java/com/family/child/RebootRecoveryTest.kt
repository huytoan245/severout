package com.family.child

import com.family.core.*
import org.junit.Assert.*
import org.junit.Test

class RebootRecoveryTest {
    private val ready=RecoveryConditions(true,true,true,false,false,false,36)
    @Test fun immediateBootAllowsBatteryNotExemptButDelayedWorkerDoesNotInheritExemption(){
        assertNull(RecoveryV233.blocked(RecoverySource.BOOT,ready))
        assertEquals("waiting_allowed_start_event",RecoveryV233.blocked(RecoverySource.WATCHDOG,ready))
    }
    @Test fun bootWithoutBackgroundPermissionReportsExactBlock(){
        assertEquals("background_permission_missing",RecoveryV233.blocked(RecoverySource.BOOT,ready.copy(background=false)))
        assertNull(RecoveryV233.blocked(RecoverySource.APP_VISIBLE,ready.copy(background=false)))
    }
    @Test fun notificationPermissionIsNotLocationFgsPrerequisite(){
        // Notifications may remain denied; no notification grant is inferred.
        assertNull(RecoveryV233.blocked(RecoverySource.BOOT,ready))
        assertEquals("permission_missing",RecoveryV233.blocked(RecoverySource.BOOT,ready.copy(fine=false)))
        assertEquals("location_off",RecoveryV233.blocked(RecoverySource.BOOT,ready.copy(locationOn=false)))
    }
    @Test fun onlyDeliveredHighFcmAndGeofenceHaveImmediateExemptions(){
        assertEquals(RecoverySource.FCM_HIGH,RecoveryV233.source("fcm_high"))
        assertEquals(RecoverySource.WATCHDOG,RecoveryV233.source("fcm_duplicate_pending"))
        assertNull(RecoveryV233.blocked(RecoverySource.GEOFENCE,ready))
        assertNull(RecoveryV233.blocked(RecoverySource.FCM_HIGH,ready))
    }
    @Test fun startRequestIsNotServiceOrSubscriptionConfirmation(){
        assertFalse(RecoveryV233.verified(false,1000,true,1100))
        assertFalse(RecoveryV233.verified(true,1000,false,1100))
        assertFalse(RecoveryV233.verified(true,1000,true,200000))
        assertTrue(RecoveryV233.verified(true,1000,true,1100))
    }
    @Test fun androidRejectionRetriesAreBoundedAndMissingPermissionDoesNotLoop(){
        assertTrue(RecoveryV233.retry(0,"fgs_start_not_allowed"))
        assertFalse(RecoveryV233.retry(4,"fgs_start_not_allowed"))
        assertFalse(RecoveryV233.retry(0,"background_permission_missing"))
    }
    @Test fun staleContinuousSubscriptionIsIndependentOfOneShotGpsAndHasCooldown(){
        assertTrue(RecoveryV233.trackingStale(1_000_000,100_000,200_000,0))
        assertFalse(RecoveryV233.trackingStale(1_000_000,100_000,200_000,900_000))
        assertFalse(RecoveryV233.trackingStale(1_000_000,990_000,200_000,0))
        assertFalse(RecoveryV233.trackingStale(1_000_000,0,200_000,0))
    }
    @Test fun freshContactCannotMaskDaysOldGpsOrService(){
        val now=1_790_000_000_000L
        val state=LivenessPolicy.assess(now,now-1000,now-5*86400000L,now-5*86400000L)
        assertTrue(state.contactFresh);assertFalse(state.serviceFresh);assertFalse(state.gpsFresh)
        assertFalse(LivenessPolicy.assess(now,now+1000,0,0).contactFresh)
    }
}
