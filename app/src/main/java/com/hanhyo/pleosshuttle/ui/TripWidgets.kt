package com.hanhyo.pleosshuttle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.hanhyo.pleosshuttle.ShuttleViewModel
import com.hanhyo.pleosshuttle.designsystem.theme.AppSpacing
import com.hanhyo.pleosshuttle.designsystem.theme.AppTheme
import com.hanhyo.pleosshuttle.domain.Attendance
import com.hanhyo.pleosshuttle.domain.ChildRecord
import com.hanhyo.pleosshuttle.domain.TripPhase
import com.hanhyo.pleosshuttle.domain.notBoardingBadge
import com.hanhyo.pleosshuttle.ui.component.Action
import com.hanhyo.pleosshuttle.ui.component.BlockReasons
import com.hanhyo.pleosshuttle.ui.component.ChildCard
import com.hanhyo.pleosshuttle.ui.component.DialogButton
import com.hanhyo.pleosshuttle.ui.component.appTextFieldColors
import com.hanhyo.pleosshuttle.ui.component.guideAnchor

/** 아이 한 명의 기록 행(이름 · 상태 / 하차 지점). 좌석 배정은 삭제됨(D39). */
@Composable internal fun ChildCard(child: ChildRecord, vm: ShuttleViewModel, actions: @Composable RowScope.() -> Unit) {
    ChildCard(
        title = "${child.name}  ·  ${child.status.label}",
        detail = vm.trip.stops.first { it.id == child.stopId }.name,
        badge = notBoardingBadge(child),
        actions = actions,
    )
}

/**
 * V-02 미탑승 확인 / 취소. 운행 준비(탑승 단계)에서만 보이고 board()와 같은 정차 조건(V-11)에서만 누를 수 있다.
 * 주행 중에는 조회 전용(비활성).
 */
@Composable internal fun NotBoardedActions(child: ChildRecord, vm: ShuttleViewModel, tagPrefix: String = "") {
    if (vm.trip.phase != TripPhase.PREPARING) return
    val stationary = vm.engine.stationaryBlockers(vm.now).isEmpty()
    when (child.status) {
        Attendance.NOT_BOARDED -> Action("미탑승 확인", "${tagPrefix}not-boarded-${child.id}", secondary = true, enabled = stationary && !vm.trip.noTripToday) { vm.perform { confirmNotBoarded(child.id, it) } }
        Attendance.CONFIRMED_NOT_BOARDED -> Action("미탑승 확인 취소", "${tagPrefix}undo-not-boarded-${child.id}", secondary = true, enabled = stationary) { vm.perform { undoNotBoarded(child.id, it) } }
        else -> Unit
    }
}

/** 기록 정정. 사유 없이는 바꿀 수 없고, 변경 전 기록과 사유가 이력에 남는다. */
@Composable internal fun Correction(child: ChildRecord, vm: ShuttleViewModel, allowAlight: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    Action("정정", "correct-${child.id}", secondary = true, enabled = vm.trip.phase !in setOf(TripPhase.DRIVING, TripPhase.ENDED) && vm.engine.stationaryBlockers(vm.now).isEmpty()) { open = true }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        containerColor = AppTheme.colors.surfaceRaised,
        title = { Text("${child.name} 기록 정정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text("정정 사유") }, colors = appTextFieldColors(), modifier = Modifier.testTag("correction-reason"))
                Text("변경 전 기록과 사유가 이력에 남습니다.")
                // 미탑승 확인은 정정이 아니라 탑승 단계의 [미탑승 확인]으로만 기록한다(엔진도 거부).
                Attendance.entries.filter { (it != Attendance.ALIGHTED || allowAlight) && it != Attendance.CONFIRMED_NOT_BOARDED }.forEach { target ->
                    Action(target.label, "correct-to-${target.name}", enabled = reason.isNotBlank()) { vm.perform { correct(child.id, target, reason, it) }; open = false }
                }
            }
        },
        confirmButton = { DialogButton("닫기") { open = false } },
    )
}

@Composable internal fun StationaryStatus(vm: ShuttleViewModel) {
    Column(Modifier.fillMaxWidth().testTag("stationary-status").guideAnchor("stationary-status")) {
        BlockReasons(vm.engine.stationaryBlockers(vm.now), "최신 정차 · P 기어 확인")
    }
}
