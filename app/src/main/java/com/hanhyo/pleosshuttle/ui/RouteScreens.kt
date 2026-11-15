@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.hanhyo.pleosshuttle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.hanhyo.pleosshuttle.ShuttleViewModel
import com.hanhyo.pleosshuttle.designsystem.theme.AppRadius
import com.hanhyo.pleosshuttle.designsystem.theme.AppSize
import com.hanhyo.pleosshuttle.designsystem.theme.AppSpacing
import com.hanhyo.pleosshuttle.designsystem.theme.AppTheme
import com.hanhyo.pleosshuttle.domain.DepartureSummary
import com.hanhyo.pleosshuttle.domain.departureSummary
import com.hanhyo.pleosshuttle.domain.RouteTimeline
import com.hanhyo.pleosshuttle.domain.StopExclusions
import com.hanhyo.pleosshuttle.domain.TripPhase
import com.hanhyo.pleosshuttle.ui.component.Action
import com.hanhyo.pleosshuttle.ui.component.ChipTone
import com.hanhyo.pleosshuttle.ui.component.StatusChip

private val SYNTHETIC_NOTE = "도착 시각은 지금 출발한다고 보고 직선거리로 어림한 이동 시간과 정차 ${ROUTE_DWELL_MINUTES}분을 더한 예상이에요. 실제 도로 시간과 다를 수 있어요."

// ---- L2 경로 확인 -------------------------------------------------------------

/**
 * 운행 시작 직전 본문: 방문 순서 띠, 빈 정류장 제외 제안, 출발 전 확인, 이 순서로 운행 시작.
 * 방문 순서는 기관(서버)이 정한 정류장 순서 그대로다. 차량 앱의 순서 바꾸기는 서버 route-revisions가 차량을 허용할 때까지 뺐다.
 */
@Composable internal fun RouteCheckBody(vm: ShuttleViewModel) {
    val trip = vm.trip
    val order = displayedRouteOrder(trip)
    val timeline = routeTimeline(trip, order)
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        StatusChip(if (trip.routeIsExample) "자동 계산한 순서 · 직선거리 기준" else "기관이 정한 순서", tone = ChipTone.NEUTRAL)
        Text("${koreanClockText(timeline.endMinute)} ${if (trip.isPickup) "기관 도착" else "복귀"} 예정 · ${timeline.totalMinutes}분", style = AppTheme.typography.body2SemiBold, color = AppTheme.colors.textSecondary)
    }
    RouteStrip(vm, order, timeline)
    StopExclusionSuggestions(vm)
    Text("출발하면 차량 위치와 예상 도착 시간을 보호자에게 실시간으로 공유해요",
        style = AppTheme.typography.body3Regular, color = AppTheme.colors.textSecondary)
    // V-08 출발 전 확인 요약 + 기사 확인 체크: [운행 시작] 바로 위(flow VS-06). 출발은 막지 않는다(D15).
    DepartureCheckBlock(vm, departureSummary(trip, order))
    Action("이 순서로 · 운행 시작", "start-trip", prominent = true) { vm.perform { startOperationalTrip(it) } }
}

@Composable private fun RouteStrip(vm: ShuttleViewModel, order: List<String>, timeline: RouteTimeline) {
    val trip = vm.trip
    Column(
        Modifier.fillMaxWidth().background(AppTheme.colors.surfaceRaised, RoundedCornerShape(AppRadius.card)).padding(AppSpacing.xl).testTag("route-strip"),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            StripItem("출", trip.origin.shortName, departureClockText(timeline.startMinute))
            order.forEachIndexed { index, id ->
                StripArrow()
                val stop = trip.stops.first { it.id == id }
                StripItem("${index + 1}", stop.shortName, "${timeline.arrivalOf(id)?.let(::arrivalClockText) ?: "도착 시각 미정"} · ${ridersAt(trip, id).size}명")
            }
            StripArrow()
            // 등원은 마지막 정류장 뒤 기관으로 간다(출발지 복귀가 아님).
            if (trip.isPickup) StripItem("기", "기관", arrivalClockText(timeline.endMinute)) else StripItem("복", "복귀", arrivalClockText(timeline.endMinute))
        }
        Text(SYNTHETIC_NOTE, style = AppTheme.typography.caption1Regular, color = AppTheme.colors.textTertiary)
    }
}

@Composable private fun StripItem(marker: String, name: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        NumberMarker(marker)
        Column {
            Text(name, style = AppTheme.typography.body2SemiBold, maxLines = 1)
            Text(detail, style = AppTheme.typography.caption1Regular, color = AppTheme.colors.textSecondary)
        }
    }
}

@Composable private fun StripArrow() {
    Text("→", style = AppTheme.typography.body1SemiBold, color = AppTheme.colors.outlineMuted, modifier = Modifier.padding(top = AppSpacing.sm))
}

@Composable private fun NumberMarker(marker: String, highlighted: Boolean = false) {
    val semantic = AppTheme.semantic
    val modifier = Modifier.size(AppSize.stopMarker)
    Box(
        if (highlighted) modifier.background(AppTheme.colors.primary, CircleShape) else modifier.border(AppSize.currentOutline, semantic.stopUpcomingOutline, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(marker, style = AppTheme.typography.caption2Bold, color = if (highlighted) AppTheme.colors.onPrimary else AppTheme.colors.textSecondary) }
}

/**
 * V-08 출발 전 확인 블록. 숫자 칩(경고 톤은 [departureChips]), 첫 정류장·경로, 기사 확인 체크, 요약 다시 듣기.
 * 기사 확인은 기사가 음성 요약을 듣고 말로 답하면 선생님이 체크한다(D37). 체크 전이면 경고 줄만 보이고 출발은 막지 않는다.
 */
@Composable private fun DepartureCheckBlock(vm: ShuttleViewModel, summary: DepartureSummary) {
    val type = AppTheme.typography
    val checked = vm.trip.driverCheckedAtMillis != null
    Column(
        Modifier.fillMaxWidth().background(AppTheme.colors.surfaceRaised, RoundedCornerShape(AppRadius.card)).padding(AppSpacing.lg).testTag("departure-summary"),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            Text("출발 전 확인", style = type.body1SemiBold, modifier = Modifier.padding(top = AppSpacing.xs))
            departureChips(summary).forEach { chip ->
                StatusChip(chip.text, tone = if (chip.warn) ChipTone.DANGER else ChipTone.NEUTRAL, modifier = Modifier.testTag(chip.tag))
            }
        }
        Text(departureRouteText(summary), style = type.body2SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("departure-route"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).heightIn(min = AppSize.touchMin)
                    .toggleable(value = checked, role = Role.Checkbox) { vm.perform { toggleDriverCheck(it) } }
                    .testTag("driver-check"),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm), verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Text(if (checked) "기사 확인 ${timeText(vm.trip.driverCheckedAtMillis)} (기사님 답을 듣고 체크함)" else "기사 확인 (기사님께 읽어 드리고 답을 들은 뒤 체크)", style = type.body2SemiBold)
            }
            Action("요약 다시 듣기", "replay-departure-summary", secondary = true) { vm.replayGuidance() }
        }
        if (!checked) Text("기사 확인 필요 · 확인 없이도 출발할 수 있어요(기록에 \"기사 확인 없음\")", style = type.body2SemiBold, color = AppTheme.colors.primaryContent, modifier = Modifier.testTag("driver-check-warning"))
    }
}

/** 경로 확인의 왼쪽 패널(분할에서는 본문 아래): 달라진 점, 보호자 도착 알림, 순서 기준. */
@Composable internal fun RouteCheckSide(vm: ShuttleViewModel, showCriteria: Boolean, onToggleCriteria: () -> Unit, modifier: Modifier = Modifier) {
    val changes = routeChanges(vm.trip)
    Column(modifier.testTag("route-side"), verticalArrangement = Arrangement.spacedBy(AppSpacing.lg)) {
        SideTitle("평소와 달라진 점")
        if (changes.isEmpty()) Text("오늘은 달라진 점이 없어요", style = AppTheme.typography.body3Regular, color = AppTheme.colors.textSecondary)
        changes.forEach { change ->
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(change.kind, tone = ChipTone.ACCENT)
                Text(change.text, style = AppTheme.typography.body3Regular)
            }
        }
        SideTitle("보호자 자동 알림")
        Text(if (vm.trip.isPickup) "등원 출발 · 정류장 도착 · 탑승 완료 · 기관 인계 완료를 알려요."
            else "하원 출발 · 내 아이 도착 약 2분 전 · 도착 확인 · 하차 완료를 알려요.", style = AppTheme.typography.body3Regular)
        Text("도착 전 알림은 최신 예상 시간이 확인될 때 한 번 전송해요.", style = AppTheme.typography.caption1Regular, color = AppTheme.colors.textTertiary)
        Action(if (showCriteria) "안내 접기" else "방문 순서 안내 보기", "route-criteria", secondary = true, action = onToggleCriteria)
        if (showCriteria) Column(
            Modifier.fillMaxWidth().background(AppTheme.colors.surfaceRaised, RoundedCornerShape(AppRadius.card)).padding(AppSpacing.lg).testTag("route-criteria-list"),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            routeCriteriaFor(vm.trip).forEach { Text("• $it", style = AppTheme.typography.body3Regular) }
        }
    }
}

@Composable private fun SideTitle(text: String) {
    Text(text, style = AppTheme.typography.body3SemiBold, color = AppTheme.colors.textSecondary)
}

/**
 * V-09·D17 빈 정류장 제외 제안. 아직 들르지 않은 정류장 중 내릴 어린이가 0명(선생님 미탑승 확인만 남음)이면 한 줄씩
 * [이 정류장 빼기]를 보인다. 기본값은 순서에 남음 — 선생님이 눌러야 빠진다. 뺀 정류장은 [다시 넣기]로 되돌린다.
 * 운행 준비와 정차 중(V-11)에만 보이고, 정차 확인이 안 되면 버튼이 꺼진다. 아무것도 없으면 그리지 않는다.
 */
@Composable internal fun StopExclusionSuggestions(vm: ShuttleViewModel) {
    val trip = vm.trip
    val suggestions = StopExclusions.suggestions(trip)
    val excluded = if (trip.phase == TripPhase.PREPARING || trip.phase == TripPhase.ALIGHTING)
        trip.excludedStops.mapNotNull { id -> trip.stops.find { it.id == id } } else emptyList()
    if (suggestions.isEmpty() && excluded.isEmpty()) return
    val enabled = vm.engine.manualRouteBlockers(vm.now).isEmpty()
    Column(
        Modifier.fillMaxWidth().background(AppTheme.colors.surfaceRaised, RoundedCornerShape(AppRadius.card))
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm).testTag("stop-exclusion"),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
    ) {
        suggestions.forEach { stop ->
            Row(Modifier.fillMaxWidth().testTag("exclusion-suggestion-${stop.id}"), horizontalArrangement = Arrangement.spacedBy(AppSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                StatusChip("제외 제안", tone = ChipTone.ACCENT)
                Text("${stop.shortName} · ${if (trip.isPickup) "탈" else "내릴"} 어린이 없음", Modifier.weight(1f), style = AppTheme.typography.body2SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Action("이 정류장 빼기", "exclude-stop-${stop.id}", secondary = true, enabled = enabled) { vm.perform { excludeStop(stop.id, it) } }
            }
        }
        excluded.forEach { stop ->
            Row(Modifier.fillMaxWidth().testTag("excluded-stop-${stop.id}"), horizontalArrangement = Arrangement.spacedBy(AppSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                StatusChip("뺀 정류장")
                Text("${stop.shortName} · 방문 순서·길 안내에서 빠짐", Modifier.weight(1f), style = AppTheme.typography.body2Regular, color = AppTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Action("다시 넣기", "include-stop-${stop.id}", secondary = true, enabled = enabled) { vm.perform { includeStop(stop.id, it) } }
            }
        }
        if (suggestions.isNotEmpty()) Text("빼지 않으면 순서에 그대로 남아요 · 다시 탈 어린이가 생기면 자동으로 다시 넣어요",
            style = AppTheme.typography.caption1Regular, color = AppTheme.colors.textTertiary)
    }
}
