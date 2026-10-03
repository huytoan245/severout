package com.family.child

import android.location.Location
import android.os.SystemClock

object LocationPolicy {
    fun problem(location: Location, previous: Location? = null, lastGpsTime: Long = 0L,
                now: Long = System.currentTimeMillis(), elapsedNow: Long = SystemClock.elapsedRealtimeNanos()): String? {
        if (!location.latitude.isFinite() || !location.longitude.isFinite() || location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0 || (location.latitude == 0.0 && location.longitude == 0.0)) return "Tọa độ không hợp lệ"
        if (!location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy < 0f || location.accuracy > 150f) return "Độ chính xác GPS không hợp lệ"
        if (location.time <= 0L || location.time > now + 60_000L || location.time < lastGpsTime) return "Timestamp GPS không hợp lệ"
        val age = (elapsedNow - location.elapsedRealtimeNanos) / 1_000_000L
        if (age > 120_000L || age < -60_000L) return "Tọa độ GPS quá cũ hoặc thời gian không hợp lệ"
        if (previous != null) {
            val seconds = ((location.time - previous.time) / 1000.0).coerceAtLeast(1.0)
            val distance = previous.distanceTo(location)
            if (distance > 1_500 && seconds < 600 && distance / seconds * 3.6 > 250 && location.accuracy >= previous.accuracy * 0.75f) return "Tọa độ mới không đáng tin cậy"
        }
        return null
    }
}
