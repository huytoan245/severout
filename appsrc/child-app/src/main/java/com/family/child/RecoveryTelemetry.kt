package com.family.child

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

internal object RecoveryTelemetry {
    private val pending=AtomicBoolean(false)
    private val names=listOf("bootObservedAt","bootRecoveryResult","recoveryAttemptAt","recoveryResult","recoveryErrorCode","serviceConfirmedAt","trackingRegisteredAt","trackingRegistered","lastTrackingCallbackAt","trackingRecoveryAt","trackingErrorCode")
    fun fields(context:Context):Map<String,Any> {
        val prefs=context.getSharedPreferences("tracking_diag",Context.MODE_PRIVATE)
        return names.mapNotNull{k->prefs.all[k]?.let{k to it}}.toMap()+mapOf("appVersion" to BuildConfig.VERSION_NAME,"appVersionCode" to BuildConfig.VERSION_CODE)
    }
    fun publish(context:Context){
        val prefs=context.getSharedPreferences("tracking_diag",Context.MODE_PRIVATE);val now=System.currentTimeMillis()
        if(now-prefs.getLong("recoveryTelemetryAttemptAt",0)<60_000 || !pending.compareAndSet(false,true))return
        prefs.edit().putLong("recoveryTelemetryAttemptAt",now).apply()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({pending.set(false)},45_000L)
        try{ChildDeviceWriter.write(SetupDiagnostics.fields(context)+fields(context)){ok->pending.set(false);if(ok)prefs.edit().putLong("recoveryTelemetryConfirmedAt",System.currentTimeMillis()).apply()}}
        catch(_:Exception){pending.set(false)}
    }
}
