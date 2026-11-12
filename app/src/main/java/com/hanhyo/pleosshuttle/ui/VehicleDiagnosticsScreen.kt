package com.hanhyo.pleosshuttle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.hanhyo.pleosshuttle.ShuttleViewModel
import com.hanhyo.pleosshuttle.designsystem.theme.AppSpacing
import com.hanhyo.pleosshuttle.designsystem.theme.AppTheme
import com.hanhyo.pleosshuttle.domain.IntegrationMode
import com.hanhyo.pleosshuttle.ui.component.Action
import com.hanhyo.pleosshuttle.ui.component.Foldout
import com.hanhyo.pleosshuttle.ui.component.Notice
import com.hanhyo.pleosshuttle.ui.component.Section

@Composable internal fun ObservationPanel(vm: ShuttleViewModel, requestSpeedPermission: () -> Unit) {
    Section("차량 관측과 권한", "지원 Area와 UI 가상 좌석은 대응 관계를 가정하지 않습니다") {
        Text("${vm.trip.integrationMode.label} · 연결 ${vm.vehicle.connected}")
        Text("속도: ${valueLabel(vm.vehicle.speedKph, vm.now)} km/h\n기어: ${if (vm.vehicle.gear.isFresh(vm.now)) vm.vehicle.gear.value?.label else "미확인/오래됨"}\n주차 브레이크: ${if (vm.vehicle.parkingBrake.isFresh(vm.now)) boolText(vm.vehicle.parkingBrake.value, "적용", "해제") else "미확인/오래됨"}\n문 잠금: ${if (vm.vehicle.doorLocked.isFresh(vm.now)) boolText(vm.vehicle.doorLocked.value, "잠김", "해제") else "미확인/오래됨"}")
        if (vm.trip.integrationMode == IntegrationMode.SDK) {
            Text("실제 제어 가드: ${vm.sdkVehicle.emulatorGuard}\n${vm.sdkVehicle.unlockBlockReason}")
            Text("UX 제한: ${vm.sdkVehicle.uxRestricted.value ?: "미확인"} / ${vm.sdkVehicle.activeUxRestrictions ?: "미수신"}")
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                Action("차량 속도 조회 권한 요청", "speed-permission", secondary = true) { requestSpeedPermission() }
                Action("관측 새로고침", "refresh-sdk") { vm.refreshSdk() }
            }
        }
        Foldout("좌석·벨트 관측", "명단과 센서 관측은 별개예요", "sensors") { Sensors(vm) }
        Foldout("내비 연결", if (vm.trip.integrationMode == IntegrationMode.SIMULATION) "모의 안내" else vm.navi.outcome.toString(), "navigation") { NavigationStatus(vm) }
        Foldout("지원 범위", "주변·어린이 잠금 접근 미확인", "support") {
            Text("가상 좌석 1–4는 명단 표시용이며 실제 센서 영역과 일치한다고 가정하지 않습니다.")
            Text("주변 카메라·접근 물체·SEA·어린이 잠금 접근, Gleo 음성 호출은 미검증입니다.")
        }
    }
}

@Composable internal fun Sensors(vm: ShuttleViewModel) {
    Text("좌석·벨트 관측", style = AppTheme.typography.body1SemiBold)
    if (vm.vehicle.seats.isEmpty()) Notice("벨트 지원 Area 또는 센서 관측 미수신.")
    if (vm.trip.integrationMode == IntegrationMode.SDK) {
        Text("벨트 지원 Area: ${vm.sdkVehicle.supportedBeltAreas}")
        vm.sdkVehicle.seatBelts.forEach { (area, v) -> Text("$area 벨트 ${if (v.isFresh(vm.now)) boolText(v.value, "체결", "미체결") else "미확인/오래됨 (마지막값 ${v.value})"} · ${v.outcome} · ${timeText(v.observedAtMillis)}") }
    } else vm.vehicle.seats.forEach { seat ->
        Text("모의 Area ${seat.areaId} · 벨트 ${if (seat.beltFastened.isFresh(vm.now)) boolText(seat.beltFastened.value, "체결", "미체결") else "미확인/오래됨"}\n${seat.beltFastened.source.label} · ${timeText(seat.beltFastened.observedAtMillis)} · ${if (seat.beltFastened.isFresh(vm.now)) "최신 관측" else "미확인/오래됨"}")
    }
}

@Composable internal fun DiagnosticsPanel(vm: ShuttleViewModel) {
    Section("진단", "시험 중 문제가 생겼을 때 확인하세요") {
        Text("음성: ${vm.audioState.ttsState}", style = AppTheme.typography.body1SemiBold)
        Action("진단 파일 저장", "export-diagnostics") { vm.exportDiagnostics() }
    }
    Foldout("앱 처리 기록", "최근 ${vm.trip.diagnostics.takeLast(25).size}건", "app-log") {
        vm.trip.diagnostics.takeLast(25).reversed().forEach { entry ->
            Text("${timeText(entry.atMillis)} · ${entry.feature} · ${entry.status.label}", style = AppTheme.typography.body2SemiBold)
            Text(entry.message)
            Text("이전 ${entry.before} / 요청 ${entry.requested} / 관측 ${entry.observed} / 오류 ${entry.errorCode}", style = AppTheme.typography.caption1Regular)
            HorizontalDivider(color = AppTheme.colors.outline)
        }
    }
    Foldout("차량·내비 연결 기록", "버전과 SDK 응답", "sdk-log") {
        Text("SDK/Service ${vm.sdkVehicle.versions}\nNavi ${vm.navi.versions}\n문 Area ${vm.sdkVehicle.supportedDoorAreas}")
        vm.sdkVehicle.diagnostics.takeLast(15).reversed().forEach { Text(it) }
        vm.navi.diagnostics.takeLast(10).reversed().forEach { Text(it) }
    }
    Foldout("음악·음성 상태", "재생과 출력 정보", "audio-log") {
        Text("음악 ${vm.audioState.musicState} · ${vm.audioState.focusState}\n출력 경로 ${vm.audioState.outputRouteLabel ?: "재생 중 관측되지 않음"}")
        Text("앱을 벗어나면 재생을 멈춥니다. 실제 청취는 따로 확인해 주세요.")
        vm.audioState.events.takeLast(10).reversed().forEach { Text(it) }
    }
    Foldout("탑승·하차 변경 이력", "${vm.trip.ledger.size}건 · 정정 전 기록 유지", "ledger-log") {
        if (vm.trip.ledger.isEmpty()) Text("아직 기록이 없습니다.")
        vm.trip.ledger.reversed().forEach { entry ->
            Text(if (entry.isDriverCheckRecord) "${timeText(entry.recordedAtMillis)} · 기사 확인 ${if (entry.driverCheck == true) "체크" else "없음·해제"}"
                else if (entry.isStopExclusionRecord) "${timeText(entry.recordedAtMillis)} · 정류장 ${entry.stopId} ${if (entry.stopExcluded == true) "제외" else "다시 넣기"}"
                else "${timeText(entry.recordedAtMillis)} · ${entry.childId} · ${entry.before.label} → ${entry.after.label}")
            Text(entry.reason, color = AppTheme.colors.textSecondary)
            HorizontalDivider(color = AppTheme.colors.outline)
        }
    }
}

@Composable internal fun NavigationStatus(vm: ShuttleViewModel) {
    if (vm.trip.integrationMode == IntegrationMode.SIMULATION) Notice("${vm.simulatedNavigation}\n순정 내비 연동은 모의 모드에서 요청하지 않습니다.")
    else {
        val dest = vm.navi.destination.value
        Notice("내비 ${vm.navi.outcome} · ${vm.navi.message}\n요청 ${vm.navi.requested}\n관측 ${dest?.name ?: "미수신"} (${dest?.latitude}, ${dest?.longitude})\n안내 ${vm.navi.routeState.value ?: "미확인"} · ${timeText(vm.navi.destination.observedAtMillis)}")
        Text("실패 시 위 다음 지점 이름·좌표를 수동으로 확인할 수 있습니다. 위치 도착으로 하차를 자동 기록하지 않습니다.")
        Action("이 앱의 시험 안내 취소", "cancel-navigation", secondary = true) { vm.cancelOwnNavigation() }
    }
}
