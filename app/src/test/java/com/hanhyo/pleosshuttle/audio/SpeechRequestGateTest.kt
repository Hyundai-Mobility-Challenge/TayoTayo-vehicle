package com.hanhyo.pleosshuttle.audio

import org.junit.Assert.*
import org.junit.Test

class SpeechRequestGateTest {
    @Test fun `초기화 실패를 마치면 새 요청으로 다시 시도한다`() {
        val gate = SpeechRequestGate()
        val first = gate.begin(1_000)!!
        assertNull(gate.begin(1_001))
        assertTrue(gate.finish(first))
        val retried = gate.begin(1_002)!!
        assertNotEquals(first, retried)
        assertFalse(gate.accepts(first))
        assertTrue(gate.accepts(retried))
    }

    @Test fun `취소한 요청의 늦은 완료는 새 요청을 바꾸지 않는다`() {
        val gate = SpeechRequestGate()
        val cancelled = gate.begin(1_000)!!
        assertTrue(gate.start(cancelled, 1_100))
        assertTrue(gate.finish(cancelled))
        val current = gate.begin(1_200)!!
        assertFalse(gate.finish(cancelled))
        assertEquals(current, gate.active?.id)
        assertEquals(SpeechRequestGate.Phase.INITIALIZING, gate.active?.phase)
    }

    @Test fun `초기화와 발화 시간 제한은 각각 적용되고 이전 타이머는 새 요청에 적용되지 않는다`() {
        val gate = SpeechRequestGate()
        val first = gate.begin(1_000)!!
        assertFalse(gate.timedOut(first, 10_999))
        assertTrue(gate.timedOut(first, 11_000))
        gate.finish(first)
        val next = gate.begin(12_000)!!
        assertFalse(gate.timedOut(first, 50_000))
        assertTrue(gate.start(next, 12_500))
        assertFalse(gate.start(next, 12_600))
        assertFalse(gate.timedOut(next, 32_499))
        assertTrue(gate.timedOut(next, 32_500))
    }

    @Test fun `같은 요청을 두 번 끝내지 않는다`() {
        val gate = SpeechRequestGate()
        val id = gate.begin(1_000)!!
        gate.start(id, 1_100)
        assertTrue(gate.finish(id))
        assertFalse(gate.finish(id))
        assertNull(gate.active)
    }
}
