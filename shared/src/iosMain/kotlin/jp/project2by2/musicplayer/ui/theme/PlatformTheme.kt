package jp.project2by2.musicplayer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/** iOS has no wallpaper-based palette; the app's own light/dark schemes are used. */
@Composable
internal actual fun platformDynamicColorScheme(darkTheme: Boolean): ColorScheme? = null
