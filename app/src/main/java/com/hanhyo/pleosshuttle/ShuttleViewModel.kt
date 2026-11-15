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
import com.hanhyo.pleosshuttle.audio.SpeechStatus
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

class ShuttleViewModel(application: Application) : AndroidViewModel(application) {
    private val store = TripStore(application)
    // 위치 공유 설정. 파일 이름("simulation-settings")은 이전 빌드와 TripTrackingService·ConsoleDriveRunner가 같이 읽으므로 그대로 둔다.
    private val trackingPrefs = application.getSharedPreferences("simulation-settings", 0)
    var engine = ShuttleEngine(); private set
    var trip by mutableStateOf(engine.state); private set
    var vehicle by mutableStateOf(engine.vehicle); private set
    var surroundings by mutableStateOf(engine.surroundings); private set
    var now by mutableStateOf(System.currentTimeMillis()); private set
    var message by mutableStateOf(engine.lastMessage); private set
    var actionError by mutableStateOf<String?>(null); private set
    var storageError by mutableStateOf<String?>(null); private set
    var panel by mutableStateOf("flow")
    // 경로 확인 화면 설정. 운행 파일과 따로 저장하며, 보호자 알림은 스위치 상태만 남기고 실제로 보내지 않는다.
    private val routePrefs = application.getSharedPreferences("route-settings", 0)
    var routeCriteriaSeen by mutableStateOf(routePrefs.getBoolean("criteriaSeen", false)); private set
    var sdkVehicle by mutableStateOf(com.hanhyo.pleosshuttle.sdk.VehicleSnapshot()); private set
    var navi by mutableStateOf(NaviSnapshot()); private set
    var audioState by mutableStateOf(AudioSnapshot()); private set
    private var sdkGeneration = 1L
    private var lastSdkConnected = false
    private var foreground = false
    private var ticker: Job? = null
    private var realVehicle: RealVehicleAdapter? = null
    private var realNavi: RealNaviAdapter? = null
    val audio = LocalAudioAdapter(application) { audioState = it }
    private var lastSaved: TripState? = null
    private val institution = InstitutionConnection(application)
    val institutionBase get() = institution.base
    var institutionDirection by mutableStateOf(institution.direction); private set
    /** 화면 표시용 운행 방향(등원/하원). 운행에 저장된 방향(TripState.direction, 이전 저장 파일은 하원)을 읽는다. */
    val tripDirectionLabel get() = TripDirections.label(trip.direction)
    var institutionName by mutableStateOf(institution.name); private set
    var institutionConnected by mutableStateOf(institution.connected); private set
    var institutionStatus by mutableStateOf("기관 서버를 연결하면 보호자에게 하차 기록을 전달합니다."); private set
    var institutionBusy by mutableStateOf(false); private set
    private var syncing = false
    private var nextSyncAt = 0L
    var trackingEnabled by mutableStateOf(trackingPrefs.getBoolean("trackingEnabled", false)); private set
    var trackingStatus by mutableStateOf("위치 전송 꺼짐"); private set
    /** 위치 보고 세대. Virtual Console 운전 시작·종료처럼 위치 출처가 바뀌면 올려, 이전 동기화가 늦게 보내지 않게 한다. */
    @Volatile private var trackingGeneration = 0L
    /** 기관에서 불러와 서버에 묶인 운행인지. 운행 없음(자리 표시) 상태는 항상 false. */
    private val tripBound get() = trip.hasTrip && trip.tripId == institution.boundTrip
    // 개발용 Virtual Console 운전: 실제 SDK 속도·기어를 적분해 앱 안에서만 위치를 움직인다(SIMULATED로만 전송).
    // 위치 적분·전송은 프로세스 범위 ConsoleDriveRunner가 한다(주행 중 Pleos가 앱을 가려도 계속).
    var consoleDriveRunning by mutableStateOf(ConsoleDriveRunner.running); private set
    var consoleDriveFrame by mutableStateOf(ConsoleDriveRunner.frame); private set
    private fun consoleSpeedMps(at: Long): Double? = sdkVehicle.speedMetersPerSecond.takeIf { sdkVehicle.connected && it.value != null && at - it.receivedAtMillis in 0..5_000 }
        ?.value?.toDouble()?.takeIf { it.isFinite() }
    private fun consoleGear(at: Long): Int? = sdkVehicle.gear.takeIf { sdkVehicle.connected && at - it.receivedAtMillis in 0..5_000 }?.value
    private fun Int?.toGear() = when (this) { 4 -> Gear.PARK; 2 -> Gear.REVERSE; 1 -> Gear.NEUTRAL; 8 -> Gear.DRIVE; else -> Gear.UNKNOWN }
    private fun consoleDriveContext() = ConsoleDriveContext(BuildConfig.DEBUG, trip.hasTrip && trip.integrationMode == IntegrationMode.SDK,
        institutionConnected && tripBound, trip.phase, storageError == null,
        foreground, sdkVehicle.uxRestricted.value == true)
    /** 시작할 수 없는 이유(없으면 null). 화면에 그대로 보여 준다. */
    val consoleDriveBlocker: String? get() = when {
        !BuildConfig.DEBUG -> "개발용 빌드에서만 쓸 수 있습니다."
        storageError != null -> "운행 저장 오류를 먼저 확인해 주세요."
        !trip.hasTrip -> "기관 연결에서 오늘 운행을 먼저 불러와 주세요."
        !institutionConnected || !tripBound -> "기관 서버에 연결된 운행에서만 쓸 수 있습니다."
        trip.phase !in listOf(TripPhase.DRIVING, TripPhase.ALIGHTING) -> "운행을 시작한 뒤 쓸 수 있습니다."
        consoleSpeedMps(now) == null -> "차량 속도 미수신 · ${sdkVehicle.speedMetersPerSecond.error ?: if (!sdkVehicle.connected) "SDK 차량 연결을 확인해 주세요." else "속도 갱신을 기다리는 중입니다."}"
        else -> null
    }
    val consoleDriveSpeedKph: Double? get() = (if (consoleDriveRunning) ConsoleDriveRunner.speedMps else consoleSpeedMps(now))?.times(3.6)
    val consoleDriveGearLabel: String get() = ConsoleDriveText.gearLabel(if (consoleDriveRunning) ConsoleDriveRunner.gear else consoleGear(now).toGear())
    val consoleDriveUploadStatus: String get() = ConsoleDriveRunner.uploadStatus
    /** 운행 카드에 보이는 한 줄. Virtual Console 운전 중일 때만. */
    val consoleDriveLine: String? get() = consoleDriveFrame?.takeIf { consoleDriveRunning }?.let { ConsoleDriveText.cardLine(it) }
    fun startConsoleDrive() {
        if (!BuildConfig.DEBUG || !foreground || ConsoleDriveRunner.running) return
        consoleDriveBlocker?.let { trackingStatus = it; return }
        val at = System.currentTimeMillis()
        ConsoleDriveRunner.hint(consoleSpeedMps(at), consoleGear(at).toGear(), at)
        if (!ConsoleDriveRunner.start(getApplication(), trip, consoleDriveContext())) return
        trackingGeneration++
        refreshConsoleDrive(); nextSyncAt = 0; syncInstitution(true)
    }
    fun stopConsoleDrive() { ConsoleDriveRunner.stop(); refreshConsoleDrive(); syncInstitution(true) }
    /** 화면 쪽 동기화만 한다. 앱이 가려지거나 백그라운드여도 Runner는 멈추지 않는다. */
    private fun refreshConsoleDrive() {
        if (ConsoleDriveRunner.running) {
            ConsoleDriveRunner.update(trip, consoleDriveContext())
            if (foreground) { val at = System.currentTimeMillis(); ConsoleDriveRunner.hint(consoleSpeedMps(at), consoleGear(at).toGear(), at) }
        }
        val running = ConsoleDriveRunner.running
        if (consoleDriveRunning && !running) { trackingGeneration++; nextSyncAt = 0 }
        consoleDriveRunning = running
        consoleDriveFrame = if (running) ConsoleDriveRunner.frame else null
    }
    val trackingControls get() = vehicleTrackingControls(
        institutionConnected, tripBound, trip.phase,
        trip.onboardChildren.isNotEmpty() || trip.isPickup, trip.currentStop != null,
        trackingEnabled, storageError == null, trip.isPickup)
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
        if (enabled && !TripTrackingService.hasPermission(getApplication())) {
            trackingStatus = "위치 권한 필요 · 운행 권한 확인에서 허용해 주세요."
            // Preserve trip intent; permission recovery starts the service without another sharing switch.
        }
        trackingEnabled = enabled
        trackingPrefs.edit().putBoolean("trackingEnabled", enabled).commit()
        realNavi?.trackLocations = false
        updateTrackingService()
        nextSyncAt = 0
        syncInstitution(true)
    }
    private val automaticGuidance = AutomaticTripGuidance()
    internal var automaticAnnouncementKeys: List<String> = emptyList(); private set
    internal var automaticNavigationStops: List<String> = emptyList(); private set
    // V-06 벨트 경고등: 메모리에서만 판단(TripState·원장·서버에 넣지 않음, D27).
    private val beltTelltaleGuidance = BeltTelltaleGuidance()
    private var beltTelltaleSpeechId: Long? = null
    internal var beltTelltaleAnnouncements = 0; private set
    /** 불러온 운행이 있을 때만 경고등을 쓴다. 운행 없음 상태는 항상 UNKNOWN(안내·배너 없음). */
    private fun beltTelltaleSignal(): BeltTelltaleSignal =
        if (!trip.hasTrip) BeltTelltaleSignal.UNKNOWN
        else beltTelltaleSignal(sdkVehicle.seatBeltTelltale, now)
    /** V-06 차량 전체 배너 한 줄(좌석·아이에 붙이지 않음). 없으면 null. */
    val beltTelltaleBanner: String? get() = beltTelltaleBanner(trip, beltTelltaleSignal())

    init {
        try {
            val saved = store.load()
            // 이전 빌드의 모의 운행은 버리되, 기관 서버에 묶인 운행의 미전송 원장이 남았으면 SDK로 바꿔 이어서 보낸다.
            val unsent = saved?.let { runCatching { institution.pending(it) }.getOrDefault(0) } ?: 0
            val restorable = StoredTrips.restorable(saved, institution.boundTrip, unsent)
            if (restorable != null) {
                engine.restore(restorable, now)
                if (saved?.integrationMode == IntegrationMode.SIMULATION)
                    institutionStatus = "이전 버전에서 저장한 기관 운행을 이어서 엽니다 · 미전송 기록 ${unsent}건을 서버로 보냅니다."
            }
            else if (saved != null) {
                // 이전 빌드의 모의 운행(또는 자리 표시 상태)은 이어서 쓰지 않는다. 혹시 모를 기록 유실을 막으려고
                // 보관 폴더(completed-trips)에 사본을 남긴 뒤 진행 중 파일을 지우고 "운행 없음"으로 시작한다.
                // 보관에 실패하면 파일은 그대로 두고(다음 실행에서 다시 시도) 화면만 운행 없음으로 시작한다.
                if (runCatching { store.archiveSnapshot(saved) }.isSuccess) store.clear()
                institutionStatus = "이전 버전에서 저장한 운행은 이어서 쓰지 않아요. 기관 연결 후 오늘 운행을 불러와 주세요."
            }
        } catch (error: Exception) { storageError = "저장된 운행을 읽지 못했습니다. 기존 파일을 보존합니다: ${error.javaClass.simpleName}" }
        runCatching { institution.recover(engine.state) }.onFailure { storageError = "기관 연결 복구 실패. 기존 파일을 보존합니다." }
        publish()
    }

    fun foreground() {
        if (foreground) return
        foreground = true
        institutionDirection = institution.direction
        locationPermissionGranted = TripTrackingService.hasPermission(getApplication())
        trackingServiceRequested = TripTrackingService.running
        trackingEnabled = trackingPrefs.getBoolean("trackingEnabled", false)
        updateTrackingService()
        audio.onForeground()
        startSdk()
        ticker = viewModelScope.launch {
            while (foreground) {
                now = System.currentTimeMillis()
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
        realVehicle?.onStop(); realNavi?.onStop()
        // Virtual Console 운전은 여기서 멈추지 않는다(주행 중 UX 제한으로 앱이 가려져도 계속).
        audio.onBackground()
        engine.background(System.currentTimeMillis())
        publish()
        syncInstitution(true)
    }
    private fun publish() {
        trip = engine.state; vehicle = engine.vehicle; surroundings = engine.surroundings
        message = engine.lastMessage
        // 운행 없음(자리 표시) 상태는 저장하지 않는다. 다음 실행도 저장 파일이 없으니 운행 없음으로 시작한다.
        if (storageError == null && trip.hasTrip && lastSaved != trip) {
            try { store.save(trip); lastSaved = trip }
            catch (error: Exception) { storageError = "운행 저장 실패. 진행을 중단했습니다: ${error.javaClass.simpleName}" }
        }
        dispatchAutomaticGuidance()
        trackingEnabled = trackingPrefs.getBoolean("trackingEnabled", false)
        realNavi?.trackLocations = false // A separate read-only foreground service owns telemetry.
        updateTrackingService()
        refreshConsoleDrive()
        DriveFocusController.update(getApplication(), trip)
        if (foreground && storageError == null) syncInstitution()
    }

    private fun updateTrackingService() {
        val wanted = TrackingLifecycle.desired(trackingEnabled, institutionConnected,
            tripBound, trip.integrationMode == IntegrationMode.SDK,
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
        if (TripTrackingService.running && !ConsoleDriveRunner.running) trackingStatus = trackingPrefs.getString("trackingServiceStatus", "Pleos 위치 수신 대기")!!
    }

    /** [ignoreSync]: 지금 도는 동기화와 그 동기화가 보낼 미전송 기록은 없는 것으로 보고 판단(동기화 뒤 이어서 준비할지 정할 때). */
    private fun canReplaceInstitutionTrip(ignoreSync: Boolean = false): Boolean = storageError == null &&
        (ignoreSync || (!syncing && institution.pending(trip) == 0)) && (!trip.hasTrip || trip.phase == TripPhase.ENDED ||
        (trip.phase == TripPhase.PREPARING && trip.ledger.isEmpty() && trip.onboardChildren.isEmpty()))

    fun pairInstitution(address: String, code: String) {
        if (institutionBusy) return
        if (!canReplaceInstitutionTrip()) { institutionStatus = "현재 운행을 완료하고 미전송 기록을 전송한 뒤 연결해 주세요."; return }
        institutionBusy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { institution.pair(address, code) }
                institutionName = institution.name; institutionConnected = true
                institutionStatus = "차량 연결 완료 · 오늘 운행을 불러와 주세요."
            } catch (e: Exception) { institutionStatus = e.message ?: "차량 연결 실패" }
            finally { institutionBusy = false }
        }
    }

    fun chooseInstitutionDirection(direction: String) {
        if (institutionBusy || direction !in setOf(TripDirections.PICK_UP, TripDirections.DROP_OFF)) return
        institution.setDirection(direction)
        institutionDirection = direction
        tripChoices = emptyList(); pendingPrepare = false; pendingPrepareTripId = null
    }
    /** 오늘 운행이 둘 이상일 때 고를 목록. 비어 있으면 목록을 보이지 않는다. 방향을 바꾸면 지운다. */
    var tripChoices by mutableStateOf<List<TripOption>>(emptyList()); private set
    /** V-28 서버가 내려준 보호자 호출 상태(tripChildId → 정보). 정차 중 5초마다 동기화로 갱신. */
    var guardianCalls by mutableStateOf<Map<String, GuardianCallInfo>>(emptyMap()); private set
    /** 동기화 중에 누른 운행 준비(고른 운행 id, 없으면 목록부터). 동기화가 끝나면 이어서 한 번 실행한다. */
    private var pendingPrepare = false
    private var pendingPrepareTripId: String? = null
    fun chooseInstitutionTrip(tripId: String) {
        if (tripChoices.none { it.id == tripId }) return
        loadInstitutionTrip(tripId)
    }
    fun dismissTripChoices() { tripChoices = emptyList(); pendingPrepare = false; pendingPrepareTripId = null }
    /**
     * [tripId]가 없으면 오늘 이 차량의 운행 목록부터 받는다. 하나뿐이면 바로 준비하고, 둘 이상이면 [tripChoices]로 고르게 한다.
     * 고르는 사람은 선생님·기사님 누구나(정차 중, 운행 없음·운행 준비 전이나 운행 종료 뒤에만 가능).
     */
    fun loadInstitutionTrip(tripId: String? = null) {
        if (institutionBusy) return
        institutionDirection = institution.direction // 고른 적 없으면 지금 시간대 기본값(VS-02)
        when (InstitutionTrips.prepareStart(canReplaceInstitutionTrip(), canReplaceInstitutionTrip(ignoreSync = true), syncing)) {
            InstitutionTrips.PrepareStart.AFTER_SYNC -> {
                pendingPrepare = true; pendingPrepareTripId = tripId
                institutionStatus = "기록 전송을 마치는 중이에요 · 끝나면 바로 운행을 준비해요"
                return
            }
            InstitutionTrips.PrepareStart.BLOCKED -> { pendingPrepare = false; pendingPrepareTripId = null; institutionStatus = "진행 중인 운행 또는 미전송 기록이 있습니다. 완료 후 다시 시도해 주세요."; return }
            InstitutionTrips.PrepareStart.NOW -> { pendingPrepare = false; pendingPrepareTripId = null }
        }
        institutionBusy = true
        viewModelScope.launch {
            try {
                val chosen = tripId ?: run {
                    val options = withContext(Dispatchers.IO) { institution.listTrips() }
                    when {
                        options.isEmpty() -> error(InstitutionTrips.noTripMessage(institution.direction))
                        options.size == 1 -> options.first().id
                        else -> {
                            tripChoices = options
                            institutionStatus = "오늘 ${InstitutionTrips.directionLabel(institution.direction)} 운행 ${options.size}개 · 준비할 운행을 골라 주세요"
                            return@launch
                        }
                    }
                }
                tripChoices = emptyList(); guardianCalls = emptyMap()
                val prepared = withContext(Dispatchers.IO) { institution.createTrip(chosen) }
                if (trip.hasTrip) store.archiveSnapshot(trip)
                store.save(prepared)
                institution.bind(prepared)
                realVehicle?.onStop(); realNavi?.onStop()
                audio.stopSpeech()
                engine = ShuttleEngine(prepared); lastSaved = prepared
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
        if (syncing || institutionBusy || storageError != null || !institutionConnected || !trip.hasTrip) return
        if (!force && System.currentTimeMillis() < nextSyncAt) return
        if (!tripBound) return
        syncing = true
        val snapshot = trip
        val enabled = trackingEnabled
        val generation = trackingGeneration
        // Virtual Console 운전 중에는 ConsoleDriveRunner가 위치를 직접 보낸다. 여기서는 위치를 보내지 않는다.
        val console = ConsoleDriveRunner.running
        val fix = if (console || !enabled || !foreground || trip.phase !in listOf(TripPhase.DRIVING, TripPhase.ALIGHTING)) null
            else realNavi?.trackingLocation?.takeIf { it.valid(System.currentTimeMillis()) }
        val navigation = if (fix != null && trip.integrationMode == IntegrationMode.SDK) realNavi?.trackingNavigation else null
        val active = snapshot.phase in listOf(TripPhase.DRIVING, TripPhase.ALIGHTING)
        viewModelScope.launch {
            try {
                // 위치 서비스가 돌지 않으면(권한 없음 등) 이 화면이 직접 보고한다. 그때는 아래에서 전송 결과대로 상태 문구를 바꾼다.
                var reportedHere: TrackingReport? = null
                val count = withContext(Dispatchers.IO) {
                    val sent = institution.sync(snapshot)
                    if (!console && !(enabled && TripTrackingService.running && active)) {
                        // 위치 서비스 대신 보고할 때도 Pleos 내비 예상 시간을 함께 보낸다(SDK 운행).
                        reportedHere = institution.reportTracking(snapshot, enabled, fix, navigation, stillCurrent = { generation == trackingGeneration })
                    }
                    sent
                }
                if (console) trackingStatus = ConsoleDriveRunner.uploadStatus
                else if (!enabled) trackingStatus = "위치 공유 중지 · 기존 공유 링크 종료"
                else if (reportedHere != null && generation == trackingGeneration)
                    trackingStatus = foregroundTrackingStatus(active, reportedHere?.locationSent == true, TripTrackingService.hasPermission(getApplication()),
                        etaFailed = reportedHere?.eta == EtaReport.FAILED)
                guardianCalls = institution.lastGuardianCalls
                institutionStatus = "서버 기록 동기화 완료 · 이번 전송 ${count}건. 보호자 푸시는 서버에서 별도 처리합니다."
            } catch (e: Exception) { trackingStatus = "위치 전송 실패 · 보호자 화면은 갱신 지연으로 전환"; institutionStatus = "기기 기록 유지 · 전송 대기 ${institution.pending(snapshot)}건: ${e.message ?: "연결 실패"}" }
            finally {
                syncing = false
                nextSyncAt = if (generation != trackingGeneration) 0 else System.currentTimeMillis() + 5000
                // 동기화 중에 눌린 운행 준비를 이어서 한다(미전송이 남았으면 그때 거절 안내).
                if (pendingPrepare) { val id = pendingPrepareTripId; pendingPrepare = false; pendingPrepareTripId = null; loadInstitutionTrip(id) }
                if (generation != trackingGeneration) syncInstitution(true)
            }
        }
    }
    private fun dispatchAutomaticGuidance() {
        // 운행 없음 상태에서는 자동 음성 안내·자동 길 안내를 하지 않는다.
        if (storageError != null || !trip.hasTrip) return
        // V-08 출발 전 요약은 흐름 카드가 [운행 시작] 단계를 보일 때와 같은 조건(정차 확인 · 차량 UX 제한 없음)에서만 읽는다.
        val departureReady = engine.stationaryBlockers(now).isEmpty() && sdkVehicle.uxRestricted.value != true
        automaticGuidance.takeSpeech(trip, foreground, departureReady)?.let { cue ->
            // V-06: 운행 안내가 읽던 벨트 경고 음성을 끊으면, 경고가 계속 켜져 있을 때 안내가 끝난 뒤 다시 읽게 한다.
            if (beltTelltaleSpeechId != null && audio.snapshot.speechRequestId == beltTelltaleSpeechId && audioBusy()) beltTelltaleGuidance.rearm()
            // Cancel stale destination speech before the new cue; the adapter rejects late callbacks.
            audio.stopSpeech()
            automaticAnnouncementKeys = (automaticAnnouncementKeys + cue.key).takeLast(100)
            audio.speak(cue.text)
        }
        // V-06: 경고등 음성은 다른 안내를 끊지 않는다. 말하는 중이면 기다렸다가 끝난 뒤 한 번 읽는다.
        beltTelltaleGuidance.take(trip, beltTelltaleSignal(), foreground, audioBusy())?.let { cue ->
            beltTelltaleAnnouncements++
            audio.speak(cue.text)
            beltTelltaleSpeechId = audio.snapshot.speechRequestId
        }
        val ready = !navi.busy && navi.routeState.isFresh(now, 6_000)
        automaticGuidance.takeNavigation(trip, foreground, ready)?.let { stop ->
            automaticNavigationStops = (automaticNavigationStops + stop.id).takeLast(100)
            // The cue is already deduplicated. A shared final destination does not imply that
            // the remaining waypoint list is unchanged after a handover or reordered route.
            navigateNext()
        }
    }

    private fun audioBusy() = audio.snapshot.speechStatus in setOf(SpeechStatus.INITIALIZING, SpeechStatus.SPEAKING)

    fun markRouteCriteriaSeen() {
        if (routeCriteriaSeen) return
        routeCriteriaSeen = true
        routePrefs.edit().putBoolean("criteriaSeen", true).apply()
    }
    fun replayGuidance() {
        audio.stopSpeech()
        audio.speak(if (!trip.hasTrip) NO_TRIP_MESSAGE else tripCue(trip)?.text ?: "어린이의 탑승을 확인하고 기록해 주세요.")
    }
    fun perform(action: ShuttleEngine.(Long) -> Boolean) {
        if (storageError != null || institutionBusy) return
        if (!trip.hasTrip) { message = NO_TRIP_MESSAGE; actionError = "불러온 운행이 없습니다. 기관 연결에서 오늘 운행을 불러와 주세요."; return }
        if (sdkVehicle.uxRestricted.value == true) { message = "차량 UX 제한 중에는 기록·상세 조작을 할 수 없습니다."; actionError = "정차 후 다시 확인해 주세요."; return }
        now = System.currentTimeMillis()
        val before = trip
        val accepted = engine.action(now)
        actionError = if (accepted) null else engine.lastMessage
        publish()
        if (!accepted || storageError != null) return
        val transition = automaticTrackingTransition(before, trip)
        if (transition == TrackingTransition.FINISH) changeTrackingEnabled(false)
        else if (foreground && trackingControls.canEnable && transition == TrackingTransition.DEPART) changeTrackingEnabled(true)
    }
    private fun startSdk() {
        if (!getApplication<Application>().packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            message = "현재 기기는 Automotive 환경이 아니어서 차량 정보를 받을 수 없습니다."
            return
        }
        if (realVehicle == null) realVehicle = RealVehicleAdapter(getApplication()) { snapshot ->
            viewModelScope.launch {
                sdkVehicle = snapshot
                if (snapshot.connected != lastSdkConnected) { sdkGeneration++; lastSdkConnected = snapshot.connected }
                if (foreground) {
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
        // V-07 삭제(D39): 좌석 점유는 받지 않는다. 좌석별 벨트 관측만 진단용으로 옮긴다.
        seats = s.seatBelts.keys.mapIndexed { index, area ->
            SeatObservation(index + 1, beltFastened = s.seatBelts[area]?.map { it } ?: Observation(), areaLabel = area)
        },
        connectionGeneration = sdkGeneration,
        parkingBrakeSupported = s.parkingBrakeSupported,
    )
    fun navigateNext() {
        if (!trip.hasTrip) return
        val upcoming = navigationWaypoints(trip).ifEmpty { listOf(trip.origin) }
        realNavi?.requestStops(upcoming.map { com.hanhyo.pleosshuttle.sdk.NaviDestination(it.name,it.latitude,it.longitude) })
    }
    fun openArrival(tripId: String?, stopId: String?, version: Long) {
        if (trip.hasTrip && trip.tripId == tripId && trip.currentStop?.id == stopId && trip.routeVersion == version && trip.phase == TripPhase.DRIVING) {
            panel = "flow"
            message = if (trip.isPickup) "정류장 근처입니다. 정차 후 도착·정차 확인을 눌러 주세요." else "하차 지점 근처입니다. 정차 후 도착·정차 확인을 눌러 주세요."
        }
    }
    fun showNativeNavigation() { realNavi?.showNavigation() }
    fun cancelOwnNavigation() { realNavi?.cancelOwnRoute() }
    /** Gleo 음성 intent(assets/schema.json): OPEN_DROPOFF_PREPARATION · SHOW_NEXT_STOP. 화면만 열고 기록을 바꾸지 않는다. */
    fun handleIntent(action: String?) {
        if (action !in setOf("OPEN_DROPOFF_PREPARATION", "SHOW_NEXT_STOP")) return
        panel = "flow"
        if (!trip.hasTrip) { message = NO_TRIP_MESSAGE; return }
        when(action) {
            "OPEN_DROPOFF_PREPARATION" -> message = if (trip.isPickup) "현재 운행 흐름을 열었습니다. 도착·정차를 확인한 뒤 선생님이 인계받음과 탑승을 기록합니다."
                else "현재 운행 흐름을 열었습니다. 도착·정차를 확인한 뒤 선생님이 인계와 하차를 기록합니다."
            "SHOW_NEXT_STOP" -> message = "다음 지점: ${(trip.currentStop ?: trip.origin).name}"
        }
    }

    fun exportDiagnostics(): File {
        val file = File(getApplication<Application>().filesDir, "diagnostics.txt")
        file.writeText(buildString {
            appendLine("타요타요 차량 앱 ${BuildConfig.VERSION_NAME} | ${java.util.Date()} | ${trip.integrationMode.label}")
            appendLine("trip=${trip.tripId} hasTrip=${trip.hasTrip} phase=${trip.phase} current=${trip.currentStop?.name}")
            appendLine("source/time/status are independent")
            appendLine("SDK: ${sdkVehicle.versions}; Navi: ${navi.versions}")
            appendLine("areas=${sdkVehicle.supportedDoorAreas}/${sdkVehicle.supportedBeltAreas}")
            sdkVehicle.diagnostics.forEach(::appendLine); navi.diagnostics.forEach(::appendLine)
            trip.diagnostics.forEach { appendLine(it.toString()) }; trip.ledger.forEach { appendLine(it.toString()) }
            audioState.events.forEach(::appendLine)
            appendLine("end=${trip.endRecord}")
        })
        message = "진단 저장: 앱 내부 diagnostics.txt"
        return file
    }
    override fun onCleared() { ticker?.cancel(); realVehicle?.close(); realNavi?.close(); audio.close() }

    companion object {
        const val NO_TRIP_MESSAGE = "불러온 운행이 없어요. 기관 연결 후 오늘 운행을 불러와 주세요."
    }
}
