package com.hanhyo.pleosshuttle.designsystem.theme.color

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme

/** Material3 컴포넌트 내부 동작용. 화면 코드는 AppTheme.colors를 쓴다. */
internal val LightColorScheme = lightColorScheme(
    primary = LightAppColors.primary,
    onPrimary = LightAppColors.onPrimary,
    primaryContainer = LightAppColors.primaryContainer,
    onPrimaryContainer = LightAppColors.onPrimaryContainer,
    secondary = LightAppColors.info,
    onSecondary = LightAppColors.onInfo,
    background = LightAppColors.background,
    onBackground = LightAppColors.textPrimary,
    surface = LightAppColors.surfaceRaised,
    onSurface = LightAppColors.textPrimary,
    surfaceVariant = LightAppColors.surface,
    onSurfaceVariant = LightAppColors.textSecondary,
    outline = LightAppColors.outlineStrong,
    outlineVariant = LightAppColors.outline,
    error = LightAppColors.danger,
    onError = LightAppColors.onInfo,
    errorContainer = LightAppColors.dangerContainer,
    onErrorContainer = LightAppColors.onDangerContainer,
)

internal val DarkColorScheme = darkColorScheme(
    primary = DarkAppColors.primary,
    onPrimary = DarkAppColors.onPrimary,
    primaryContainer = DarkAppColors.primaryContainer,
    onPrimaryContainer = DarkAppColors.onPrimaryContainer,
    secondary = DarkAppColors.info,
    onSecondary = DarkAppColors.onInfo,
    background = DarkAppColors.background,
    onBackground = DarkAppColors.textPrimary,
    surface = DarkAppColors.surfaceRaised,
    onSurface = DarkAppColors.textPrimary,
    surfaceVariant = DarkAppColors.surface,
    onSurfaceVariant = DarkAppColors.textSecondary,
    outline = DarkAppColors.outlineStrong,
    outlineVariant = DarkAppColors.outline,
    error = DarkAppColors.danger,
    onError = DarkAppColors.onInfo,
    errorContainer = DarkAppColors.dangerContainer,
    onErrorContainer = DarkAppColors.onDangerContainer,
)
