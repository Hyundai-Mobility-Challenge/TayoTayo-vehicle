package com.hanhyo.pleosshuttle

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hanhyo.pleosshuttle.audio.AudioSnapshot
import com.hanhyo.pleosshuttle.audio.LocalAudioAdapter
import com.hanhyo.pleosshuttle.domain.*
import com.hanhyo.pleosshuttle.domain.VehicleSnapshot
import com.hanhyo.pleosshuttle.sdk.RealVehicleAdapter
import com.hanhyo.pleosshuttle.sdk.RealNaviAdapter
import com.hanhyo.pleosshuttle.sdk.SdkObservation
import com.hanhyo.pleosshuttle.sdk.SdkOutcome
import com.hanhyo.pleosshuttle.sdk.NaviSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class TrialScenario(val label: String) {
    PARKED("정차 · P"), MOVING("이동 중"), SIGNAL("신호 정차 · D"), UNKNOWN("속도·기어 미확인"),
    STALE("관측 갱신 중단"), WARNING("접근 물체 경고"), DISCONNECTED("센서 연결 끊김"),
    OCCUPIED("좌석 점유 불일치"), ACCEPT_ONLY("문 수락 후 잠금 유지")
}

class ShuttleViewModel(application: Application) : AndroidViewModel(application) {
    private val store = TripStore(application)
    private val trialPrefs = application.getSharedPreferences("simulation-settings", 0)
    var engine = ShuttleEngine(); private set
    var trip by mutableStateOf(engine.state); private set
    var vehicle by mutableStateOf(engine.vehicle); private set
    var surroundings by mutableStateOf(engine.surroundings); private set
    var now by mutableStateOf(System.currentTimeMillis()); private set
    var message by mutableStateOf(engine.lastMessage); private set
    var actionError by mutableStateOf<String?>(null); private set
    var storageError by mutableStateOf<String?>(null); private set
    var scenario by mutableStateOf(runCatching { TrialScenario.valueOf(trialPrefs.getString("scenario", "PARKED")!!) }.getOrDefault(TrialScenario.UNKNOWN)); private set
    var panel by mutableStateOf("flow")
    // 경로 확인 화면 설정. 운행 파일과 따로 저장하며, 보호자 알림은 스위치 상태만 남기고 실제로 보내지 않는다.
    private val routePrefs = application.getSharedPreferences("route-settings", 0)
    var routeCriteriaSeen by mutableStateOf(routePrefs.getBoolean("criteriaSeen", false)); private set
    var sdkVehicle by mutableStateOf(com.hanhyo.pleosshuttle.sdk.VehicleSnapshot()); private set
    var navi by mutableStateOf(NaviSnapshot()); private set
    var simulatedNavigation by mutableStateOf("아직 안내하지 않음"); private set
    var audioState by mutableStateOf(AudioSnapshot()); private set
    var simulatedDoorRequests by mutableStateOf(0); private set
    var autoSimulationMessage by mutableStateOf("모의 자동 해제 미실행"); private set
    var autoSimulationUnlocked by mutableStateOf(false); private set
    private var simLocked = trialPrefs.getBoolean("doorLocked", true)
    private var simGeneration = 1L
    private var sdkGeneration = 1L
    private var lastSdkConnected = false
    private var foreground = false
    private var ticker: Job? = null
    private var realToken: com.hanhyo.pleosshuttle.sdk.UnlockConfirmation? = null
    private var realVehicle: RealVehicleAdapter? = null
    private var realNavi: RealNaviAdapter? = null
    val audio = LocalAudioAdapter(application) { audioState = it }
    private var lastSaved: TripState? = null
    private val institution = InstitutionConnection(application)
    val institutionBase get() = institution.base
    var institutionDirection by mutableStateOf(institution.direction); private set
    /** 화면 표시용 운행 방향(등원/하원). 기관 운행은 불러올 때 고른 방향, 모의 운행은 하원. */
    val tripDirectionLabel get() = if (institutionConnected && trip.tripId == institution.boundTrip) InstitutionTrips.directionLabel(institutionDirection) else "하원"
    var institutionName by mutableStateOf(institution.name); private set
    var institutionConnected by mutableStateOf(institution.connected); private set
    var institutionStatus by mutableStateOf("기관 서버를 연결하면 보호자에게 하차 기록을 전달합니다."); private set
    var institutionBusy by mutableStateOf(false); private set
    private var syncing = false
    private var nextSyncAt = 0L
    var trackingEnabled by mutableStateOf(trialPrefs.getBoolean("trackingEnabled", false)); private set
    var trackingStatus by mutableStateOf("위치 전송 꺼짐"); private set
    private var demoTrackingLocation: TrackingLocation? = null
    private val movingDemo = MovingTrackingDemo()
    private var demoNavigationEpoch = 0L
    private var demoTrackingNavigation: TrackingNavigation? = null
    @Volatile private var demoGeneration = 0L
    var movingDemoPhase by mutableStateOf(MovingDemoPhase.STOPPED); private set
    var movingDemoProgress by mutableStateOf(0); private set
    private fun demoKey() = "${trip.tripId}/${trip.routeVersion}/${trip.routeOrder}/${trip.currentStopIndex}/${trip.currentStop?.latitude}/${trip.currentStop?.longitude}"
    private fun clearMovingDemo() {
        demoGeneration++; movingDemo.stop(); movingDemoPhase = MovingDemoPhase.STOPPED
        movingDemoProgress = 0; demoTrackingLocation = null; demoTrackingNavigation = null; nextSyncAt = 0
    }
    fun startMovingDemo() {
        if (!BuildConfig.DEBUG || !foreground || !trackingControls.canSendDemo || movingDemo.phase != MovingDemoPhase.STOPPED) return
        val stop = trip.currentStop ?: return
        runCatching { movingDemo.start(demoKey(), stop.latitude, stop.longitude) }
            .onFailure { trackingStatus = "이 픽업 좌표에서는 시연을 시작할 수 없습니다."; return }
        demoGeneration++; demoNavigationEpoch = demoGeneration; refreshMovingDemo(); nextSyncAt = 0; syncInstitution(true)
    }
    fun pauseMovingDemo() { movingDemo.pause(); demoGeneration++; refreshMovingDemo(); nextSyncAt = 0; syncInstitution(true) }
    fun resumeMovingDemo() {
        if (!foreground || !trackingControls.canSendDemo || movingDemo.key != demoKey()) { stopMovingDemo(); return }
        movingDemo.resume(); demoGeneration++; refreshMovingDemo(); nextSyncAt = 0; syncInstitution(true)
    }
    fun stopMovingDemo() { clearMovingDemo(); syncInstitution(true) }
    private fun refreshMovingDemo() {
        if (movingDemo.phase == MovingDemoPhase.STOPPED) return
        if (!movingDemo.retain(foreground && trackingControls.canSendDemo, demoKey())) { clearMovingDemo(); return }
        val frame = movingDemo.sample() ?: return
        val stop = trip.currentStop ?: return
        val at = System.currentTimeMillis()
        demoTrackingLocation = TrackingLocation(frame.latitude, frame.longitude, at, true, movingDemo.routeId, trip.currentStopIndex)
        // Paused movement has no arrival prediction; never mislabel demo values as native Pleos observations.
        demoTrackingNavigation = if (movingDemo.phase == MovingDemoPhase.PAUSED) TrackingNavigation("UNAVAILABLE", demoNavigationEpoch)
            else TrackingNavigation("READY", demoNavigationEpoch, movingDemo.routeId, stop.latitude, stop.longitude, frame.seconds, frame.meters, at)
        movingDemoPhase = movingDemo.phase; movingDemoProgress = frame.progress
    }
    val trackingControls get() = vehicleTrackingControls(
        institutionConnected, trip.tripId == institution.boundTrip, trip.phase,
        trip.onboardChildren.isNotEmpty(), trip.currentStop != null, trip.integrationMode,
        trackingEnabled, storageError == null)
    var trackingPermissionRequested by mutableStateOf(false); private set
    private var trackingServiceRequested = false
    var locationPermissionGranted by mutableStateOf(TripTrackingService.hasPermission(getApplication())); private set
    fun requestTrackingPermission() { trackingPermissionRequested = true }
    fun trackingPermissionResult() {
        trackingPermissionRequested = false
        locationPermissionGranted = TripTrackingService.hasPermission(getApplication())
        if (locationPermissionGranted && trip.phase in listOf(TripPhase.DRIVING, TripPhase.ALIGHTING) && trackingControls.canEnable)
            changeTrackingEnabled(true)
        else if (!locationPermissionGranted) trackingStatus = "위치 권한 필요 · 운행 권한 확인에서 허용해 주세요."
        refreshSdk()
    }
    fun changeTrackingEnabled(enabled: Boolean) {
        if (!enabled) trackingPermissionRequested = false
        if (enabled && !trackingControls.canEnable) {
            trackingStatus = trackingControls.guidance
            return
        }
        if (enabled && trip.integrationMode == IntegrationMode.SDK && !TripTrackingService.hasPermission(getApplication())) {
            trackingStatus = "위치 권한 필요 · 운행 권한 확인에서 허용해 주세요."
            // Preserve trip intent; permission recovery starts the service without another sharing switch.
        }
        trackingEnabled = enabled
        trialPrefs.edit().putBoolean("trackingEnabled", enabled).commit()
        clearMovingDemo()
        realNavi?.trackLocations = false
        updateTrackingService()
        if (enabled && trip.phase == TripPhase.DRIVING) startMovingDemo()
        nextSyncAt = 0
        syncInstitution(true)
    }
    fun sendDemoTrackingLocation() {
        if (!BuildConfig.DEBUG || !foreground) return
        if (!trackingControls.canSendDemo) { trackingStatus = trackingControls.guidance; return }
        val stop = trip.currentStop ?: return
        clearMovingDemo()
        // Explicit synthetic input only. It never advances a trip or confirms arrival/handover.
        demoTrackingLocation = TrackingLocation(stop.latitude - 0.002, stop.longitude - 0.002, System.currentTimeMillis(), true)
        syncInstitution(true)
    }
    private val automaticGuidance = AutomaticTripGuidance()
    internal var automaticAnnouncementKeys: List<String> = emptyList(); private set
    internal var automaticNavigationStops: List<String> = emptyList(); private set

    init {
        try { store.load()?.let { engine.restore(it, now) } }
        catch (error: Exception) { storageError = "저장된 운행을 읽지 못했습니다. 기존 파일을 보존합니다: ${error.javaClass.simpleName}" }
        runCatching { institution.recover(engine.state) }.onFailure { storageError = "기관 연결 복구 실패. 기존 파일을 보존합니다." }
        publish()
    }

    fun foreground() {
        if (foreground) return
        foreground = true
        locationPermissionGranted = TripTrackingService.hasPermission(getApplication())
        trackingServiceRequested = TripTrackingService.running
        trackingEnabled = trialPrefs.getBoolean("trackingEnabled", false)
        updateTrackingService()
        audio.onForeground()
        if (trip.integrationMode == IntegrationMode.SDK) startSdk()
        else if (trackingEnabled && trip.phase == TripPhase.DRIVING) startMovingDemo()
        ticker = viewModelScope.launch {
            while (foreground) {
                now = System.currentTimeMillis()
                if (trip.integrationMode == IntegrationMode.SIMULATION) feedSimulation()
                engine.tick(now)
                publish()
                audio.refresh()
                delay(500)
            }
        }
    }
    fun background() {
        foreground = false
        ticker?.cancel(); ticker = null
        realToken = null
        realVehicle?.onStop(); realNavi?.onStop()
        clearMovingDemo()
        audio.onBackground()
        engine.background(System.currentTimeMillis())
        publish()
        syncInstitution(true)
    }
    private fun publish() {
        trip = engine.state; vehicle = engine.vehicle; surroundings = engine.surroundings
        message = engine.lastMessage
        if (storageError == null && lastSaved != trip) {
            try { store.save(trip); lastSaved = trip }
            catch (error: Exception) { storageError = "운행 저장 실패. 진행을 중단했습니다: ${error.javaClass.simpleName}" }
        }
        dispatchAutomaticGuidance()
        trackingEnabled = trialPrefs.getBoolean("trackingEnabled", false)
        realNavi?.trackLocations = false // A separate read-only foreground service owns telemetry.
        updateTrackingService()
        refreshMovingDemo()
        if (foreground && storageError == null) syncInstitution()
    }

    private fun updateTrackingService() {
        val wanted = TrackingLifecycle.desired(trackingEnabled, institutionConnected,
            trip.tripId == institution.boundTrip, trip.integrationMode == IntegrationMode.SDK,
            storageError == null && trip.phase in listOf(TripPhase.DRIVING, TripPhase.ALIGHTING))
        val app = getApplication<Application>()
        if (!wanted) {
            if (trackingServiceRequested || TripTrackingService.running) app.stopService(Intent(app, TripTrackingService::class.java))
            trackingServiceRequested = false
        } else if (!trackingServiceRequested && TrackingLifecycle.mayStart(wanted, foreground, TripTrackingService.hasPermission(app))) {
            trackingServiceRequested = true
            runCatching { ContextCompat.startForegroundService(app, Intent(app, TripTrackingService::class.java).putExtra("tripId", trip.tripId)) }
                .onFailure { trackingStatus = "위치 서비스 시작 실패 · 권한과 연결을 확인해 주세요." }
        } else if (!TripTrackingService.hasPermission(app)) {
            trackingStatus = "위치 권한 필요 · 운행 권한 확인에서 허용해 주세요."
        }
        if (TripTrackingService.running) trackingStatus = trialPrefs.getString("trackingServiceStatus", "Pleos 위치 수신 대기")!!
    }

    private fun canReplaceInstitutionTrip(): Boolean = storageError == null && !syncing &&
        institution.pending(trip) == 0 && (trip.phase == TripPhase.ENDED ||
        (trip.phase == TripPhase.PREPARING && trip.ledger.isEmpty() && trip.onboardChildren.isEmpty()))

    fun pairInstitution(address: String, code: String) {
        if (institutionBusy) return
        if (!canReplaceInstitutionTrip()) { institutionStatus = "현재 운행을 완료하고 미전송 기록을 전송한 뒤 연결해 주세요."; return }
        institutionBusy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { institution.pair(address, code) }
                institutionName = institution.name; institutionConnected = true
                institutionStatus = "차량 연결 완료 · 배정 명단을 불러와 주세요."
            } catch (e: Exception) { institutionStatus = e.message ?: "차량 연결 실패" }
            finally { institutionBusy = false }
        }
    }

    val canPrepareConnectedDemo get() = canArchiveLocalTrackingDemo(BuildConfig.DEBUG,
        institutionConnected, trip.tripId == institution.boundTrip, trip.integrationMode, trip.tripId,
        storageError == null, syncing || institutionBusy)
    fun prepareConnectedDemo() = loadInstitutionTrip(true)
    fun loadInstitutionTrip() = loadInstitutionTrip(false)
    fun chooseInstitutionDirection(direction: String) {
        if (institutionBusy || direction !in setOf("PICK_UP", "DROP_OFF")) return
        institution.setDirection(direction)
        institutionDirection = direction
    }
    private fun loadInstitutionTrip(archiveLocalDemo: Boolean) {
        if (institutionBusy) return
        if (!(if (archiveLocalDemo) canPrepareConnectedDemo else canReplaceInstitutionTrip())) { institutionStatus = "진행 중인 운행 또는 미전송 기록이 있습니다. 완료 후 다시 시도해 주세요."; return }
        institutionBusy = true
        viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) { institution.createTrip() }
                store.archiveSnapshot(trip)
                store.save(prepared)
                institution.bind(prepared)
                realVehicle?.onStop(); realNavi?.onStop(); realToken = null
                audio.stopSpeech(); audio.stop()
                engine = ShuttleEngine(prepared); lastSaved = prepared
                scenario = TrialScenario.PARKED
                trialPrefs.edit().putString("scenario", scenario.name).commit()
                clearMovingDemo()
                publish()
                if (foreground) startSdk()
                institutionStatus = if (prepared.noTripToday) "탈 어린이 0명 · 오늘 ${InstitutionTrips.directionLabel(institution.direction)} 운행 없음"
                    else "${prepared.children.size}명 명단 준비 완료 · Pleos 자동 연결 중. 탑승 기록 후 운행을 시작하세요."
                panel = "flow"
            } catch (e: Exception) { institutionStatus = e.message ?: "명단 불러오기 실패" }
            finally { institutionBusy = false }
        }
    }

    fun syncInstitution(force: Boolean = false) {
        if (syncing || institutionBusy || storageError != null || !institutionConnected) return
        if (!force && System.currentTimeMillis() < nextSyncAt) return
        if (trip.tripId != institution.boundTrip) return
        syncing = true
        val snapshot = trip
        val enabled = trackingEnabled
        val generation = demoGeneration
        val navigation = demoTrackingNavigation
        val fix = if (!enabled || !foreground || trip.phase !in listOf(TripPhase.DRIVING, TripPhase.ALIGHTING)) null
            else (if (trip.integrationMode == IntegrationMode.SDK) realNavi?.trackingLocation else demoTrackingLocation)
                ?.takeIf { it.valid(System.currentTimeMillis()) }
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    val sent = institution.sync(snapshot)
                    if (!(snapshot.integrationMode == IntegrationMode.SDK && enabled && TripTrackingService.running &&
                        snapshot.phase in listOf(TripPhase.DRIVING, TripPhase.ALIGHTING))) {
                        institution.reportTracking(snapshot, enabled, fix, navigation, stillCurrent = { generation == demoGeneration })
                    }
                    sent
                }
                if (snapshot.integrationMode != IntegrationMode.SDK || !enabled) trackingStatus = if (!enabled) "위치 공유 중지 · 기존 공유 링크 종료" else if (fix == null) "위치 미수신 · 보호자 화면에 갱신 대기로 표시" else if (fix.simulated) "모의 위치 전송 · 실제 차량 위치 아님" else "Pleos 내비 위치 전송 · SDK 수신 시각 기준"
                institutionStatus = "서버 기록 동기화 완료 · 이번 전송 ${count}건. 보호자 푸시는 서버에서 별도 처리합니다."
            } catch (e: Exception) { trackingStatus = "위치 전송 실패 · 보호자 화면은 갱신 지연으로 전환"; institutionStatus = "기기 기록 유지 · 전송 대기 ${institution.pending(snapshot)}건: ${e.message ?: "연결 실패"}" }
            finally {
                syncing = false
                nextSyncAt = if (generation != demoGeneration) 0 else System.currentTimeMillis() + 5000
                if (generation != demoGeneration) syncInstitution(true)
            }
        }
    }
    private fun dispatchAutomaticGuidance() {
        if (storageError != null) return
        // V-08 출발 전 요약은 흐름 카드가 [운행 시작] 단계를 보일 때와 같은 조건(정차 확인 · 차량 UX 제한 없음)에서만 읽는다.
        val departureReady = engine.stationaryBlockers(now).isEmpty() &&
            !(trip.integrationMode == IntegrationMode.SDK && sdkVehicle.uxRestricted.value == true)
        automaticGuidance.takeSpeech(trip, foreground, departureReady)?.let { cue ->
            // Cancel stale destination speech before the new cue; the adapter rejects late callbacks.
            audio.stopSpeech()
            automaticAnnouncementKeys = (automaticAnnouncementKeys + cue.key).takeLast(100)
            audio.speak(cue.text)
        }
        val ready = trip.integrationMode == IntegrationMode.SIMULATION ||
            (!navi.busy && navi.routeState.isFresh(now, 6_000))
        automaticGuidance.takeNavigation(trip, foreground, ready)?.let { stop ->
            automaticNavigationStops = (automaticNavigationStops + stop.id).takeLast(100)
            // The cue is already deduplicated. A shared final destination does not imply that
            // the remaining waypoint list is unchanged after a handover or reordered route.
            navigateNext()
        }
    }

    fun markRouteCriteriaSeen() {
        if (routeCriteriaSeen) return
        routeCriteriaSeen = true
        routePrefs.edit().putBoolean("criteriaSeen", true).apply()
    }
    fun replayGuidance() {
        audio.stopSpeech()
        audio.speak(tripCue(trip)?.text ?: "어린이의 탑승을 확인하고 기록해 주세요.")
    }
    fun perform(action: ShuttleEngine.(Long) -> Boolean) {
        if (storageError != null || institutionBusy) return
        if (trip.integrationMode == IntegrationMode.SDK && sdkVehicle.uxRestricted.value == true) { message = "차량 UX 제한 중에는 기록·상세 조작을 할 수 없습니다."; actionError = "정차 후 다시 확인해 주세요."; return }
        now = System.currentTimeMillis()
        val before = trip
        val accepted = engine.action(now)
        actionError = if (accepted) null else engine.lastMessage
        publish()
        if (!accepted || storageError != null) return
        val transition = automaticTrackingTransition(before, trip)
        if (transition == TrackingTransition.FINISH) changeTrackingEnabled(false)
        else if (foreground && trackingControls.canEnable) {
            if (transition == TrackingTransition.DEPART) changeTrackingEnabled(true)
            else if (transition == TrackingTransition.NEXT_STOP && trackingEnabled) startMovingDemo()
        }
    }
    fun changeMode(mode: IntegrationMode) {
        if (storageError != null) return
        if (engine.setIntegrationMode(mode, System.currentTimeMillis())) {
            realToken = null
            if (mode == IntegrationMode.SDK) startSdk()
            else { realVehicle?.onStop(); realNavi?.onStop(); simGeneration++; feedSimulation() }
        }
        publish()
    }
    private fun startSdk() {
        if (!getApplication<Application>().packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            message = "현재 기기는 Automotive 환경이 아닙니다. 모의 체험을 사용할 수 있습니다."
            return
        }
        if (realVehicle == null) realVehicle = RealVehicleAdapter(getApplication()) { snapshot ->
            viewModelScope.launch {
                sdkVehicle = snapshot
                if (snapshot.connected != lastSdkConnected) { sdkGeneration++; lastSdkConnected = snapshot.connected }
                if (engine.state.integrationMode == IntegrationMode.SDK && foreground) {
                    now = System.currentTimeMillis()
                    engine.updateVehicle(mapVehicle(snapshot), now)
                    publish()
                }
            }
        }
        if (realNavi == null) realNavi = RealNaviAdapter(getApplication()) { snapshot ->
            viewModelScope.launch { navi = snapshot }
        }
        realVehicle?.onStart(); realNavi?.onStart()
    }
    fun refreshSdk() { realVehicle?.refresh(); realNavi?.refresh() }
    private fun SdkOutcome.domain() = when(this) {
        SdkOutcome.UNSUPPORTED -> ResultStatus.UNSUPPORTED_HERE
        else -> ResultStatus.valueOf(name)
    }
    private fun <A,B> SdkObservation<A>.map(transform: (A)->B): Observation<B> = Observation(
        value?.let(transform), Source.SDK, observedAtMillis, outcome.domain(), error, sdkGeneration
    )
    private fun mapVehicle(s: com.hanhyo.pleosshuttle.sdk.VehicleSnapshot): VehicleSnapshot = VehicleSnapshot(
        connected = s.connected,
        speedKph = s.speedMetersPerSecond.map { it.toDouble() * 3.6 },
        gear = s.gear.map { when(it) { 4 -> Gear.PARK; 2 -> Gear.REVERSE; 1 -> Gear.NEUTRAL; 8 -> Gear.DRIVE; else -> Gear.UNKNOWN } },
        parkingBrake = s.parkingBrake.map { it }, doorLocked = s.doorLocked.map { it },
        seats = (s.seatOccupancy.keys + s.seatBelts.keys).distinct().mapIndexed { index, area ->
            SeatObservation(index + 1, s.seatOccupancy[area]?.map { it } ?: Observation(), s.seatBelts[area]?.map { it } ?: Observation(), areaLabel = area)
        },
        connectionGeneration = sdkGeneration,
        parkingBrakeSupported = s.parkingBrakeSupported,
    )
    fun chooseScenario(value: TrialScenario) {
        if (trip.integrationMode != IntegrationMode.SIMULATION) return
        scenario = value
        trialPrefs.edit().putString("scenario", value.name).commit()
        realToken = null
        engine.invalidateConfirmation("모의 시험 입력 변경", System.currentTimeMillis())
        if (value != TrialScenario.STALE) feedSimulation()
        publish()
    }
    private fun feedSimulation(at: Long = System.currentTimeMillis()) {
        if (engine.state.integrationMode != IntegrationMode.SIMULATION || scenario == TrialScenario.STALE) return
        now = at
        fun <T> observed(value: T?) = Observation(value, Source.SIMULATED, if(value == null) null else at,
            if(value == null) ResultStatus.UNVERIFIED else ResultStatus.TARGET_OBSERVED, generation = simGeneration)
        val connected = scenario != TrialScenario.DISCONNECTED
        val unknown = scenario == TrialScenario.UNKNOWN || !connected
        engine.updateVehicle(VehicleSnapshot(
            connected = connected,
            speedKph = observed(if(unknown) null else if(scenario == TrialScenario.MOVING) 24.0 else 0.0),
            gear = observed(if(unknown) null else if(scenario in listOf(TrialScenario.MOVING,TrialScenario.SIGNAL)) Gear.DRIVE else Gear.PARK),
            parkingBrake = observed(if(unknown) null else scenario !in listOf(TrialScenario.MOVING,TrialScenario.SIGNAL)),
            doorLocked = observed(if(connected) simLocked else null),
            seats = listOf(SeatObservation(101, observed(if(connected) scenario == TrialScenario.OCCUPIED else null), observed(if(connected) false else null))),
            connectionGeneration = simGeneration, parkingBrakeSupported = true
        ), at)
        val nextKind = if (scenario == TrialScenario.WARNING) SurroundingKind.WARNING else SurroundingKind.UNKNOWN
        // No synthetic heartbeat renews a human surrounding confirmation.
        if (engine.surroundings.kind != nextKind || engine.surroundings.source != Source.SIMULATED)
            engine.updateSurroundings(Surroundings(nextKind, Source.SIMULATED, at), at)
    }
    fun prepareDoor() {
        if (storageError != null) return
        val prepared = engine.prepareUnlock(System.currentTimeMillis())
        if (prepared && trip.integrationMode == IntegrationMode.SDK) {
            realToken = realVehicle?.prepareUnlockConfirmation(sdkVehicle.selectedDoorArea)
            if (realToken == null) engine.invalidateConfirmation("실제 어댑터 조건 미충족: ${sdkVehicle.unlockBlockReason}", System.currentTimeMillis())
        }
        actionError = if (engine.state.unlockConfirmation == null) engine.lastMessage else null
        publish()
    }
    fun confirmDoor() {
        if (storageError != null) return
        val request = engine.consumeUnlock(System.currentTimeMillis()) ?: run { actionError = engine.lastMessage; publish(); return }
        actionError = null
        publish() // Durable checkpoint before the side effect.
        if (storageError != null) return
        if (request.mode == IntegrationMode.SIMULATION) {
            simulatedDoorRequests++
            engine.onUnlockResult(request.id, ResultStatus.ACCEPTED, simLocked, null, System.currentTimeMillis())
            publish()
            viewModelScope.launch {
                delay(1000)
                if (engine.state.integrationMode != IntegrationMode.SIMULATION || !foreground) return@launch
                if (scenario == TrialScenario.ACCEPT_ONLY) {
                    engine.onUnlockResult(request.id, ResultStatus.TIMEOUT, true, "SIM_ACCEPTED_WITHOUT_CHANGE", System.currentTimeMillis())
                } else if (engine.state.unlockAttempt?.invalidated != true) {
                    simLocked = false; trialPrefs.edit().putBoolean("doorLocked", false).commit(); feedSimulation()
                    engine.onUnlockResult(request.id, ResultStatus.TARGET_OBSERVED, false, null, System.currentTimeMillis())
                }
                publish()
            }
        } else {
            val token = realToken; realToken = null
            if (token == null) {
                engine.onUnlockResult(request.id, ResultStatus.ERROR, null, "MISSING_ADAPTER_CONFIRMATION", System.currentTimeMillis()); publish(); return
            }
            realVehicle?.requestUnlock(token) { result -> viewModelScope.launch {
                engine.onUnlockResult(request.id, result.outcome.domain(), result.value, result.error, System.currentTimeMillis()); publish()
            } }
        }
    }
    fun simulateAutomaticIdea() {
        if (trip.integrationMode != IntegrationMode.SIMULATION) return
        // An isolated local illustration. It never holds or invokes a RealVehicleAdapter.
        val preview = SimulationOnlyUnlockPreview()
        autoSimulationUnlocked = vehicle.speedKph.isFresh(now) && vehicle.speedKph.value == 0.0 && vehicle.gear.value == Gear.PARK && surroundings.kind != SurroundingKind.WARNING
        autoSimulationMessage = preview.explain(autoSimulationUnlocked)
    }
    fun resetSimulatedDoor() {
        if (trip.integrationMode != IntegrationMode.SIMULATION) return
        simLocked = true
        trialPrefs.edit().putBoolean("doorLocked", true).commit()
        engine.invalidateConfirmation("사용자가 모의 잠금 입력을 true로 변경", System.currentTimeMillis())
        feedSimulation(); publish()
    }
    fun navigateNext() {
        val next = trip.currentStop ?: trip.origin
        if (trip.integrationMode == IntegrationMode.SIMULATION) simulatedNavigation = "모의 안내 · ${next.name} (${next.latitude}, ${next.longitude})"
        else {
            val upcoming = trip.routeOrder.drop(trip.currentStopIndex).take(3).mapNotNull { id -> trip.stops.find { it.id==id } }.ifEmpty { listOf(trip.origin) }
            realNavi?.requestStops(upcoming.map { com.hanhyo.pleosshuttle.sdk.NaviDestination(it.name,it.latitude,it.longitude) })
        }
    }
    fun openArrival(tripId: String?, stopId: String?, version: Long) {
        if (trip.tripId == tripId && trip.currentStop?.id == stopId && trip.routeVersion == version && trip.phase == TripPhase.DRIVING) {
            panel = "flow"
            message = "하차 지점 근처입니다. 정차 후 도착·정차 확인을 눌러 주세요."
        }
    }
    fun showNativeNavigation() { realNavi?.showNavigation() }
    fun cancelOwnNavigation() { realNavi?.cancelOwnRoute() }
    fun handleIntent(action: String?) {
        when(action) {
            "OPEN_DROPOFF_PREPARATION" -> { panel = "flow"; message = "현재 운행 흐름을 열었습니다. 도착·정차를 확인한 뒤 선생님이 인계와 하차를 기록합니다." }
            "SHOW_NEXT_STOP" -> { panel = "flow"; message = "다음 지점: ${(trip.currentStop ?: trip.origin).name} · 로컬 Intent 실행, Gleo 음성은 미검증" }
            "PLAY_TEST_MUSIC" -> audio.play()
        }
    }
    fun startNewSimulationTrip() {
        if (institutionBusy || syncing || institution.pending(trip) > 0) { actionError = "기관 기록 전송을 먼저 완료해 주세요."; return }
        if (trip.phase != TripPhase.ENDED || storageError != null) return
        try { store.archiveEnded(trip) }
        catch (error: Exception) { storageError = "완료 운행 보관 실패. 기존 운행을 유지합니다: ${error.javaClass.simpleName}"; return }
        realVehicle?.onStop(); realNavi?.onStop(); realToken = null
        engine = ShuttleEngine(SampleTrip.create().copy(tripId = "fictional-afternoon-${System.currentTimeMillis()}"))
        scenario = TrialScenario.PARKED
        trialPrefs.edit().putString("scenario", scenario.name).commit()
        feedSimulation(); publish(); panel = "flow"
    }
    fun restartSimulation(stage: Int) {
        if (institutionBusy || syncing || institution.pending(trip) > 0) { actionError = "기관 기록 전송을 먼저 완료해 주세요."; return }
        if (storageError != null || trip.integrationMode != IntegrationMode.SIMULATION || stage !in 0..4) return
        val fresh = SampleTrip.create().copy(tripId = "fictional-demo-${java.util.UUID.randomUUID()}")
        val prepared = when(stage) {
            1 -> fresh.copy(phase = TripPhase.ALIGHTING, children = fresh.children.map { it.copy(status = Attendance.ONBOARD) })
            2 -> fresh.copy(phase = TripPhase.END_REVIEW, currentStopIndex = fresh.routeOrder.size,
                children = fresh.children.map { it.copy(status = Attendance.ALIGHTED) })
            // 테스트 도구 전용: 전원 미탑승 예정(결석) 명단 → D34 "오늘 운행 없음" 화면 확인.
            3 -> fresh.copy(children = fresh.children.map { it.copy(notExpectedToBoard = true, notBoardingReason = "ABSENT") })
            // 테스트 도구 전용: 일부 미탑승 예정(나 결석, 라 직접 픽업) → V-02 배지·미탑승 확인 화면 확인.
            4 -> fresh.copy(children = fresh.children.map { when (it.id) {
                "child-b" -> it.copy(notExpectedToBoard = true, notBoardingReason = "ABSENT")
                "child-d" -> it.copy(notExpectedToBoard = true, notBoardingReason = "SELF_PICKUP")
                else -> it
            } })
            else -> fresh
        }.let { it.copy(diagnostics = listOf(DiagnosticEntry(System.currentTimeMillis(), "체험 시작", ResultStatus.UNVERIFIED,
            "가상 단계 $stage 사전 설정. 실제 탑승·인계·하차 기록이 아닙니다."))) }
        try {
            store.archiveSnapshot(trip)
            store.save(prepared)
        } catch (error: Exception) {
            actionError = "보관 또는 새 체험 저장 실패. 기존 운행을 유지합니다: ${error.javaClass.simpleName}"
            return
        }
        audio.stopSpeech(); audio.stop()
        engine = ShuttleEngine(prepared)
        lastSaved = prepared
        scenario = TrialScenario.PARKED
        trialPrefs.edit().putString("scenario", scenario.name).commit()
        feedSimulation(); publish(); panel = "flow"
    }
    fun archiveSummary(): String = runCatching {
        store.savedTrips().joinToString("\n\n") {
            "${it.tripId}\n${it.phase.label} · 기록 ${it.ledger.size}건 · 탑승 ${it.onboardChildren.size}명"
        }.ifEmpty { "보관된 운행이 없습니다." }
    }.getOrElse { "보관 기록을 읽지 못했습니다. 파일은 유지됩니다." }

    fun exportDiagnostics(): File {
        val file = File(getApplication<Application>().filesDir, "diagnostics.txt")
        file.writeText(buildString {
            appendLine("PleosShuttlePoC ${BuildConfig.VERSION_NAME} | ${java.util.Date()} | ${trip.integrationMode.label}")
            appendLine("trip=${trip.tripId} phase=${trip.phase} current=${trip.currentStop?.name}")
            appendLine("local fictional data only; source/time/status are independent")
            appendLine("SDK: ${sdkVehicle.versions}; Navi: ${navi.versions}")
            appendLine("areas=${sdkVehicle.supportedDoorAreas}/${sdkVehicle.supportedSeatAreas}/${sdkVehicle.supportedBeltAreas}")
            sdkVehicle.diagnostics.forEach(::appendLine); navi.diagnostics.forEach(::appendLine)
            trip.diagnostics.forEach { appendLine(it.toString()) }; trip.ledger.forEach { appendLine(it.toString()) }
            audioState.events.forEach(::appendLine)
            appendLine("end=${trip.endRecord}")
        })
        message = "진단 저장: 앱 내부 diagnostics.txt (가상 기록만 포함)"
        return file
    }
    override fun onCleared() { ticker?.cancel(); realVehicle?.close(); realNavi?.close(); audio.close() }
}

/** Intentionally no SDK dependencies or adapter reference. */
class SimulationOnlyUnlockPreview {
    fun explain(stopped: Boolean): String = if (stopped) "모의 자동 해제 표현: 별도 모의 문 해제 · 실제 요청 0회 · 실차 제어 없음" else "모의 자동 해제 보류: 이동·미확인·위험 입력 · 실제 요청 0회"
}
