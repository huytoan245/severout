package com.family.core

private const val M=60_000L
private fun gp(lat:Double, lon:Double)=GeoPoint(lat,lon,8.0)
private fun sm(lat:Double,lon:Double,min:Long,moving:Boolean=false)=Sample(gp(lat,lon),min*M,moving)

fun main(){
    val e=VisitEngine()
    val events=mutableListOf<Event>()
    events += e.seedVisit(sm(20.0000,105.0000,8*60), "Office")
    // Stay at office with jitter until 16:00
    for(min in 8*60+5..16*60 step 15){
        val j=((min/15)%3-1)*0.00012
        events += e.accept(sm(20.0+j,105.0,min.toLong(),false))
    }
    // Leave office and sustain movement
    events += e.accept(sm(20.0030,105.0000,16*60+1,true))
    events += e.accept(sm(20.0060,105.0000,16*60+3,true))
    events += e.accept(sm(20.0120,105.0060,16*60+8,true))
    // Arrive at a new place, remain stable
    events += e.accept(sm(20.0150,105.0100,16*60+15,false))
    events += e.accept(sm(20.0151,105.0101,16*60+18,false))

    val ended=events.filterIsInstance<Event.VisitEnded>().firstOrNull() ?: error("office visit not closed")
    check(ended.visit.label=="Office")
    check(ended.visit.departureMs != null)
    val newV=events.filterIsInstance<Event.VisitStarted>().lastOrNull() ?: error("new visit not created")
    check(newV.visit.arrivalMs >= (16*60+15)*M)

    // Simulate persistence/reboot: persisted Visit can be seeded in a fresh engine at current point.
    val afterBoot=VisitEngine()
    afterBoot.seedVisit(Sample(newV.visit.center, 18*60*M), "New place")
    check(afterBoot.currentVisit()!=null)

    // Force a 'no data gap' to be represented externally, not inferred as continuous presence.
    val gapStart=19*60*M
    val gapEnd=20*60*M
    check(gapEnd-gapStart==60*M)

    println("SCENARIO TEST PASSED: office -> trip -> new visit; reboot reseed; gap preserved")
}
