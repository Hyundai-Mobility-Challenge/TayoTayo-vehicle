package com.hanhyo.pleosshuttle.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.hanhyo.pleosshuttle.designsystem.theme.AppRadius
import com.hanhyo.pleosshuttle.designsystem.theme.AppSize
import com.hanhyo.pleosshuttle.designsystem.theme.AppSpacing
import com.hanhyo.pleosshuttle.designsystem.theme.AppTheme
import com.hanhyo.pleosshuttle.designsystem.theme.canvasDp

internal enum class StopProgress { DONE, CURRENT, UPCOMING }

/** 타임라인 한 줄. [marker]는 번호 원 안 글자(정류장 번호, 출발은 '출', 복귀는 '복'). */
internal data class TimelineStop(val marker: String, val name: String, val detail: String, val progress: StopProgress)

/** 가로 전체화면의 왼쪽 정류장 타임라인(폭 AppSize.timelineWidth). 보기 전용이다. */
@Composable internal fun StopTimelinePanel(title: String, stops: List<TimelineStop>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(AppTheme.colors.surface, RoundedCornerShape(AppRadius.panel))
            .padding(horizontal = AppSpacing.sidePanelHorizontal, vertical = AppSpacing.sidePanelVertical)
            .verticalScroll(rememberScrollState())
            .testTag("stop-timeline"),
        verticalArrangement = Arrangement.spacedBy(canvasDp(6)),
    ) {
        Text(title, Modifier.padding(start = AppSpacing.sm, end = AppSpacing.sm, bottom = AppSpacing.sm), style = AppTheme.typography.body3SemiBold, color = AppTheme.colors.textSecondary)
        stops.forEach { TimelineRow(it) }
    }
}

@Composable private fun TimelineRow(stop: TimelineStop) {
    val colors = AppTheme.colors
    val semantic = AppTheme.semantic
    val current = stop.progress == StopProgress.CURRENT
    Surface(
        color = if (current) colors.surfaceRaised else Color.Transparent,
        contentColor = colors.textPrimary,
        shape = RoundedCornerShape(AppRadius.row),
        border = if (current) BorderStroke(AppSize.currentOutline, semantic.stopCurrentOutline) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(canvasDp(10)), horizontalArrangement = Arrangement.spacedBy(canvasDp(14)), verticalAlignment = Alignment.CenterVertically) {
            StopMarker(stop)
            Column(verticalArrangement = Arrangement.spacedBy(canvasDp(2))) {
                Text(
                    stop.name,
                    style = AppTheme.typography.body2SemiBold.copy(fontWeight = if (current) FontWeight.Bold else FontWeight.SemiBold),
                    color = if (stop.progress == StopProgress.DONE) colors.textSecondary else colors.textPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(stop.detail, style = AppTheme.typography.caption1Regular, color = if (current) semantic.stopCurrentContent else colors.textTertiary)
            }
        }
    }
}

@Composable private fun StopMarker(stop: TimelineStop) {
    val semantic = AppTheme.semantic
    val shape = CircleShape
    when (stop.progress) {
        StopProgress.DONE -> Box(Modifier.size(AppSize.stopMarker).background(semantic.stopDone, shape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, contentDescription = "완료", tint = semantic.onStopDone, modifier = Modifier.size(canvasDp(18)))
        }
        StopProgress.CURRENT -> Box(Modifier.size(AppSize.stopMarker).border(AppSize.currentOutline, semantic.stopCurrentOutline, shape), contentAlignment = Alignment.Center) {
            Text(stop.marker, style = AppTheme.typography.caption2Bold, color = AppTheme.colors.primaryMarker)
        }
        StopProgress.UPCOMING -> Box(Modifier.size(AppSize.stopMarker).border(AppSize.currentOutline, semantic.stopUpcomingOutline, shape), contentAlignment = Alignment.Center) {
            Text(stop.marker, style = AppTheme.typography.caption2Bold, color = AppTheme.colors.textSecondary)
        }
    }
}

/** 세로 분할에서 타임라인 대신 쓰는 '다음 · 이후' 두 줄. */
@Composable internal fun CompactTimeline(stops: List<TimelineStop>, modifier: Modifier = Modifier) {
    val currentIndex = stops.indexOfFirst { it.progress == StopProgress.CURRENT }
    val next = stops.getOrNull(currentIndex)
    val later = if (currentIndex >= 0) stops.drop(currentIndex + 1) else emptyList()
    Column(
        modifier
            .fillMaxWidth()
            .background(AppTheme.colors.surface, RoundedCornerShape(AppRadius.card))
            .padding(horizontal = AppSpacing.xl, vertical = AppSpacing.md)
            .testTag("stop-timeline-compact"),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Text("다음", style = AppTheme.typography.caption1Bold, color = AppTheme.semantic.stopCurrentContent)
            Text(next?.let { "${it.name} · ${it.detail}" } ?: "모든 정류장 완료", style = AppTheme.typography.body1SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (later.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Text("이후", style = AppTheme.typography.caption1Bold, color = AppTheme.colors.textTertiary)
            Text(later.joinToString(" → ") { it.name }, style = AppTheme.typography.body3Regular, color = AppTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
