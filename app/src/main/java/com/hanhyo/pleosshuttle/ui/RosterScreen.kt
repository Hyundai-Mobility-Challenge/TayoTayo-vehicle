package com.hanhyo.pleosshuttle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.hanhyo.pleosshuttle.ShuttleViewModel
import com.hanhyo.pleosshuttle.designsystem.theme.AppSpacing
import com.hanhyo.pleosshuttle.designsystem.theme.AppTheme
import com.hanhyo.pleosshuttle.domain.Attendance
import com.hanhyo.pleosshuttle.domain.TripPhase
import com.hanhyo.pleosshuttle.domain.notBoardingBadge
import com.hanhyo.pleosshuttle.ui.component.Action
import com.hanhyo.pleosshuttle.ui.component.ChipTone
import com.hanhyo.pleosshuttle.ui.component.Foldout
import com.hanhyo.pleosshuttle.ui.component.Section
import com.hanhyo.pleosshuttle.ui.component.StatusChip

@Composable internal fun RosterPanel(vm: ShuttleViewModel) {
    val confirmedNotBoarded = vm.trip.confirmedNotBoardedChildren.size
    Section("오늘의 명단", "${vm.trip.children.size}명 · 차내 기록 ${vm.trip.onboardChildren.size}명 · 하차 ${vm.trip.children.count { it.status == Attendance.ALIGHTED }}명" +
        if (confirmedNotBoarded > 0) " · 미탑승 확인 ${confirmedNotBoarded}명" else "") {
        vm.trip.children.forEachIndexed { index, child ->
            Row(Modifier.fillMaxWidth().padding(vertical = AppSpacing.xs), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xl), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    Text(child.name.removePrefix("가상 "), style = AppTheme.typography.titleBold)
                    Text(vm.trip.stops.first { it.id == child.stopId }.shortName, style = AppTheme.typography.body3Regular, color = AppTheme.colors.textSecondary)
                    // V-02: 결석·직접 픽업 등은 배지로만 표시하고 명단에서 빼지 않는다.
                    notBoardingBadge(child)?.let { StatusChip(it, tone = ChipTone.ACCENT, modifier = Modifier.testTag("roster-badge-${child.id}")) }
                }
                StatusChip(child.status.label, tone = riderTone(child.status))
                NotBoardedActions(child, vm, tagPrefix = "roster-")
                Correction(child, vm, allowAlight = vm.trip.phase == TripPhase.END_REVIEW)
            }
            if (index < vm.trip.children.lastIndex) HorizontalDivider(color = AppTheme.colors.outline)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
        Action(if (vm.trip.phase == TripPhase.ENDED) "완료 기록·새 운행" else "명단·방문 순서 조작", "open-controls", secondary = true) { vm.panel = "controls" }
        Text("진행은 ‘${vm.tripDirectionLabel}으로’에서 이어가세요.", modifier = Modifier.align(Alignment.CenterVertically), color = AppTheme.colors.textSecondary)
    }
    Foldout("방문 순서", "남은 ${vm.trip.remainingStopCount}곳 · 마지막은 출발지", "route-summary") {
        vm.trip.routeOrder.forEachIndexed { index, id ->
            Text("${index + 1}. ${vm.trip.stops.first { it.id == id }.shortName}", style = AppTheme.typography.body1SemiBold)
        }
        Text("출발지 복귀 · 직선거리 기준의 가상 체험 경로", color = AppTheme.colors.textSecondary)
    }
}

@Composable internal fun TripHistory(vm: ShuttleViewModel) {
    Section("${vm.tripDirectionLabel} 기록", "선생님이 확인한 기록입니다") {
        vm.trip.children.forEach { Text("${it.name.removePrefix("가상 ")} · ${it.status.label}", style = AppTheme.typography.body1SemiBold) }
        // V-08: 기사 확인은 시각만 기록. 체크 없이 출발했으면 "기사 확인 없음"(D15).
        if (vm.trip.phase != com.hanhyo.pleosshuttle.domain.TripPhase.PREPARING) Text(
            if (vm.trip.departedWithoutDriverCheck) "기사 확인 없음" else "기사 확인 ${timeText(vm.trip.driverCheckedAtMillis)}",
            modifier = Modifier.testTag("history-driver-check"))
        vm.trip.endRecord?.let { Text("종료 ${timeText(it.recordedAtMillis)} · 실내 직접 확인 ${timeText(it.manualInspectionAtMillis)}") }
        Text("탑승·하차·정정 기록 ${vm.trip.ledger.size}건")
    }
}
