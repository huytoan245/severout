package com.family.core

import java.util.UUID

/**
 * Battery-oriented state machine. Raw samples are transient; durable history is Visit/Trip.
 * It requires sustained movement before closing a visit, which rejects single GPS jumps.
 */
class VisitEngine(private val cfg: EngineConfig = EngineConfig()) {
    private var visit: Visit? = null
    private var trip: Trip? = null
    private var departureCandidateSince: Long? = null
    private var arrivalCandidate: Sample? = null
    private var lastTripPersisted: Sample? = null

    fun snapshot() = EngineSnapshot(visit?.copy(), trip?.copy(points = mutableListOf()), departureCandidateSince, arrivalCandidate, lastTripPersisted)
    fun restoreSnapshot(s: EngineSnapshot) {
        visit = s.visit?.copy(); trip = s.trip?.copy(points = mutableListOf())
        departureCandidateSince = s.departureCandidateSince; arrivalCandidate = s.arrivalCandidate; lastTripPersisted = s.lastTripPersisted
    }
    fun currentVisit(): Visit? = visit
    fun currentTrip(): Trip? = trip

    fun restoreVisit(v: Visit) { visit = v; trip = null; departureCandidateSince = null; arrivalCandidate = null; lastTripPersisted = null }
    fun restoreTrip(t: Trip) { trip = t; visit = null; departureCandidateSince = null; arrivalCandidate = null; lastTripPersisted = t.points.lastOrNull() }

    fun seedVisit(sample: Sample, label: String? = null): List<Event> {
        if (visit != null || trip != null) return emptyList()
        val v = Visit(UUID.randomUUID().toString(), sample.point, sample.timeMs, label = label)
        visit = v
        return listOf(Event.VisitStarted(v.copy()))
    }

    fun accept(sample: Sample): List<Event> {
        val out = mutableListOf<Event>()
        val v = visit
        if (v != null) {
            val d = Geo.distanceM(v.center, sample.point)
            if (d <= cfg.sameAreaM) {
                departureCandidateSince = null
                return out
            }
            if (d >= cfg.leaveConfirmM || sample.moving) {
                val since = departureCandidateSince
                if (since == null) {
                    departureCandidateSince = sample.timeMs
                    return out
                }
                if (sample.timeMs - since >= cfg.leaveConfirmMs) {
                    v.departureMs = since
                    out += Event.VisitEnded(v.copy())
                    visit = null
                    val t = Trip(UUID.randomUUID().toString(), since, points = mutableListOf(sample))
                    trip = t
                    lastTripPersisted = sample
                    out += Event.TripStarted(t.copy(points = t.points.toMutableList()))
                    out += Event.TripPointAdded(t.copy(points = t.points.toMutableList()), sample)
                    departureCandidateSince = null
                }
            }
            return out
        }

        val t = trip
        if (t != null) {
            val lastPersisted = lastTripPersisted
            if (lastPersisted == null || Geo.distanceM(lastPersisted.point, sample.point) >= cfg.minTripPointDistanceM) {
                // History lives in the journal; keep only the current anchor in RAM.
                t.points.clear()
                t.points += sample
                lastTripPersisted = sample
                out += Event.TripPointAdded(t.copy(points = t.points.toMutableList()), sample)
            }

            val candidate = arrivalCandidate
            if (candidate == null) {
                arrivalCandidate = sample
            } else {
                val d = Geo.distanceM(candidate.point, sample.point)
                if (d <= cfg.arrivalRadiusM && !sample.moving) {
                    if (sample.timeMs - candidate.timeMs >= cfg.arrivalConfirmMs) {
                        t.endMs = candidate.timeMs
                        out += Event.TripEnded(t.copy(points = t.points.toMutableList()))
                        trip = null
                        val newVisit = Visit(UUID.randomUUID().toString(), candidate.point, candidate.timeMs)
                        visit = newVisit
                        out += Event.VisitStarted(newVisit.copy())
                        arrivalCandidate = null
                        lastTripPersisted = null
                    }
                } else {
                    arrivalCandidate = sample
                }
            }
        }
        return out
    }
}

data class EngineSnapshot(val visit: Visit?, val trip: Trip?, val departureCandidateSince: Long?, val arrivalCandidate: Sample?, val lastTripPersisted: Sample?)
