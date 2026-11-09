package com.amar.vault.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = VaultDarkPrimary,
    onPrimary = VaultDarkOnPrimary,
    primaryContainer = VaultDarkPrimaryContainer,
    onPrimaryContainer = VaultDarkOnPrimaryContainer,
    secondary = VaultDarkSecondary,
    onSecondary = VaultDarkOnSecondary,
    secondaryContainer = VaultDarkSecondaryContainer,
    onSecondaryContainer = VaultDarkOnSecondaryContainer,
    background = VaultDarkBackground,
    onBackground = VaultDarkOnBackground,
    surface = VaultDarkSurface,
    onSurface = VaultDarkOnSurface,
    surfaceVariant = VaultDarkSurfaceVariant,
    onSurfaceVariant = VaultDarkOnSurfaceVariant,
    outline = VaultDarkOutline,
    outlineVariant = VaultDarkOutlineVariant,
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
    background = VaultLightBackground,
    onBackground = VaultLightOnBackground,
    surface = VaultLightSurface,
    onSurface = VaultLightOnSurface,
    surfaceVariant = VaultLightSurfaceVariant,
    onSurfaceVariant = VaultLightOnSurfaceVariant,
    outline = VaultLightOutline,
    outlineVariant = VaultLightOutlineVariant,
)

@Composable
fun AmarTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    largerText: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val typography = if (largerText) LargerTypography else Typography

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content
    )
}
