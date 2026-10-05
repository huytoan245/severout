package com.family.core

import kotlin.math.*

object Geo {
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dp = Math.toRadians(b.lat - a.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val h = sin(dp/2).pow(2) + cos(p1)*cos(p2)*sin(dl/2).pow(2)
        return 2*r*atan2(sqrt(h), sqrt(1-h))
    }
}
