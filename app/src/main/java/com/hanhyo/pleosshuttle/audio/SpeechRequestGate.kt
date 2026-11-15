package com.hanhyo.pleosshuttle.audio

/** One voice request at a time; late callbacks cannot finish a later request. */
internal class SpeechRequestGate {
    enum class Phase { INITIALIZING, SPEAKING }
    data class Request(val id: Long, val phase: Phase, val deadlineMillis: Long)
    private var nextId = 0L
    var active: Request? = null
        private set

    fun begin(now: Long): Long? {
        if (active != null) return null
        val id = ++nextId
        active = Request(id, Phase.INITIALIZING, now + 10_000)
        return id
    }

    fun accepts(id: Long): Boolean = active?.id == id

    fun start(id: Long, now: Long): Boolean {
        val request = active?.takeIf { it.id == id && it.phase == Phase.INITIALIZING } ?: return false
        active = request.copy(phase = Phase.SPEAKING, deadlineMillis = now + 20_000)
        return true
    }

    fun timedOut(id: Long, now: Long): Boolean = active?.let { it.id == id && now >= it.deadlineMillis } == true

    /** [id]가 지금 요청이면 끝내고 true. 이미 끝났거나 다른 요청이면 false(늦은 콜백 무시). */
    fun finish(id: Long): Boolean {
        if (active?.id != id) return false
        active = null
        return true
    }
}
