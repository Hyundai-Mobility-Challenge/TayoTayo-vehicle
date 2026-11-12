package com.hanhyo.pleosshuttle.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.hanhyo.pleosshuttle.ShuttleViewModel
import com.hanhyo.pleosshuttle.TrialScenario
import com.hanhyo.pleosshuttle.designsystem.theme.AppRadius
import com.hanhyo.pleosshuttle.designsystem.theme.AppSpacing
import com.hanhyo.pleosshuttle.designsystem.theme.AppTheme
import com.hanhyo.pleosshuttle.designsystem.theme.canvasDp
import com.hanhyo.pleosshuttle.domain.IntegrationMode
import com.hanhyo.pleosshuttle.domain.TripPhase
import com.hanhyo.pleosshuttle.ui.component.Action
import com.hanhyo.pleosshuttle.ui.component.BlockReasons
import com.hanhyo.pleosshuttle.ui.component.ChipTone
import com.hanhyo.pleosshuttle.ui.component.DialogButton
import com.hanhyo.pleosshuttle.ui.component.Foldout
import com.hanhyo.pleosshuttle.ui.component.GuideReasonField
import com.hanhyo.pleosshuttle.ui.component.Notice
import com.hanhyo.pleosshuttle.ui.component.Section
import com.hanhyo.pleosshuttle.ui.component.StatusChip
import com.hanhyo.pleosshuttle.ui.component.guideAnchor

@Composable internal fun TestTools(vm: ShuttleViewModel) {
    Text("필요한 시험만 골라보세요", style = AppTheme.typography.head4Bold)
    TrialSessionTools(vm)
    if (com.hanhyo.pleosshuttle.BuildConfig.DEBUG) Foldout("위치 전송 시험", "개발용 · 평상시에는 출발과 함께 자동 전송", "tracking-lab") {
        VehicleSharingControls(vm.trackingControls, vm.trackingEnabled, vm.institutionBusy, vm.trackingStatus,
            simulation = vm.trip.integrationMode == IntegrationMode.SIMULATION,
            onEnabled = vm::changeTrackingEnabled, onDemo = vm::sendDemoTrackingLocation,
            demoPhase = vm.movingDemoPhase, demoProgress = vm.movingDemoProgress,
            onStart = vm::startMovingDemo, onPause = vm::pauseMovingDemo, onResume = vm::resumeMovingDemo, onStop = vm::stopMovingDemo)
    }
    val items = listOf(
        Triple("trials", "상황 바꾸기", "정차·이동·연결 끊김을 모의로 재현"),
        Triple("door-lab", "문 시험", "기록과 별도로 문 잠금 요청 확인"),
        Triple("observations", "차량 관측", "속도·기어·센서 상태와 연결 확인"),
        Triple("diagnostics", "진단", "오류·변경 이력을 확인하고 파일 저장"),
    )
    items.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.lg)) {
            row.forEach { (id, title, description) ->
                Card(
                    onClick = { vm.panel = id },
                    modifier = Modifier.weight(1f).testTag("tab-$id"),
                    shape = RoundedCornerShape(AppRadius.card),
                    colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceRaised, contentColor = AppTheme.colors.textPrimary),
                ) {
                    Column(Modifier.padding(AppSpacing.xxl), verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                        Text(title, style = AppTheme.typography.titleBold)
                        Text(description, style = AppTheme.typography.body2Regular, color = AppTheme.colors.textSecondary)
                    }
                }
            }
        }
    }
    if (vm.trip.phase == TripPhase.PREPARING) Foldout("연결 방식", vm.trip.integrationMode.label, "connection-mode") {
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
            Action("모의 체험", "mode-simulation") { vm.changeMode(IntegrationMode.SIMULATION) }
            Action("Pleos SDK 연결", "mode-sdk", secondary = true) { vm.changeMode(IntegrationMode.SDK) }
        }
    }
}

@Composable private fun TrialSessionTools(vm: ShuttleViewModel) {
    var selected by remember { mutableStateOf<Int?>(null) }
    var history by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    Foldout("체험 시작·공유", "기록 보관 후 새 체험 / 결과 공유", "session-tools") {
        Text("시험 멜로디 사용 · 단계 선택은 가상 사전 설정으로 시작합니다.")
        if (vm.trip.integrationMode == IntegrationMode.SIMULATION) {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                listOf("처음부터", "하차 지점부터", "종료 점검부터").forEachIndexed { index, label ->
                    Action(label, "restart-$index") { selected = index }
                }
            }
            // D34 확인용 시험 명단. 실제 기관 명단이 아님.
            Action("시험 · 탈 어린이 0명 (전원 미탑승 예정)", "restart-3", secondary = true) { selected = 3 }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
            Action("보관 기록", "archived-trips") { history = vm.archiveSummary() }
            Action("결과 공유", "share-results") {
                runCatching {
                    val report = vm.exportDiagnostics().readText()
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Pleos 모의 체험 결과")
                        putExtra(Intent.EXTRA_TEXT, report)
                    }, "테스트 결과 공유"))
                }.onFailure { history = "공유 화면을 열지 못했습니다. 진단 파일은 앱 내부에서 확인할 수 있습니다." }
            }
        }
    }
    selected?.let { stage ->
        AlertDialog(
            onDismissRequest = { selected = null }, containerColor = AppTheme.colors.surfaceRaised, title = { Text("기록 보관 후 새 체험") },
            text = { Text(if (stage == 3) "현재 운행은 그대로 보관합니다. 가상 어린이 4명이 모두 미탑승 예정(결석)인 시험 명단으로 '오늘 운행 없음' 화면을 확인합니다."
                else "현재 운행은 그대로 보관합니다. 새 가상 운행에서 선택한 단계부터 체험합니다.") },
            confirmButton = { DialogButton("보관하고 시작") { selected = null; vm.restartSimulation(stage) } },
            dismissButton = { DialogButton("취소") { selected = null } },
        )
    }
    history?.let { value ->
        AlertDialog(
            onDismissRequest = { history = null }, containerColor = AppTheme.colors.surfaceRaised, title = { Text("체험 기록") },
            text = { Text(value, Modifier.heightIn(max = canvasDp(340)).verticalScroll(rememberScrollState())) },
            confirmButton = { DialogButton("닫기") { history = null } },
        )
    }
}

@Composable internal fun TrialsPanel(vm: ShuttleViewModel) {
    Section("모의 상황 바꾸기", "이 화면의 입력은 실제 차량 제어 어댑터로 전달되지 않습니다") {
        if (vm.trip.integrationMode != IntegrationMode.SIMULATION) Notice("모의 시나리오는 운행 준비에서 모의 체험으로 전환한 뒤 사용할 수 있습니다.")
        TrialScenario.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                row.forEach { scenario ->
                    Action(scenario.label, "scenario-${scenario.name}", secondary = vm.scenario != scenario, enabled = vm.trip.integrationMode == IntegrationMode.SIMULATION) { vm.chooseScenario(scenario) }
                }
            }
        }
        Text("현재 모의 입력: ${vm.scenario.label} · 모의 문 요청 ${vm.simulatedDoorRequests}회")
        Foldout("모의 문 입력", "실제 문 요청 없이 표현만 확인", "simulation-door") {
            Action("모의 문 입력을 잠김으로 설정", "sim-lock", secondary = true, enabled = vm.trip.integrationMode == IntegrationMode.SIMULATION) { vm.resetSimulatedDoor() }
            Action("모의 자동 해제 아이디어 보기", "sim-auto", enabled = vm.trip.integrationMode == IntegrationMode.SIMULATION) { vm.simulateAutomaticIdea() }
            StatusChip(if (vm.autoSimulationUnlocked) "모의 문  [  열림  ]" else "모의 문  [잠김]", tone = if (vm.autoSimulationUnlocked) ChipTone.DANGER else ChipTone.NEUTRAL)
            Notice(vm.autoSimulationMessage)
            Text("‘관측 갱신 중단’ 후 5초가 지나면 기존 값은 오래된 값이 됩니다. 잠깐 멈춤이나 모의 자동 해제 예시는 실제 문 요청을 만들지 않습니다.")
        }
    }
    Foldout("오디오 포커스 시험", "음악과 안내 전환 확인", "audio-focus") {
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            Action("일시 포커스 요청", "focus-transient") { vm.audio.beginFocusProbe(false) }
            Action("감쇄 포커스 요청", "focus-duck") { vm.audio.beginFocusProbe(true) }
            Action("시험 포커스 반환", "focus-end", secondary = true) { vm.audio.endFocusProbe() }
        }
        Text("시험 포커스는 내비 음성 자체가 아닙니다. 실제 순정 내비와의 동시 재생은 별도 시험입니다.")
    }
}

@Composable internal fun DoorLab(vm: ShuttleViewModel, reason: String, onReasonChange: (String) -> Unit) {
    val step = AppTheme.typography.body1SemiBold
    Section("별도 문 잠금 시험", "하차 기록과 독립적인 개발용 시험입니다") {
        Text("1  정차 확인", style = step)
        StationaryStatus(vm)
        Text("2  주변 정보와 직접 확인", style = step)
        Notice("${vm.surroundings.kind.label} · ${vm.surroundings.source.label} · ${timeText(vm.surroundings.observedAtMillis)}\n카메라·접근 물체·SEA·어린이 잠금: 현재 앱 접근 미확인", modifier = Modifier.testTag("surroundings-status").guideAnchor("surroundings-status"))
        Action("운전자 · 주변 직접 확인", "confirm-surroundings") { vm.perform { confirmSurroundings(it) } }
        Text("직접 확인 기록: ${timeText(vm.trip.manualSurroundingsCheckAtMillis)}", style = AppTheme.typography.caption1Regular)
        Text("3  최종 확인 후 일반 문 잠금 해제 시험", style = step)
        if (vm.trip.integrationMode == IntegrationMode.SDK) Notice("해제 시험 대상은 SDK가 반환한 정확한 일반 문 잠금 그룹 전체입니다: ${vm.sdkVehicle.selectedDoorArea ?: "미확인"}\n개별 어린이 잠금이나 SEA 제어가 아닙니다.")
        BlockReasons(vm.engine.unlockBlockReasons(vm.now))
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            Action("문 해제 시험 준비", "prepare-door", enabled = vm.engine.unlockBlockReasons(vm.now).isEmpty() && vm.trip.unlockAttempt == null) { vm.prepareDoor() }
            if (vm.trip.unlockConfirmation != null) Action("운전자 최종 확인 · 1회 요청", "confirm-door") { vm.confirmDoor() }
        }
        vm.trip.unlockAttempt?.let { Notice("문 요청: ${it.status.label}\n요청 전 true → 요청 false → 관측 ${it.observedLocked ?: "미수신"}${if (it.invalidated) " · 확인 무효화됨" else ""}\n문 상태는 하차 기록을 바꾸지 않습니다.", modifier = Modifier.testTag("door-result").guideAnchor("door-result")) }
        GuideReasonField(reason, onReasonChange, "문을 직접 처리한 사유", "reason")
        Action("문 직접 처리 · 수동 대안 기록", "manual-door", secondary = true, enabled = reason.isNotBlank()) { vm.perform { manualDoorHandling(reason, it) } }
        Text("수동 대안은 앱의 실제 문 해제 성공을 뜻하지 않습니다. 시험을 진행할 수 없는 이유와 직접 처리 사실을 별도로 남깁니다.", style = AppTheme.typography.caption1Regular)
    }
}
