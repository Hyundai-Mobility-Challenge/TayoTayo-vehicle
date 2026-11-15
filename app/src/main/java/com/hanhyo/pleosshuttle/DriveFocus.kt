package com.hanhyo.pleosshuttle

import android.content.Context
import android.content.Intent
import com.hanhyo.pleosshuttle.domain.Gear
import com.hanhyo.pleosshuttle.domain.IntegrationMode
import com.hanhyo.pleosshuttle.domain.TripPhase
import com.hanhyo.pleosshuttle.domain.TripState
import java.lang.ref.WeakReference
import kotlinx.coroutines.*
import kotlin.math.abs

/** 주행 집중 화면 전환 동작. HIDE_APP = 내비만 보이게(앱을 뒤로), SHOW_APP = 정차했으니 앱을 앞으로. */
enum class DriveFocusAction { NONE, HIDE_APP, SHOW_APP }

/**
 * 운전 중에는 내비만, 정차(P)하면 운행 화면을 보이게 하는 규칙. 기록·운행 상태는 바꾸지 않는다.
 * - 대상: 차량 연결(SDK) 운행의 이동·정차 단계(DRIVING·ALIGHTING)만. 준비·종료·운행 없음 상태는 손대지 않는다.
 * - 숨김: 기어 D이고 실제로 움직일 때(신호 대기처럼 D에서 잠깐 멈춘 것은 계속 주행으로 본다).
 * - 복귀: 숨긴 뒤 기어 P·속도 0이 되면 한 번.
 * 오래된 관측(5초 넘음)이나 기어 모름이면 아무것도 하지 않는다.
 */
class DriveFocusPolicy {
    private var hidden = false
    fun evaluate(trip: TripState?, motion: DriveObservation, now: Long): DriveFocusAction {
        val eligible = trip != null && trip.integrationMode == IntegrationMode.SDK &&
            (trip.phase == TripPhase.DRIVING || trip.phase == TripPhase.ALIGHTING)
        if (!eligible) { hidden = false; return DriveFocusAction.NONE }
        if (!motion.fresh(now)) return DriveFocusAction.NONE
        val speed = abs(motion.speedMps!!)
        return when {
            !hidden && motion.gear == Gear.DRIVE && speed >= MOVING_MPS -> { hidden = true; DriveFocusAction.HIDE_APP }
            hidden && motion.gear == Gear.PARK && speed <= STOPPED_MPS -> { hidden = false; DriveFocusAction.SHOW_APP }
            else -> DriveFocusAction.NONE
        }
    }
    companion object {
        /** 약 3.6 km/h. 이보다 느리면 아직 출발 전(또는 서행 정차)으로 본다. */
        const val MOVING_MPS = 1.0
        const val STOPPED_MPS = 0.01
    }
}

/**
 * 프로세스 범위에서 1초마다 기어·속도를 읽어 [DriveFocusPolicy]를 적용한다. 앱이 뒤로 가 Activity가 멈춰도 계속 돈다.
 * 숨김은 화면(MainActivity)이 등록한 처리기로 내비를 앞으로 요청하고 앱 작업을 뒤로 보낸다.
 * 복귀는 MainActivity를 앞으로 연다(플랫폼이 막으면 TripTrackingService의 도착 알림처럼 알림으로만 안내).
 */
object DriveFocusController {
    private const val TICK_MS = 1_000L
    private var scope: CoroutineScope? = null
    private var observer: DriveStateObserver? = null
    private var app: Context? = null
    private var trip: TripState? = null
    private val policy = DriveFocusPolicy()
    private var hideHandler: WeakReference<() -> Unit>? = null
    @Volatile var lastAction: String = "없음"; private set

    /** MainActivity가 살아 있는 동안 등록한다. */
    fun registerHideHandler(handler: () -> Unit) { hideHandler = WeakReference(handler) }
    fun clearHideHandler(handler: () -> Unit) { if (hideHandler?.get() === handler) hideHandler = null }

    /** ViewModel이 화면에 보일 때마다 최신 운행을 넘긴다. 대상 운행이 아니면 관측을 멈춘다. */
    fun update(context: Context, current: TripState) {
        val wanted = current.integrationMode == IntegrationMode.SDK &&
            (current.phase == TripPhase.DRIVING || current.phase == TripPhase.ALIGHTING)
        trip = current
        if (!wanted) { stop(); return }
        if (scope != null) return
        app = context.applicationContext
        observer = runCatching { DriveStateObserver(context.applicationContext) }.getOrNull()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch { while (isActive) { tick(); delay(TICK_MS) } }
    }

    fun stop() {
        scope?.cancel(); scope = null
        observer?.close(); observer = null
        policy.evaluate(null, DriveObservation(), 0)
    }

    private fun tick() {
        val motion = observer?.current ?: return
        when (policy.evaluate(trip, motion, System.currentTimeMillis())) {
            DriveFocusAction.HIDE_APP -> {
                val handler = hideHandler?.get()
                lastAction = if (handler != null) { handler(); "주행 · 내비만 표시" } else "주행 · 앱이 이미 뒤에 있음"
                log("hide app (handler=${handler != null})")
            }
            DriveFocusAction.SHOW_APP -> {
                val context = app ?: return
                runCatching {
                    context.startActivity(Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }.onSuccess { lastAction = "정차 · 운행 화면 표시" }.onFailure { lastAction = "정차 · 화면 열기 실패 ${it.javaClass.simpleName}" }
                log("show app: $lastAction")
            }
            DriveFocusAction.NONE -> Unit
        }
    }

    private fun log(message: String) { if (BuildConfig.DEBUG) android.util.Log.d("DriveFocus", message) }
}
