package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.*
import com.hanhyo.pleosshuttle.sdk.BeltTelltaleState
import com.hanhyo.pleosshuttle.sdk.SdkObservation
import com.hanhyo.pleosshuttle.sdk.SdkOutcome
import org.junit.Assert.*
import org.junit.Test

/** V-06 벨트 경고등 연동(보조): 신호 판정, 배너 구간, 음성 한 번(Off → On 재경고), 이름 덧붙이기. */
class BeltTelltaleGuidanceTest {
    private val now = 1_000_000L
    private fun obs(state: BeltTelltaleState?, at: Long = now, outcome: SdkOutcome = SdkOutcome.TARGET_OBSERVED) =
        SdkObservation(state, at, "test", outcome)
    private val base = SampleTrip.create()
    private fun onboard(phase: TripPhase = TripPhase.DRIVING, belted: Set<String> = emptySet()) = base.copy(
        phase = phase,
        children = base.children.map { it.copy(status = Attendance.ONBOARD, beltConfirmed = it.id in belted) },
    )

    @Test fun `켜짐과 깜빡임은 경고 꺼짐은 해제 오래되었거나 미수신이면 판단하지 않는다`() {
        assertEquals(BeltTelltaleSignal.WARNING, beltTelltaleSignal(obs(BeltTelltaleState.ON), now))
        assertEquals(BeltTelltaleSignal.WARNING, beltTelltaleSignal(obs(BeltTelltaleState.BLINK), now))
        assertEquals(BeltTelltaleSignal.CLEAR, beltTelltaleSignal(obs(BeltTelltaleState.OFF), now))
        assertEquals(BeltTelltaleSignal.UNKNOWN, beltTelltaleSignal(SdkObservation(), now))
        assertEquals(BeltTelltaleSignal.UNKNOWN, beltTelltaleSignal(obs(BeltTelltaleState.ON, at = now - BELT_TELLTALE_MAX_AGE_MILLIS - 1), now))
        assertEquals(BeltTelltaleSignal.WARNING, beltTelltaleSignal(obs(BeltTelltaleState.ON, at = now - BELT_TELLTALE_MAX_AGE_MILLIS), now))
        assertEquals(BeltTelltaleSignal.UNKNOWN, beltTelltaleSignal(obs(BeltTelltaleState.ON, outcome = SdkOutcome.ERROR), now))
    }

    @Test fun `배너는 탑승 아이가 있는 운행 중 모든 단계에서 보이고 탑승 전 전원 하차 운행 완료에는 없다`() {
        listOf(TripPhase.PREPARING, TripPhase.DRIVING, TripPhase.ALIGHTING, TripPhase.END_REVIEW).forEach {
            assertEquals(BELT_TELLTALE_BANNER, beltTelltaleBanner(onboard(it), BeltTelltaleSignal.WARNING))
        }
        assertEquals("벨트 확인 필요 · 차량 벨트 경고등이 켜졌어요", BELT_TELLTALE_BANNER)
        assertNull(beltTelltaleBanner(base, BeltTelltaleSignal.WARNING)) // 탑승 전
        assertNull(beltTelltaleBanner(onboard(TripPhase.ENDED), BeltTelltaleSignal.WARNING))
        assertNull(beltTelltaleBanner(base.copy(phase = TripPhase.END_REVIEW, children = base.children.map { it.copy(status = Attendance.ALIGHTED) }), BeltTelltaleSignal.WARNING))
        assertNull(beltTelltaleBanner(onboard(), BeltTelltaleSignal.CLEAR))
        assertNull(beltTelltaleBanner(onboard(), BeltTelltaleSignal.UNKNOWN))
    }

    @Test fun `경고 음성은 한 구간에 한 번이며 꺼짐을 본 뒤 다시 켜지면 또 읽는다`() {
        val g = BeltTelltaleGuidance()
        val trip = onboard()
        assertNull(g.take(trip, BeltTelltaleSignal.CLEAR, true, false))
        assertNotNull(g.take(trip, BeltTelltaleSignal.WARNING, true, false))
        assertNull(g.take(trip, BeltTelltaleSignal.WARNING, true, false))
        // 오래된 값·미수신은 해제가 아니다 → 다시 켜져도 반복하지 않음.
        assertNull(g.take(trip, BeltTelltaleSignal.UNKNOWN, true, false))
        assertNull(g.take(trip, BeltTelltaleSignal.WARNING, true, false))
        assertNull(g.take(trip, BeltTelltaleSignal.CLEAR, true, false))
        assertNotNull(g.take(trip, BeltTelltaleSignal.WARNING, true, false))
    }

    @Test fun `다른 음성 중이거나 백그라운드거나 운행 구간이 아니면 소모하지 않고 기다린다`() {
        val g = BeltTelltaleGuidance()
        val trip = onboard(TripPhase.ALIGHTING)
        assertNull(g.take(trip, BeltTelltaleSignal.WARNING, true, speechBusy = true))
        assertNull(g.take(trip, BeltTelltaleSignal.WARNING, false, false))
        assertNull(g.take(base, BeltTelltaleSignal.WARNING, true, false))
        assertNotNull(g.take(trip, BeltTelltaleSignal.WARNING, true, false))
    }

    @Test fun `운행 안내에 끊기면 다시 읽고 새 운행은 같은 경고 구간에서도 한 번 읽는다`() {
        val g = BeltTelltaleGuidance()
        val trip = onboard()
        assertNotNull(g.take(trip, BeltTelltaleSignal.WARNING, true, false))
        g.rearm()
        assertNotNull(g.take(trip, BeltTelltaleSignal.WARNING, true, false))
        assertNotNull(g.take(trip.copy(tripId = "other"), BeltTelltaleSignal.WARNING, true, false))
    }

    @Test fun `문구는 미확인 아이 이름을 덧붙이고 키에는 이름이 없다`() {
        val trip = onboard(belted = setOf("child-a", "child-b"))
        val cue = BeltTelltaleGuidance().take(trip, BeltTelltaleSignal.WARNING, true, false)!!
        assertEquals("벨트 경고등이 켜졌어요. 정차 후 벨트를 확인해 주세요. 벨트 미확인: 어린이 다, 어린이 라.", cue.text)
        assertFalse(cue.key.contains("어린이"))
        val allBelted = onboard(belted = base.children.map { it.id }.toSet())
        assertEquals("벨트 경고등이 켜졌어요. 정차 후 벨트를 확인해 주세요.", BeltTelltaleGuidance().take(allBelted, BeltTelltaleSignal.WARNING, true, false)!!.text)
        assertTrue(beltTelltaleSpeech(listOf("가", "나", "다", "라", "마")).endsWith("벨트 미확인: 가, 나, 다 외 2명."))
    }
}
