package com.amar.vault.ui.theme

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = VaultDarkPrimary,
    onPrimary = VaultDarkOnPrimary,
    primaryContainer = VaultDarkPrimaryContainer,
    onPrimaryContainer = VaultDarkOnPrimaryContainer,
    secondary = VaultDarkSecondary,
    onSecondary = VaultDarkOnSecondary,
    secondaryContainer = VaultDarkSecondaryContainer,
    onSecondaryContainer = VaultDarkOnSecondaryContainer,
    tertiary = VaultDarkTertiary,
    onTertiary = VaultDarkOnTertiary,
    tertiaryContainer = VaultDarkTertiaryContainer,
    onTertiaryContainer = VaultDarkOnTertiaryContainer,
    error = VaultDarkError,
    onError = VaultDarkOnError,
    errorContainer = VaultDarkErrorContainer,
    onErrorContainer = VaultDarkOnErrorContainer,
    background = VaultDarkBackground,
    onBackground = VaultDarkOnBackground,
    surface = VaultDarkSurface,
    onSurface = VaultDarkOnSurface,
    surfaceVariant = VaultDarkSurfaceVariant,
    onSurfaceVariant = VaultDarkOnSurfaceVariant,
    outline = VaultDarkOutline,
    outlineVariant = VaultDarkOutlineVariant,
    inverseSurface = VaultDarkInverseSurface,
    inverseOnSurface = VaultDarkInverseOnSurface,
    surfaceContainerLowest = VaultDarkSurfaceLowest,
    surfaceContainerLow = VaultDarkSurfaceLow,
    surfaceContainer = VaultDarkSurfaceContainer,
    surfaceContainerHigh = VaultDarkSurfaceHigh,
    surfaceContainerHighest = VaultDarkSurfaceHighest,
    surfaceBright = VaultDarkSurfaceBright,
    surfaceTint = VaultDarkPrimary,
)

private val LightColorScheme = lightColorScheme(
    primary = VaultLightPrimary,
    onPrimary = VaultLightOnPrimary,
    primaryContainer = VaultLightPrimaryContainer,
    onPrimaryContainer = VaultLightOnPrimaryContainer,
    secondary = VaultLightSecondary,
    onSecondary = VaultLightOnSecondary,
    secondaryContainer = VaultLightSecondaryContainer,
    onSecondaryContainer = VaultLightOnSecondaryContainer,
    tertiary = VaultLightTertiary,
    onTertiary = VaultLightOnTertiary,
    tertiaryContainer = VaultLightTertiaryContainer,
    onTertiaryContainer = VaultLightOnTertiaryContainer,
    error = VaultLightError,
    onError = VaultLightOnError,
    errorContainer = VaultLightErrorContainer,
    onErrorContainer = VaultLightOnErrorContainer,
    background = VaultLightBackground,
    onBackground = VaultLightOnBackground,
    surface = VaultLightSurface,
    onSurface = VaultLightOnSurface,
    surfaceVariant = VaultLightSurfaceVariant,
    onSurfaceVariant = VaultLightOnSurfaceVariant,
    outline = VaultLightOutline,
    outlineVariant = VaultLightOutlineVariant,
    inverseSurface = VaultLightInverseSurface,
    inverseOnSurface = VaultLightInverseOnSurface,
    surfaceContainerLowest = VaultLightSurfaceLowest,
    surfaceContainerLow = VaultLightSurfaceLow,
    surfaceContainer = VaultLightSurfaceContainer,
    surfaceContainerHigh = VaultLightSurfaceHigh,
    surfaceContainerHighest = VaultLightSurfaceHighest,
    surfaceBright = VaultLightSurfaceBright,
    surfaceTint = VaultLightPrimary,
)

data class VaultExtraColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val scrim: Color,
    val switchOn: Color = VaultLightSwitchOn,
)

val LocalVaultExtraColors = staticCompositionLocalOf {
    VaultExtraColors(
        success = VaultLightSuccess,
        successContainer = VaultLightSuccessContainer,
        onSuccessContainer = Color(0xFF072711),
        scrim = Color.Black,
    )
}

val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun AmarTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    largerText: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val context = LocalContext.current
    val reducedMotion = remember {
        runCatching {
            val resolver = context.contentResolver
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f &&
                Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f &&
                Settings.Global.getFloat(resolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }

    val extra = if (darkTheme) {
        VaultExtraColors(
            success = VaultDarkSuccess,
            successContainer = VaultDarkSuccessContainer,
            onSuccessContainer = Color(0xFFC9F2D3),
            scrim = Color(0xD9000000),
            switchOn = VaultDarkSwitchOn,
        )
    } else {
        VaultExtraColors(
            success = VaultLightSuccess,
            successContainer = VaultLightSuccessContainer,
            onSuccessContainer = Color(0xFF072711),
            scrim = Color(0x8A000000),
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalVaultExtraColors provides extra,
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = if (largerText) LargerTypography else Typography,
            content = content
        )
    }
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
