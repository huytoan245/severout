package com.family.core

enum class RecoverySource { BOOT, PACKAGE_REPLACED, APP_VISIBLE, FCM_HIGH, GEOFENCE, WATCHDOG }
data class RecoveryConditions(val fine:Boolean,val background:Boolean,val locationOn:Boolean,val restricted:Boolean,val batteryExempt:Boolean,val foregroundRunning:Boolean,val api:Int)
object RecoveryV233 {
    fun source(reason:String)=when{
        reason=="boot"->RecoverySource.BOOT
        reason=="package_replace"->RecoverySource.PACKAGE_REPLACED
        reason=="app_visible"->RecoverySource.APP_VISIBLE
        reason=="fcm_high"->RecoverySource.FCM_HIGH
        reason in setOf("geofence_live","geofence_exit")->RecoverySource.GEOFENCE
        else->RecoverySource.WATCHDOG
    }
    fun blocked(s:RecoverySource,c:RecoveryConditions):String?=when{
        !c.fine->"permission_missing"
        !c.locationOn->"location_off"
        !c.background && c.api>=29 && !c.foregroundRunning && s!=RecoverySource.APP_VISIBLE->"background_permission_missing"
        c.restricted && !c.foregroundRunning && s!=RecoverySource.APP_VISIBLE->"background_restricted"
        c.api>=31 && !c.foregroundRunning && !c.batteryExempt && s==RecoverySource.WATCHDOG->"waiting_allowed_start_event"
        else->null
    }
    fun trackingStale(now:Long,registeredAt:Long,callbackAt:Long,lastReset:Long)=registeredAt>0 && now-maxOf(registeredAt,callbackAt)>=6*60_000 && now-lastReset>=5*60_000
    fun verified(running:Boolean,heartbeat:Long,registered:Boolean,now:Long)=running && registered && now-heartbeat in 0L..150_000
    fun retry(attempt:Int,code:String)=attempt<4 && code !in setOf("permission_missing","background_permission_missing","location_off","background_restricted","waiting_allowed_start_event")
}

data class ChildLiveness(val contactFresh:Boolean,val serviceFresh:Boolean,val gpsFresh:Boolean)
object LivenessPolicy {
    fun assess(now:Long,contact:Long,service:Long,gps:Long)=ChildLiveness(contact>0 && now-contact in 0L..10*60_000,service>0 && now-service in 0L..150_000,gps>0 && now-gps in 0L..10*60_000)
}
