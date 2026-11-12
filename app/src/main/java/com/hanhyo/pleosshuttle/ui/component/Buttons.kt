package com.hanhyo.pleosshuttle.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hanhyo.pleosshuttle.designsystem.theme.AppRadius
import com.hanhyo.pleosshuttle.designsystem.theme.AppSize
import com.hanhyo.pleosshuttle.designsystem.theme.AppTheme
import com.hanhyo.pleosshuttle.designsystem.theme.canvasDp

/**
 * 화면의 모든 조작 버튼. [prominent]만 노란 채우기 주요 버튼이고(화면당 하나),
 * 그 밖의 주요 행동은 호박색 외곽선, [secondary]는 회색 외곽선이다.
 */
@Composable internal fun Action(
    text: String,
    tag: String,
    enabled: Boolean = true,
    secondary: Boolean = false,
    prominent: Boolean = false,
    action: () -> Unit,
) {
    val onGuideAction = LocalGuideAction.current
    val focusManager = LocalFocusManager.current
    val onClick = { focusManager.clearFocus(); action(); onGuideAction(tag) }
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(AppRadius.button)
    val padding = PaddingValues(horizontal = canvasDp(18), vertical = canvasDp(8))
    val modifier = (
        if (prominent) Modifier.fillMaxWidth().heightIn(min = if (LocalCompactLayout.current) AppSize.primaryButtonCompact else AppSize.primaryButton)
        else Modifier.heightIn(min = AppSize.touchMin)
    ).testTag(tag).guideAnchor(tag)
    if (prominent && !secondary) {
        Button(
            onClick = onClick, enabled = enabled, modifier = modifier, shape = shape, contentPadding = padding,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.primary, contentColor = colors.onPrimary,
                disabledContainerColor = colors.disabledContainer, disabledContentColor = colors.disabledContent,
            ),
        ) { Text(text, style = AppTheme.typography.titleBold) }
    } else {
        val stroke = when {
            !enabled -> BorderStroke(1.dp, colors.outline)
            secondary -> BorderStroke(1.dp, colors.outlineStrong)
            else -> BorderStroke(AppSize.currentOutline, colors.primaryStroke)
        }
        OutlinedButton(
            onClick = onClick, enabled = enabled, modifier = modifier, shape = shape, contentPadding = padding, border = stroke,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = colors.surfaceRaised,
                contentColor = if (secondary) colors.textPrimary else colors.primaryContent,
                disabledContainerColor = colors.disabledContainer, disabledContentColor = colors.disabledContent,
            ),
        ) { Text(text, style = if (prominent) AppTheme.typography.titleBold else AppTheme.typography.body2SemiBold) }
    }
}

/** 대화상자 버튼. Material 기본 TextButton은 노란 글자가 되어 흰 바탕에서 읽기 어렵다. */
@Composable internal fun DialogButton(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = AppSize.touchMin),
        colors = ButtonDefaults.textButtonColors(contentColor = AppTheme.colors.primaryContent),
    ) { Text(text, style = AppTheme.typography.body2SemiBold) }
}
