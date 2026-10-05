package com.family.parent

object SurvivalHealth {
    val fields = listOf("refreshPersistedFor", "refreshUploadedFor", "serviceInstanceId", "serviceStartCount", "serviceRestartCount", "lastLocalServiceHeartbeatAt", "lastGpsCallbackAt",
        "lastCloudHeartbeatAttemptAt", "lastCloudHeartbeatSuccessAt", "processExitReason", "processExitImportance", "watchdogLastRunAt", "watchdogResult",
        "geofenceRegisteredAt", "geofenceTriggeredAt", "fcmTokenConfirmedAt", "fcmWakeReceivedAt", "fcmOriginalPriority", "fcmDeliveredPriority",
        "batteryOptimizationIgnored", "backgroundRestricted", "appStandbyRestricted", "unusedAppRestricted", "unusedAppRestrictionMeaning",
        "routeMode", "cellularFailoverActive", "pendingJourneyCount", "protectionChannelEnabled", "wakeNotificationEnabled", "notificationGranted", "fcmTokenOwnerUid")
    fun from(data: Map<String, Any?>): Map<String, String> = fields.associateWith { name ->
        val value = data[name]
        when {
            value == null -> "Chưa xác định"
            name.endsWith("At") && value is Number -> if (value.toLong() > 0L) java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(value.toLong())) else "Chưa có dữ liệu"
            name == "fcmOriginalPriority" || name == "fcmDeliveredPriority" -> when ((value as? Number)?.toInt()) { 1 -> "HIGH"; 2 -> "NORMAL"; else -> "UNKNOWN" }
            value is Boolean -> if (value) "Có" else "Không"
            else -> value.toString()
        }
    }
}
