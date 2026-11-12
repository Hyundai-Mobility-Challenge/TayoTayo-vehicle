package com.hanhyo.pleosshuttle.designsystem.theme.color

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** 통학 운행 도메인에만 쓰는 색. 상태는 색만으로 구분하지 않고 글자·아이콘을 함께 쓴다. */
@Immutable
data class SemanticColors(
    /** 타임라인: 지난 정류장 원 채우기 */
    val stopDone: Color,
    val onStopDone: Color,
    /** 타임라인·카드: 지금 처리할 정류장/아이 테두리 */
    val stopCurrentOutline: Color,
    val stopCurrentContent: Color,
    /** 타임라인: 남은 정류장 원 테두리 */
    val stopUpcomingOutline: Color,
    /** 오늘의 통학: 확인 전 변경 카드 테두리 */
    val changePendingOutline: Color,
    /** 동요 바·재생목록 강조 */
    val music: Color,
    val musicContainer: Color,
    /** 체험용(차량 이벤트 흉내) 점선 버튼 — 실제 앱에서는 숨김 */
    val demoOutline: Color,
)

val LightSemanticColors = SemanticColors(
    stopDone = ColorPalette.blue500,
    onStopDone = ColorPalette.white,
    stopCurrentOutline = ColorPalette.amber600,
    stopCurrentContent = ColorPalette.amber800,
    stopUpcomingOutline = ColorPalette.gray400,
    changePendingOutline = ColorPalette.yellowMuted,
    music = ColorPalette.blue600,
    musicContainer = ColorPalette.blue50,
    demoOutline = ColorPalette.gray500,
)

val DarkSemanticColors = SemanticColors(
    stopDone = ColorPalette.blue200,
    onStopDone = ColorPalette.graphite950,
    stopCurrentOutline = ColorPalette.yellow400,
    stopCurrentContent = ColorPalette.yellow300,
    stopUpcomingOutline = ColorPalette.graphite600,
    changePendingOutline = ColorPalette.olive800,
    music = ColorPalette.blue150,
    musicContainer = ColorPalette.navy900,
    demoOutline = ColorPalette.graphite500,
)

val LocalSemanticColors = staticCompositionLocalOf { LightSemanticColors }
