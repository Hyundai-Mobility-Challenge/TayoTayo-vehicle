package com.hanhyo.pleosshuttle.designsystem.theme.color

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 통학메이트 색 토큰. 이름과 쓰임은 docs/design/TOKENS.md 표와 같다.
 * primary(노랑)는 채우기 전용 — 흰 바탕의 테두리·글자는 primaryStroke/primaryContent를 쓴다.
 */
@Immutable
data class AppColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceInset: Color,
    val outline: Color,
    val outlineStrong: Color,
    val outlineMuted: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val disabledContainer: Color,
    val disabledContent: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryStroke: Color,
    val primaryContent: Color,
    val primaryMarker: Color,
    val primaryContainer: Color,
    val primaryContainerOutline: Color,
    val onPrimaryContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContent: Color,
    val infoContainer: Color,
    val danger: Color,
    val dangerContent: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
    val neutralTag: Color,
    val onNeutralTag: Color,
)

val LightAppColors = AppColors(
    background = ColorPalette.white,
    surface = ColorPalette.gray50,
    surfaceRaised = ColorPalette.white,
    surfaceInset = ColorPalette.gray100,
    outline = ColorPalette.gray200,
    outlineStrong = ColorPalette.gray300,
    outlineMuted = ColorPalette.gray400,
    textPrimary = ColorPalette.gray900,
    textSecondary = ColorPalette.gray700,
    textTertiary = ColorPalette.gray600,
    disabledContainer = ColorPalette.gray150,
    disabledContent = ColorPalette.gray600,
    primary = ColorPalette.yellow400,
    onPrimary = ColorPalette.amber950,
    primaryStroke = ColorPalette.amber600,
    primaryContent = ColorPalette.amber800,
    primaryMarker = ColorPalette.amber700,
    primaryContainer = ColorPalette.yellow100,
    primaryContainerOutline = ColorPalette.yellowMuted,
    onPrimaryContainer = ColorPalette.amber900,
    info = ColorPalette.blue500,
    onInfo = ColorPalette.white,
    infoContent = ColorPalette.blue600,
    infoContainer = ColorPalette.blue50,
    danger = ColorPalette.coral500,
    dangerContent = ColorPalette.coral600,
    dangerContainer = ColorPalette.coral50,
    onDangerContainer = ColorPalette.coral900,
    neutralTag = ColorPalette.gray100,
    onNeutralTag = ColorPalette.gray800,
)

/** 첫 시안(다크)의 값. 야간 모드 여부는 팀 결정 전이다. */
val DarkAppColors = AppColors(
    background = ColorPalette.graphite950,
    surface = ColorPalette.graphite850,
    surfaceRaised = ColorPalette.graphite800,
    surfaceInset = ColorPalette.graphite900,
    outline = ColorPalette.graphite700,
    outlineStrong = ColorPalette.gray800,
    outlineMuted = ColorPalette.graphite600,
    textPrimary = ColorPalette.ivory,
    textSecondary = ColorPalette.mist300,
    textTertiary = ColorPalette.mist400,
    disabledContainer = ColorPalette.graphite750,
    disabledContent = ColorPalette.mist350,
    primary = ColorPalette.yellow400,
    onPrimary = ColorPalette.amber950,
    primaryStroke = ColorPalette.yellow400,
    primaryContent = ColorPalette.yellow300,
    primaryMarker = ColorPalette.yellow400,
    primaryContainer = ColorPalette.olive900,
    primaryContainerOutline = ColorPalette.olive800,
    onPrimaryContainer = ColorPalette.yellow50,
    info = ColorPalette.blue200,
    onInfo = ColorPalette.graphite950,
    infoContent = ColorPalette.blue150,
    infoContainer = ColorPalette.navy900,
    danger = ColorPalette.coral300,
    dangerContent = ColorPalette.coral250,
    dangerContainer = ColorPalette.maroon900,
    onDangerContainer = ColorPalette.coral100,
    neutralTag = ColorPalette.graphite700,
    onNeutralTag = ColorPalette.mist200,
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }
