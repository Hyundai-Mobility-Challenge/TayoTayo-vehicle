package com.hanhyo.pleosshuttle.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.hanhyo.pleosshuttle.designsystem.theme.AppRadius
import com.hanhyo.pleosshuttle.designsystem.theme.AppSize
import com.hanhyo.pleosshuttle.designsystem.theme.AppSpacing
import com.hanhyo.pleosshuttle.designsystem.theme.AppTheme
import com.hanhyo.pleosshuttle.designsystem.theme.canvasDp

/** 화면 맨 아래 동요 바. 음악과 안내 음성의 상태 한 줄과 재생·정지·다시 듣기 버튼. */
@Composable internal fun MusicBar(
    status: String,
    playing: Boolean,
    speaking: Boolean,
    compact: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onReplay: () -> Unit,
    onStopSpeech: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) AppSize.playerBarHeightCompact else AppSize.playerBarHeight)
            .background(AppTheme.colors.surface, RoundedCornerShape(AppRadius.panel))
            .padding(start = AppSpacing.lg, end = AppSpacing.md, top = AppSpacing.sm, bottom = AppSpacing.sm)
            .testTag("music-toolbar"),
        horizontalArrangement = Arrangement.spacedBy(if (compact) AppSpacing.sm else AppSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!compact) Box(
            Modifier.size(canvasDp(56)).background(AppTheme.colors.surfaceRaised, RoundedCornerShape(canvasDp(14))),
            contentAlignment = Alignment.Center,
        ) { Text("♪", style = AppTheme.typography.head4Bold, color = AppTheme.semantic.music) }
        Text(status, modifier = Modifier.weight(1f).testTag("music-status"), style = if (compact) AppTheme.typography.body3SemiBold else AppTheme.typography.body1SemiBold)
        if (playing) Action("일시정지", "music-pause", secondary = true, action = onPause)
        else Action("음악 재생", "music-play", secondary = true, action = onPlay)
        Action("음악 정지", "music-stop", secondary = true, action = onStop)
        if (speaking) Action("음성 중지", "tts-stop", secondary = true, action = onStopSpeech)
        else Action("다시 듣기", "tts", secondary = true, action = onReplay)
    }
}
