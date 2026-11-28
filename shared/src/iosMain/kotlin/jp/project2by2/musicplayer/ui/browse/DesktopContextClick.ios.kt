package jp.project2by2.musicplayer.ui.browse

import androidx.compose.ui.Modifier

/** Touch platforms open the track actions with a long press, as on Android. */
actual fun Modifier.desktopContextClick(enabled: Boolean, onClick: () -> Unit): Modifier = this
