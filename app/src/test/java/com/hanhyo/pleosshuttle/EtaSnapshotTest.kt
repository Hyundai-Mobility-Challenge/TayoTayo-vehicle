package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.*
import org.junit.Assert.*
import org.junit.Test

/** 내비 남은 시간 → 서버 eta-snapshots 항목(InstitutionTrips.etaSnapshot). */
class EtaSnapshotTest {
    private val origin = Stop("origin", "기관", 37.5665, 126.9780)
    private val s1 = Stop("101", "정류장 1", 37.5445, 127.0374)
    private val s2 = Stop("102", "정류장 2", 37.5512, 126.9882)
    private val s3 = Stop("103", "정류장 3", 37.5796, 126.9770)
    private fun trip(index: Int = 0, version: Long = 2) = TripState(tripId = "77", integrationMode = IntegrationMode.SDK,
        phase = TripPhase.DRIVING, children = emptyList(), origin = origin, stops = listOf(s1, s2, s3),
        currentStopIndex = index, routeVersion = version, routeIsExample = false)
    private fun eta(stop: Stop, sec: Int, m: Int, at: Long = 1_000L, source: String = "PLEOS") =
        NavigationStopEta(stop.latitude, stop.longitude, sec, m, at, stop.name, source)

    @Test fun notReadyNavigationSendsNothing() {
        assertNull(InstitutionTrips.etaSnapshot(trip(), null, false))
        assertNull(InstitutionTrips.etaSnapshot(trip(), TrackingNavigation("REROUTING", 3), false))
        assertNull(InstitutionTrips.etaSnapshot(trip(), TrackingNavigation("UNAVAILABLE", 3), false))
    }

    @Test fun waypointBundleMapsEachEntryToItsTripStop() {
        val stops = listOf(eta(s1, 120, 800), eta(s2, 300, 2100, source = "REGISTERED_STOP"), eta(s3, 540, 3900))
        val nav = TrackingNavigation("READY", 3, "route-a", s1.latitude, s1.longitude, 120, 800, 1_000L, stops)
        val snap = InstitutionTrips.etaSnapshot(trip(), nav, false)!!
        assertEquals(2, snap.routeRevisionNo)
        assertEquals("route-a", snap.navRouteKey)
        assertEquals(1_000L, snap.sourceTime)
        assertFalse(snap.simulated)
        assertEquals(listOf(101L, 102L, 103L), snap.entries.map { it.tripStopId })
        assertEquals(listOf(0, 1, 2), snap.entries.map { it.bundleIndex })
        assertEquals(listOf("WAYPOINT", "WAYPOINT", "DESTINATION"), snap.entries.map { it.pointType })
        assertEquals(listOf(120, 300, 540), snap.entries.map { it.remainingSec })
        assertEquals(listOf(800, 2100, 3900), snap.entries.map { it.remainingM })
    }

    @Test fun singleDestinationUsesTopLevelValuesForCurrentStop() {
        val nav = TrackingNavigation("READY", 1, "route-b", s2.latitude, s2.longitude, 90, 500, 2_000L)
        val snap = InstitutionTrips.etaSnapshot(trip(index = 1), nav, true)!!
        assertTrue(snap.simulated)
        assertEquals(1, snap.entries.size)
        assertEquals(EtaEntry(0, "DESTINATION", 102L, s2.latitude, s2.longitude, 90, 500), snap.entries.single())
    }

    @Test fun destinationThatIsNotAnUpcomingStopHasNoTripStopId() {
        // 정류장을 모두 지나 기관(출발지)으로 돌아가는 구간, 또는 이미 지난 정류장 좌표.
        val toOrigin = TrackingNavigation("READY", 1, "r", origin.latitude, origin.longitude, 60, 400, 3_000L)
        assertNull(InstitutionTrips.etaSnapshot(trip(index = 3), toOrigin, false)!!.entries.single().tripStopId)
        val passed = TrackingNavigation("READY", 1, "r", s1.latitude, s1.longitude, 60, 400, 3_000L)
        assertNull(InstitutionTrips.etaSnapshot(trip(index = 1), passed, false)!!.entries.single().tripStopId)
    }

    @Test fun nonNumericStopIdIsNotSentAsTripStopId() {
        val local = Stop("forest", "가상 정류장", 37.5445, 127.0374)
        val t = trip().copy(stops = listOf(local), routeOrder = listOf("forest"))
        val nav = TrackingNavigation("READY", 1, "r", local.latitude, local.longitude, 30, 200, 1L)
        assertNull(InstitutionTrips.etaSnapshot(t, nav, false)!!.entries.single().tripStopId)
    }

    @Test fun readyWithoutAnyTimingSendsNothing() {
        assertNull(InstitutionTrips.etaSnapshot(trip(), TrackingNavigation("READY", 1, "r"), false))
    }

    @Test fun longRouteKeyIsTrimmedToServerColumnLength() {
        val nav = TrackingNavigation("READY", 1, "x".repeat(150), s1.latitude, s1.longitude, 10, 10, 1L)
        assertEquals(100, InstitutionTrips.etaSnapshot(trip(), nav, false)!!.navRouteKey!!.length)
    }

    @Test fun alightingAtCurrentStopDoesNotSendItsEta() {
        // 운행 7: 정류장 정차·하차 중(ALIGHTING)인데 그 정류장 ETA가 전송됐던 경우. 이미 도착한 정류장 항목은 뺀다.
        val alighting = trip(index = 0).copy(phase = TripPhase.ALIGHTING)
        val stops = listOf(eta(s1, 5, 10), eta(s2, 300, 2100), eta(s3, 540, 3900))
        val nav = TrackingNavigation("READY", 3, "route-a", s1.latitude, s1.longitude, 5, 10, 1_000L, stops)
        val snap = InstitutionTrips.etaSnapshot(alighting, nav, false)!!
        assertEquals(listOf(102L, 103L), snap.entries.map { it.tripStopId })
        assertEquals(listOf(1, 2), snap.entries.map { it.bundleIndex })
        // 운행 중(DRIVING)이면 같은 내비 값의 현재 정류장 항목을 그대로 보낸다.
        assertEquals(listOf(101L, 102L, 103L), InstitutionTrips.etaSnapshot(trip(index = 0), nav, false)!!.entries.map { it.tripStopId })
    }

    @Test fun alightingWithOnlyTheArrivedStopSendsNothing() {
        val alighting = trip(index = 2).copy(phase = TripPhase.ALIGHTING)
        val nav = TrackingNavigation("READY", 1, "r", s3.latitude, s3.longitude, 3, 5, 1_000L)
        assertNull(InstitutionTrips.etaSnapshot(alighting, nav, false))
    }
}
