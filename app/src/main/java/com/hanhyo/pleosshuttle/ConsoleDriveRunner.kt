package com.hanhyo.pleosshuttle

import android.content.Context
import com.hanhyo.pleosshuttle.domain.Gear
import com.hanhyo.pleosshuttle.domain.TripPhase
import com.hanhyo.pleosshuttle.domain.TripState
import kotlinx.coroutines.*

/**
 * 프로세스 범위에서 Virtual Console 운전을 돌린다(개발용).
 * 주행 중 Pleos가 앱을 가려 Activity가 멈춰도 1초마다 속도·기어를 직접 읽어(DriveStateObserver) 위치를 적분하고,
 * 위치 공유가 켜져 있으면 약 5초마다 모의 위치(SIMULATED)를 서버에 보낸다. 출결·도착 기록은 바꾸지 않는다.
 * 모든 상태 변경은 메인 스레드에서만 한다.
 */
object ConsoleDriveRunner {
    private const val TICK_MS = 1_000L
    private const val UPLOAD_MS = 5_000L
    private const val UPLOAD_TIMEOUT_MS = 20_000L
    private val drive = ConsoleDrive()
    private var scope: CoroutineScope? = null
    private var observer: DriveStateObserver? = null
    private var app: Context? = null
    private var connection: InstitutionConnection? = null
    private var trip: TripState? = null
    private var hintSpeedMps: Double? = null
    private var hintGear: Gear = Gear.UNKNOWN
    private var hintAt = 0L
    private var lastUploadAt = 0L
    @Volatile private var uploading = false

    @Volatile var frame: ConsoleDriveFrame? = null; private set
    @Volatile var speedMps: Double? = null; private set
    @Volatile var gear: Gear = Gear.UNKNOWN; private set
    @Volatile var uploadStatus: String = "아직 전송하지 않음"; private set
    val running get() = drive.running

    fun start(context: Context, current: TripState, ctx: ConsoleDriveContext): Boolean {
        if (drive.running || !ConsoleDrivePolicy.keep(ctx) || !drive.start(current)) return false
        app = context.applicationContext
        connection = InstitutionConnection(context.applicationContext)
        trip = current
        ConsoleDriveBridge.activeTripId = current.tripId
        observer = runCatching { DriveStateObserver(context.applicationContext) }.getOrNull()
        lastUploadAt = 0; uploadStatus = "전송 준비 중"
        // 주의: Main.immediate의 launch는 그 자리에서 첫 tick을 실행한다. scope를 먼저 저장해야
        // 첫 tick의 upload()가 scope를 null로 보고 uploading=true에 갇히지 않는다(이전 버그).
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        uploading = false
        log("start trip=${current.tripId} leg=${current.currentStopIndex}")
        s.launch { while (isActive) { tick(); delay(TICK_MS) } }
        return true
    }

    /** 화면(ViewModel)이 살아 있을 때 최신 운행과 조건을 알려 준다. 앱이 가려진 동안에는 마지막 값으로 계속 간다. */
    fun update(current: TripState, ctx: ConsoleDriveContext) {
        if (!drive.running) return
        if (!drive.retain(ConsoleDrivePolicy.keep(ctx), current.tripId)) { stop(); return }
        trip = current
    }

    /** 화면이 보일 때 SDK 어댑터 값을 보조로 넘긴다(관측기가 아직 값을 못 읽었을 때만 쓴다). */
    fun hint(speed: Double?, gearValue: Gear, at: Long) { hintSpeedMps = speed; hintGear = gearValue; hintAt = at }

    fun stop() {
        if (drive.running) log("stop")
        drive.stop(); scope?.cancel(); scope = null
        observer?.close(); observer = null
        ConsoleDriveBridge.activeTripId = null
        frame = null; speedMps = null; gear = Gear.UNKNOWN; trip = null
        uploadStatus = "아직 전송하지 않음"
    }

    private fun tick() {
        val current = trip ?: run { stop(); return }
        val now = System.currentTimeMillis()
        val observed = observer?.current?.takeIf { it.speedMps?.isFinite() == true && now - it.receivedAt in 0..5_000 }
        val useHint = observed == null && hintSpeedMps != null && now - hintAt in 0..5_000
        speedMps = observed?.speedMps ?: if (useHint) hintSpeedMps else null
        gear = observed?.gear ?: if (useHint) hintGear else Gear.UNKNOWN
        val next = drive.tick(current, speedMps, gear == Gear.DRIVE) ?: run { stop(); return }
        frame = next
        log("tick speed=${speedMps} gear=${gear} remain=${next.remainingMeters}m near=${next.nearTarget} src=${if (observed != null) "observer" else if (useHint) "hint" else "none"}")
        if (now - lastUploadAt >= UPLOAD_MS) { lastUploadAt = now; upload(current, next, now) }
    }

    private fun upload(current: TripState, f: ConsoleDriveFrame, at: Long) {
        val context = app ?: run { status("전송 안 함 · 앱 컨텍스트 없음"); return }
        val conn = connection ?: run { status("전송 안 함 · 기관 연결 없음"); return }
        val s = scope ?: run { status("전송 안 함 · 실행 범위 없음"); return }
        if (uploading) { status("이전 전송 응답 대기 중"); return }
        if (!context.getSharedPreferences("simulation-settings", 0).getBoolean("trackingEnabled", false)) {
            status("위치 공유 꺼짐 · 모의 위치를 보내지 않음"); return
        }
        if (current.phase != TripPhase.DRIVING && current.phase != TripPhase.ALIGHTING) { status("운행 중이 아니라 전송하지 않음"); return }
        if (!conn.connected || conn.boundTrip != current.tripId) { status("전송 안 함 · 이 운행이 기관 서버에 연결되어 있지 않음"); return }
        val routeId = drive.routeId
        // 항상 simulated=true: 콘솔로 움직인 위치는 실제 Pleos/GPS 관측이 아니다.
        val fix = TrackingLocation(f.position.latitude, f.position.longitude, at, true, routeId, current.currentStopIndex)
        val nav = TrackingNavigation("READY", 0, routeId, f.target.point.latitude, f.target.point.longitude, f.etaSeconds, f.remainingMeters, at)
        uploading = true
        log("upload start lat=${fix.latitude} lon=${fix.longitude}")
        s.launch {
            try {
                // HTTP 자체에도 연결 7초·읽기 10초 제한이 있다(Api.request). 여기서 한 번 더 상한을 둔다.
                withTimeout(UPLOAD_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        conn.reportTracking(current, true, fix, nav) { drive.tripId == current.tripId && drive.routeId == routeId }
                    }
                }
                status("모의 위치 전송 · 실제 차량 위치 아님")
            } catch (e: TimeoutCancellationException) {
                status("모의 위치 전송 시간 초과")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status("모의 위치 전송 실패 · ${e.message ?: e.javaClass.simpleName}")
            } finally { uploading = false }
        }
    }

    private fun status(value: String) { uploadStatus = value; log("upload: $value") }
    private fun log(message: String) { if (BuildConfig.DEBUG) android.util.Log.d("ConsoleDrive", message) }
}
