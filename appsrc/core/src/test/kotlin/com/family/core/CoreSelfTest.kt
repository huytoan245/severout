package com.family.core

private fun p(lat: Double, lon: Double) = GeoPoint(lat, lon, 8.0)
private fun s(lat: Double, lon: Double, min: Long, moving: Boolean=false) = Sample(p(lat,lon), min*60_000, moving)

fun main() {
    // ~111m per 0.001 latitude.
    run {
        val e = VisitEngine()
        e.seedVisit(s(20.0,105.0,0))
        repeat(20) { i ->
            val jitter = (i%3 - 1) * 0.00015
            check(e.accept(s(20.0+jitter,105.0,1L+i)).isEmpty())
        }
        check(e.currentVisit()!=null) { "GPS jitter must not close visit" }
    }
    run {
        val e = VisitEngine()
        e.seedVisit(s(20.0,105.0,0))
        e.accept(s(20.003,105.0,10,true)) // candidate
        val ev=e.accept(s(20.004,105.0,12,true))
        check(ev.any{it is Event.VisitEnded})
        check(ev.any{it is Event.TripStarted})
    }
    run {
        val e = VisitEngine()
        e.seedVisit(s(20.0,105.0,0))
        e.accept(s(20.003,105.0,10,true))
        val back=e.accept(s(20.0001,105.0,11,false))
        check(back.isEmpty())
        check(e.currentVisit()!=null) { "single GPS jump must not close visit" }
    }
    run {
        val e = VisitEngine()
        e.seedVisit(s(20.0,105.0,0))
        e.accept(s(20.003,105.0,10,true)); e.accept(s(20.004,105.0,12,true))
        e.accept(s(20.010,105.010,20,false))
        e.accept(s(20.0101,105.0101,23,false))
        check(e.currentVisit()!=null) { "stable stop should create visit" }
        check(e.currentTrip()==null)
    }
    run {
        val known=listOf(KnownPlace("Home",p(20.0,105.0),250.0))
        val v=Visit("1",p(20.1,105.1),0,40*60_000)
        val f=AnomalyDetector.flags(v,known,40*60_000)
        check(f.unfamiliar && f.longUnfamiliarStop && f.farFromKnown)
    }
    println("ALL CORE TESTS PASSED")
}
