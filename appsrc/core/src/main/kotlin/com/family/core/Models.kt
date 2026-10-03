package com.family.core

data class GeoPoint(val lat: Double, val lon: Double, val accuracyM: Double = 0.0)
data class Sample(val point: GeoPoint, val timeMs: Long, val moving: Boolean = false)
data class Visit(val id: String, val center: GeoPoint, val arrivalMs: Long, var departureMs: Long? = null, var label: String? = null)
data class Trip(val id: String, val startMs: Long, var endMs: Long? = null, val points: MutableList<Sample> = mutableListOf())

data class EngineConfig(
    val jitterIgnoreM: Double = 50.0,
    val sameAreaM: Double = 150.0,
    val leaveConfirmM: Double = 220.0,
    val leaveConfirmMs: Long = 90_000L,
    val arrivalRadiusM: Double = 120.0,
    val arrivalConfirmMs: Long = 120_000L,
    val minTripPointDistanceM: Double = 180.0,
)

sealed interface Event {
    data class VisitStarted(val visit: Visit): Event
    data class VisitEnded(val visit: Visit): Event
    data class TripStarted(val trip: Trip): Event
    data class TripPointAdded(val trip: Trip, val sample: Sample): Event
    data class TripEnded(val trip: Trip): Event
}
