package com.hanhyo.pleosshuttle.sdk

import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.constants.WaypointIndex
import com.hanhyo.pleosshuttle.NavigationStopEta
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.CurrentLocationInfo
import com.hanhyo.pleosshuttle.TrackingLocation
import com.hanhyo.pleosshuttle.NavigationTracking
import com.hanhyo.pleosshuttle.TrackingNavigation
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlin.math.abs

/** Owns up to three consecutive approved stops (two waypoints + destination). No pairwise matrix. */
class RealNaviAdapter(context: Context, private val diagnosticsEnabled: Boolean = true, private val onUpdate: (NaviSnapshot) -> Unit) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var helper: NaviHelper? = null
    private var listener: NaviHelperEventListener? = null
    var trackLocations = false
        set(value) { if (field && !value) telemetry.invalidate(false); field = value }
    private val telemetry = NavigationTracking()
    val trackingLocation: TrackingLocation? get() = telemetry.location
    val trackingNavigation: TrackingNavigation get() = telemetry.snapshot()
    internal val trackingDiagnostic get() = telemetry.diagnostic()
    internal var observedWaypointInfo: List<WaypointInfo> = emptyList(); private set
    fun invalidateTracking() = telemetry.invalidate(false)
    fun expectTrackingStops(points: List<Pair<Double,Double>>, names: List<String> = emptyList()) = telemetry.expectStops(points,names)
    private var requestedStops: List<NaviDestination> = emptyList()
    private var running = false
    private var closed = false
    private var epoch = 0L
    private var requestSerial = 0L
    private var subscriptionSerial = 0L
    private var ownsRoute = false
    private var route: RouteStateInfo? = null
    private var routeAt = 0L
    private var destination: DestinationInfo? = null
    private var destinationAt = 0L
    private var requestedAt = 0L
    private var snapshot = NaviSnapshot(versions = mapOf("NaviHelper SDK" to "3.0.5", "Navi Service" to packageVersion("ai.pleos.playground.service.navi")))
    val current: NaviSnapshot get() = snapshot

    fun initialize() = onStart()
    fun onStart() {
        if (running || closed) return
        running = true
        val currentEpoch = ++epoch
        try {
            val h = NaviHelper(app)
            helper = h
            val events = object : NaviHelperEventListener {
                override fun onCurrentLocationInfo(info: CurrentLocationInfo) = dispatch(currentEpoch) {
                    if (trackLocations) telemetry.position(info.latitude, info.longitude, info.routeId, info.currentRouteIndex)
                }
                override fun onRouteStarted(info: RouteStartInfo) = dispatch(currentEpoch) { telemetry.invalidate(); refresh() }
                override fun onReRouteEnded() = dispatch(currentEpoch) { telemetry.completed(); refresh() }
                override fun onRouteOptionChanged(option: RouteOption) = dispatch(currentEpoch) { telemetry.invalidate(); refresh() }
                override fun onWaypointChanged(info: WaypointChangedInfo) = dispatch(currentEpoch) { telemetry.invalidate(); refresh() }
                override fun onWayPointInfo(infos: List<WaypointInfo>) = dispatch(currentEpoch) {
                    observedWaypointInfo=infos.toList()
                    if (trackLocations) telemetry.waypoints(infos.map { NavigationStopEta(it.latitude,it.longitude,it.remainTime,it.remainDistance,System.currentTimeMillis(),it.name) })
                }
                override fun onDestinationInfo(info: DestinationInfo) = dispatch(currentEpoch) {
                    if (trackLocations) telemetry.destination(info.latitude, info.longitude, info.remainTime, info.remainDistance,info.name)
                    destination = info; destinationAt = System.currentTimeMillis()
                    snapshot = snapshot.copy(destination = SdkObservation(NaviDestination(info.name, info.latitude, info.longitude), destinationAt, "실제 NaviHelper 목적지 조회", SdkOutcome.TARGET_OBSERVED))
                    captureInitial(); classifyRequest(); publish()
                }
                override fun onRouteStateInfo(info: RouteStateInfo) = dispatch(currentEpoch) {
                    telemetry.routeState(info.naviMode == NaviMode.ROUTE_GUIDING || info.naviMode == NaviMode.SIMULATION_GUIDING, info.naviMode == NaviMode.SIMULATION_GUIDING, info.isRouting)
                    route = info; routeAt = System.currentTimeMillis()
                    snapshot = snapshot.copy(routeState = SdkObservation("mode=${info.naviMode}, isRouting=${info.isRouting}, terms=${info.termsAgreement}", routeAt, "실제 NaviHelper 안내 상태", SdkOutcome.TARGET_OBSERVED), routeMode = info.naviMode.name, routeIsRouting = info.isRouting, termsAccepted = info.termsAgreement)
                    captureInitial(); classifyRequest(); publish()
                }
                override fun onNavigationStatus(status: NavigationStatus) = dispatch(currentEpoch) {
                    if (status != NavigationStatus.ROUTE_DRIVE) telemetry.guidance(false, false)
                    snapshot = snapshot.copy(navigationStatus = status.name); log("NavigationStatus=$status")
                }
                override fun onDestinationChanged() = dispatch(currentEpoch) { telemetry.invalidate(false); log("DestinationChanged; re-reading actual destination"); refresh() }
                override fun onRoutingCompleted() = dispatch(currentEpoch) { telemetry.completed(); log("RoutingCompleted callback; matching destination and mode separately"); refresh() }
                override fun onRouteCancelled() = dispatch(currentEpoch) {
                    telemetry.invalidate()
                    ownsRoute = false
                    log("RouteCancelled callback observed; target state requery follows")
                    snapshot = snapshot.copy(busy = false, message = "경로 취소 이벤트 관측 · 목적지 재조회 중")
                    refresh()
                }
                override fun onError(errorCode: NaviErrorCode) = dispatch(currentEpoch) {
                    telemetry.invalidate()
                    snapshot = snapshot.copy(outcome = SdkOutcome.ERROR, busy = false, message = "내비 오류: $errorCode · 다음 지점 정보로 수동 대안 사용")
                    log("Navi ERROR code=$errorCode; no automatic retry")
                }
            }
            listener = events
            h.initialize()
            h.addListener(events)
            log("Navi initialize returned; SUBSCRIBE epoch=$currentEpoch key=navi serial=${++subscriptionSerial} app call returned. Existing route unchanged.")
            main.postDelayed({ if (running && epoch == currentEpoch) refresh() }, 700)
            main.post(poll)
        } catch (e: Exception) {
            snapshot = snapshot.copy(outcome = classifyError(e), message = "내비 초기화 ${e.javaClass.simpleName}: ${e.message}")
            log(snapshot.message)
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            refresh()
            main.postDelayed(this, 2_500)
        }
    }
    fun refresh() {
        if (!running) return
        val h = helper ?: return
        try { h.getDestinationInfo(); h.getRouteStateInfo(); if (trackLocations) { h.getCurrentLocationInfo(); h.getWaypointInfo() } }
        catch (e: Exception) {
            snapshot = snapshot.copy(outcome = classifyError(e), message = "내비 조회 ${e.javaClass.simpleName}: ${e.message}")
            log(snapshot.message)
        }
    }

    /** Called after an approved trip transition or explicit retry; requests one approved stop. */
    fun requestNextStop(name: String, latitude: Double, longitude: Double) = requestStops(listOf(NaviDestination(name,latitude,longitude)))
    fun requestStops(stops: List<NaviDestination>) {
        if (stops.isEmpty() || stops.size>3 || stops.any { it.name.isBlank() || !it.latitude.isFinite() || !it.longitude.isFinite() || it.latitude !in -90.0..90.0 || it.longitude !in -180.0..180.0 }) return
        val (name,latitude,longitude) = stops.last()
        if (snapshot.busy) { log("Duplicate navigation request blocked"); return }
        if (!running || helper == null) { snapshot = snapshot.copy(outcome = SdkOutcome.UNVERIFIED, message = "내비 연결 미확인 · 다음 지점 정보로 수동 안내"); publish(); return }
        if (name.isBlank() || !latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || (latitude == 0.0 && longitude == 0.0)) {
            snapshot = snapshot.copy(outcome = SdkOutcome.ERROR, message = "유효한 승인 지점 이름·좌표 필요"); publish(); return
        }
        if (route == null || System.currentTimeMillis() - routeAt !in 0..6_000 || destination == null) {
            snapshot = snapshot.copy(outcome = SdkOutcome.UNVERIFIED, message = "현재 내비 상태를 먼저 조회해야 함"); refresh(); publish(); return
        }
        if (route?.termsAgreement != true) {
            snapshot = snapshot.copy(outcome = SdkOutcome.PERMISSION_REQUIRED, message = "내비 약관 동의 상태 미확인 · 앱에서 자동 동의하지 않음"); publish(); return
        }
        val target = NaviDestination(name, latitude, longitude)
        requestedStops = stops.toList()
        telemetry.expectStops(stops.map { it.latitude to it.longitude },stops.map { it.name })
        val currentSerial = ++requestSerial
        telemetry.invalidate()
        requestedAt = System.currentTimeMillis()
        snapshot = snapshot.copy(requested = target, busy = true, outcome = SdkOutcome.UNVERIFIED, message = "다음 지점 요청 중")
        log("Navi before=${destination} route=$route; request name=$name latitude=$latitude longitude=$longitude")
        try {
            val destinationRoute = RouteInfo(latitude=latitude,longitude=longitude,poiName=name,poiId="",poiSubId="",address="승인된 하차 지점")
            if(stops.size==1) helper!!.requestRoute(destinationRoute)
            else helper!!.requestRouteWithWaypoints(RouteWithWaypointsRequest(destinationRoute,stops.dropLast(1).mapIndexed { i,p ->
                RequestWaypointInfo(latitude=p.latitude,longitude=p.longitude,poiName=p.name,
                    waypointIndex=if(i==0)WaypointIndex.FIRST else WaypointIndex.SECOND,poiId="",poiSubId="",address="승인된 하차 지점")
            }))
            ownsRoute = true
            snapshot = snapshot.copy(outcome = SdkOutcome.UNVERIFIED, message = "요청 전송 · 수락 이벤트/상태 미확인 · 순정 화면 별도 확인")
            log("Navi request returned; response does not establish route completion")
            main.postDelayed({ if (running && currentSerial == requestSerial) refresh() }, 1_000)
            main.postDelayed({
                if (running && currentSerial == requestSerial && snapshot.busy) {
                    classifyRequest()
                    if (snapshot.busy) snapshot = snapshot.copy(busy = false, outcome = if (destinationAt >= requestedAt || routeAt >= requestedAt) SdkOutcome.PARTIAL else SdkOutcome.TIMEOUT, message = "요청 후 목적지/안내 상태 모두 일치하지 않음 · 다음 지점 정보 유지 · 자동 재요청 없음")
                    log("Navi final=${snapshot.outcome} destination=$destination route=$route")
                }
            }, 15_000)
        } catch (e: Exception) {
            snapshot = snapshot.copy(busy = false, outcome = classifyError(e), message = "경로 요청 ${e.javaClass.simpleName}: ${e.message} · 수동 안내 사용")
            log(snapshot.message)
        }
    }
    private fun classifyRequest() {
        val wanted = snapshot.requested ?: return
        if (!snapshot.busy || destinationAt < requestedAt || routeAt < requestedAt) return
        val actual = destination ?: return
        val coords = abs(actual.latitude - wanted.latitude) < 0.00001 && abs(actual.longitude - wanted.longitude) < 0.00001
        val name = actual.name == wanted.name
        val guiding = route?.naviMode == NaviMode.ROUTE_GUIDING || route?.naviMode == NaviMode.SIMULATION_GUIDING
        if (coords && name && guiding) {
            snapshot = snapshot.copy(busy = false, outcome = SdkOutcome.TARGET_OBSERVED, message = "SDK 목적지 이름·좌표·안내 모드 일치 관측 · 순정 화면 반영은 별도 확인")
            log("Navi TARGET_OBSERVED name+coordinates+guiding; native screen not automatically verified")
        }
    }
    private fun captureInitial() {
        if (snapshot.initialSnapshot == null && route != null && destination != null) {
            val initial = "${System.currentTimeMillis()} destination=$destination route=$route"
            snapshot = snapshot.copy(initialSnapshot = initial)
            if (diagnosticsEnabled) runCatching { File(app.filesDir, "navi-initial-snapshot.txt").writeText(initial) }
            log("Initial navigation snapshot saved without mutation")
        }
    }
    fun showNavigation() {
        try { helper?.requestNavigationScreen(NavigationScreenRequest.Foreground); log("Native navigation foreground requested; visual observation still required") }
        catch (e: Exception) { log("Navigation screen ERROR $e") }
    }
    /** Explicit button only. Does not run on initialize, backgrounding, or close. */
    fun cancelOwnRoute() {
        if (!ownsRoute) { log("No route owned by this adapter; cancel not sent"); return }
        try { helper?.cancelRoute(); snapshot = snapshot.copy(outcome = SdkOutcome.UNVERIFIED, message = "시험 경로 취소 전송 · 수락 이벤트/상태 미확인"); publish() }
        catch (e: Exception) { log("Cancel ERROR $e") }
    }
    fun onStop() {
        if (!running) return
        val stoppedEpoch = epoch
        running = false; epoch++; requestSerial++; telemetry.invalidate()
        main.removeCallbacks(poll)
        var cleanupFailed = false
        listener?.let { events -> runCatching { helper?.removeListener(events) }.onFailure { cleanupFailed = true; log("Navi listener cleanup ERROR $it") } }
        listener = null
        runCatching { helper?.release() }.onFailure { log("Navi release ERROR $it") }
        helper = null
        snapshot = snapshot.copy(busy = false, destination = snapshot.destination.copy(outcome = SdkOutcome.UNVERIFIED, error = "앱 복귀 후 재조회 필요"), routeState = snapshot.routeState.copy(outcome = SdkOutcome.UNVERIFIED, error = "앱 복귀 후 재조회 필요"), message = "앱 비활성화 · 마지막 안내 관측 보존 · 경로 자동 취소 없음")
        log("Navi UNSUBSCRIBE epoch=$stoppedEpoch key=navi app call failed=$cleanupFailed; native route not cancelled")
    }
    fun close() { onStop(); closed = true }
    private fun dispatch(expectedEpoch: Long, action: () -> Unit) { main.post { if (running && epoch == expectedEpoch) action() } }
    private fun classifyError(e: Exception) = if (e is SecurityException) SdkOutcome.PERMISSION_REQUIRED else SdkOutcome.ERROR
    private fun packageVersion(name: String) = runCatching { @Suppress("DEPRECATION") app.packageManager.getPackageInfo(name, 0).versionName ?: "미확인" }.getOrDefault("미설치 또는 조회 권한/가시성 미확인")
    private fun log(message: String) {
        if (!diagnosticsEnabled) return
        val line = "${System.currentTimeMillis()} $message"
        snapshot = snapshot.copy(diagnostics = (snapshot.diagnostics + line).takeLast(80))
        runCatching { File(app.filesDir, "sdk-navi.log").appendText(line + "\n") }
        publish()
    }
    private fun publish() = onUpdate(snapshot)
}
