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
import com.hanhyo.pleosshuttle.domain.RouteObjective
import com.hanhyo.pleosshuttle.domain.RouteTimeline
import com.hanhyo.pleosshuttle.domain.RouteWarning
import com.hanhyo.pleosshuttle.domain.RouteTimelines
import com.hanhyo.pleosshuttle.ui.component.Action
import com.hanhyo.pleosshuttle.ui.component.ChipTone
import com.hanhyo.pleosshuttle.ui.component.StatusChip

private const val SYNTHETIC_NOTE = "도착 시각은 가상 출발 15:30에 직선거리로 만든 합성 이동 시간과 정차 3분을 더한 예시예요. 실제 도로 시간이 아니에요."

// ---- L2 경로 확인 -------------------------------------------------------------

/** 운행 시작 직전 본문: 방문 순서 띠, 정류장 메모, 순서 바꾸기 · 이 순서로 운행 시작. */
@Composable internal fun RouteCheckBody(vm: ShuttleViewModel, onOpenReorder: () -> Unit) {
    val trip = vm.trip
    val order = displayedRouteOrder(trip)
    val timeline = routeTimeline(trip, order)
    val manual = !trip.routeIsExample && trip.routeObjective == RouteObjective.FIXED
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        StatusChip(if (manual) "직접 바꾼 순서" else if (trip.routeIsExample) "자동 계산한 순서 · 직선거리 기준" else "적용한 순서", tone = if (manual) ChipTone.ACCENT else ChipTone.NEUTRAL)
        Text("복귀 ${clockText(timeline.endMinute)} · ${timeline.totalMinutes}분", style = AppTheme.typography.body2SemiBold, color = AppTheme.colors.textSecondary)
    }
    RouteStrip(vm, order, timeline)
    StopMemos(vm, order)
    Text(if (trip.integrationMode == com.hanhyo.pleosshuttle.domain.IntegrationMode.SIMULATION)
        "출발하면 모의 이동과 보호자 공유가 자동으로 시작돼요. 도착·인계·하차는 직접 확인해 주세요."
        else "출발하면 위치와 내비 예상 도착 시간을 보호자에게 공유해요. 처음에는 위치 권한 허용이 필요해요.",
        style = AppTheme.typography.body2Regular, color = AppTheme.colors.textSecondary)
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
            StripItem("출", "${trip.origin.shortName} 출발", clockText(timeline.startMinute))
            order.forEachIndexed { index, id ->
                StripArrow()
                val stop = trip.stops.first { it.id == id }
                StripItem("${index + 1}", stop.shortName, "${timeline.arrivalOf(id)?.let(::clockText) ?: "-"} · ${ridersAt(trip, id).size}명")
            }
            StripArrow()
            StripItem("복", "복귀", clockText(timeline.endMinute))
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

@Composable private fun StopMemos(vm: ShuttleViewModel, order: List<String>) {
    Column(
        Modifier.fillMaxWidth().background(AppTheme.colors.surfaceRaised, RoundedCornerShape(AppRadius.card)).padding(AppSpacing.xl).testTag("stop-memos"),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        Text("정류장 메모", style = AppTheme.typography.body1SemiBold)
        order.forEachIndexed { index, id ->
            val stop = vm.trip.stops.first { it.id == id }
            Text("${index + 1}  ${stop.shortName} · ${stopMemo(id) ?: "메모 없음"}", style = AppTheme.typography.body3Regular, color = AppTheme.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
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
        Text("하원 출발 · 내 아이 도착 약 2분 전 · 도착 확인 · 하차 완료를 알려요.", style = AppTheme.typography.body3Regular)
        Text("도착 전 알림은 최신 예상 시간이 확인될 때 한 번 전송해요.", style = AppTheme.typography.caption1Regular, color = AppTheme.colors.textTertiary)
        Action(if (showCriteria) "기준 접기" else "순서를 정하는 기준 보기", "route-criteria", secondary = true, action = onToggleCriteria)
        if (showCriteria) Column(
            Modifier.fillMaxWidth().background(AppTheme.colors.surfaceRaised, RoundedCornerShape(AppRadius.card)).padding(AppSpacing.lg).testTag("route-criteria-list"),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            routeCriteria.forEach { Text("• $it", style = AppTheme.typography.body3Regular) }
            Text("순서를 바꿔 기준에 어긋나면 순서 바꾸기 화면에서 경고로 알려줘요.", style = AppTheme.typography.caption1Regular, color = AppTheme.colors.textTertiary)
        }
    }
}

@Composable private fun SideTitle(text: String) {
    Text(text, style = AppTheme.typography.body3SemiBold, color = AppTheme.colors.textSecondary)
}

// ---- L9 순서 바꾸기 -----------------------------------------------------------

/** 순서 바꾸기 초안. 화면을 닫거나 운행 단계가 바뀌면 버린다. */
internal data class ReorderDraft(val base: List<String>, val order: List<String>, val lastMoved: String? = null, val reason: String? = null)

@Composable internal fun ReorderBody(vm: ShuttleViewModel, draft: ReorderDraft, onChange: (ReorderDraft) -> Unit, onClose: () -> Unit) {
    val trip = vm.trip
    val locked = vm.engine.lockedRouteCount()
    val base = routeTimeline(trip, draft.base)
    val timeline = routeTimeline(trip, draft.order)
    val warnings = routeWarnings(timeline)
    val blockers = vm.engine.manualRouteBlockers(vm.now)
    Column(Modifier.fillMaxWidth().testTag("reorder"), verticalArrangement = Arrangement.spacedBy(AppSpacing.lg)) {
        Text(if (locked == 0) "순서 바꾸기" else "남은 순서 바꾸기", style = AppTheme.typography.head3Bold)
        Text(if (locked == 0) "위·아래 버튼으로 한 칸씩 옮겨요. 출발과 복귀는 고정이에요." else "지나온 정류장과 지금 정류장은 고정이에요. 다음 정류장부터 바뀌어요.", style = AppTheme.typography.body3Regular, color = AppTheme.colors.textSecondary)
        LockedRow("출", "${trip.origin.shortName} 출발", clockText(timeline.startMinute))
        draft.order.forEachIndexed { index, id ->
            val stop = trip.stops.first { it.id == id }
            val movable = index >= locked
            ReorderRow(
                number = "${index + 1}",
                name = stop.shortName,
                riders = ridersAt(trip, id).joinToString().ifBlank { "내릴 친구 없음" },
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
        LockedRow("복", "${trip.origin.shortName} 복귀", clockText(timeline.endMinute))
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
                Text(arrival?.let(::clockText) ?: "-", style = AppTheme.typography.titleBold.copy(fontFeatureSettings = "tnum"))
                if (original != null && original != arrival) Text(clockText(original), style = AppTheme.typography.caption1Regular.copy(textDecoration = TextDecoration.LineThrough), color = colors.textTertiary)
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
    val base = routeTimeline(vm.trip, draft.base)
    val timeline = routeTimeline(vm.trip, draft.order)
    val warnings = routeWarnings(timeline)
    Column(modifier.testTag("reorder-side"), verticalArrangement = Arrangement.spacedBy(AppSpacing.lg)) {
        SideTitle("복귀 예정")
        CompareRow(clockText(base.endMinute), clockText(timeline.endMinute), timeline.endMinute - base.endMinute)
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
