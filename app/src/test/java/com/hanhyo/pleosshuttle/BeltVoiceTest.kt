package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.*
import org.junit.Assert.*
import org.junit.Test

/** V-16 벨트 미확인 음성: 출발 전 요약의 이름, 정류장 뒤 재출발 안내(한 번), 다시 듣기 문구. */
class BeltVoiceTest {
    private val now = 1_000_000L
    private fun <T> observed(value: T) = Observation(value, Source.SIMULATED, now, ResultStatus.TARGET_OBSERVED)
    private fun parked() = VehicleSnapshot(connected = true, speedKph = observed(0.0), gear = observed(Gear.PARK),
        parkingBrake = observed(true), doorLocked = observed(true), connectionGeneration = 1)
    private fun engine() = ShuttleEngine(SampleTrip.create()).also { it.updateVehicle(parked(), now) }
    private fun ShuttleEngine.allBoarded() = apply { state.children.forEach { assertTrue(board(it.id, now)) } }
    /** 현재 지점의 아이를 모두 인계·하차 기록해 다음 지점 DRIVING으로 넘어간다(recordAlightingAndContinue 자동 완료). */
    private fun ShuttleEngine.finishStop() {
        state.currentStopChildren.filter { it.status == Attendance.ONBOARD }.forEach {
            assertTrue(confirmHandover(it.id, now)); assertTrue(recordAlightingAndContinue(it.id, now))
        }
        assertEquals(TripPhase.DRIVING, state.phase)
    }

    @Test fun `이름 나열은 4명까지 모두 읽고 5명 이상은 앞 3명과 외 n명으로 줄인다`() {
        assertEquals("어린이 다", spokenNameList(listOf("어린이 다")))
        assertEquals("가, 나, 다, 라", spokenNameList(listOf("가", "나", "다", "라")))
        assertEquals("가, 나, 다 외 2명", spokenNameList(listOf("가", "나", "다", "라", "마")))
        assertEquals("가, 나, 다 외 3명", spokenNameList(listOf("가", "나", "다", "라", "마", "바")))
        assertEquals("출발 전 벨트 확인: 어린이 다, 어린이 라 벨트 미확인.", redepartureBeltSpeech(listOf("어린이 다", "어린이 라")))
        assertNull(redepartureBeltSpeech(emptyList()))
    }

    @Test fun `출발 전 요약은 미확인 아이 이름을 읽고 모두 확인이면 이름 없이 확인했다고 읽는다`() {
        val base = DepartureSummary(3, 3, 2, 0, listOf("A"), driverChecked = false, beltUncheckedNames = listOf("어린이 나", "어린이 다"))
        assertEquals("출발 전 확인입니다. 탑승 3명, 첫 정류장 A, 경로 A. 벨트 미확인 2명, 어린이 나, 어린이 다. 기사님, 확인해 주세요.", departureSpeech(base))
        val many = base.copy(boarded = 6, expected = 6, beltUnchecked = 6, beltUncheckedNames = listOf("가", "나", "다", "라", "마", "바"))
        assertTrue(departureSpeech(many).contains("벨트 미확인 6명, 가, 나, 다 외 3명. "))
        assertTrue(departureSpeech(base.copy(beltUnchecked = 0, beltUncheckedNames = emptyList())).contains("벨트는 모두 확인했습니다."))
    }

    @Test fun `출발 전 요약은 벨트 토글로 다시 읽지 않지만 다시 듣기는 최신 이름을 읽는다`() {
        val e = engine().allBoarded()
        assertTrue(e.confirmBelt("child-a", now))
        val gate = AutomaticTripGuidance()
        val first = gate.takeSpeech(e.state, true, true)!!
        assertTrue(first.text.contains("벨트 미확인 3명, 어린이 나, 어린이 다, 어린이 라. "))
        assertFalse(first.text.contains("가상"))
        assertFalse(first.key.contains("어린이"))
        assertTrue(e.confirmBelt("child-b", now)); assertTrue(e.confirmBelt("child-c", now))
        assertNull(gate.takeSpeech(e.state, true, true))
        assertTrue(tripCue(e.state)!!.text.contains("벨트 미확인 1명, 어린이 라. ")) // [다시 듣기]
        assertTrue(e.confirmBelt("child-d", now))
        assertTrue(tripCue(e.state)!!.text.contains("벨트는 모두 확인했습니다."))
    }

    @Test fun `정류장 뒤 재출발 때 남은 아이 중 벨트 미확인 이름을 한 번만 읽는다`() {
        val e = engine().allBoarded()
        listOf("child-a", "child-b").forEach { assertTrue(e.confirmBelt(it, now)) }
        assertTrue(e.startOperationalTrip(now))
        val gate = AutomaticTripGuidance()
        // 첫 출발 안내에는 붙이지 않는다(출발 전 요약이 이미 이름을 읽음).
        assertFalse(gate.takeSpeech(e.state, true)!!.text.contains("벨트"))
        assertTrue(e.chooseAlighting(now))
        assertFalse(gate.takeSpeech(e.state, true)!!.text.contains("벨트"))
        e.finishStop()
        val unchecked = e.state.beltUncheckedChildren.map(::spokenChildName)
        assertTrue(unchecked.isNotEmpty())
        val stop = e.state.currentStop!!
        val expected = "출발 전 벨트 확인: ${unchecked.joinToString(", ")} 벨트 미확인. " +
            approachSpeech(stopLabel(stop), alightingChildren(e.state, stop).map(::spokenChildName))
        val cue = gate.takeSpeech(e.state, true)!!
        assertEquals(expected, cue.text)
        assertFalse(cue.key.contains("어린이"))
        // 같은 단계에서는 다시 읽지 않는다(화면 갱신·백그라운드 복귀).
        assertNull(gate.takeSpeech(e.state, true))
        assertNull(gate.takeSpeech(e.state.copy(revision = 999), true))
        assertNull(gate.takeSpeech(e.state, false))
        assertNull(gate.takeSpeech(e.state, true))
        assertEquals(expected, tripCue(e.state)!!.text) // [다시 듣기]
        // 다음 정차에서 남은 아이 벨트를 모두 확인하면 다음 재출발에는 덧붙이지 않는다.
        assertTrue(e.chooseAlighting(now))
        assertNotNull(gate.takeSpeech(e.state, true))
        e.state.onboardChildren.forEach { if (!it.beltConfirmed) assertTrue(e.confirmBelt(it.id, now)) }
        e.finishStop()
        val next = gate.takeSpeech(e.state, true)!!
        assertFalse(next.text.contains("벨트"))
        assertTrue(next.text.startsWith("다음은 "))
    }

    @Test fun `모두 확인이면 재출발에 아무것도 덧붙이지 않고 백그라운드면 읽지 않는다`() {
        val e = engine().allBoarded()
        e.state.children.forEach { assertTrue(e.confirmBelt(it.id, now)) }
        assertTrue(e.startOperationalTrip(now))
        assertTrue(e.chooseAlighting(now))
        e.finishStop()
        assertEquals("", redepartureBeltPrefix(e.state))
        assertTrue(tripCue(e.state)!!.text.startsWith("다음은 "))
        val gate = AutomaticTripGuidance()
        assertNull(gate.takeSpeech(e.state, false))
    }
}
