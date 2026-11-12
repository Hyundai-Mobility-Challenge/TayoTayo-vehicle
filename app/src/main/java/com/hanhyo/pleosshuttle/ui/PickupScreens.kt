@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.hanhyo.pleosshuttle.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.hanhyo.pleosshuttle.PICKUP_PREPARE_TAG
import com.hanhyo.pleosshuttle.PICKUP_STOP_TAG
import com.hanhyo.pleosshuttle.ShuttleViewModel
import com.hanhyo.pleosshuttle.designsystem.theme.AppRadius
import com.hanhyo.pleosshuttle.designsystem.theme.AppSize
import com.hanhyo.pleosshuttle.designsystem.theme.AppSpacing
import com.hanhyo.pleosshuttle.designsystem.theme.AppTheme
import com.hanhyo.pleosshuttle.domain.Attendance
import com.hanhyo.pleosshuttle.domain.ChildRecord
import com.hanhyo.pleosshuttle.domain.PickupFlow
import com.hanhyo.pleosshuttle.domain.PickupFlow.StopAction
import com.hanhyo.pleosshuttle.domain.PickupReceivers
import com.hanhyo.pleosshuttle.ui.component.Action
import com.hanhyo.pleosshuttle.ui.component.ChipTone
import com.hanhyo.pleosshuttle.ui.component.DialogButton
import com.hanhyo.pleosshuttle.ui.component.StatusChip
import com.hanhyo.pleosshuttle.ui.component.guideAnchor

/*
 * 등원(V-25~V-29) 흐름 카드 본문. 엔진 호출만 하고 규칙은 PickupFlow(순수)와 ShuttleEngine이 정한다.
 * - VS-17 등원 운행 준비: PickupPrepareBody
 * - VS-18 정류장 인계받음·탑승: PickupStopBody (+ VS-04 (b) 미탑승 확인 시트, VS-14 정류장 모드 정정)
 * - VS-20 최소 구현: PickupEndStep(처리 안 된 아이 미탑승 확인, 아이마다 기관 인계)
 * VS-19 호출 대기 화면·등원 음성은 2부(호출은 기록과 "호출함 HH:mm" 표시까지만).
 */

private fun shortName(child: ChildRecord) = child.name.removePrefix("가상 ")

/** VS-17: 명단은 탑승 명단 보드(왼쪽 [명단] 탭 또는 아래)에 있고, 여기서는 요약·안내와 [준비 마침]만 둔다. */
@Composable internal fun PickupPrepareBody(vm: ShuttleViewModel, onPrepared: () -> Unit) {
    val trip = vm.trip
    val type = AppTheme.typography
    val riders = trip.children.count { it.ridesToday }
    val notice = trip.children.count { !it.expectedToBoard && it.status == Attendance.NOT_BOARDED }
    val confirmed = trip.confirmedNotBoardedChildren.size
    Text("탈 어린이 ${riders}명" + (if (notice > 0) " · 미탑승 예정 ${notice}명" else "") + (if (confirmed > 0) " · 미탑승 확인 ${confirmed}명" else ""),
        style = type.titleBold.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), modifier = Modifier.testTag("pickup-prepare-summary"))
    Text("모든 어린이가 \"탑승 전\"이에요. 탑승은 정류장에서 인계받은 뒤 기록해요.", style = type.body2Regular, color = AppTheme.colors.textSecondary)
    Text("미탑승 예정 알림이 있는 아이만 지금 [미탑승 확인]할 수 있어요 · $PICKUP_PREPARE_NOTE", style = type.body2Regular, color = AppTheme.colors.textSecondary,
        modifier = Modifier.testTag("pickup-prepare-note"))
    Action("준비 마침 · 순서 확인", PICKUP_PREPARE_TAG, prominent = true, enabled = vm.engine.stationaryBlockers(vm.now).isEmpty(), action = onPrepared)
}

/** VS-18 정류장 인계받음·탑승. 아무 행이나 먼저 처리할 수 있다. */
@Composable internal fun PickupStopBody(vm: ShuttleViewModel, remainingReorder: @Composable () -> Unit) {
    val trip = vm.trip
    val type = AppTheme.typography
    val stop = trip.currentStop ?: return
    val stationary = vm.engine.stationaryBlockers(vm.now).isEmpty()
    // 아이별로 고른 "넘겨준 사람 유형"(인계받음·정정 공용). 정류장이 바뀌면 버린다. 이름은 받지 않는다.
    var chosen by remember(trip.tripId, trip.currentStopIndex) { mutableStateOf(mapOf<String, String>()) }
    var correcting by remember(trip.tripId, trip.currentStopIndex) { mutableStateOf(setOf<String>()) }
    var sheetChildId by remember(trip.tripId, trip.currentStopIndex) { mutableStateOf<String?>(null) }
    val earlier = PickupFlow.earlierUnresolvedChildren(trip)
    if (earlier.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().testTag("pickup-earlier"), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            Text("앞 정류장에서 확인하지 못한 어린이 ${earlier.size}명", style = type.body2SemiBold, color = AppTheme.colors.primaryContent)
            earlier.forEach { child ->
                RowCard(highlight = true, tag = "pickup-earlier-${child.id}") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(shortName(child), style = type.titleBold)
                            Text("${trip.stops.find { it.id == child.stopId }?.shortName ?: "정류장 없음"} · 탑승 전", style = type.body2Regular, color = AppTheme.colors.textSecondary)
                        }
                        Action("미탑승 확인", "pickup-not-boarded-${child.id}", secondary = true, enabled = stationary) { sheetChildId = child.id }
                    }
                }
            }
        }
    }
    val here = trip.currentStopChildren
    Text("${stop.shortName} · 탈 어린이 ${here.count { it.status != Attendance.CONFIRMED_NOT_BOARDED }}명",
        style = type.titleBold, modifier = Modifier.testTag("pickup-stop-header").guideAnchor(PICKUP_STOP_TAG))
    Text("넘겨준 사람을 고르고 [인계받음] → [탑승] → [벨트 확인] 순서로 기록해요. 이름은 받지 않아요.", style = type.body2Regular, color = AppTheme.colors.textSecondary)
    if (here.isEmpty()) Text("이 정류장에 탈 어린이가 없어요.", style = type.body2Regular, color = AppTheme.colors.textSecondary)
    here.forEach { child ->
        val actions = PickupFlow.stopActions(trip, child)
        RowCard(highlight = child.status == Attendance.NOT_BOARDED, tag = "pickup-row-${child.id}") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    Text(shortName(child), style = type.titleBold)
                    val details = listOfNotNull(
                        child.receivedFromType?.let { "인계: ${PickupReceivers.label(it)}" },
                        child.guardianCalledAtMillis?.let { "호출함 ${hourMinuteText(it)}" },
                        if (child.status == Attendance.ONBOARD) (if (child.beltConfirmed) "벨트 확인" else "벨트 미확인") else null,
                    )
                    if (details.isNotEmpty()) Text(details.joinToString(" · "), style = type.body2Regular, color = AppTheme.colors.textSecondary, modifier = Modifier.testTag("pickup-detail-${child.id}"))
                    PickupFlow.notBoardingBadge(child)?.let { StatusChip(it, tone = ChipTone.ACCENT) }
                }
                StatusChip(PickupFlow.statusLabel(trip, child), tone = riderTone(child), modifier = Modifier.testTag("pickup-status-${child.id}"))
            }
            val choosingType = StopAction.RECEIVE in actions || child.id in correcting
            if (choosingType) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    Text("넘겨준 사람", style = type.body2SemiBold, modifier = Modifier.padding(top = AppSpacing.md))
                    PickupReceivers.ALL.forEach { code ->
                        ReceiverChip(PickupReceivers.label(code), selected = chosen[child.id] == code, tag = "receiver-$code-${child.id}") {
                            chosen = chosen + (child.id to code)
                        }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                val type0 = chosen[child.id]
                actions.forEach { action ->
                    when (action) {
                        StopAction.RECEIVE -> Action("인계받음", "receive-${child.id}", enabled = stationary && PickupFlow.receiverChosen(type0)) {
                            type0?.let { code -> vm.perform { recordReceived(child.id, code, it) } }
                        }
                        StopAction.BOARD -> Action("탑승", "pickup-board-${child.id}", enabled = stationary) { vm.perform { board(child.id, it) } }
                        StopAction.BELT -> Action("벨트 확인", "pickup-belt-${child.id}", enabled = stationary) { vm.perform { confirmBelt(child.id, it) } }
                        StopAction.CALL_GUARDIAN -> Action("보호자에게 도착 알림", "call-guardian-${child.id}", secondary = true, enabled = stationary) {
                            vm.perform { callGuardian(child.id, it) }
                        }
                        StopAction.CONFIRM_NOT_BOARDED -> Action("미탑승 확인", "pickup-not-boarded-${child.id}", secondary = true, enabled = stationary) { sheetChildId = child.id }
                        StopAction.CORRECT_TO_BOARDED -> if (child.id !in correcting) {
                            Action("탑승으로 정정", "pickup-correct-${child.id}", secondary = true, enabled = stationary) { correcting = correcting + child.id }
                        } else {
                            // VS-14 정류장 모드: 넘겨준 사람 유형과 함께 인계받음 + 탑승을 한 번에 기록. 뺀 정류장은 엔진이 자동 재포함(V-09).
                            Action("인계받음 · 탑승으로 정정", "pickup-correct-confirm-${child.id}", enabled = stationary && PickupFlow.receiverChosen(type0)) {
                                type0?.let { code -> vm.perform { correctNotBoardedToBoarded(child.id, code, it) } }
                                correcting = correcting - child.id
                            }
                            Action("취소", "pickup-correct-cancel-${child.id}", secondary = true) { correcting = correcting - child.id }
                        }
                    }
                }
            }
        }
    }
    val beltUnchecked = trip.beltUncheckedCount
    if (beltUnchecked > 0) Text("벨트 미확인 ${beltUnchecked}명 · 출발 전에 직접 확인해 주세요", style = type.body2SemiBold, color = AppTheme.colors.dangerContent,
        modifier = Modifier.testTag("pickup-belt-warning"))
    PickupFlow.leaveStopBlocker(trip)?.let { Text(it, style = type.body2SemiBold, color = AppTheme.colors.primaryContent, modifier = Modifier.testTag("pickup-leave-blocker")) }
    Action(PickupFlow.leaveStopLabel(trip), "pickup-leave-stop", prominent = true, enabled = stationary && PickupFlow.canLeaveStop(trip)) {
        vm.perform { completeCurrentStop(it) }
    }
    remainingReorder()
    sheetChildId?.let { id -> trip.children.find { it.id == id } }?.let { child ->
        PickupNotBoardedDialog(vm, child) { sheetChildId = null }
    }
}

/** VS-20 최소 구현(2부에서 화면 확장): 처리 안 된 아이 미탑승 확인, 탄 아이마다 기관 담당자 인계 후 하차 기록. */
@Composable internal fun PickupEndStep(vm: ShuttleViewModel, child: ChildRecord, tag: String) {
    val type = AppTheme.typography
    var sheet by remember(tag) { mutableStateOf(false) }
    if (tag.startsWith("end-not-boarded-")) {
        Text("탑승도 미탑승 확인도 없어요 · 오늘 안 탔나요?", style = type.titleBold)
        Text("${vm.trip.stops.find { it.id == child.stopId }?.shortName ?: "정류장 없음"} · 탑승 전", style = type.body2Regular, color = AppTheme.colors.textSecondary)
        Action("미탑승 확인", tag, prominent = true) { sheet = true }
        if (sheet) PickupNotBoardedDialog(vm, child) { sheet = false }
    } else {
        Text("기관 담당자에게 직접 인계했나요?", style = type.titleBold)
        Text("아이마다 따로 인계를 확인해요.", style = type.body2Regular, color = AppTheme.colors.textSecondary)
        Action("기관 인계 확인 · 하차 기록", tag, prominent = true) { vm.perform { handoverToInstitution(child.id, it) } }
    }
}

/**
 * VS-04 (b) 정류장 미탑승 확인 시트. "보호자에게 미탑승 안내 보내기"(D30: NOTIFY / SILENT). 기관 웹에는 자동 기록(D31, 별도 UI 없음).
 * 도착 알림을 보내지 않았으면 경고 줄만 보이고 막지 않는다(V-28).
 */
@Composable internal fun PickupNotBoardedDialog(vm: ShuttleViewModel, child: ChildRecord, onClose: () -> Unit) {
    val sheet = PickupFlow.notBoardedSheet(vm.trip, child)
    var notify by remember(child.id) { mutableStateOf(false) }
    val type = AppTheme.typography
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = AppTheme.colors.surfaceRaised,
        title = { Text("${shortName(child)} · 미탑승 확인") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm), modifier = Modifier.testTag("pickup-not-boarded-sheet")) {
                Text(sheet.evidence, style = type.body2SemiBold)
                sheet.callNote?.let { Text("$it ${child.guardianCalledAtMillis?.let(::hourMinuteText) ?: ""}".trim(), style = type.body2Regular) }
                if (sheet.suggestGuardianCall) Text("보호자에게 도착 알림을 먼저 보낼까요?", style = type.body2SemiBold, color = AppTheme.colors.primaryContent,
                    modifier = Modifier.testTag("pickup-call-suggestion"))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = AppSize.touchMin)
                        .toggleable(value = notify, role = Role.Checkbox) { notify = it }
                        .testTag("pickup-notify-guardian"),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = notify, onCheckedChange = null)
                    Text("보호자에게 미탑승 안내 보내기", style = type.body2SemiBold)
                }
                Text("미탑승 확인은 기관에 자동으로 기록돼요.", style = type.caption1Regular, color = AppTheme.colors.textSecondary)
            }
        },
        confirmButton = {
            DialogButton("미탑승 확인") {
                onClose()
                vm.perform { confirmNotBoardedAtStop(child.id, notify, it) }
            }
        },
        dismissButton = { DialogButton("돌아가기", onClose) },
    )
}

@Composable private fun RowCard(highlight: Boolean, tag: String, content: @Composable () -> Unit) {
    val colors = AppTheme.colors
    Surface(
        color = if (highlight) colors.primaryContainer else colors.surfaceRaised, contentColor = colors.textPrimary,
        shape = RoundedCornerShape(AppRadius.row),
        border = if (highlight) BorderStroke(AppSize.currentOutline, colors.primaryStroke) else null,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    ) {
        Column(Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) { content() }
    }
}

@Composable private fun ReceiverChip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = AppTheme.typography.body2SemiBold) },
        modifier = Modifier.heightIn(min = AppSize.touchMin).testTag(tag),
        shape = RoundedCornerShape(AppRadius.full),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = AppTheme.colors.surface, labelColor = AppTheme.colors.textSecondary,
            selectedContainerColor = AppTheme.colors.infoContainer, selectedLabelColor = AppTheme.colors.infoContent,
        ),
    )
}
