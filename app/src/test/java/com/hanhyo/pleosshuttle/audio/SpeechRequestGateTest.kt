package com.hanhyo.pleosshuttle.audio

import org.junit.Assert.*
import org.junit.Test

class SpeechRequestGateTest {
    @Test fun `초기화 실패를 마치면 새 요청으로 다시 시도한다`() {
        val gate = SpeechRequestGate()
        val first = gate.begin(1_000)!!
        assertNull(gate.begin(1_001))
        assertFalse(gate.finish(first, 0, true, true)!!.resumeMusic)
        val retried = gate.begin(1_002)!!
        assertNotEquals(first, retried)
        assertFalse(gate.accepts(first))
        assertTrue(gate.accepts(retried))
    }

    @Test fun `취소한 요청의 늦은 완료는 새 요청이나 음악 의도를 바꾸지 않는다`() {
        val gate = SpeechRequestGate()
        val cancelled = gate.begin(1_000)!!
        assertTrue(gate.start(cancelled, 1_100, true, 4))
        assertFalse(gate.finish(cancelled, 5, true, false)!!.resumeMusic)
        val current = gate.begin(1_200)!!
        assertNull(gate.finish(cancelled, 5, true, true))
        assertEquals(current, gate.active?.id)
        assertEquals(SpeechRequestGate.Phase.INITIALIZING, gate.active?.phase)
    }

    @Test fun `초기화와 발화 시간 제한은 각각 적용되고 이전 타이머는 새 요청에 적용되지 않는다`() {
        val gate = SpeechRequestGate()
        val first = gate.begin(1_000)!!
        assertFalse(gate.timedOut(first, 10_999))
        assertTrue(gate.timedOut(first, 11_000))
        gate.finish(first, 0, true, true)
        val next = gate.begin(12_000)!!
        assertFalse(gate.timedOut(first, 50_000))
        assertTrue(gate.start(next, 12_500, false, 0))
        assertFalse(gate.start(next, 12_600, true, 1))
        assertFalse(gate.timedOut(next, 32_499))
        assertTrue(gate.timedOut(next, 32_500))
    }

    @Test fun `발화 전 음악이 재생 중이고 사용자 조작이 없을 때만 완료 후 복귀한다`() {
        val gate = SpeechRequestGate()
        fun finish(playing: Boolean, revisionAfter: Long, foreground: Boolean): Boolean {
            val id = gate.begin(1_000)!!
            gate.start(id, 1_100, playing, 4)
            return gate.finish(id, revisionAfter, foreground, true)!!.resumeMusic
        }
        assertTrue(finish(true, 4, true))
        assertFalse(finish(false, 4, true))
        assertFalse(finish(true, 5, true))
        assertFalse(finish(true, 4, false))
    }

    @Test fun `사용자 일시정지는 발화가 끝나도 음악을 다시 켜지 않는다`() {
        val gate = SpeechRequestGate()
        val id = gate.begin(1_000)!!
        gate.start(id, 1_100, true, 4)
        gate.clearResumeIntent()
        assertFalse(gate.finish(id, 4, true, true)!!.resumeMusic)
        assertNull(gate.finish(id, 4, true, true))
    }
}
