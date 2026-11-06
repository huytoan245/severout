package com.family.enrollment

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RecoveryModeTest {
    private fun context(name:String="com.family.parent",recovery:Boolean=true):Context {
        val app=RuntimeEnvironment.getApplication()
        val info=PackageInfo().apply{packageName=name;applicationInfo=ApplicationInfo().apply{packageName=name;metaData=Bundle().apply{putBoolean("family.parent_recovery",recovery);putBoolean("family.update_in_place",true)}}}
        shadowOf(app.packageManager).installPackage(info)
        app.getSharedPreferences("parent_recovery_v233",Context.MODE_PRIVATE).edit().clear().commit()
        return object:ContextWrapper(app){override fun getPackageName()=name}
    }
    @Test fun recoveryStartupBlocksEnrollmentBeforeFirebaseOrKeystoreAccess(){
        val c=context()
        val e=runCatching{EnrollmentClient.ensureRegistered(c,"https://unused","parent","")}.exceptionOrNull()
        assertTrue(e is EnrollmentFailure);assertEquals("recovery_inspection_only",(e as EnrollmentFailure).code)
        assertFalse(c.getSharedPreferences("parent_recovery_v233",Context.MODE_PRIVATE).contains("phase"))
    }
    @Test fun genericSignedRebindAndWakeCannotBypassApproval(){
        val c=context()
        for(purpose in listOf("register","rebind","wake")){
            val e=runCatching{EnrollmentClient.signed(c,"https://unused","parent",purpose,JSONObject())}.exceptionOrNull()
            assertEquals("recovery_inspection_only",(e as EnrollmentFailure).code)
        }
    }
    @Test fun childAndNormalParentAreNotRecoveryMode(){
        assertFalse(EnrollmentMode.recovery(context("com.family.child")))
        assertFalse(EnrollmentMode.recovery(context(recovery=false)))
    }
    @Test fun completionOnlyReenablesExistingIdentityUpdateMode(){
        val c=context();assertTrue(EnrollmentMode.inspectionOnly(c))
        c.getSharedPreferences("parent_recovery_v233",Context.MODE_PRIVATE).edit().putBoolean("completed",true).commit()
        assertFalse(EnrollmentMode.inspectionOnly(c));assertTrue(EnrollmentMode.updateOnly(c))
    }
}
