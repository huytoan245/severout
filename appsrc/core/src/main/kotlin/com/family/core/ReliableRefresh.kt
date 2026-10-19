package com.family.core

import java.util.concurrent.atomic.AtomicBoolean

enum class RefreshStage { REQUESTED, DISPATCHED, FCM_RECEIVED, SERVICE_STARTED, GPS_ACQUIRING, GPS_VALIDATED, LOCAL_PERSISTED, SERVER_CONFIRMED, FAILED }

/** Evidence is always joined by request ID, never by a previous result string. */
object RefreshTimeline {
    const val UI_WAIT_MS = 90_000L
    fun stage(id: Long, fields: Map<String, Any?>, serverRead: Boolean = true): RefreshStage {
        fun n(k: String) = (fields[k] as? Number)?.toLong() ?: 0L
        if (id <= 0) return RefreshStage.REQUESTED
        val coordinates = fields["lastLat"] is Number && fields["lastLon"] is Number
        if (serverRead && n("refreshCompletedFor") == id && n("locationTime") >= id && coordinates) return RefreshStage.SERVER_CONFIRMED
        if (n("refreshFailedFor") == id) return RefreshStage.FAILED
        if (n("refreshPersistedFor") == id) return RefreshStage.LOCAL_PERSISTED
        if (n("refreshValidatedFor") == id) return RefreshStage.GPS_VALIDATED
        if (n("refreshLocatingFor") == id) return RefreshStage.GPS_ACQUIRING
        if (n("refreshServiceFor") == id || n("refreshAckFor") == id) return RefreshStage.SERVICE_STARTED
        if (n("refreshReceivedFor") == id) return RefreshStage.FCM_RECEIVED
        if (n("wakeDispatchFor") == id && fields["wakeDispatchResult"] == "sent") return RefreshStage.DISPATCHED
        return RefreshStage.REQUESTED
    }
    fun waitingTimeout(id: Long, now: Long, stage: RefreshStage) = now - id >= UI_WAIT_MS && stage !in setOf(RefreshStage.SERVER_CONFIRMED, RefreshStage.FAILED)
}

/** Primary gets a head start. Timeout/failure starts one CAS fallback; completion fires once. */
class ConfirmedWrite(private val fallback: ((Boolean) -> Unit) -> Unit, private val complete: (Boolean) -> Unit) {
    private val completed = AtomicBoolean(false)
    private val fallbackStarted = AtomicBoolean(false)
    fun primary(ok: Boolean) { if (ok) finish(true) else timeout() }
    fun timeout() { if (!completed.get() && fallbackStarted.compareAndSet(false, true)) fallback { finish(it) } }
    private fun finish(ok: Boolean) { if (completed.compareAndSet(false, true)) complete(ok) }
}

/** Pure acquisition lease: superseded/cancelled/late callbacks cannot finish a newer attempt. */
class AcquisitionLease {
    private var generation = 0L
    @Synchronized fun next(): Long = ++generation
    @Synchronized fun current(value: Long) = generation == value
    @Synchronized fun finish(value: Long): Boolean { if (generation != value) return false; generation++; return true }
}

class GpsDeadline(private val schedule: (Runnable,Long)->Unit, private val remove:(Runnable)->Unit, private val timeout:()->Unit) {
    private val active=AtomicBoolean(true)
    private val task=Runnable{if(active.compareAndSet(true,false))timeout()}
    fun arm(){schedule(task,30_000L)}
    fun finish():Boolean {if(!active.compareAndSet(true,false))return false;remove(task);return true}
    fun cancel(){active.set(false);remove(task)}
}

data class HistoryPoint(val id: String, val time: Long, val lat: Double, val lon: Double, val accuracy: Double)
data class HistoryMetrics(val distance: Double, val movingMs: Long, val stoppedMs: Long, val gaps: Int)
object JourneyPolicy {
    const val GAP_MS = 5 * 60_000L
    fun metrics(input: List<HistoryPoint>): HistoryMetrics {
        val points = input.distinctBy { it.id }.filter { it.time > 0 && it.lat.isFinite() && it.lon.isFinite() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 && it.accuracy.isFinite() && it.accuracy in 0.0..150.0 }.sortedWith(compareBy({it.time},{it.id}))
        var distance=0.0; var moving=0L; var stopped=0L; var gaps=0
        points.zipWithNext().forEach { (a,b) ->
            val dt=b.time-a.time
            if(dt<=0) return@forEach
            val d=Geo.distanceM(GeoPoint(a.lat,a.lon),GeoPoint(b.lat,b.lon))
            if(dt>GAP_MS || d/(dt/1000.0)>70.0) { gaps++; return@forEach }
            // Accuracy circles overlap: do not manufacture travel from jitter.
            if(d<=maxOf(20.0, (a.accuracy+b.accuracy)*0.65)) stopped+=dt
            else { distance+=d; moving+=dt }
        }
        return HistoryMetrics(distance,moving,stopped,gaps)
    }
    fun moving(speed: Float?, distance: Float) = (speed != null && speed > 0.8f) || distance >= 25f
    fun interval(moving: Boolean) = if(moving) 30_000L else 120_000L
    fun segments(input:List<HistoryPoint>):List<List<HistoryPoint>> {
        val out=mutableListOf<MutableList<HistoryPoint>>()
        input.distinctBy{it.id}.sortedWith(compareBy({it.time},{it.id})).forEach{p->
            if(!p.lat.isFinite()||!p.lon.isFinite()||p.lat !in -90.0..90.0||p.lon !in -180.0..180.0||p.accuracy !in 0.0..150.0)return@forEach
            val old=out.lastOrNull()?.lastOrNull()
            val dt=if(old==null)0 else p.time-old.time
            if(old==null||dt<=0||dt>GAP_MS||Geo.distanceM(GeoPoint(old.lat,old.lon),GeoPoint(p.lat,p.lon))/(dt/1000.0)>70){out+=mutableListOf(p)}
            else out.last()+=p
        }
        return out
    }
}
