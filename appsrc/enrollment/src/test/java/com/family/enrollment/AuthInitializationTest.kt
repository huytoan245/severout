package com.family.enrollment

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class AuthInitializationTest {
    @Test fun restoredUserWaitsForSdkAndRemovesListener() {
        val current=AtomicReference<String?>();var removed=false
        val user=AuthInitialization.await({current.get()},{changed->
            Thread {Thread.sleep(20);current.set("existing-identity");changed()}.start()
            val remove:()->Unit={removed=true};remove
        },1000)
        assertEquals("existing-identity",user);assertTrue(removed)
    }
    @Test fun nullTimeoutDoesNotInventIdentity() {
        var removed=false
        assertNull(AuthInitialization.await<String>({null},{ {removed=true} },5))
        assertTrue(removed)
    }
    @Test fun existingIdentityDoesNotSubscribe() {
        assertEquals("existing",AuthInitialization.await({"existing"},{error("unexpected subscription")},5))
    }
    @Test fun synchronousSdkCallbackIsNotLost() {
        var value:String?=null
        assertEquals("restored",AuthInitialization.await({value},{changed->value="restored";changed();{}},5))
    }
    @Test fun protocol231RequiresProofMigrationAnd232RequiresExactUidAndKey() {
        assertFalse(EnrollmentStartup.cacheMatches(231,"same","same","key","key"))
        assertTrue(EnrollmentStartup.cacheMatches(232,"same","same","key","key"))
        assertFalse(EnrollmentStartup.cacheMatches(232,"old","new","key","key"))
        assertFalse(EnrollmentStartup.cacheMatches(232,"same","same","old","new"))
    }
    @Test fun lostKeyNeverRetriesIntoRecoveryAndAuthTimeoutIsBounded() {
        assertTrue(EnrollmentStartup.recovery("device_key_missing"))
        assertFalse(EnrollmentStartup.retry("device_key_missing",0))
        assertTrue(EnrollmentStartup.retry("auth_init_timeout",0))
        assertFalse(EnrollmentStartup.retry("auth_init_timeout",4))
    }
    @Test fun offlineThenOnlineHasActionableStates() {
        assertEquals("Chưa kết nối Internet",EnrollmentStartup.message("auth_initializing",false))
        assertEquals("Đang xác thực Máy Cha",EnrollmentStartup.message("auth_initializing",true))
        assertEquals("Lỗi kết nối Firebase",EnrollmentStartup.message("firebase_connection_error",true))
        assertEquals("Cần khôi phục định danh",EnrollmentStartup.message("device_key_missing",true))
    }
}
