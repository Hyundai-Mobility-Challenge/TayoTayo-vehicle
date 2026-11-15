@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.hanhyo.pleosshuttle.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hanhyo.pleosshuttle.ShuttleViewModel
import com.hanhyo.pleosshuttle.designsystem.theme.AppRadius
import com.hanhyo.pleosshuttle.designsystem.theme.AppSize
import com.hanhyo.pleosshuttle.designsystem.theme.AppSpacing
import com.hanhyo.pleosshuttle.designsystem.theme.AppTheme
import com.hanhyo.pleosshuttle.designsystem.theme.canvasDp
import com.hanhyo.pleosshuttle.domain.DepartureSummary
import com.hanhyo.pleosshuttle.domain.RouteObjective
import com.hanhyo.pleosshuttle.domain.departureSummary
import com.hanhyo.pleosshuttle.domain.RouteTimeline
import com.hanhyo.pleosshuttle.domain.RouteWarning
import com.hanhyo.pleosshuttle.domain.RouteTimelines
import com.hanhyo.pleosshuttle.domain.StopExclusions
import com.hanhyo.pleosshuttle.domain.TripPhase
import com.hanhyo.pleosshuttle.ui.component.Action
import com.hanhyo.pleosshuttle.ui.component.ChipTone
import com.hanhyo.pleosshuttle.ui.component.StatusChip

private val SYNTHETIC_NOTE = "도착 시각은 지금 출발한다고 보고 직선거리로 어림한 이동 시간과 정차 ${ROUTE_DWELL_MINUTES}분을 더한 예상이에요. 실제 도로 시간과 다를 수 있어요."

// ---- L2 경로 확인 -------------------------------------------------------------

/** 운행 시작 직전 본문: 방문 순서 띠, 정류장 메모, 순서 바꾸기 · 이 순서로 운행 시작. */
@Composable internal fun RouteCheckBody(vm: ShuttleViewModel, onOpenReorder: () -> Unit) {
    val trip = vm.trip
    val order = displayedRouteOrder(trip)
    val timeline = routeTimeline(trip, order)
    val manual = !trip.routeIsExample && trip.routeObjective == RouteObjective.FIXED
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        StatusChip(if (manual) "직접 바꾼 순서" else if (trip.routeIsExample) "자동 계산한 순서 · 직선거리 기준" else "적용한 순서", tone = if (manual) ChipTone.ACCENT else ChipTone.NEUTRAL)
        Text("${koreanClockText(timeline.endMinute)} ${if (trip.isPickup) "기관 도착" else "복귀"} 예정 · ${timeline.totalMinutes}분", style = AppTheme.typography.body2SemiBold, color = AppTheme.colors.textSecondary)
    }
    RouteStrip(vm, order, timeline)
    StopExclusionSuggestions(vm)
    Text("출발하면 차량 위치와 예상 도착 시간을 보호자에게 실시간으로 공유해요",
        style = AppTheme.typography.body3Regular, color = AppTheme.colors.textSecondary)
    // V-08 출발 전 확인 요약 + 기사 확인 체크: [운행 시작] 바로 위(flow VS-06). 출발은 막지 않는다(D15).
    DepartureCheckBlock(vm, departureSummary(trip, order))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.md), verticalAlignment = Alignment.CenterVertically) {
        Action("순서 바꾸기", "open-reorder", secondary = true, enabled = vm.engine.manualRouteBlockers(vm.now).isEmpty(), action = onOpenReorder)
        Box(Modifier.weight(1f)) {
            Action("이 순서로 · 운행 시작", "start-trip", prominent = true) { vm.perform { startOperationalTrip(it) } }
        }
    }
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
        Action(if (showCriteria) "기준 접기" else "순서를 정하는 기준 보기", "route-criteria", secondary = true, action = onToggleCriteria)
        if (showCriteria) Column(
            Modifier.fillMaxWidth().background(AppTheme.colors.surfaceRaised, RoundedCornerShape(AppRadius.card)).padding(AppSpacing.lg).testTag("route-criteria-list"),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            routeCriteriaFor(vm.trip).forEach { Text("• $it", style = AppTheme.typography.body3Regular) }
            Text("순서를 바꿔 기준에 어긋나면 순서 바꾸기 화면에서 경고로 알려줘요.", style = AppTheme.typography.caption1Regular, color = AppTheme.colors.textTertiary)
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

// ---- L9 순서 바꾸기 -----------------------------------------------------------

/** 순서 바꾸기 초안. 화면을 닫거나 운행 단계가 바뀌면 버린다. */
internal data class ReorderDraft(val base: List<String>, val order: List<String>, val lastMoved: String? = null, val reason: String? = null)

@Composable internal fun ReorderBody(vm: ShuttleViewModel, draft: ReorderDraft, onChange: (ReorderDraft) -> Unit, onClose: () -> Unit) {
    val trip = vm.trip
    val locked = vm.engine.lockedRouteCount()
    val start = currentClockMinute()
    val base = routeTimeline(trip, draft.base, start)
    val timeline = routeTimeline(trip, draft.order, start)
    val warnings = routeWarnings(timeline)
    val blockers = vm.engine.manualRouteBlockers(vm.now)
    Column(Modifier.fillMaxWidth().testTag("reorder"), verticalArrangement = Arrangement.spacedBy(AppSpacing.lg)) {
        Text(if (locked == 0) "순서 바꾸기" else "남은 순서 바꾸기", style = AppTheme.typography.head3Bold)
        Text(if (locked == 0) "위·아래 버튼으로 한 칸씩 옮겨요. 출발과 ${if (trip.isPickup) "기관 도착" else "복귀"}은 고정이에요." else "지나온 정류장과 지금 정류장은 고정이에요. 다음 정류장부터 바뀌어요.", style = AppTheme.typography.body3Regular, color = AppTheme.colors.textSecondary)
        LockedRow("출", trip.origin.shortName, departureClockText(timeline.startMinute))
        draft.order.forEachIndexed { index, id ->
            val stop = trip.stops.first { it.id == id }
            val movable = index >= locked
            ReorderRow(
                number = "${index + 1}",
                name = stop.shortName,
                riders = ridersAt(trip, id).joinToString().ifBlank { if (trip.isPickup) "탈 친구 없음" else "내릴 친구 없음" },
                arrival = timeline.arrivalOf(id),
                original = base.arrivalOf(id),
                highlighted = draft.lastMoved == id,
                warned = warnings.any { it.stopId == id },
                lockedLabel = when { movable -> null; index == trip.currentStopIndex -> "지금 정류장"; else -> "지나옴" },
                canUp = movable && index - 1 >= locked,
                canDown = movable && index < draft.order.lastIndex,
                tag = id,
                onMove = { delta -> onChange(draft.copy(order = RouteTimelines.move(draft.order, index, delta), lastMoved = id)) },
            )
        }
        if (trip.isPickup) LockedRow("기", "기관", arrivalClockText(timeline.endMinute)) else LockedRow("복", "${trip.origin.shortName} 복귀", arrivalClockText(timeline.endMinute))
        RouteWarnings(warnings)
        if (blockers.isNotEmpty()) Text(blockers.joinToString(" · "), style = AppTheme.typography.body2SemiBold, color = AppTheme.colors.primaryContent)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            Action("취소", "reorder-cancel", secondary = true, action = onClose)
            Action("제안 순서로 되돌리기", "reorder-suggested", secondary = true) {
                onChange(draft.copy(order = suggestedRouteOrder(trip, locked), lastMoved = null))
            }
            Box(Modifier.weight(1f)) {
                Action("이 순서로 정하기", "reorder-apply", prominent = true, enabled = blockers.isEmpty()) {
                    vm.perform { applyManualRoute(draft.order, draft.reason, it) }
                    if (vm.actionError == null) onClose()
                }
            }
        }
    }
}

@Composable private fun LockedRow(marker: String, name: String, time: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NumberMarker(marker)
        Text(name, Modifier.weight(1f), style = AppTheme.typography.body2SemiBold, color = AppTheme.colors.textSecondary)
        Text("$time · 고정", style = AppTheme.typography.body3Regular, color = AppTheme.colors.textTertiary)
    }
}

@Composable private fun ReorderRow(
    number: String, name: String, riders: String, arrival: Int?, original: Int?, highlighted: Boolean, warned: Boolean,
    lockedLabel: String?, canUp: Boolean, canDown: Boolean, tag: String, onMove: (Int) -> Unit,
) {
    val colors = AppTheme.colors
    Surface(
        color = colors.surfaceRaised,
        contentColor = colors.textPrimary,
        shape = RoundedCornerShape(AppRadius.row),
        border = when {
            warned -> BorderStroke(AppSize.currentOutline, colors.danger)
            highlighted -> BorderStroke(AppSize.currentOutline, colors.primaryStroke)
            else -> null
        },
        modifier = Modifier.fillMaxWidth().testTag("reorder-row-$tag"),
    ) {
        Row(Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm), horizontalArrangement = Arrangement.spacedBy(AppSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
            NumberMarker(number, highlighted)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(canvasDp(2))) {
                Text(name, style = AppTheme.typography.body1SemiBold)
                Text(riders, style = AppTheme.typography.body3Regular, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(arrival?.let(::koreanClockText) ?: "-", style = AppTheme.typography.titleBold.copy(fontFeatureSettings = "tnum"))
                Text(if (arrival != null) "도착 예정" else "도착 시각 미정", style = AppTheme.typography.caption1Regular, color = colors.textSecondary)
                if (original != null && original != arrival) Text(koreanClockText(original), style = AppTheme.typography.caption1Regular.copy(textDecoration = TextDecoration.LineThrough), color = colors.textTertiary)
            }
            if (lockedLabel != null) StatusChip(lockedLabel)
            else {
                MoveButton("▲", "move-up-$tag", canUp, "위로") { onMove(-1) }
                MoveButton("▼", "move-down-$tag", canDown, "아래로") { onMove(1) }
            }
        }
    }
}

/** 위·아래 버튼(캔버스 64×52). 차 안 흔들림에서도 정확하도록 끌어서 옮기기 대신 쓴다. */
@Composable private fun MoveButton(symbol: String, tag: String, enabled: Boolean, label: String, onClick: () -> Unit) {
    val colors = AppTheme.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.width(AppSize.touchMin).heightIn(min = canvasDp(52)).semantics { contentDescription = label }.testTag(tag),
        shape = RoundedCornerShape(AppRadius.card),
        border = BorderStroke(1.dp, if (enabled) colors.outlineStrong else colors.outline),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = colors.surfaceRaised, contentColor = colors.textPrimary, disabledContainerColor = colors.disabledContainer, disabledContentColor = colors.disabledContent),
    ) { Text(symbol, style = AppTheme.typography.body1SemiBold) }
}

@Composable private fun RouteWarnings(warnings: List<RouteWarning>) {
    if (warnings.isEmpty()) {
        Text("확인할 점이 없어요", style = AppTheme.typography.body2SemiBold, color = AppTheme.colors.infoContent)
        return
    }
    Column(
        Modifier.fillMaxWidth().background(AppTheme.colors.dangerContainer, RoundedCornerShape(AppRadius.card)).padding(AppSpacing.lg).testTag("route-warnings"),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
    ) {
        warnings.forEach { Text("⚠ ${it.message}", style = AppTheme.typography.body2SemiBold, color = AppTheme.colors.onDangerContainer) }
    }
}

/** 순서 바꾸기의 왼쪽 패널(분할에서는 본문 아래): 복귀 시각·총 소요 비교, 확인할 점 개수, 바꾸는 이유. */
@Composable internal fun ReorderSide(vm: ShuttleViewModel, draft: ReorderDraft, onChange: (ReorderDraft) -> Unit, modifier: Modifier = Modifier) {
    val start = currentClockMinute()
    val base = routeTimeline(vm.trip, draft.base, start)
    val timeline = routeTimeline(vm.trip, draft.order, start)
    val warnings = routeWarnings(timeline)
    Column(modifier.testTag("reorder-side"), verticalArrangement = Arrangement.spacedBy(AppSpacing.lg)) {
        SideTitle(if (vm.trip.isPickup) "기관 도착 예정" else "복귀 예정")
        CompareRow(koreanClockText(base.endMinute), koreanClockText(timeline.endMinute), timeline.endMinute - base.endMinute)
        SideTitle("총 소요")
        CompareRow("${base.totalMinutes}분", "${timeline.totalMinutes}분", timeline.totalMinutes - base.totalMinutes)
        StatusChip(if (warnings.isEmpty()) "확인할 점 없음" else "확인할 점 ${warnings.size}개", tone = if (warnings.isEmpty()) ChipTone.INFO else ChipTone.DANGER)
        SideTitle("바꾸는 이유 (선택)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            reorderReasons.forEach { reason ->
                FilterChip(
                    selected = draft.reason == reason,
                    onClick = { onChange(draft.copy(reason = if (draft.reason == reason) null else reason)) },
                    label = { Text(reason, style = AppTheme.typography.body3SemiBold) },
                    modifier = Modifier.heightIn(min = AppSize.chipHeight).testTag("reorder-reason-$reason"),
                    shape = RoundedCornerShape(AppRadius.full),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = AppTheme.colors.surfaceRaised, labelColor = AppTheme.colors.textSecondary,
                        selectedContainerColor = AppTheme.colors.primaryContainer, selectedLabelColor = AppTheme.colors.primaryContent,
                    ),
                )
            }
        }
        Text(SYNTHETIC_NOTE, style = AppTheme.typography.caption1Regular, color = AppTheme.colors.textTertiary)
    }
}

@Composable private fun CompareRow(before: String, after: String, diff: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Text(before, style = AppTheme.typography.body2Regular, color = AppTheme.colors.textTertiary)
        Text("→", style = AppTheme.typography.body2Regular, color = AppTheme.colors.outlineMuted)
        Text(after, style = AppTheme.typography.titleBold)
        StatusChip(minuteDiffText(diff), tone = when { diff > 0 -> ChipTone.DANGER; diff < 0 -> ChipTone.INFO; else -> ChipTone.NEUTRAL })
    }
}
