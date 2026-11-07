package com.family.enrollment

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Wait on the SDK callback on a worker thread; a transient null never creates an identity. */
internal object AuthInitialization {
    fun <T:Any> await(current:()->T?, subscribe:((()->Unit))->(()->Unit), timeoutMs:Long=10_000):T? {
        current()?.let{return it}
        val ready=CountDownLatch(1);val value=AtomicReference<T?>()
        val remove=subscribe { current()?.let{value.set(it);ready.countDown()} }
        return try{if(ready.await(timeoutMs,TimeUnit.MILLISECONDS))value.get() else current()}finally{remove()}
    }
}

object EnrollmentStartup {
    private val recoveryCodes=setOf("identity_missing","device_key_missing","slot_occupied","role_conflict","retired_identity","role_not_registered","fresh_key_required","fresh_identity_required","operator_recovery_required")
    fun recovery(code:String)=code in recoveryCodes
    fun retry(code:String,attempt:Int)=!recovery(code) && attempt<4
    fun cacheMatches(protocol:Int,cachedUid:String?,uid:String,cachedKey:String?,key:String)=protocol==232 && cachedUid==uid && cachedKey==key
    fun message(code:String,online:Boolean,state:String=""):String=when{
        !online -> "Chưa kết nối Internet"
        recovery(code) || state=="RECOVERY_REQUIRED" -> "Cần khôi phục định danh"
        code in setOf("firebase_connection_error","network_error","upstream_unavailable","auth_keys_unavailable") -> "Lỗi kết nối Firebase"
        code=="auth_init_timeout" -> "Chưa đọc được định danh đã lưu · thử xác thực lại"
        state=="RETRY_EXHAUSTED" -> "Không thể xác thực Máy Cha · thử lại khi có kết nối"
        else -> "Đang xác thực Máy Cha"
    }
}
