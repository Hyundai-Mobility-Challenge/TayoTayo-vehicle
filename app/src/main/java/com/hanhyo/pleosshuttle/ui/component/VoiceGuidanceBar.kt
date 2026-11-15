package com.hanhyo.pleosshuttle.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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

/** 화면 맨 아래 음성 안내 바. 안내 음성 상태 한 줄과 [다시 듣기]/[음성 멈춤] 버튼. */
@Composable internal fun VoiceGuidanceBar(
    status: String,
    speaking: Boolean,
    compact: Boolean,
    onReplay: () -> Unit,
    onStopSpeech: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) AppSize.playerBarHeightCompact else AppSize.playerBarHeight)
            .background(AppTheme.colors.surface, RoundedCornerShape(AppRadius.panel))
            .padding(start = AppSpacing.lg, end = AppSpacing.md, top = AppSpacing.sm, bottom = AppSpacing.sm)
            .testTag("voice-toolbar"),
        horizontalArrangement = Arrangement.spacedBy(if (compact) AppSpacing.sm else AppSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(status, modifier = Modifier.weight(1f).testTag("voice-status"), style = if (compact) AppTheme.typography.body3SemiBold else AppTheme.typography.body1SemiBold)
        if (speaking) Action("음성 멈춤", "tts-stop", secondary = true, action = onStopSpeech)
        else Action("다시 듣기", "tts", secondary = true, action = onReplay)
    }
}
