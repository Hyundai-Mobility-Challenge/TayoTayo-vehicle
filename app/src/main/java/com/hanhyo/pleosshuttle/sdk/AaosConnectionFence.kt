package com.hanhyo.pleosshuttle.sdk

/** Main-thread owned. A reconnect invalidates outstanding reads even if the manager is reused. */
internal class AaosConnectionFence<T : Any> {
    class Capture<T : Any> internal constructor(val manager: T, val generation: Long)
    var manager: T? = null
        private set
    var generation: Long = 0L
        private set

    fun replace(manager: T?) {
        generation++
        this.manager = manager
    }

    fun capture(): Capture<T>? = manager?.let { Capture(it, generation) }
    fun accepts(capture: Capture<T>): Boolean = manager != null &&
        manager === capture.manager && generation == capture.generation
}
