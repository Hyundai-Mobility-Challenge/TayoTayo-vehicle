@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.hanhyo.pleosshuttle.ui.component

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** 다음에 누를 버튼(FlowGuide.targetTag)을 화면 안으로 끌어오는 연결. 표시 전용이며 동작을 대신 실행하지 않는다. */
internal data class GuideFocus(val tag: String?, val requester: BringIntoViewRequester)
internal val LocalGuideFocus = staticCompositionLocalOf<GuideFocus?> { null }
internal val LocalGuideEditing = staticCompositionLocalOf<(Boolean) -> Unit> { {} }
internal val LocalGuideAction = staticCompositionLocalOf<(String) -> Unit> { {} }

/** 창 폭이 AppSize.compactWidthBreakpoint보다 좁은 세로 분할 배치인지. */
internal val LocalCompactLayout = staticCompositionLocalOf { false }

@Composable internal fun Modifier.guideAnchor(tag: String): Modifier {
    val focus = LocalGuideFocus.current
    return if (focus?.tag == tag) bringIntoViewRequester(focus.requester) else this
}
