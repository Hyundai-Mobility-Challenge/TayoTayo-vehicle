package com.hanhyo.pleosshuttle.audio

/** One voice request at a time; late callbacks cannot finish a later request. */
internal class SpeechRequestGate {
    enum class Phase { INITIALIZING, SPEAKING }
    data class Request(val id: Long, val phase: Phase, val deadlineMillis: Long, val resumeRevision: Long?)
    data class Completion(val resumeMusic: Boolean)
    private var nextId = 0L
    var active: Request? = null
        private set

    fun begin(now: Long): Long? {
        if (active != null) return null
        val id = ++nextId
        active = Request(id, Phase.INITIALIZING, now + 10_000, null)
        return id
    }

    fun accepts(id: Long): Boolean = active?.id == id

    fun start(id: Long, now: Long, musicPlaying: Boolean, interactionRevision: Long): Boolean {
        val request = active?.takeIf { it.id == id && it.phase == Phase.INITIALIZING } ?: return false
        active = request.copy(phase = Phase.SPEAKING, deadlineMillis = now + 20_000,
            resumeRevision = interactionRevision.takeIf { musicPlaying })
        return true
    }

    fun clearResumeIntent() { active = active?.copy(resumeRevision = null) }

    fun timedOut(id: Long, now: Long): Boolean = active?.let { it.id == id && now >= it.deadlineMillis } == true

    fun finish(id: Long, interactionRevision: Long, foreground: Boolean, allowResume: Boolean): Completion? {
        val request = active?.takeIf { it.id == id } ?: return null
        active = null
        return Completion(allowResume && foreground && request.resumeRevision != null && request.resumeRevision == interactionRevision)
    }
}
