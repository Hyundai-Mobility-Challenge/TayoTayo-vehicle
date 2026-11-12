package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.*
import org.junit.Assert.*
import org.junit.Test

class AutomaticTripGuidanceTest {
    private val trip = SampleTrip.create().copy(phase = TripPhase.DRIVING)
    @Test fun `관측 갱신과 같은 화면 복귀는 동일 안내를 반복하지 않는다`() {
        val gate = AutomaticTripGuidance()
        assertNull(gate.takeSpeech(trip, false))
        assertNotNull(gate.takeSpeech(trip, true))
        assertNull(gate.takeSpeech(trip.copy(revision = 100), true))
        assertNull(gate.takeSpeech(trip, false))
        assertNull(gate.takeSpeech(trip, true))
    }
    @Test fun `도착과 다음 지점은 안내하고 아이별 인계와 기록은 안내를 반복하지 않는다`() {
        val gate = AutomaticTripGuidance()
        assertNotNull(gate.takeSpeech(trip, true))
        val arrived = trip.copy(phase = TripPhase.ALIGHTING)
        assertNotNull(gate.takeSpeech(arrived, true))
        assertNull(gate.takeSpeech(arrived.copy(handoverChildIds = setOf("child-a")), true))
        assertNotNull(gate.takeSpeech(trip.copy(currentStopIndex = 1), true))
    }
    @Test fun `내비 연결 대기 중에는 요청을 소모하지 않고 준비 후 한 번만 보낸다`() {
        val gate = AutomaticTripGuidance()
        assertNull(gate.takeNavigation(trip, true, false))
        assertNull(gate.takeNavigation(trip, false, true))
        assertEquals(trip.currentStop, gate.takeNavigation(trip, true, true))
        assertNull(gate.takeNavigation(trip, true, true))
        assertNull(gate.takeNavigation(trip.copy(phase = TripPhase.ALIGHTING), true, true))
        assertEquals(trip.stops.first { it.id == trip.routeOrder[1] }, gate.takeNavigation(trip.copy(currentStopIndex = 1), true, true))
    }
    @Test fun `복귀 목적지는 출발지이며 새 운행은 같은 첫 지점도 다시 안내한다`() {
        val gate = AutomaticTripGuidance()
        val returning = trip.copy(currentStopIndex = trip.routeOrder.size)
        assertEquals(trip.origin, gate.takeNavigation(returning, true, true))
        assertTrue(gate.takeSpeech(returning, true)!!.text.contains("돌아갑니다"))
        assertNotNull(gate.takeSpeech(trip, true))
        assertNotNull(gate.takeSpeech(trip.copy(tripId = "fresh-trip"), true))
        assertNull(tripCue(trip.copy(phase = TripPhase.PREPARING)))
    }
}
