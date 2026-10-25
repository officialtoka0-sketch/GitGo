package com.gitgo.app

import android.app.Activity
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class ThemeKind(@StringRes val labelRes: Int, val swatch: Color, val dark: Boolean) {
    DARK(R.string.theme_dark, Color(0xFF000000), true),
    LIGHT(R.string.theme_light, Color(0xFFFFFFFF), false),
    PISTACHIO(R.string.theme_pistachio, Color(0xFF93C572), false),
    PURPLE(R.string.theme_purple, Color(0xFF8E24AA), true)
}

private val Blue = Color(0xFF4F9BD6)
private val Orange = Color(0xFFE08A4F)

@Composable
fun GitGoTheme(kind: ThemeKind, content: @Composable () -> Unit) {
    val scheme = when (kind) {
        ThemeKind.DARK -> darkColorScheme(
            primary = Blue, onPrimary = Color.Black, secondary = Orange, onSecondary = Color.Black,
            background = Color.Black, onBackground = Color.White,
            surface = Color(0xFF0E0E0E), onSurface = Color.White,
            surfaceVariant = Color(0xFF1B1B1B), onSurfaceVariant = Color(0xFFCCCCCC),
            primaryContainer = Color(0xFF123A59), onPrimaryContainer = Color.White
        )
        ThemeKind.LIGHT -> lightColorScheme(
            primary = Color(0xFF2B6CA3), onPrimary = Color.White, secondary = Color(0xFFC2672A),
            background = Color.White, onBackground = Color(0xFF111111),
            surface = Color.White, onSurface = Color(0xFF111111),
            surfaceVariant = Color(0xFFF0F0F0), onSurfaceVariant = Color(0xFF444444),
            primaryContainer = Color(0xFFD3E6F7), onPrimaryContainer = Color(0xFF0B2A44)
        )
        ThemeKind.PISTACHIO -> lightColorScheme(
            primary = Color(0xFF5E8C31), onPrimary = Color.White, secondary = Color(0xFF8A6D2F),
            background = Color(0xFFF4F9EC), onBackground = Color(0xFF1C2410),
            surface = Color(0xFFEAF4DC), onSurface = Color(0xFF1C2410),
            surfaceVariant = Color(0xFFDDEBC8), onSurfaceVariant = Color(0xFF3B4A28),
            primaryContainer = Color(0xFFC8E6A0), onPrimaryContainer = Color(0xFF1C2410)
        )
        ThemeKind.PURPLE -> darkColorScheme(
            primary = Color(0xFFD0A2FF), onPrimary = Color(0xFF2A0A45), secondary = Color(0xFFFF9ACD),
            onSecondary = Color(0xFF3B0A26),
            background = Color(0xFF160B22), onBackground = Color(0xFFF3E9FF),
            surface = Color(0xFF211232), onSurface = Color(0xFFF3E9FF),
            surfaceVariant = Color(0xFF2E1A45), onSurfaceVariant = Color(0xFFD9C6F0),
            primaryContainer = Color(0xFF5B2A8C), onPrimaryContainer = Color.White
        )
    }
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val c = WindowCompat.getInsetsController(window, view)
            c.isAppearanceLightStatusBars = !kind.dark
            c.isAppearanceLightNavigationBars = !kind.dark
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
