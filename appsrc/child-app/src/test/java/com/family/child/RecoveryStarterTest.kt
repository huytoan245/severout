package com.family.child

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ComponentName
import android.location.LocationManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class RecoveryStarterTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val prefs get()=app.getSharedPreferences("tracking_diag",Context.MODE_PRIVATE)
    @Before fun setup(){
        prefs.edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
    }
    @Test fun missingPermissionIsPersistedAndCannotClaimRecovery(){
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertFalse(RecoveryStarter.startLocationService(app,"boot"))
        assertEquals("permission_missing",prefs.getString("recoveryErrorCode",""))
        assertEquals("blocked",prefs.getString("recoveryResult",""))
    }
    @Test fun androidRejectionIsPersistedWithoutLoggingExceptionContents(){
        val rejecting=object:ContextWrapper(app){
            override fun getApplicationContext():Context=this
            override fun startForegroundService(intent:Intent):ComponentName?=throw SecurityException("do not expose message")
        }
        assertFalse(RecoveryStarter.startLocationService(rejecting,"boot"))
        assertEquals("fgs_security_exception",prefs.getString("recoveryErrorCode",""))
        assertEquals("rejected",prefs.getString("recoveryResult",""))
    }
    @Test fun acceptedStartIsOnlyARequestUntilServiceAndSubscriptionAreConfirmed(){
        assertTrue(RecoveryStarter.startLocationService(app,"boot"))
        assertEquals("start_requested",prefs.getString("recoveryResult",""))
        assertFalse(prefs.getBoolean("trackingRegistered",false))
    }
    @Test @Config(sdk=[35]) fun bootRequiresBackgroundPermissionOnModernAndroid(){
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION,Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(RecoveryStarter.startLocationService(app,"boot"))
        assertEquals("background_permission_missing",prefs.getString("recoveryErrorCode",""))
    }
    @Test @Config(sdk=[35]) fun notificationDeniedDoesNotInventLocationPermissionFailure(){
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(RecoveryStarter.startLocationService(app,"boot"))
        assertEquals("start_requested",prefs.getString("recoveryResult",""))
    }
    @Test @Config(sdk=[35]) fun androidBackgroundStartRejectionHasExactCode(){
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val rejecting=object:ContextWrapper(app){
            override fun getApplicationContext():Context=this
            override fun startForegroundService(intent:Intent):ComponentName?=throw android.app.ForegroundServiceStartNotAllowedException("private message")
        }
        assertFalse(RecoveryStarter.startLocationService(rejecting,"boot"))
        assertEquals("fgs_start_not_allowed",prefs.getString("recoveryErrorCode",""))
    }
}
