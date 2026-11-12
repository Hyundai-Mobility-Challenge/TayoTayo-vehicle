package com.hanhyo.pleosshuttle.audio

/** Platform-independent playback intent. A late focus gain never overrides a user pause. */
class AudioFocusPolicy {
    enum class Playback { STOPPED, PLAYING, PAUSED }
    enum class Change { GAIN, LOSS, TRANSIENT_LOSS, CAN_DUCK }

    var playback: Playback = Playback.STOPPED
        private set
    var volume: Float = 1f
        private set
    var resumeOnGain: Boolean = false
        private set
    var foreground: Boolean = true
        private set

    fun playGranted(): Boolean {
        if (!foreground) return false
        playback = Playback.PLAYING
        volume = 1f
        resumeOnGain = false
        return true
    }

    fun pause() {
        if (playback != Playback.STOPPED) playback = Playback.PAUSED
        resumeOnGain = false
        volume = 1f
    }

    fun stop() {
        playback = Playback.STOPPED
        resumeOnGain = false
        volume = 1f
    }

    fun background() {
        foreground = false
        pause()
    }

    fun foreground() { foreground = true }

    fun changed(change: Change) {
        when (change) {
            Change.GAIN -> {
                volume = 1f
                if (resumeOnGain && foreground) playback = Playback.PLAYING
                resumeOnGain = false
            }
            Change.LOSS -> stop()
            Change.TRANSIENT_LOSS -> {
                // Repeated loss callbacks must not erase an earlier resume intention.
                resumeOnGain = resumeOnGain || playback == Playback.PLAYING
                if (playback == Playback.PLAYING) playback = Playback.PAUSED
                volume = 1f
            }
            Change.CAN_DUCK -> {
                if (playback == Playback.PLAYING) volume = .2f
            }
        }
    }
}
