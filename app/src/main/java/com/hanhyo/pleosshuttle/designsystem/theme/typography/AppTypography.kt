package com.hanhyo.pleosshuttle.designsystem.theme.typography

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.hanhyo.pleosshuttle.designsystem.theme.canvasSp

/**
 * 차량 화면용 글자 크기. 주석의 크기/행간은 캔버스 px이며 canvasSp로 ×1.3 환산한다(docs/design/TOKENS.md '단위').
 * 숫자(시각·인원)는 tabular 설정(fontFeatureSettings = "tnum")을 쓴다.
 */
@Immutable
data class AppTypography(
    val displayEta: TextStyle,      // 104 / 104 / Bold — 남은 시간
    val displayEtaCompact: TextStyle, // 88 / 88 / Bold — 분할
    val stopName: TextStyle,        // 48 / 58 / Bold — 이동 중 정류장
    val stopNameCompact: TextStyle, // 34 / 44 / Bold — 분할
    val head1Bold: TextStyle,       // 40 / 50
    val head2Bold: TextStyle,       // 36 / 46
    val head3Bold: TextStyle,       // 30 / 40 — 일반 화면 제목
    val head4Bold: TextStyle,       // 26 / 32 — 헤더 운행 이름
    val titleBold: TextStyle,       // 24 / 30 — 아이 이름, 주요 버튼
    val body1SemiBold: TextStyle,   // 20 / 28
    val body2Regular: TextStyle,    // 19 / 28
    val body2SemiBold: TextStyle,   // 19 / 28
    val body3Regular: TextStyle,    // 17 / 24
    val body3SemiBold: TextStyle,   // 17 / 24
    val caption1Regular: TextStyle, // 15 / 20
    val caption1Bold: TextStyle,    // 15 / 20
    val caption2Regular: TextStyle, // 14 / 20
    val caption2Bold: TextStyle,    // 14 / 20
)

private fun style(size: Int, lineHeight: Int, weight: FontWeight, tabular: Boolean = false) = TextStyle(
    fontFamily = AppFont,
    fontSize = canvasSp(size),
    lineHeight = canvasSp(lineHeight),
    fontWeight = weight,
    fontFeatureSettings = if (tabular) "tnum" else null,
)

val DefaultAppTypography = AppTypography(
    displayEta = style(104, 104, FontWeight.Bold, tabular = true),
    displayEtaCompact = style(88, 88, FontWeight.Bold, tabular = true),
    stopName = style(48, 58, FontWeight.Bold),
    stopNameCompact = style(34, 44, FontWeight.Bold),
    head1Bold = style(40, 50, FontWeight.Bold),
    head2Bold = style(36, 46, FontWeight.Bold),
    head3Bold = style(30, 40, FontWeight.Bold),
    head4Bold = style(26, 32, FontWeight.Bold),
    titleBold = style(24, 30, FontWeight.Bold),
    body1SemiBold = style(20, 28, FontWeight.SemiBold),
    body2Regular = style(19, 28, FontWeight.Normal),
    body2SemiBold = style(19, 28, FontWeight.SemiBold),
    body3Regular = style(17, 24, FontWeight.Normal),
    body3SemiBold = style(17, 24, FontWeight.SemiBold),
    caption1Regular = style(15, 20, FontWeight.Normal),
    caption1Bold = style(15, 20, FontWeight.Bold),
    caption2Regular = style(14, 20, FontWeight.Normal),
    caption2Bold = style(14, 20, FontWeight.Bold),
)

val LocalAppTypography = staticCompositionLocalOf { DefaultAppTypography }
