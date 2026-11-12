package com.hanhyo.pleosshuttle.audio

import org.junit.Assert.*
import org.junit.Test

class AudioFocusPolicyTest {
    @Test fun `재생 중 일시 포커스를 잃으면 일시정지하고 복귀 때 재생한다`() {
        val policy = AudioFocusPolicy()
        assertTrue(policy.playGranted())
        policy.changed(AudioFocusPolicy.Change.TRANSIENT_LOSS)
        assertEquals(AudioFocusPolicy.Playback.PAUSED, policy.playback)
        assertTrue(policy.resumeOnGain)
        policy.changed(AudioFocusPolicy.Change.GAIN)
        assertEquals(AudioFocusPolicy.Playback.PLAYING, policy.playback)
        assertFalse(policy.resumeOnGain)
    }

    @Test fun `일시 손실 콜백이 반복돼도 재생 복귀 의도를 보존한다`() {
        val policy = AudioFocusPolicy().apply { playGranted() }
        repeat(2) { policy.changed(AudioFocusPolicy.Change.TRANSIENT_LOSS) }
        assertTrue(policy.resumeOnGain)
        policy.changed(AudioFocusPolicy.Change.GAIN)
        assertEquals(AudioFocusPolicy.Playback.PLAYING, policy.playback)
    }

    @Test fun `포커스 손실 중 사용자가 일시정지하면 복귀해도 재생하지 않는다`() {
        val policy = AudioFocusPolicy().apply { playGranted() }
        policy.changed(AudioFocusPolicy.Change.TRANSIENT_LOSS)
        policy.pause()
        policy.changed(AudioFocusPolicy.Change.GAIN)
        assertEquals(AudioFocusPolicy.Playback.PAUSED, policy.playback)
        assertFalse(policy.resumeOnGain)
    }

    @Test fun `영구 포커스 손실 뒤 늦은 복귀는 정지 상태를 유지한다`() {
        val policy = AudioFocusPolicy().apply { playGranted() }
        policy.changed(AudioFocusPolicy.Change.TRANSIENT_LOSS)
        policy.changed(AudioFocusPolicy.Change.LOSS)
        policy.changed(AudioFocusPolicy.Change.GAIN)
        assertEquals(AudioFocusPolicy.Playback.STOPPED, policy.playback)
        assertFalse(policy.resumeOnGain)
    }

    @Test fun `백그라운드 전환 뒤 포커스 복귀로 자동 재생하지 않는다`() {
        val policy = AudioFocusPolicy().apply { playGranted() }
        policy.changed(AudioFocusPolicy.Change.TRANSIENT_LOSS)
        policy.background()
        policy.changed(AudioFocusPolicy.Change.GAIN)
        assertEquals(AudioFocusPolicy.Playback.PAUSED, policy.playback)
        assertFalse(policy.playGranted())
        policy.foreground()
        assertEquals(AudioFocusPolicy.Playback.PAUSED, policy.playback)
    }

    @Test fun `duck 콜백은 재생을 유지하며 음량을 낮추고 복귀 때 회복한다`() {
        val policy = AudioFocusPolicy().apply { playGranted() }
        policy.changed(AudioFocusPolicy.Change.CAN_DUCK)
        assertEquals(AudioFocusPolicy.Playback.PLAYING, policy.playback)
        assertEquals(.2f, policy.volume, .001f)
        policy.changed(AudioFocusPolicy.Change.GAIN)
        assertEquals(1f, policy.volume, .001f)
        assertEquals(AudioFocusPolicy.Playback.PLAYING, policy.playback)
    }

    @Test fun `정지나 수동 일시정지 상태는 포커스 콜백으로 재생하지 않는다`() {
        val policy = AudioFocusPolicy()
        policy.changed(AudioFocusPolicy.Change.CAN_DUCK)
        policy.changed(AudioFocusPolicy.Change.GAIN)
        assertEquals(AudioFocusPolicy.Playback.STOPPED, policy.playback)
        policy.playGranted()
        policy.pause()
        policy.changed(AudioFocusPolicy.Change.TRANSIENT_LOSS)
        policy.changed(AudioFocusPolicy.Change.GAIN)
        assertEquals(AudioFocusPolicy.Playback.PAUSED, policy.playback)
    }
}
