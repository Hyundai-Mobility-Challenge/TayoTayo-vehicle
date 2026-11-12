package com.hanhyo.pleosshuttle.designsystem.theme

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import com.hanhyo.pleosshuttle.designsystem.theme.color.AppColors
import com.hanhyo.pleosshuttle.designsystem.theme.color.DarkAppColors
import com.hanhyo.pleosshuttle.designsystem.theme.color.DarkColorScheme
import com.hanhyo.pleosshuttle.designsystem.theme.color.DarkSemanticColors
import com.hanhyo.pleosshuttle.designsystem.theme.color.LightAppColors
import com.hanhyo.pleosshuttle.designsystem.theme.color.LightColorScheme
import com.hanhyo.pleosshuttle.designsystem.theme.color.LightSemanticColors
import com.hanhyo.pleosshuttle.designsystem.theme.color.LocalAppColors
import com.hanhyo.pleosshuttle.designsystem.theme.color.LocalSemanticColors
import com.hanhyo.pleosshuttle.designsystem.theme.color.SemanticColors
import com.hanhyo.pleosshuttle.designsystem.theme.typography.AppTypography
import com.hanhyo.pleosshuttle.designsystem.theme.typography.DefaultAppTypography
import com.hanhyo.pleosshuttle.designsystem.theme.typography.LocalAppTypography

/**
 * 기본은 라이트(사용자 결정, 2026-11-01). 차량 야간 모드 연동 여부가 정해지기 전까지 darkTheme 기본값은 false.
 * 차량 앱은 터치 영역을 AppSize.touchMin(캔버스 64)으로 직접 보장하므로 M3 최소 터치 보정은 끈다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val appColors = if (darkTheme) DarkAppColors else LightAppColors
    val semanticColors = if (darkTheme) DarkSemanticColors else LightSemanticColors
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    CompositionLocalProvider(
        LocalAppColors provides appColors,
        LocalSemanticColors provides semanticColors,
        LocalAppTypography provides DefaultAppTypography,
        LocalMinimumInteractiveComponentEnforcement provides false,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = materialTypography(DefaultAppTypography),
            content = content,
        )
    }
}

/** 대화상자·입력란처럼 Material3 기본 글자를 쓰는 곳도 같은 글꼴과 크기를 쓰도록 연결한다. */
private fun materialTypography(app: AppTypography) = Typography(
    headlineLarge = app.head1Bold,
    headlineMedium = app.head2Bold,
    headlineSmall = app.head3Bold,
    titleLarge = app.head4Bold,
    titleMedium = app.body1SemiBold,
    titleSmall = app.body3SemiBold,
    bodyLarge = app.body2Regular,
    bodyMedium = app.body3Regular,
    bodySmall = app.caption1Regular,
    labelLarge = app.body2SemiBold,
    labelMedium = app.caption1Bold,
    labelSmall = app.caption2Regular,
)

object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalAppColors.current
    val semantic: SemanticColors
        @Composable @ReadOnlyComposable get() = LocalSemanticColors.current
    val typography: AppTypography
        @Composable @ReadOnlyComposable get() = LocalAppTypography.current
}
