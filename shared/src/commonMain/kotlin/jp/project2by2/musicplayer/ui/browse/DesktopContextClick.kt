package jp.project2by2.musicplayer.ui.browse

import androidx.compose.ui.Modifier

/** Secondary mouse clicks share the existing action dialogs; Android keeps its touch gestures. */
expect fun Modifier.desktopContextClick(enabled: Boolean = true, onClick: () -> Unit): Modifier
