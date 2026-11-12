package com.hanhyo.pleosshuttle.domain

import com.hanhyo.pleosshuttle.AutomaticTripGuidance
import com.hanhyo.pleosshuttle.InstitutionTrips
import com.hanhyo.pleosshuttle.departureCue
import com.hanhyo.pleosshuttle.tripCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/** V-08 출발 전 확인(D15·D37): 요약, 기사 확인 체크·자동 해제, 출발 비차단, "기사 확인 없음" 기록, 서버 driverConfirmed. */
class DepartureCheckTest {
    private val now = 1_000_000L
    private fun <T> observed(value: T) = Observation(value, Source.SIMULATED, now, ResultStatus.TARGET_OBSERVED)
    private fun parked() = VehicleSnapshot(connected = true, speedKph = observed(0.0), gear = observed(Gear.PARK),
        parkingBrake = observed(true), doorLocked = observed(true), connectionGeneration = 1)
    private fun moving() = parked().copy(speedKph = observed(20.0), gear = observed(Gear.DRIVE))
    private fun engine(trip: TripState = SampleTrip.create()) = ShuttleEngine(trip).also { it.updateVehicle(parked(), now) }

    /** 가·나·다 탑승, 라 미탑승 확인 → [운행 시작] 단계. */
    private fun ready(): ShuttleEngine = engine().apply {
        assertTrue(board("child-a", now)); assertTrue(board("child-b", now)); assertTrue(board("child-c", now))
        assertTrue(confirmNotBoarded("child-d", now))
        assertTrue(state.awaitingDeparture)
    }
    private fun ShuttleEngine.driverEntries() = state.ledger.filter { it.isDriverCheckRecord }

    @Test fun `요약은 탑승·예정·벨트 미확인·미결정 수와 출발 순서의 정류장 이름을 담는다`() {
        val e = ready()
        assertTrue(e.confirmBelt("child-a", now))
        val order = departureRouteOrder(e.state)
        val summary = departureSummary(e.state)
        assertEquals(3, summary.boarded)
        assertEquals(4, summary.expected)
        assertEquals(2, summary.beltUnchecked)
        assertEquals(0, summary.undecided)
        assertEquals(order.map { id -> stopLabel(e.state.stops.first { it.id == id }) }, summary.stopNames)
        assertEquals(summary.stopNames.first(), summary.firstStopName)
        assertFalse(summary.driverChecked)
        // 예시 순서면 [운행 시작]이 적용할 계산 순서를 미리 보여 준다.
        assertEquals(RoutePlanner.plan(e.state).stopOrder, order)
        // 탑승 단계 중이면 미결정 수가 잡힌다(화면에서는 경고 칩).
        val boarding = engine().apply { assertTrue(board("child-a", now)) }.state
        assertEquals(3, departureSummary(boarding).undecided)
        assertFalse(boarding.awaitingDeparture)
        assertEquals("서울숲", stopLabel(Stop("x", "가상 정류장 A · 서울숲", 0.0, 0.0)))
        assertEquals("정류장 1", stopLabel(Stop("x", "정류장 1", 0.0, 0.0)))
    }

    @Test fun `음성 요약 문장은 인원·첫 정류장·경로·벨트를 읽고 기사 확인을 요청한다`() {
        val summary = DepartureSummary(3, 4, 2, 0, listOf("서울숲", "국립중앙박물관", "올림픽공원"), driverChecked = false)
        assertEquals("출발 전 확인입니다. 탑승 3명, 예정 4명, 첫 정류장 서울숲, 경로 서울숲, 국립중앙박물관, 올림픽공원. 벨트 미확인 2명. 기사님, 확인해 주세요.",
            departureSpeech(summary))
        assertEquals("출발 전 확인입니다. 탑승 2명, 첫 정류장 A, 경로 A, B. 벨트는 모두 확인했습니다. 아직 결정 안 된 어린이 1명. 기사님, 확인해 주세요.",
            departureSpeech(DepartureSummary(2, 2, 0, 1, listOf("A", "B"), driverChecked = true)))
    }

    @Test fun `기사 확인 체크와 해제는 운행 준비·정차 중에만 시각과 원장을 남긴다`() {
        val e = ready()
        assertNull(e.state.driverCheckedAtMillis)
        assertTrue(e.setDriverChecked(true, now + 5))
        assertEquals(now + 5, e.state.driverCheckedAtMillis)
        assertFalse(e.setDriverChecked(true, now + 6)) // 같은 상태 반복은 기록하지 않음
        assertTrue(e.toggleDriverCheck(now + 7))
        assertNull(e.state.driverCheckedAtMillis)
        assertTrue(e.toggleDriverCheck(now + 8))
        assertEquals(listOf(true, false, true), e.driverEntries().map { it.driverCheck })
        assertEquals(listOf(DriverCheckRecords.CHECKED, DriverCheckRecords.UNCHECKED, DriverCheckRecords.CHECKED), e.driverEntries().map { it.reason })
        assertTrue(e.driverEntries().all { it.childId.isEmpty() })
        // 정차 미확인(주행 중)이면 거부
        val m = ready(); m.updateVehicle(moving(), now)
        assertFalse(m.setDriverChecked(true, now))
        // 탑승 0명이면 체크할 것이 없음
        assertFalse(engine().setDriverChecked(true, now))
    }

    @Test fun `체크 뒤 출결이 바뀌면 기사 확인을 자동 해제한다`() {
        val e = ready()
        assertTrue(e.setDriverChecked(true, now))
        assertTrue(e.undoNotBoarded("child-d", now + 1))
        assertNull(e.state.driverCheckedAtMillis)
        assertEquals(DriverCheckRecords.autoCleared("탑승 인원·출결 변경"), e.driverEntries().last().reason)
        assertEquals(false, e.driverEntries().last().driverCheck)
        assertTrue(e.lastMessage.contains("기사 확인을 해제"))
        // 탑승 기록 정정도 같은 규칙
        assertTrue(e.board("child-d", now + 2))
        assertTrue(e.setDriverChecked(true, now + 3))
        assertTrue(e.correct("child-d", Attendance.NOT_BOARDED, "잘못 기록", now + 4))
        assertNull(e.state.driverCheckedAtMillis)
    }

    @Test fun `체크 뒤 방문 순서가 바뀌면 해제하고 같은 순서면 유지한다`() {
        val e = ready()
        val shown = departureRouteOrder(e.state)
        assertTrue(e.setDriverChecked(true, now))
        assertTrue(e.applyManualRoute(shown, null, now + 1)) // 보이던 순서 그대로 확정 → 변경 아님
        assertNotNull(e.state.driverCheckedAtMillis)
        assertTrue(e.applyManualRoute(shown.reversed(), "도로 상황", now + 2))
        assertNull(e.state.driverCheckedAtMillis)
        assertEquals(DriverCheckRecords.autoCleared("방문 순서 변경"), e.driverEntries().last().reason)
        assertTrue(e.lastMessage.contains("기사 확인이 해제"))
    }

    @Test fun `벨트 확인 토글은 기사 확인을 풀지 않는다`() {
        val e = ready()
        assertTrue(e.setDriverChecked(true, now))
        assertTrue(e.confirmBelt("child-a", now + 1))
        assertTrue(e.unconfirmBelt("child-a", now + 2))
        assertEquals(now, e.state.driverCheckedAtMillis)
    }

    @Test fun `체크 없이도 출발은 막지 않고 기사 확인 없음을 기록하며 서버에는 false를 보낸다`() {
        val e = ready()
        assertFalse(InstitutionTrips.driverConfirmed(e.state))
        assertTrue(e.startOperationalTrip(now + 10))
        assertEquals(TripPhase.DRIVING, e.state.phase)
        assertTrue(e.state.departedWithoutDriverCheck)
        assertEquals(DriverCheckRecords.MISSING, e.driverEntries().single().reason)
        assertEquals(false, e.driverEntries().single().driverCheck)
        assertTrue(e.lastMessage.contains("기사 확인 없음"))
        assertFalse(InstitutionTrips.driverConfirmed(e.state))
        // 기사 확인 기록은 서버 출결 이벤트로 보내지 않는다.
        assertTrue(InstitutionTrips.serverLedger(e.state.ledger).none { it.isDriverCheckRecord })
        assertEquals(4, InstitutionTrips.serverLedger(e.state.ledger).size)
        assertEquals("driverConfirmed", InstitutionTrips.DRIVER_CONFIRMED_FIELD)
    }

    @Test fun `체크 후 출발하면 예시 순서 자동 적용에도 체크가 유지되고 서버에는 true를 보낸다`() {
        val e = ready()
        assertTrue(e.state.routeIsExample)
        assertTrue(e.setDriverChecked(true, now))
        assertTrue(e.startOperationalTrip(now + 10))
        assertEquals(TripPhase.DRIVING, e.state.phase)
        assertEquals(now, e.state.driverCheckedAtMillis)
        assertFalse(e.state.departedWithoutDriverCheck)
        assertTrue(e.driverEntries().none { it.reason == DriverCheckRecords.MISSING })
        assertTrue(InstitutionTrips.driverConfirmed(e.state))
        // 출발 뒤에는 체크를 바꿀 수 없다.
        assertFalse(e.setDriverChecked(false, now + 11))
    }

    @Test fun `출발 전 음성 요약은 운행 시작 단계에서 한 번, 정차 확인 전에는 기다린다`() {
        val e = ready()
        assertNull(tripCue(SampleTrip.create()))
        val cue = departureCue(e.state)!!
        assertEquals(departureSpeech(departureSummary(e.state)), cue.text)
        assertEquals(cue, tripCue(e.state)) // [다시 듣기]도 같은 문장
        val gate = AutomaticTripGuidance()
        assertNull(gate.takeSpeech(e.state, foreground = true, departureReady = false))
        assertEquals(cue, gate.takeSpeech(e.state, foreground = true, departureReady = true))
        assertNull(gate.takeSpeech(e.state, true, true))
        // 벨트 토글만으로는 다시 읽지 않음
        assertTrue(e.confirmBelt("child-a", now))
        assertNull(gate.takeSpeech(e.state, true, true))
        // 순서가 바뀌면 바뀐 요약을 다시 읽음
        assertTrue(e.applyManualRoute(departureRouteOrder(e.state).reversed(), null, now + 1))
        val again = gate.takeSpeech(e.state, true, true)
        assertNotNull(again)
        assertNotEquals(cue.key, again!!.key)
    }

    @Test fun `기사 확인 시각과 원장 표시는 직렬화로 그대로 복원된다`() {
        val e = ready()
        assertTrue(e.setDriverChecked(true, now + 3))
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { out -> out.writeObject(e.state) } }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as TripState }
        assertEquals(e.state, restored)
        assertEquals(now + 3, restored.driverCheckedAtMillis)
        assertEquals(true, restored.ledger.last().driverCheck)
        // 복원 뒤에도 체크는 유지(사람 확인 기록, 정차·문 확인처럼 시간 제한 없음)
        val r = ShuttleEngine(); r.restore(restored, now + 4)
        assertEquals(now + 3, r.state.driverCheckedAtMillis)
    }
}
