package com.hanhyo.pleosshuttle

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.hanhyo.pleosshuttle.ui.ShuttleApp

class MainActivity : ComponentActivity() {
    lateinit var model: ShuttleViewModel; private set
    private var pendingIntent: Intent? = null
    private val setup by lazy { getSharedPreferences("permission-onboarding",0) }
    private var explainPermissions by mutableStateOf(false)
    /**
     * 주행 시작(D·이동) 때 내비만 보이게: 내비를 앞으로 요청하고 앱 작업을 뒤로 보낸다(전체 화면 기준).
     * Pleos 분할 화면은 앱이 풀 수 없다(moveTaskToBack·작업 종료 모두 반쪽이 다른 앱으로 채워질 뿐, 내비 SDK에도 전체·분할 요청 없음).
     */
    private val hideForDriving: () -> Unit = { model.showNativeNavigation(); moveTaskToBack(true) }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.trackingPermissionResult()
    }
    private fun missingPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_COARSE_LOCATION); add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) add("android.car.permission.CAR_SPEED")
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.filter { ContextCompat.checkSelfPermission(this,it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    private fun requestPermissionsOnce() {
        explainPermissions = false
        setup.edit().putBoolean("explained",true).commit()
        val missing = missingPermissions()
        if (missing.isNotEmpty()) permissions.launch(missing) else model.trackingPermissionResult()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[ShuttleViewModel::class.java]
        explainPermissions = !setup.getBoolean("explained",false) && missingPermissions().isNotEmpty()
        setContent {
            LaunchedEffect(model.trackingPermissionRequested) {
                if (model.trackingPermissionRequested) requestPermissionsOnce()
            }
            ShuttleApp(model) { requestPermissionsOnce() }
            if (explainPermissions) AlertDialog(
                onDismissRequest = { explainPermissions=false; setup.edit().putBoolean("explained",true).apply() },
                title = { Text("운행을 위한 최초 권한 안내") },
                text = { Text("위치·차량 속도·알림 권한을 허용하면 출발 시 보호자 공유가 자동으로 시작됩니다. 운행 종료 시 중단합니다. 이후에는 권한이 유지되는 동안 다시 묻지 않습니다.") },
                confirmButton = { TextButton(onClick=::requestPermissionsOnce) { Text("권한 확인") } },
                dismissButton = { TextButton(onClick={ explainPermissions=false; setup.edit().putBoolean("explained",true).apply() }) { Text("나중에") } })
        }
        pendingIntent = intent
        DriveFocusController.registerHideHandler(hideForDriving)
    }
    override fun onDestroy() { DriveFocusController.clearHideHandler(hideForDriving); super.onDestroy() }
    private fun handlePending() {
        val value=pendingIntent ?: return
        pendingIntent=null
        if (value.action == TripTrackingService.OPEN_ARRIVAL)
            model.openArrival(value.getStringExtra("tripId"),value.getStringExtra("stopId"),value.getLongExtra("routeVersion",-1))
        else model.handleIntent(value.action)
    }
    override fun onStart() { super.onStart(); model.foreground(); handlePending() }
    override fun onStop() { model.background(); super.onStop() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); pendingIntent=intent; handlePending() }
}
