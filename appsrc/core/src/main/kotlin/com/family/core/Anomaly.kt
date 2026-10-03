package com.family.core

data class KnownPlace(val name: String, val point: GeoPoint, val radiusM: Double)
data class ScheduleWindow(val placeName: String, val startMinute: Int, val endMinute: Int)
data class AnomalyConfig(val unfamiliarStopMs: Long = 15*60_000L, val longStopMs: Long = 30*60_000L, val farFromKnownM: Double = 5_000.0)

data class VisitFlags(
    val unfamiliar: Boolean,
    val longUnfamiliarStop: Boolean,
    val farFromKnown: Boolean,
)

object AnomalyDetector {
    fun flags(v: Visit, known: List<KnownPlace>, nowMs: Long, cfg: AnomalyConfig = AnomalyConfig()): VisitFlags {
        val end = v.departureMs ?: nowMs
        val duration = (end - v.arrivalMs).coerceAtLeast(0)
        val distances = known.map { Geo.distanceM(v.center, it.point) - it.radiusM }
        val nearest = distances.minOrNull() ?: Double.POSITIVE_INFINITY
        val unfamiliar = known.none { Geo.distanceM(v.center, it.point) <= it.radiusM }
        return VisitFlags(
            unfamiliar = unfamiliar && duration >= cfg.unfamiliarStopMs,
            longUnfamiliarStop = unfamiliar && duration >= cfg.longStopMs,
            farFromKnown = nearest > cfg.farFromKnownM,
        )
    }
}
