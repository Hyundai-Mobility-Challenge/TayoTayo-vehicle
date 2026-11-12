package com.hanhyo.pleosshuttle.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hanhyo.pleosshuttle.MovingDemoPhase
import com.hanhyo.pleosshuttle.ShuttleViewModel
import com.hanhyo.pleosshuttle.VehicleTrackingControls
import com.hanhyo.pleosshuttle.domain.IntegrationMode
import com.hanhyo.pleosshuttle.designsystem.theme.AppTheme
import com.hanhyo.pleosshuttle.ui.component.Action

@Composable internal fun VehicleTrackingSummary(vm: ShuttleViewModel) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).testTag("vehicle-tracking-summary"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("보호자 하원 진행 · 위치 공유", style = AppTheme.typography.body1SemiBold)
        Text(vm.trackingControls.guidance, style = AppTheme.typography.body3Regular)
        if (vm.trackingEnabled) Text(vm.trackingStatus, style = AppTheme.typography.body3Regular)
        Action("기관 · 배정 명단", "open-tracking-settings", secondary = true) { vm.panel = "institution" }
        if (vm.trip.integrationMode == IntegrationMode.SDK && !vm.locationPermissionGranted) {
            Action("운행 권한 확인", "tracking-permission", secondary = true) { vm.requestTrackingPermission() }
        }
        if (vm.trip.integrationMode == IntegrationMode.SDK) {
            Action("Pleos 내비 지도", "flow-native-map", secondary = true) { vm.showNativeNavigation() }
        }
    }
}

@Composable internal fun VehicleSharingControls(
    state: VehicleTrackingControls, enabled: Boolean, busy: Boolean, status: String,
    simulation: Boolean, onEnabled: (Boolean) -> Unit, onDemo: () -> Unit,
    demoPhase: MovingDemoPhase = MovingDemoPhase.STOPPED, demoProgress: Int = 0,
    onStart: () -> Unit = {}, onPause: () -> Unit = {}, onResume: () -> Unit = {}, onStop: () -> Unit = {},
) {
    Text("보호자 하원 위치 전송", style = MaterialTheme.typography.titleLarge)
    Text(state.source)
    Text(state.guidance, modifier = Modifier.testTag("vehicle-tracking-guidance"))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(checked = enabled, onCheckedChange = onEnabled,
            enabled = enabled || (state.canEnable && !busy), modifier = Modifier.testTag("vehicle-tracking-toggle"))
        Text(if (enabled) "위치 전송 켜짐" else "위치 전송 꺼짐")
    }
    Text(status, modifier = Modifier.testTag("tracking-status"))
    if (simulation) {
        Text("이동 시연 · 가상 좌표", style = MaterialTheme.typography.titleMedium)
        Text(when(demoPhase) {
            MovingDemoPhase.STOPPED -> "시작하면 90초 동안 현재 픽업 지점으로 이동합니다."
            MovingDemoPhase.RUNNING -> "시연 이동 중 · $demoProgress%"
            MovingDemoPhase.PAUSED -> "시연 일시정지 · $demoProgress% · 도착 시간 표시 중지"
            MovingDemoPhase.ARRIVED -> "시연 지점 도달 · 도착·인계·하차는 직접 확인해 주세요."
        }, modifier = Modifier.testTag("moving-demo-status"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (demoPhase == MovingDemoPhase.STOPPED) Button(onClick = onStart, enabled = state.canSendDemo && !busy,
                modifier = Modifier.testTag("moving-demo-start")) { Text("이동 시연 시작") }
            if (demoPhase == MovingDemoPhase.RUNNING) OutlinedButton(onClick = onPause,
                modifier = Modifier.testTag("moving-demo-pause")) { Text("일시정지") }
            if (demoPhase == MovingDemoPhase.PAUSED) Button(onClick = onResume,
                modifier = Modifier.testTag("moving-demo-resume")) { Text("시연 계속") }
            if (demoPhase != MovingDemoPhase.STOPPED) OutlinedButton(onClick = onStop,
                modifier = Modifier.testTag("moving-demo-stop")) { Text("시연 종료") }
        }
        Text("약 5초마다 가상 위치를 전송합니다. 실제 도로 경로나 Pleos ETA가 아닙니다. 앱을 벗어나면 시연이 중지됩니다. 다음 지점은 자동으로 시작합니다.")
        OutlinedButton(onClick = onDemo, enabled = state.canSendDemo && !busy && demoPhase == MovingDemoPhase.STOPPED,
            modifier = Modifier.testTag("vehicle-demo-location")) { Text("시험용 위치 1회 전송") }
        Text("실제 차량 위치가 아닙니다. 30초 뒤 갱신 지연으로 표시하며 도착 시간·하차 기록을 만들지 않습니다.")
    } else {
        Text("최초 위치 권한을 허용하면 출발 시 자동 전송하고 Pleos 내비 화면에서도 전송을 유지합니다. 운행 종료·공유 끄기 시 중지합니다.")
    }
    Text("보호자 앱에는 하차 순서와 우리 아이 예상 시간이 표시됩니다. Pleos 연동에서는 내비 시간을, 모의 체험에서는 시연 시간을 사용합니다.")
}
