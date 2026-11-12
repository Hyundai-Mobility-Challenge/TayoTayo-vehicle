@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.hanhyo.pleosshuttle.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import com.hanhyo.pleosshuttle.designsystem.theme.AppTheme
import kotlinx.coroutines.launch

/** 입력란 색. Material 기본은 노란 테두리·라벨이라 흰 바탕에서 대비가 낮다. */
@Composable internal fun appTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AppTheme.colors.primaryStroke,
    unfocusedBorderColor = AppTheme.colors.outlineStrong,
    focusedLabelColor = AppTheme.colors.primaryContent,
    unfocusedLabelColor = AppTheme.colors.textSecondary,
    cursorColor = AppTheme.colors.textPrimary,
)

/** 사유 입력. 완료를 누르면 키보드를 닫고 다음에 누를 버튼을 다시 화면 안으로 가져온다. */
@Composable internal fun GuideReasonField(reason: String, onReasonChange: (String) -> Unit, label: String, tag: String) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val onEditing = LocalGuideEditing.current
    val scope = rememberCoroutineScope()
    val focus by rememberUpdatedState(LocalGuideFocus.current)
    OutlinedTextField(
        value = reason, onValueChange = onReasonChange, label = { Text(label) },
        modifier = Modifier.fillMaxWidth().testTag(tag).guideAnchor(tag).onFocusChanged { onEditing(it.isFocused) }, singleLine = true,
        colors = appTextFieldColors(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            keyboard?.hide()
            focusManager.clearFocus()
            scope.launch {
                withFrameNanos { }
                focus?.requester?.bringIntoView()
            }
        }),
    )
}
