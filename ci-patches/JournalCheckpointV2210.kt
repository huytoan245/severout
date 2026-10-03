package com.family.child

import com.family.core.*
import org.json.JSONObject

/** Compact full engine state, including confirmation candidates and the last GPS anchor. */
object JournalCheckpoint {
    private fun sample(s: Sample): JSONObject = JSONObject().put("lat", s.point.lat).put("lon", s.point.lon)
        .put("accuracy", s.point.accuracyM).put("time", s.timeMs).put("moving", s.moving)
    private fun sample(o: JSONObject): Sample = Sample(GeoPoint(o.getDouble("lat"), o.getDouble("lon"), o.getDouble("accuracy")), o.getLong("time"), o.getBoolean("moving"))
    fun encode(engine: VisitEngine, latest: Sample): String {
        val s = engine.snapshot()
        val o = JSONObject().put("latest", sample(latest))
        s.visit?.let { o.put("visit", JSONObject().put("id", it.id).put("center", sample(Sample(it.center, it.arrivalMs))).put("label", it.label)) }
        s.trip?.let { o.put("trip", JSONObject().put("id", it.id).put("start", it.startMs)) }
        s.departureCandidateSince?.let { o.put("departure", it) }
        s.arrivalCandidate?.let { o.put("arrival", sample(it)) }
        s.lastTripPersisted?.let { o.put("lastTrip", sample(it)) }
        return o.toString()
    }
    fun restore(engine: VisitEngine, json: String): Sample {
        val o = JSONObject(json)
        val visit = o.optJSONObject("visit")?.let { v -> val center = sample(v.getJSONObject("center")); Visit(v.getString("id"), center.point, center.timeMs, label = v.optString("label").takeIf { it.isNotBlank() }) }
        val trip = o.optJSONObject("trip")?.let { Trip(it.getString("id"), it.getLong("start")) }
        engine.restoreSnapshot(EngineSnapshot(visit, trip, if (o.has("departure")) o.getLong("departure") else null, o.optJSONObject("arrival")?.let(::sample), o.optJSONObject("lastTrip")?.let(::sample)))
        return sample(o.getJSONObject("latest"))
    }
}
