package com.family.child
import android.content.Context
import com.family.core.*

class StateStore(c:Context){
    private val p=c.getSharedPreferences("tracking_state",Context.MODE_PRIVATE)
    fun save(engine:VisitEngine){
        engine.currentVisit()?.let{v->p.edit().putString("mode","visit").putString("id",v.id).putLong("start",v.arrivalMs).putLong("lat",java.lang.Double.doubleToRawLongBits(v.center.lat)).putLong("lon",java.lang.Double.doubleToRawLongBits(v.center.lon)).apply();return}
        engine.currentTrip()?.let{t->p.edit().putString("mode","trip").putString("id",t.id).putLong("start",t.startMs).apply();return}
        p.edit().clear().apply()
    }
    fun restore(engine:VisitEngine){
        when(p.getString("mode",null)){
            "visit"->{ val id=p.getString("id",null)?:return; val lat=java.lang.Double.longBitsToDouble(p.getLong("lat",0)); val lon=java.lang.Double.longBitsToDouble(p.getLong("lon",0)); engine.restoreVisit(Visit(id,GeoPoint(lat,lon),p.getLong("start",System.currentTimeMillis()))) }
            "trip"->{ val id=p.getString("id",null)?:return; engine.restoreTrip(Trip(id,p.getLong("start",System.currentTimeMillis()))) }
        }
    }
}
