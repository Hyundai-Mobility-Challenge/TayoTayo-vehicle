package com.hanhyo.pleosshuttle.designsystem.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 캔버스 CSS px → dp(sp) 환산 비율. Pleos AVD 앱 창(1682×1210 dp)과 캔버스(1280×920)를 대조해
 * 2026-11-02 확정했다(docs/design/TOKENS.md '단위'). 캔버스 수치를 그대로 넘기면 이 비율로 바꾼다.
 */
const val CanvasScale = 1.3f

fun canvasDp(px: Int): Dp = (px * CanvasScale).roundToInt().dp
fun canvasSp(px: Int): TextUnit = (px * CanvasScale).roundToInt().sp

/** 테마와 무관한 크기 상수. 괄호 안은 캔버스 px. */
object AppSize {
    val touchMin = canvasDp(64)
    val primaryButton = canvasDp(80)
    val primaryButtonCompact = canvasDp(72)
    val secondaryButton = canvasDp(64)
    val headerHeight = canvasDp(72)
    val headerHeightCompact = canvasDp(64)
    val timelineWidth = canvasDp(360)
    /** 운행 준비 중 왼쪽 패널을 [명단] 탭으로 볼 때의 폭. 행은 이름·상태 줄 + 버튼(또는 벨트) 줄 두 줄로 그린다. */
    val rosterPaneWidth = canvasDp(420)
    val playerBarHeight = canvasDp(88)
    val playerBarHeightCompact = canvasDp(76)
    val chipHeight = canvasDp(44)
    val logo = canvasDp(52)
    val avatar = canvasDp(52)
    val stopMarker = canvasDp(30)
    val currentOutline = canvasDp(2)

    /**
     * 이 폭보다 좁으면 세로 분할 레이아웃(타임라인을 본문 안 '다음 · 이후' 줄로 접음). 캔버스 값이 아니라 실제 dp다.
     * 측정한 분할 창 841 dp와 전체 창 1682 dp 사이에 둔다.
     */
    val compactWidthBreakpoint = 1200.dp
}

object AppSpacing {
    val xs = canvasDp(4)
    val sm = canvasDp(8)
    val md = canvasDp(12)
    val lg = canvasDp(16)
    val xl = canvasDp(20)
    val xxl = canvasDp(24)
    val panelHorizontal = canvasDp(32)
    val panelVertical = canvasDp(28)
    val sidePanelHorizontal = canvasDp(16)
    val sidePanelVertical = canvasDp(20)
    val screen = canvasDp(24)
    val screenCompact = canvasDp(20)
}

object AppRadius {
    val row = canvasDp(14)
    val card = canvasDp(16)
    val button = canvasDp(18)
    val panel = canvasDp(20)
    val full = 999.dp
}
