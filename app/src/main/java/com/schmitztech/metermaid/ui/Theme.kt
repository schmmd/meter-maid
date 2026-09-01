package com.schmitztech.metermaid.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Aquifer = Color(0xFF0F6E68)
private val AquiferLight = Color(0xFF55B7AD)
private val Brass = Color(0xFFA97C2E)
private val Warn = Color(0xFF97650F)
private val WarnDark = Color(0xFFD2A03F)

val WarnColor: Color
    @Composable get() = if (isSystemInDarkTheme()) WarnDark else Warn

private val Light = lightColorScheme(
    primary = Aquifer,
    secondary = Brass,
    background = Color(0xFFEBF0EF),
    surface = Color(0xFFF8FAF9),
)

private val Dark = darkColorScheme(
    primary = AquiferLight,
    secondary = Color(0xFFD7AC55),
    background = Color(0xFF0F1716),
    surface = Color(0xFF16211F),
)

@Composable
fun MeterMaidTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
