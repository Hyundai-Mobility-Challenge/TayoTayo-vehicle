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

    // ---- V-15 하차 아이 이름 안내 ----
    private fun withStatus(t: TripState, vararg pairs: Pair<String, Attendance>) =
        t.copy(children = t.children.map { c -> pairs.firstOrNull { it.first == c.id }?.let { c.copy(status = it.second) } ?: c })
    private fun at(stopId: String, t: TripState = trip) = t.copy(currentStopIndex = t.routeOrder.indexOf(stopId))
    private val allOnboard = withStatus(trip, "child-a" to Attendance.ONBOARD, "child-b" to Attendance.ONBOARD,
        "child-c" to Attendance.ONBOARD, "child-d" to Attendance.ONBOARD)

    @Test fun `문구 빌더는 이름을 쉼표로 잇고 없으면 없다고 말한다`() {
        assertEquals("다음은 서울숲, 어린이 가, 어린이 나 하차.", approachSpeech("서울숲", listOf("어린이 가", "어린이 나")))
        assertEquals("다음은 올림픽공원, 내릴 어린이 없음.", approachSpeech("올림픽공원", emptyList()))
        assertEquals("서울숲 도착, 어린이 가 하차 예정. 보호자 인계와 하차를 기록해 주세요.", arrivalSpeech("서울숲", listOf("어린이 가")))
        assertEquals("서울숲 도착, 내릴 어린이 없음.", arrivalSpeech("서울숲", emptyList()))
        assertEquals("다음은 서울숲 정류장, 어린이 가, 어린이 나 탑승.", boardingApproachSpeech("서울숲", listOf("어린이 가", "어린이 나")))
        assertEquals("다음은 서울숲 정류장, 탈 어린이 없음.", boardingApproachSpeech("서울숲", emptyList()))
    }
    @Test fun `다음 지점 안내는 정류장 이름과 탑승 중인 하차 아이 이름을 명단 순서로 읽는다`() {
        assertEquals("다음은 서울숲, 어린이 가, 어린이 나 하차.", tripCue(at("forest", allOnboard))!!.text)
        assertEquals("다음은 국립중앙박물관, 어린이 다 하차.", tripCue(at("museum", allOnboard))!!.text)
        val arrived = at("forest", allOnboard).copy(phase = TripPhase.ALIGHTING)
        assertEquals("서울숲 도착, 어린이 가, 어린이 나 하차 예정. 보호자 인계와 하차를 기록해 주세요.", tripCue(arrived)!!.text)
    }
    @Test fun `미탑승 확인 이미 하차 탑승 전 미예정 아이는 하차로 읽지 않는다`() {
        val mixed = withStatus(allOnboard, "child-a" to Attendance.CONFIRMED_NOT_BOARDED, "child-b" to Attendance.ALIGHTED)
        assertEquals("다음은 서울숲, 내릴 어린이 없음.", tripCue(at("forest", mixed))!!.text)
        val notExpected = trip.copy(children = trip.children.map {
            if (it.id == "child-a") it.copy(notExpectedToBoard = true) else if (it.id == "child-b") it.copy(status = Attendance.ONBOARD) else it })
        assertEquals("다음은 서울숲, 어린이 나 하차.", tripCue(at("forest", notExpected))!!.text)
        assertFalse(tripCue(at("forest", notExpected))!!.text.contains("가상"))
        assertEquals(listOf("child-b"), alightingChildren(notExpected, notExpected.stops.first { it.id == "forest" }).map { it.id })
    }
    @Test fun `하차 대상이 바뀌면 다시 안내하고 같으면 반복하지 않으며 내비는 다시 요청하지 않는다`() {
        val gate = AutomaticTripGuidance()
        val forest = at("forest", allOnboard)
        assertNotNull(gate.takeSpeech(forest, true))
        assertNull(gate.takeSpeech(forest.copy(revision = 100), true))
        assertEquals(forest.currentStop, gate.takeNavigation(forest, true, true))
        val changed = withStatus(forest, "child-b" to Attendance.CONFIRMED_NOT_BOARDED)
        assertEquals("다음은 서울숲, 어린이 가 하차.", gate.takeSpeech(changed, true)!!.text)
        assertNull(gate.takeSpeech(changed, true))
        assertNull(gate.takeNavigation(changed, true, true))
        // 키에는 이름이 아니라 id만 들어간다.
        assertFalse(tripCue(changed)!!.key.contains("어린이"))
    }
    @Test fun `도착 뒤 아이별 하차 기록은 안내를 반복하지 않고 다시 듣기는 현재 문구를 읽는다`() {
        val gate = AutomaticTripGuidance()
        val arrived = at("forest", allOnboard).copy(phase = TripPhase.ALIGHTING)
        assertNotNull(gate.takeSpeech(arrived, true))
        val oneDone = withStatus(arrived, "child-a" to Attendance.ALIGHTED)
        assertNull(gate.takeSpeech(oneDone, true))
        assertEquals("서울숲 도착, 어린이 나 하차 예정. 보호자 인계와 하차를 기록해 주세요.", tripCue(oneDone)!!.text)
    }
}
