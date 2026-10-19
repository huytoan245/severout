package com.family.parent

import org.junit.Assert.*
import org.junit.Test

class JourneyHistoryTest {
    private val day=1_700_000_000_000L
    @Test fun sevenThousandSameDayEventsAndAnotherDayAreNotTruncated(){
        val page=(0 until 7200).map{UiEvent(type="location_sample",time=day+it*1000L,lat=20.0+it*0.00001,lon=105.0,accuracy=3.0,id="id-$it",documentId="doc-$it")}
        val all=mergeUiEvents(page.take(500),page.drop(500)+page.take(100))
        assertEquals(7200,all.size);assertEquals("doc-0",all.first().documentId)
        assertEquals(7201,mergeUiEvents(all,listOf(page.first().copy(time=day-86_400_000,documentId="previous-day"))).size)
    }
    @Test fun equalTimestampAndLogicalTripIdRemainDistinctByDocumentId(){
        val a=UiEvent(type="trip_point",time=day,id="same-trip",documentId="a")
        assertEquals(2,mergeUiEvents(listOf(a),listOf(a.copy(documentId="b"),a)).size)
    }
    @Test fun legacyTripPointsAndNewRawSamplesBothRemainInStatisticsWithoutDoubleCountingSameFix(){
        val legacy=UiEvent(type="trip_point",time=day,lat=20.0,lon=105.0,accuracy=5.0,id="trip",documentId="legacy")
        val raw=legacy.copy(type="location_sample",time=day+60_000,documentId="raw")
        val derived=raw.copy(type="trip_point",documentId="derived")
        assertEquals(3,mergeUiEvents(listOf(legacy),listOf(raw,derived)).size)
        assertEquals(2,validRouteEvents(listOf(legacy,raw,derived)).size)
    }
    @Test fun visitAcrossMidnightIsClippedToSelectedDay(){
        val rows=listOf(UiEvent(type="visit_start",time=day-60_000,id="v",lat=20.0,lon=105.0),UiEvent(type="visit_end",time=day+120_000,id="v"))
        val visit=toVisits(rows,day,day+86_400_000).single()
        assertEquals(day,visit.arrival);assertEquals(120_000L,visit.durationMs)
        assertFalse(visit.completeEvidence) // lifecycle anchor alone is not proof of continuous GPS
    }
    @Test fun statisticsUseAllPointsAndNeverBridgeAnOfflineGap(){
        val points=(0 until 7000).map{UiEvent(type="location_sample",time=day+it*1000L,lat=20.0,lon=105.0,accuracy=5.0,id="$it",documentId="$it")}
        val summary=calculateJourneySummary(points+UiEvent(type="location_sample",time=day+86_000_000,lat=22.0,lon=105.0,accuracy=5.0,id="gap",documentId="gap"),emptyList(),day,day+86_400_000)
        assertEquals(7001,summary.validPoints);assertEquals(0.0,summary.distanceMeters,0.01);assertTrue(summary.gaps>=1)
    }
}
