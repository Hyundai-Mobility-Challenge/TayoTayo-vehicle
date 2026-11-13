package com.hanhyo.pleosshuttle

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hanhyo.pleosshuttle.domain.*
import com.hanhyo.pleosshuttle.sdk.RealNaviAdapter
import kotlinx.coroutines.*

/** User-visible, non-sticky observation service. Never drives the route or changes trip/handover state. */
class TripTrackingService : Service() {
    companion object {
        @Volatile var running = false; private set
        const val OPEN_ARRIVAL = "com.hanhyo.pleosshuttle.OPEN_ARRIVAL"
        fun hasPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private var navi: RealNaviAdapter? = null
    private var expectedTrip: String? = null
    private val prefs by lazy { getSharedPreferences("simulation-settings", 0) }
    private val connection by lazy { InstitutionConnection(this) }
    private val store by lazy { TripStore(this) }
    private var routeKey: String? = null
    private var drive: DriveStateObserver? = null
    private val navigationPolicy = TripNavigationPolicy()
    private var arrivalNoticeKey: String? = null
    private fun arrivalNotice(trip: TripState, ready: Boolean) {
        val manager=getSystemService(NotificationManager::class.java)
        if (!ready) { manager.cancel(92);arrivalNoticeKey=null;return }
        val key="${trip.tripId}/${trip.routeVersion}/${trip.currentStopIndex}"
        if (key==arrivalNoticeKey) return
        if (Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return
        manager.createNotificationChannel(NotificationChannel("trip_arrival","하차 지점 도착 확인",NotificationManager.IMPORTANCE_HIGH))
        val intent=Intent(this,MainActivity::class.java).setAction(OPEN_ARRIVAL)
            .putExtra("tripId",trip.tripId).putExtra("stopId",trip.currentStop?.id).putExtra("routeVersion",trip.routeVersion)
        val pending=PendingIntent.getActivity(this,key.hashCode(),intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(92,NotificationCompat.Builder(this,"trip_arrival").setSmallIcon(R.drawable.ic_shuttle)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION).setContentTitle(if (trip.isPickup) "정류장 근처 · 정차 확인" else "하차 지점 근처 · 정차 확인")
            .setContentText(if (trip.isPickup) "${trip.currentStop?.name} · 눌러서 도착·인계받음을 확인해 주세요." else "${trip.currentStop?.name} · 눌러서 도착·인계를 확인해 주세요.")
            .setContentIntent(pending).addAction(0,"도착·정차 확인",pending).setOnlyAlertOnce(true).setAutoCancel(true).build())
        arrivalNoticeKey=key
    }
    private fun active(trip: TripState) = TrackingLifecycle.desired(
        prefs.getBoolean("trackingEnabled", false), connection.connected,
        trip.tripId == connection.boundTrip && trip.tripId == expectedTrip,
        trip.integrationMode == IntegrationMode.SDK,
        trip.phase == TripPhase.DRIVING || trip.phase == TripPhase.ALIGHTING)

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loop != null) return START_NOT_STICKY
        expectedTrip = intent?.getStringExtra("tripId")
        val trip = runCatching { store.load() }.getOrNull()
        if (trip == null || !active(trip) || !hasPermission(this) ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            stopSelf(); return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("trip_tracking", "운행 위치 공유", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 91, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(this, "trip_tracking").setSmallIcon(R.drawable.ic_shuttle)
                .setContentTitle("운행 위치 공유 중").setContentText("Pleos 내비 위치를 보호자에게 전달합니다.")
                .setOngoing(true).setContentIntent(open).build()
            ServiceCompat.startForeground(this, 91, notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } catch (_: RuntimeException) {
            prefs.edit().putString("trackingServiceStatus", "위치 서비스 시작 불가 · 앱에서 권한과 위치 설정을 확인해 주세요.").apply()
            stopSelf(); return START_NOT_STICKY
        }
        running = true
        drive = DriveStateObserver(this)
        navi = RealNaviAdapter(this, diagnosticsEnabled = false, onUpdate = {}).also { it.trackLocations = true; it.onStart() }
        loop = scope.launch {
            while (isActive) {
                val current = runCatching { store.load() }.getOrNull()
                if (current == null || !active(current) || !hasPermission(this@TripTrackingService)) {
                    navi?.close(); navi = null
                    if (current != null && current.tripId == expectedTrip) runCatching {
                        withContext(Dispatchers.IO) { connection.reportTracking(current, false, null) }
                    }
                    break
                }
                val key = "${current.tripId}/${current.routeVersion}/${current.currentStopIndex}"
                if (key != routeKey) {
                    navi?.invalidateTracking()
                    val upcoming=com.hanhyo.pleosshuttle.domain.navigationWaypoints(current)
                    navi?.expectTrackingStops(upcoming.map { it.latitude to it.longitude },upcoming.map { it.name })
                    routeKey = key; navi?.refresh()
                }
                val fix = navi?.trackingLocation?.takeIf { it.valid(System.currentTimeMillis()) }
                val navigation = navi?.trackingNavigation
                val decision=navigationPolicy.evaluate(current,fix,navigation,drive?.current ?: DriveObservation(),System.currentTimeMillis())
                if (decision.showNavigation) navi?.showNavigation()
                arrivalNotice(current,decision.arrivalReady)
                // 개발용 Virtual Console 운전 중에는 앱이 모의 위치(SIMULATED)를 보내므로 Pleos 위치 보고를 쉰다.
                if (ConsoleDriveBridge.activeTripId == current.tripId) {
                    prefs.edit().putString("trackingServiceStatus", "Virtual Console 운전 중 · 앱이 모의 위치를 전송").apply()
                    delay(5000); continue
                }
                runCatching {
                    withContext(Dispatchers.IO) {
                        connection.reportTracking(current, true, fix, navigation) {
                            val latest = runCatching { store.load() }.getOrNull()
                            prefs.getBoolean("trackingEnabled", false) && latest != null && active(latest) &&
                                latest.routeVersion == current.routeVersion && latest.currentStopIndex == current.currentStopIndex
                        }
                    }
                }.onSuccess {
                    prefs.edit().putString("trackingServiceStatus", if (fix == null) "Pleos 위치 수신 대기" else "Pleos 내비 위치 전송 중 · 내비 화면에서도 유지").apply()
                }.onFailure {
                    prefs.edit().putString("trackingServiceStatus", "전송 연결 끊김 · 보호자 화면은 갱신 대기로 전환").apply()
                }
                delay(5000)
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        running = false; scope.cancel(); navi?.close(); navi = null
        drive?.close();drive=null
        getSystemService(NotificationManager::class.java).cancel(92)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
