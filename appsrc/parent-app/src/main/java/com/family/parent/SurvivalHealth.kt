package com.family.parent

object SurvivalHealth {
    val fields = listOf("bootObservedAt","bootRecoveryResult","recoveryAttemptAt","recoveryResult","recoveryErrorCode","serviceConfirmedAt","trackingRegisteredAt","trackingRegistered","lastTrackingCallbackAt","trackingRecoveryAt","trackingErrorCode","lastValidGpsAt","lastGpsRejectedAt","lastGpsErrorCode","refreshErrorCode","refreshStartErrorCode","refreshValidatedFor","refreshPersistedFor", "refreshUploadedFor", "syncCompletedAt","pendingEventCount", "serviceInstanceId", "serviceStartCount", "serviceRestartCount", "lastLocalServiceHeartbeatAt", "lastGpsCallbackAt",
        "lastCloudHeartbeatAttemptAt", "lastCloudHeartbeatSuccessAt", "processExitReason", "processExitImportance", "watchdogLastRunAt", "watchdogResult",
        "geofenceRegisteredAt", "geofenceTriggeredAt", "fcmTokenConfirmedAt", "fcmWakeReceivedAt", "fcmOriginalPriority", "fcmDeliveredPriority",
        "batteryOptimizationIgnored", "backgroundRestricted", "appStandbyRestricted", "unusedAppRestricted", "unusedAppRestrictionMeaning",
        "routeMode", "cellularFailoverActive", "pendingJourneyCount", "protectionChannelEnabled", "wakeNotificationEnabled", "notificationGranted","gpsSummary","lastRequestStage")
    private val labels=mapOf("lastValidGpsAt" to "GPS hợp lệ gần nhất","lastGpsCallbackAt" to "GPS callback gần nhất","lastGpsRejectedAt" to "GPS bị từ chối gần nhất","lastGpsErrorCode" to "Lỗi GPS cuối","refreshErrorCode" to "Lỗi request cuối","refreshStartErrorCode" to "Lỗi khởi chạy service","refreshPersistedFor" to "Request đã lưu local","refreshUploadedFor" to "Request đã xác nhận upload","syncCompletedAt" to "Upload thành công gần nhất","pendingEventCount" to "Bản ghi chờ upload")
    fun label(name:String)=labels[name]?:name
    fun from(data: Map<String, Any?>): Map<String, String> = fields.associateWith { name ->
        val value = data[name]
        when {
            name=="gpsSummary" -> {
                val valid=(data["lastValidGpsAt"] as? Number)?.toLong() ?: (data["locationTime"] as? Number)?.toLong() ?: 0L
                when{valid<=0->"UNKNOWN · chưa có GPS hợp lệ";System.currentTimeMillis()-valid>120_000L->"GPS cũ; heartbeat online không chứng minh GPS mới";else->"GPS hợp lệ gần đây"}
            }
            name=="lastRequestStage" -> com.family.core.RefreshTimeline.stage((data["refreshRequestedAt"] as? Number)?.toLong()?:0,data).name
            value == null -> "Chưa xác định"
            name.endsWith("At") && value is Number -> if (value.toLong() > 0L) java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(value.toLong())) else "Chưa có dữ liệu"
            name == "fcmOriginalPriority" || name == "fcmDeliveredPriority" -> when ((value as? Number)?.toInt()) { 1 -> "HIGH"; 2 -> "NORMAL"; else -> "UNKNOWN" }
            value is Boolean -> if (value) "Có" else "Không"
            else -> value.toString()
        }
    }
}
