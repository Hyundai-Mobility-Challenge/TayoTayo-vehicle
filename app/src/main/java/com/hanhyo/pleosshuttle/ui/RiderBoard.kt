package com.hanhyo.pleosshuttle.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.hanhyo.pleosshuttle.ShuttleViewModel
import com.hanhyo.pleosshuttle.designsystem.theme.AppRadius
import com.hanhyo.pleosshuttle.designsystem.theme.AppSize
import com.hanhyo.pleosshuttle.designsystem.theme.AppSpacing
import com.hanhyo.pleosshuttle.designsystem.theme.AppTheme
import com.hanhyo.pleosshuttle.domain.Attendance
import com.hanhyo.pleosshuttle.domain.ChildRecord
import com.hanhyo.pleosshuttle.domain.IntegrationMode
import com.hanhyo.pleosshuttle.domain.TripPhase
import com.hanhyo.pleosshuttle.domain.TripState
import com.hanhyo.pleosshuttle.domain.notBoardingBadge
import com.hanhyo.pleosshuttle.ui.component.Action
import com.hanhyo.pleosshuttle.ui.component.ChipTone
import com.hanhyo.pleosshuttle.ui.component.LocalCompactLayout
import com.hanhyo.pleosshuttle.ui.component.StatusChip

/*
 * V-03 탑승 명단 보드(v1.3, D39: 좌석 배치도 없음). 아이 행 목록(이름·상태·[벨트 자리])을 운행 화면 본문에 늘 보여준다.
 * 화면에 그릴 내용은 아래 순수 함수 riderBoard()가 TripState만 읽어 정한다(저장 필드 추가 없음, 직렬화 형식 그대로).
 */

/** 행에서 누를 수 있는 기록. 엔진 호출은 흐름 카드와 같은 board / confirmNotBoarded / undoNotBoarded(V-04, V-02). */
internal enum class RiderAction(val label: String) {
    BOARD("탑승 기록"),
    CONFIRM_NOT_BOARDED("미탑승 확인"),
    UNDO_NOT_BOARDED("미탑승 확인 취소"),
}

/** 행 강조. CURRENT_STOP = 지금(주행 중엔 다음) 정류장에서 내릴 아이(V-20), REMAINING = 종료 점검 때 차 안 기록이 남은 아이(V-22). */
internal enum class RiderHighlight { NONE, CURRENT_STOP, REMAINING }

internal data class RiderRow(
    val id: String,
    val name: String,
    val status: Attendance,
    val statusLabel: String,
    val tone: ChipTone,
    /** 오늘 미탑승 예정 배지(V-02). 운행 중(이름+상태만)에는 null. */
    val badge: String?,
    /** 정류장 짧은 이름. 운행 중(이름+상태만)에는 null. */
    val stopName: String?,
    val highlight: RiderHighlight,
    val actions: List<RiderAction>,
    /** V-05 벨트 확인 자리. 다음 기능에서 채운다. 지금은 항상 null(표시 안 함). */
    val belt: String? = null,
)

internal data class RiderBoardModel(
    val rows: List<RiderRow>,
    /** true면 행 버튼을 하나도 그리지 않는다(주행 중·조작 제한·정차 미확인·운행 준비가 아닌 단계). */
    val readOnly: Boolean,
    /** true면 "운행 중엔 이름과 상태만"(V-03·V-11·V-13): 배지·정류장 이름을 숨긴다. */
    val namesAndStatusOnly: Boolean,
    val summary: String,
    /** 단계별 한 줄 안내(예: 종료 점검의 잔류 아이 수). 없으면 null. */
    val note: String?,
)

/** 보드 상태 문구. NOT_BOARDED는 enum 라벨("탑승 전")을 그대로 쓰고, 하차는 V-20 "하차 완료"로 보인다. */
internal fun riderStatusLabel(status: Attendance): String = when (status) {
    Attendance.NOT_BOARDED -> status.label
    Attendance.ONBOARD -> "탑승"
    Attendance.ALIGHTED -> "하차 완료"
    Attendance.CONFIRMED_NOT_BOARDED -> status.label
}

/** 상태 색(V-03: 탑승 전 / 탑승 / 하차 / 오늘 미탑승). 색만으로 구분하지 않도록 칩에는 항상 글자를 함께 쓴다. */
internal fun riderTone(status: Attendance): ChipTone = when (status) {
    Attendance.NOT_BOARDED -> ChipTone.NEUTRAL
    Attendance.ONBOARD -> ChipTone.INFO
    Attendance.ALIGHTED -> ChipTone.MUTED
    Attendance.CONFIRMED_NOT_BOARDED -> ChipTone.ACCENT
}

/**
 * 보드 모델. [visitOrder]는 화면에 보이는 방문 순서(운행 준비 중엔 제안 순서), [stationary]는 정차·P 확인(V-11),
 * [uxRestricted]는 차량 UX 제한. 행 순서는 방문 순서 → 이름. 방문 순서에 없는 정류장의 아이는 맨 뒤.
 */
internal fun riderBoard(
    trip: TripState,
    visitOrder: List<String> = trip.routeOrder,
    stationary: Boolean,
    uxRestricted: Boolean,
): RiderBoardModel {
    val moving = trip.phase == TripPhase.DRIVING || uxRestricted
    val readOnly = moving || !stationary || trip.phase != TripPhase.PREPARING
    val stopRank: (String) -> Int = { id ->
        visitOrder.indexOf(id).takeIf { it >= 0 } ?: (visitOrder.size + trip.stops.indexOfFirst { it.id == id }.let { if (it < 0) trip.stops.size else it })
    }
    val focusStopId = if (trip.phase == TripPhase.DRIVING || trip.phase == TripPhase.ALIGHTING) trip.currentStop?.id else null
    val rows = trip.children
        .sortedWith(compareBy<ChildRecord>({ stopRank(it.stopId) }, { displayName(it) }))
        .map { child ->
            RiderRow(
                id = child.id,
                name = displayName(child),
                status = child.status,
                statusLabel = riderStatusLabel(child.status),
                tone = riderTone(child.status),
                badge = if (moving) null else notBoardingBadge(child),
                stopName = if (moving) null else trip.stops.find { it.id == child.stopId }?.shortName,
                highlight = when {
                    trip.phase == TripPhase.END_REVIEW && child.status == Attendance.ONBOARD -> RiderHighlight.REMAINING
                    focusStopId != null && child.stopId == focusStopId &&
                        (child.status == Attendance.ONBOARD || child.status == Attendance.ALIGHTED) -> RiderHighlight.CURRENT_STOP
                    else -> RiderHighlight.NONE
                },
                actions = if (readOnly) emptyList() else rowActions(trip, child),
            )
        }
    val count = { s: Attendance -> trip.children.count { it.status == s } }
    val summary = buildList {
        add("${trip.children.size}명")
        add("탑승 ${count(Attendance.ONBOARD)}")
        if (trip.phase != TripPhase.PREPARING || count(Attendance.ALIGHTED) > 0) add("하차 완료 ${count(Attendance.ALIGHTED)}")
        if (count(Attendance.NOT_BOARDED) > 0) add("탑승 전 ${count(Attendance.NOT_BOARDED)}")
        if (count(Attendance.CONFIRMED_NOT_BOARDED) > 0) add("미탑승 확인 ${count(Attendance.CONFIRMED_NOT_BOARDED)}")
    }.joinToString(" · ")
    val remaining = rows.count { it.highlight == RiderHighlight.REMAINING }
    val note = when {
        moving -> "운행 중에는 조회만 할 수 있어요"
        trip.phase == TripPhase.END_REVIEW -> if (remaining > 0) "차 안에 남은 기록 ${remaining}명 · 직접 확인해 주세요" else "차 안에 남은 기록 없음 · 실내는 직접 확인해 주세요"
        trip.phase == TripPhase.PREPARING && !stationary -> "정차 후 기록할 수 있어요"
        else -> null
    }
    return RiderBoardModel(rows, readOnly, moving, summary, note)
}

/** 흐름 카드와 같은 규칙(feature 3): 미탑승 예정 아이는 [미탑승 확인]이 먼저. "오늘 운행 없음"이면 탑승·미탑승 확인 없음(엔진도 거부). */
private fun rowActions(trip: TripState, child: ChildRecord): List<RiderAction> = when (child.status) {
    Attendance.NOT_BOARDED -> when {
        trip.noTripToday -> emptyList()
        child.expectedToBoard -> listOf(RiderAction.BOARD, RiderAction.CONFIRM_NOT_BOARDED)
        else -> listOf(RiderAction.CONFIRM_NOT_BOARDED, RiderAction.BOARD)
    }
    Attendance.CONFIRMED_NOT_BOARDED -> listOf(RiderAction.UNDO_NOT_BOARDED)
    else -> emptyList()
}

private fun displayName(child: ChildRecord) = child.name.removePrefix("가상 ")

internal fun riderActionTag(action: RiderAction, childId: String): String = when (action) {
    RiderAction.BOARD -> "rider-board-$childId"
    RiderAction.CONFIRM_NOT_BOARDED -> "rider-not-boarded-$childId"
    RiderAction.UNDO_NOT_BOARDED -> "rider-undo-not-boarded-$childId"
}

/** 운행 화면 본문 아래의 탑승 명단 보드. 다이얼로그 없이 행 버튼만 둔다. */
@Composable internal fun RiderBoardSection(vm: ShuttleViewModel, modifier: Modifier = Modifier) {
    val trip = vm.trip
    if (trip.children.isEmpty()) return
    val model = riderBoard(
        trip = trip,
        visitOrder = displayedRouteOrder(trip),
        stationary = vm.engine.stationaryBlockers(vm.now).isEmpty(),
        uxRestricted = trip.integrationMode == IntegrationMode.SDK && vm.sdkVehicle.uxRestricted.value == true,
    )
    val type = AppTheme.typography
    Surface(
        color = AppTheme.colors.surfaceRaised, contentColor = AppTheme.colors.textPrimary,
        shape = RoundedCornerShape(AppRadius.card), modifier = modifier.fillMaxWidth().testTag("rider-board"),
    ) {
        Column(Modifier.padding(AppSpacing.xl), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            Text("탑승 명단", style = type.titleBold)
            Text(model.summary, style = type.body2SemiBold, color = AppTheme.colors.textSecondary, modifier = Modifier.testTag("rider-board-summary"))
            model.note?.let {
                Text(it, style = type.body2SemiBold, modifier = Modifier.testTag("rider-board-note"),
                    color = if (model.rows.any { r -> r.highlight == RiderHighlight.REMAINING }) AppTheme.colors.dangerContent else AppTheme.colors.textSecondary)
            }
            model.rows.forEach { row -> RiderRowView(row, vm) }
        }
    }
}

@Composable private fun RiderRowView(row: RiderRow, vm: ShuttleViewModel) {
    val colors = AppTheme.colors
    val (background, border) = when (row.highlight) {
        RiderHighlight.NONE -> colors.surface to null
        RiderHighlight.CURRENT_STOP -> colors.primaryContainer to BorderStroke(AppSize.currentOutline, colors.primaryStroke)
        RiderHighlight.REMAINING -> colors.dangerContainer to BorderStroke(AppSize.currentOutline, colors.danger)
    }
    val compact = LocalCompactLayout.current
    val actions = @Composable {
        row.actions.forEach { action ->
            Action(action.label, riderActionTag(action, row.id), secondary = true) {
                when (action) {
                    RiderAction.BOARD -> vm.perform { board(row.id, it) }
                    RiderAction.CONFIRM_NOT_BOARDED -> vm.perform { confirmNotBoarded(row.id, it) }
                    RiderAction.UNDO_NOT_BOARDED -> vm.perform { undoNotBoarded(row.id, it) }
                }
            }
        }
    }
    Surface(color = background, contentColor = colors.textPrimary, shape = RoundedCornerShape(AppRadius.row), border = border, modifier = Modifier.fillMaxWidth().testTag("rider-row-${row.id}")) {
        Column(Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    Text(row.name, style = AppTheme.typography.titleBold)
                    val detail = listOfNotNull(row.stopName, if (row.highlight == RiderHighlight.REMAINING) "차 안 기록 남음" else null).joinToString(" · ")
                    if (detail.isNotEmpty()) Text(detail, style = AppTheme.typography.body2Regular, color = if (row.highlight == RiderHighlight.REMAINING) colors.dangerContent else colors.textSecondary)
                    row.badge?.let { StatusChip(it, tone = ChipTone.ACCENT, modifier = Modifier.testTag("rider-badge-${row.id}")) }
                }
                StatusChip(row.statusLabel, tone = row.tone, modifier = Modifier.testTag("rider-status-${row.id}"))
                // V-05 벨트 자리: 다음 기능에서 칩/토글을 넣는다.
                row.belt?.let { StatusChip(it, modifier = Modifier.testTag("rider-belt-${row.id}")) }
                if (!compact) actions()
            }
            // 세로 분할(좁은 창)에서는 버튼을 이름 아래 줄로 내린다.
            if (compact && row.actions.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) { actions() }
        }
    }
}
