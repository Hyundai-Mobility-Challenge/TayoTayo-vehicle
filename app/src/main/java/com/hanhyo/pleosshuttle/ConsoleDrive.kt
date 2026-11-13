package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.Stop
import com.hanhyo.pleosshuttle.domain.TripPhase
import com.hanhyo.pleosshuttle.domain.TripState
import java.util.UUID
import kotlin.math.*

/**
 * 개발용 "Virtual Console 운전". 차량(Virtual Console)의 실제 속도·기어를 적분해 앱 안에서만 모의 위치를 움직인다.
 * GNSS·Pleos 내비·출결·도착 기록을 바꾸지 않으며, 만든 위치는 항상 SIMULATED로만 쓴다.
 * Android 타입을 쓰지 않아 JVM 단위 시험이 가능하다.
 */
data class GeoPoint(val latitude: Double, val longitude: Double)

data class ConsoleDriveStep(val position: GeoPoint, val remainingMeters: Double, val arrivedNearTarget: Boolean, val movedMeters: Double)

data class ConsoleDriveTarget(val point: GeoPoint, val name: String, val legIndex: Int, val toOrigin: Boolean)

object ConsoleDriveMath {
    const val DEFAULT_ARRIVAL_RADIUS_METERS = 40.0
    /** ETA 계산용 기본 속도(30km/h). 정지 중에도 남은 시간이 무한대가 되지 않게 한다. */
    const val DEFAULT_ETA_SPEED_MPS = 30.0 / 3.6
    /** 한 틱에서 적분할 최대 시간. 앱이 멈췄다 돌아와도 위치가 한 번에 튀지 않게 한다. */
    const val MAX_DT_SECONDS = 3.0

    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude); val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
        return 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** 기어 D이고 속도가 0보다 클 때만 목표를 향해 직선으로 speed×dt 만큼 움직인다. 목표를 넘지 않는다. */
    fun advance(position: GeoPoint, target: GeoPoint, speedMps: Double?, dtSeconds: Double, gearDrive: Boolean,
                arrivalRadiusMeters: Double = DEFAULT_ARRIVAL_RADIUS_METERS): ConsoleDriveStep {
        val total = distanceMeters(position, target)
        val speed = speedMps?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val dt = dtSeconds.takeIf { it.isFinite() && it > 0.0 }?.coerceAtMost(MAX_DT_SECONDS) ?: 0.0
        val step = if (gearDrive) speed * dt else 0.0
        val next = when {
            step <= 0.0 || total <= 0.0 -> position
            step >= total -> target
            else -> {
                val f = step / total
                GeoPoint(position.latitude + (target.latitude - position.latitude) * f,
                    position.longitude + (target.longitude - position.longitude) * f)
            }
        }
        val remaining = if (next == target) 0.0 else distanceMeters(next, target)
        return ConsoleDriveStep(next, remaining, remaining <= arrivalRadiusMeters, if (next == position) 0.0 else min(step, total))
    }

    fun etaSeconds(remainingMeters: Double, speedMps: Double?): Int {
        val speed = max(speedMps?.takeIf { it.isFinite() } ?: 0.0, DEFAULT_ETA_SPEED_MPS)
        return ceil(remainingMeters.coerceAtLeast(0.0) / speed).toInt().coerceIn(0, 86_400)
    }

    /** 다음 목표: 운행 중이면 방문 순서의 현재 정류장, 정류장을 모두 들렀으면 출발지(하원)·기관(등원) = trip.origin. */
    fun target(trip: TripState): ConsoleDriveTarget? {
        if (trip.phase != TripPhase.DRIVING && trip.phase != TripPhase.ALIGHTING) return null
        val stop = trip.currentStop
        return if (stop != null) ConsoleDriveTarget(stop.point(), stop.name, trip.currentStopIndex, false)
        else if (trip.phase == TripPhase.DRIVING) ConsoleDriveTarget(trip.origin.point(), trip.origin.name, trip.currentStopIndex, true)
        else null
    }

    /** 현재 구간의 시작점: 첫 구간은 출발지, 그 뒤는 바로 앞에서 마친 정류장. */
    fun legStart(trip: TripState): GeoPoint {
        val previousId = trip.routeOrder.getOrNull(trip.currentStopIndex - 1)
        val previous = previousId?.let { id -> trip.stops.find { it.id == id } }
        return (previous ?: trip.origin).point()
    }

    private fun Stop.point() = GeoPoint(latitude, longitude)
}

data class ConsoleDriveFrame(
    val position: GeoPoint, val target: ConsoleDriveTarget, val remainingMeters: Int, val etaSeconds: Int,
    val nearTarget: Boolean, val speedMps: Double?, val gearDrive: Boolean,
)

/** 운행 하나에 묶인 Virtual Console 운전 상태. 구간(currentStopIndex)이 바뀌면 방금 마친 정류장에서 다시 출발한다. */
class ConsoleDrive(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    @Volatile var tripId: String? = null; private set
    @Volatile var routeId: String? = null; private set
    var frame: ConsoleDriveFrame? = null; private set
    private var position: GeoPoint? = null
    private var legIndex = -1
    private var lastTickAt = 0L
    val running get() = tripId != null

    fun start(trip: TripState): Boolean {
        val target = ConsoleDriveMath.target(trip) ?: return false
        tripId = trip.tripId; routeId = "console-${UUID.randomUUID()}"
        position = ConsoleDriveMath.legStart(trip); legIndex = target.legIndex
        lastTickAt = clock(); frame = null
        return true
    }

    /** eligible이 아니거나 다른 운행이면 멈춘다. 계속 유지되면 true. */
    fun retain(eligible: Boolean, currentTripId: String): Boolean {
        if (!eligible || tripId != currentTripId) stop()
        return running
    }

    fun stop() { tripId = null; routeId = null; frame = null; position = null; legIndex = -1 }

    fun tick(trip: TripState, speedMps: Double?, gearDrive: Boolean): ConsoleDriveFrame? {
        if (!running || trip.tripId != tripId) return null
        val target = ConsoleDriveMath.target(trip) ?: run { stop(); return null }
        val at = clock()
        val dt = (at - lastTickAt).coerceAtLeast(0) / 1000.0
        lastTickAt = at
        if (target.legIndex != legIndex) { position = ConsoleDriveMath.legStart(trip); legIndex = target.legIndex }
        // 정차 중(ALIGHTING)에는 움직이지 않는다. 다음 구간 출발 시 마친 정류장에서 시작한다.
        val moving = trip.phase == TripPhase.DRIVING && gearDrive
        val step = ConsoleDriveMath.advance(position!!, target.point, speedMps, dt, moving)
        position = step.position
        val remaining = step.remainingMeters.roundToInt()
        return ConsoleDriveFrame(step.position, target, remaining, ConsoleDriveMath.etaSeconds(step.remainingMeters, speedMps),
            step.arrivedNearTarget, speedMps, gearDrive).also { frame = it }
    }
}

/** Virtual Console 운전을 계속할지 정하는 입력. foreground·uxRestricted는 기록용이며 판단에 쓰지 않는다. */
data class ConsoleDriveContext(
    val debug: Boolean, val sdkMode: Boolean, val serverBound: Boolean, val phase: TripPhase, val storageOk: Boolean,
    val foreground: Boolean = true, val uxRestricted: Boolean = false,
)

object ConsoleDrivePolicy {
    /**
     * 운행이 끝나거나(END_REVIEW·ENDED·PREPARING) SDK 모드를 벗어나거나 서버 연결이 풀릴 때만 멈춘다.
     * 주행 중 Pleos가 앱을 가리는 UX 제한·앱 이탈(foreground=false)로는 멈추지 않는다(개발용 도구라 운행 내내 유지).
     */
    fun keep(c: ConsoleDriveContext): Boolean = c.debug && c.sdkMode && c.serverBound && c.storageOk &&
        (c.phase == TripPhase.DRIVING || c.phase == TripPhase.ALIGHTING)
}

/** 위치 공유 서비스(같은 프로세스)가 Virtual Console 운전 중에는 Pleos 위치 보고를 쉬도록 알리는 메모리 신호. 저장하지 않는다. */
object ConsoleDriveBridge {
    @Volatile var activeTripId: String? = null
}

/** Virtual Console 운전 화면 문구. */
object ConsoleDriveText {
    /** AAOS GEAR_SELECTION 값(P=4, R=2, N=1, D=8). */
    fun gearLabel(gear: Int?): String = when (gear) { 4 -> "P"; 2 -> "R"; 1 -> "N"; 8 -> "D"; null -> "미확인"; else -> "기타($gear)" }
    fun gearLabel(gear: com.hanhyo.pleosshuttle.domain.Gear): String = gear.label
    fun distance(meters: Int): String = if (meters >= 1000) String.format(java.util.Locale.ROOT, "%.1fkm", meters / 1000.0) else "${meters}m"
    fun eta(seconds: Int): String = if (seconds < 60) "${seconds}초" else "${seconds / 60}분 ${seconds % 60}초"
    fun cardLine(frame: ConsoleDriveFrame): String =
        "Virtual Console 운전 · ${frame.target.name}까지 ${frame.remainingMeters}m" +
            if (frame.nearTarget) " · 도착 지점 근처 · 정차 후 도착·정차 확인을 눌러 주세요." else ""
    fun status(frame: ConsoleDriveFrame?, speedKph: Double?, gear: String): String {
        val speed = speedKph?.let { "${it.roundToInt()}km/h" } ?: "미수신"
        if (frame == null) return "속도 $speed · 기어 $gear"
        return "속도 $speed · 기어 $gear · ${frame.target.name}까지 ${distance(frame.remainingMeters)} · 약 ${eta(frame.etaSeconds)}" +
            if (frame.nearTarget) " · 도착 지점 근처" else ""
    }
}
