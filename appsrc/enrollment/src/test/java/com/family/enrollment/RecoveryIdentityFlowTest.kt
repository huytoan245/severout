package com.family.enrollment

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RecoveryIdentityFlowTest {
    private class Fixture {
        var state=RecoveryIdentityRecord();var current:String?="old";var key:String?=null;var authCreates=0;var keyCreates=0
        var loseAuthResponse=false;var authNeverCompletes=false;var loseKeyResponse=false;var refuseSave=false
        fun flow()=RecoveryIdentityFlow({state},{check(!refuseSave);state=it},{current},{key!=null},{key!!},{
            authCreates++;current=null
            if(!authNeverCompletes)current="fresh"
            if(loseAuthResponse||authNeverCompletes)error("timeout")
        },{keyCreates++;key="fresh-key";if(loseKeyResponse)error("process-death");key!!})
        fun run()=flow().resume("old",RecoveryEnvelope.hash("old"))
    }
    @Test fun uidStillPresentAndKeyMissingCreatesOnlyAfterDurableIntent(){
        val f=Fixture();assertEquals("fresh" to "fresh-key",f.run());assertEquals(1,f.authCreates);assertEquals(1,f.keyCreates)
        assertEquals(f.run(),f.run());assertEquals(1,f.authCreates);assertEquals(1,f.keyCreates)
    }
    @Test fun lostAuthResponseResumesSameSdkUidAcrossProcessInstance(){
        val f=Fixture();f.loseAuthResponse=true;assertTrue(runCatching{f.run()}.isFailure);assertEquals("fresh",f.current)
        assertEquals("fresh",f.run().first);assertEquals(1,f.authCreates)
    }
    @Test fun ambiguousAuthTimeoutNeverCreatesAnotherUser(){
        val f=Fixture();f.authNeverCompletes=true;assertTrue(runCatching{f.run()}.isFailure)
        repeat(4){assertTrue(runCatching{f.run()}.isFailure)};assertEquals(1,f.authCreates);assertEquals(0,f.keyCreates)
    }
    @Test fun lostKeyResponseReusesExistingAliasWithoutRegeneration(){
        val f=Fixture();f.loseKeyResponse=true;assertTrue(runCatching{f.run()}.isFailure)
        assertEquals("fresh-key",f.run().second);assertEquals(1,f.authCreates);assertEquals(1,f.keyCreates)
    }
    @Test fun existingKeyOrChangedAuthBeforeStartIsPreserved(){
        val f=Fixture();f.key="old-key";assertTrue(runCatching{f.run()}.isFailure);assertEquals(0,f.authCreates);assertEquals("old-key",f.key)
        f.key=null;f.current="different";assertTrue(runCatching{f.run()}.isFailure);assertEquals(0,f.authCreates)
    }
    @Test fun failedCheckpointPreventsSdkSideEffects(){
        val f=Fixture();f.refuseSave=true;assertTrue(runCatching{f.run()}.isFailure);assertEquals(0,f.authCreates);assertEquals(0,f.keyCreates)
    }
    @Test fun missingOrChangedPreparedIdentityCannotRotateAgain(){
        val f=Fixture();f.run();f.current="other";assertTrue(runCatching{f.run()}.isFailure);assertEquals(1,f.authCreates)
        f.current="fresh";f.key=null;assertTrue(runCatching{f.run()}.isFailure);assertEquals(1,f.keyCreates)
    }
    @Test fun genuinelyAbsentUidIsNotAssumedToBeTheOldParent(){
        val f=Fixture();f.current=null
        assertEquals("fresh",f.flow().resume(null,RecoveryEnvelope.hash("old")).first);assertEquals(1,f.authCreates)
    }
}
