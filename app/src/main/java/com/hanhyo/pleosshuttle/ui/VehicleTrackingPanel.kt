package com.hanhyo.pleosshuttle.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hanhyo.pleosshuttle.ShuttleViewModel
import com.hanhyo.pleosshuttle.VehicleTrackingControls
import com.hanhyo.pleosshuttle.domain.TripDirections
import com.hanhyo.pleosshuttle.designsystem.theme.AppTheme
import com.hanhyo.pleosshuttle.ui.component.Action

@Composable internal fun VehicleTrackingSummary(vm: ShuttleViewModel) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).testTag("vehicle-tracking-summary"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("보호자 ${vm.tripDirectionLabel} 진행 · 위치 공유", style = AppTheme.typography.body1SemiBold)
        Text(vm.trackingControls.guidance, style = AppTheme.typography.body3Regular)
        if (vm.trackingEnabled) Text(vm.trackingStatus, style = AppTheme.typography.body3Regular)
        Action("기관 · 배정 명단", "open-tracking-settings", secondary = true) { vm.panel = "institution" }
        if (!vm.locationPermissionGranted) {
            Action("운행 권한 확인", "tracking-permission", secondary = true) { vm.requestTrackingPermission() }
        }
        Action("Pleos 내비 지도", "flow-native-map", secondary = true) { vm.showNativeNavigation() }
    }
}

/** 개발 도구의 위치 전송 켜기·끄기. 평상시에는 출발할 때 자동으로 켜지고 운행이 끝나면 꺼진다. */
@Composable internal fun VehicleSharingControls(
    state: VehicleTrackingControls, enabled: Boolean, busy: Boolean, status: String,
    onEnabled: (Boolean) -> Unit, directionLabel: String = "하원",
) {
    val pickup = directionLabel == TripDirections.label(TripDirections.PICK_UP)
    Text("보호자 $directionLabel 위치 전송", style = MaterialTheme.typography.titleLarge)
    Text(state.source)
    Text(state.guidance, modifier = Modifier.testTag("vehicle-tracking-guidance"))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(checked = enabled, onCheckedChange = onEnabled,
            enabled = enabled || (state.canEnable && !busy), modifier = Modifier.testTag("vehicle-tracking-toggle"))
        Text(if (enabled) "위치 전송 켜짐" else "위치 전송 꺼짐")
    }
    Text(status, modifier = Modifier.testTag("tracking-status"))
    Text("최초 위치 권한을 허용하면 출발 시 자동 전송하고 Pleos 내비 화면에서도 전송을 유지합니다. 운행 종료·공유 끄기 시 중지합니다.")
    Text("보호자 앱에는 ${if (pickup) "정류장 순서" else "하차 순서"}와 우리 아이 예상 시간이 표시됩니다. 예상 시간은 Pleos 내비 시간을 사용합니다.")
}

/** 개발용 Virtual Console 운전. 디버그 빌드에서 기관 서버에 연결된 운행일 때만 쓸 수 있다(막힌 이유는 화면에 보인다). */
@Composable internal fun ConsoleDriveControls(vm: ShuttleViewModel) {
    if (!com.hanhyo.pleosshuttle.BuildConfig.DEBUG) return
    val blocker = vm.consoleDriveBlocker
    Text("Virtual Console 운전 · 앱 안 위치", style = MaterialTheme.typography.titleMedium)
    Text(if (vm.consoleDriveRunning) com.hanhyo.pleosshuttle.ConsoleDriveText.status(vm.consoleDriveFrame, vm.consoleDriveSpeedKph, vm.consoleDriveGearLabel)
        else blocker ?: "시작하면 Virtual Console의 속도·기어(D)로 다음 지점까지 앱 안 위치가 움직입니다.",
        modifier = Modifier.testTag("console-drive-status"))
    if (vm.consoleDriveRunning) Text(vm.consoleDriveUploadStatus, modifier = Modifier.testTag("console-drive-upload"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!vm.consoleDriveRunning) Button(onClick = vm::startConsoleDrive, enabled = blocker == null,
            modifier = Modifier.testTag("console-drive-start")) { Text("Virtual Console 운전 시작") }
        else OutlinedButton(onClick = vm::stopConsoleDrive, modifier = Modifier.testTag("console-drive-stop")) { Text("Virtual Console 운전 종료") }
    }
    Text("기어 D에서 속도만큼 직선으로 움직이고 P·정지면 멈춥니다. 이 위치는 실제 차량 위치가 아니라고 표시해(SIMULATED) 전송합니다. 도착·정차는 직접 확인해 주세요. 주행 중 앱이 가려져도 계속 움직이며, 운행이 끝나거나 직접 종료하면 멈춥니다. 전송은 위치 공유가 켜져 있을 때만 합니다.")
}
