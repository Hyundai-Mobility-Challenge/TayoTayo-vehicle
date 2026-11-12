package com.hanhyo.pleosshuttle.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hanhyo.pleosshuttle.designsystem.theme.AppRadius
import com.hanhyo.pleosshuttle.designsystem.theme.AppSize
import com.hanhyo.pleosshuttle.designsystem.theme.AppTheme
import com.hanhyo.pleosshuttle.designsystem.theme.canvasDp

internal enum class ChipTone { NEUTRAL, INFO, ACCENT, DANGER }

/** 헤더·명단의 상태 칩. 색만으로 상태를 구분하지 않도록 항상 글자를 함께 쓴다. */
@Composable internal fun StatusChip(text: String, modifier: Modifier = Modifier, tone: ChipTone = ChipTone.NEUTRAL) {
    val colors = AppTheme.colors
    val (container, content) = when (tone) {
        ChipTone.NEUTRAL -> colors.surface to colors.textPrimary
        ChipTone.INFO -> colors.infoContainer to colors.infoContent
        ChipTone.ACCENT -> colors.primaryContainer to colors.primaryContent
        ChipTone.DANGER -> colors.dangerContainer to colors.dangerContent
    }
    Surface(
        modifier = modifier,
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(AppRadius.full),
        border = if (tone == ChipTone.NEUTRAL) BorderStroke(1.dp, colors.outline) else null,
    ) {
        Box(Modifier.heightIn(min = AppSize.chipHeight).padding(horizontal = canvasDp(18)), contentAlignment = Alignment.Center) {
            Text(text, style = AppTheme.typography.body2SemiBold)
        }
    }
}

/** 안내·경고 배너. [error]는 저장·요청 실패처럼 바로 봐야 하는 문제에만 쓴다. */
@Composable internal fun Notice(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    val colors = AppTheme.colors
    Surface(
        color = if (error) colors.dangerContainer else colors.primaryContainer,
        contentColor = if (error) colors.onDangerContainer else colors.onPrimaryContainer,
        shape = RoundedCornerShape(AppRadius.card),
        modifier = modifier.fillMaxWidth(),
    ) { Text(text, Modifier.padding(canvasDp(16)), style = AppTheme.typography.body2Regular) }
}
