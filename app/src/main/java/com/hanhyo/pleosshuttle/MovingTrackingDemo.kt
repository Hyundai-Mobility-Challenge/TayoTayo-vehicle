package com.hanhyo.pleosshuttle

import java.util.UUID
import kotlin.math.*

enum class MovingDemoPhase { STOPPED, RUNNING, PAUSED, ARRIVED }
data class MovingDemoFrame(val latitude: Double, val longitude: Double, val seconds: Int, val meters: Int, val progress: Int)

/** Explicit synthetic 90-second approach. Never changes GNSS, routing or attendance. */
class MovingTrackingDemo(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    var phase = MovingDemoPhase.STOPPED; private set
    var key: String? = null; private set
    var routeId: String? = null; private set
    private var latitude = 0.0
    private var longitude = 0.0
    private var startAt = 0L
    private var elapsed = 0L
    private val duration = 90_000L
    fun start(key: String, latitude: Double, longitude: Double) {
        require(latitude.isFinite() && longitude.isFinite() && latitude in -85.0..85.0 && longitude in -180.0..180.0)
        require(latitude != 0.0 || longitude != 0.0)
        this.key = key; this.latitude = latitude; this.longitude = longitude
        routeId = "demo-${UUID.randomUUID()}"; elapsed = 0; startAt = clock(); phase = MovingDemoPhase.RUNNING
    }
    fun pause() { if (phase == MovingDemoPhase.RUNNING) { elapsed = elapsedNow(); phase = if(elapsed >= duration) MovingDemoPhase.ARRIVED else MovingDemoPhase.PAUSED } }
    fun resume() { if (phase == MovingDemoPhase.PAUSED) { startAt = clock(); phase = MovingDemoPhase.RUNNING } }
    fun retain(eligible: Boolean, currentKey: String): Boolean {
        if (!eligible || key != currentKey) stop()
        return phase != MovingDemoPhase.STOPPED
    }
    fun stop() { phase = MovingDemoPhase.STOPPED; key = null; routeId = null; elapsed = 0 }
    private fun elapsedNow() = (elapsed + if(phase == MovingDemoPhase.RUNNING) (clock()-startAt).coerceAtLeast(0) else 0).coerceAtMost(duration)
    fun sample(): MovingDemoFrame? {
        if (phase == MovingDemoPhase.STOPPED) return null
        val spent = elapsedNow(); val fraction = spent.toDouble()/duration
        if (spent >= duration) { elapsed = duration; phase = MovingDemoPhase.ARRIVED }
        val fromLat = (latitude-.003).coerceAtLeast(-85.0)
        val fromLon = (longitude-.004).coerceAtLeast(-180.0)
        // A schematic bend for demo movement; deliberately NOT claimed as road geometry.
        val lat = if(fraction<.5) fromLat else fromLat+(latitude-fromLat)*(fraction-.5)*2
        val lon = if(fraction<.5) fromLon+(longitude-fromLon)*fraction*2 else longitude
        val remainingMeters = (abs(latitude-lat)*111_000 + abs(longitude-lon)*111_000*cos(Math.toRadians(latitude))).roundToInt()
        return MovingDemoFrame(lat, lon, ceil((duration-spent)/1000.0).toInt(), remainingMeters, (fraction*100).roundToInt())
    }
}
