package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.*
import org.junit.Assert.*
import org.junit.Test

/** 등원(V-25) 음성: 하원 문장을 쓰지 않고 V-15 탑승 안내, 기관 안내, V-16 재출발 벨트 경고를 쓴다. */
class PickupVoiceTest {
    private val now = 1_000_000L
    private fun <T> observed(value: T) = Observation(value, Source.SDK, now, ResultStatus.TARGET_OBSERVED)
    private fun parked() = VehicleSnapshot(connected = true, speedKph = observed(0.0), gear = observed(Gear.PARK),
        parkingBrake = observed(true), doorLocked = observed(true), connectionGeneration = 1)
    private fun engine() = ShuttleEngine(SampleTrip.createPickup().copy(routeIsExample = false)).also { it.updateVehicle(parked(), now) }
    private val dropoffWords = listOf("내릴 어린이", "하차", "출발지", "돌아갑니다", "보호자 인계와")

    private fun cue(e: ShuttleEngine) = tripCue(e.state)!!.text
    private fun assertPickupWording(text: String) = dropoffWords.forEach { assertFalse("\"$it\" in: $text", text.contains(it)) }

    @Test fun `등원 운행 전체에서 하원 문장이 나오지 않는다`() {
        val e = engine()
        val spoken = mutableListOf<String>()
        fun say() { spoken += cue(e) }
        say() // 출발 전 요약
        assertTrue(e.startDriving(now)); say()
        assertEquals("다음은 서울숲 정류장, 어린이 가, 어린이 나 탑승.", spoken.last())
        assertTrue(e.arriveAtPickupStop(now)); say()
        assertEquals("서울숲 정류장 도착, 어린이 가, 어린이 나 탑승 예정. 인계받음과 탑승을 기록해 주세요.", spoken.last())
        assertTrue(e.recordReceived("child-a", PickupReceivers.GUARDIAN, now)); assertTrue(e.board("child-a", now))
        assertTrue(e.confirmNotBoardedAtStop("child-b", notifyGuardian = false, now))
        assertTrue(e.completeCurrentStop(now)); say()
        // V-16: 벨트 미확인으로 정류장을 떠나면 이름과 함께 경고한 뒤 다음 정류장 안내
        assertEquals("출발 전 벨트 확인: 어린이 가 벨트 미확인. 다음은 국립중앙박물관 정류장, 어린이 다 탑승.", spoken.last())
        assertTrue(e.arriveAtPickupStop(now)); say()
        assertTrue(e.recordReceived("child-c", PickupReceivers.PROXY, now)); assertTrue(e.board("child-c", now))
        assertTrue(e.confirmBelt("child-a", now)); assertTrue(e.confirmBelt("child-c", now))
        assertTrue(e.completeCurrentStop(now)); say()
        assertEquals("다음은 올림픽공원 정류장, 어린이 라 탑승.", spoken.last()) // 벨트 모두 확인이면 경고 없음
        assertTrue(e.arriveAtPickupStop(now)); say()
        assertTrue(e.confirmNotBoardedAtStop("child-d", notifyGuardian = false, now))
        assertTrue(e.completeCurrentStop(now)); say()
        assertEquals(institutionApproachSpeech(), spoken.last())
        assertTrue(e.arriveAtInstitution(now)); say()
        assertEquals("기관 도착. 어린이 가, 어린이 다 기관 담당자에게 인계해 주세요.", spoken.last())
        assertTrue(e.handoverToInstitution("child-a", now)); assertTrue(e.handoverToInstitution("child-c", now))
        assertTrue(e.inspectAndFinish(now)); say()
        assertEquals("등원 기록을 저장했습니다.", spoken.last())
        spoken.forEach(::assertPickupWording)
    }

    @Test fun `다음 정류장 안내 키는 탈 아이가 바뀔 때만 다시 읽고 도착 안내는 기록마다 반복하지 않는다`() {
        val e = engine()
        assertTrue(e.startDriving(now))
        val gate = AutomaticTripGuidance()
        assertNotNull(gate.takeSpeech(e.state, true))
        assertNull(gate.takeSpeech(e.state.copy(revision = 99), true))
        assertTrue(e.arriveAtPickupStop(now))
        assertNotNull(gate.takeSpeech(e.state, true))
        assertTrue(e.recordReceived("child-a", PickupReceivers.GUARDIAN, now))
        assertNull(gate.takeSpeech(e.state, true)) // 아이별 기록은 안내 사건이 아님
        assertTrue(e.callGuardian("child-b", now))
        assertNull(gate.takeSpeech(e.state, true))
    }

    @Test fun `하원 안내 문장은 그대로다`() {
        val base = SampleTrip.create()
        val driving = base.copy(phase = TripPhase.DRIVING, children = base.children.map { it.copy(status = Attendance.ONBOARD) })
        assertEquals("다음은 서울숲, 어린이 가, 어린이 나 하차.", tripCue(driving)!!.text)
        val back = base.copy(phase = TripPhase.DRIVING, currentStopIndex = 3, children = base.children.map { it.copy(status = Attendance.ALIGHTED) })
        assertEquals("하차 지점 방문을 마쳤습니다. 출발지인 서울시청으로 돌아갑니다.", tripCue(back)!!.text)
    }
}
