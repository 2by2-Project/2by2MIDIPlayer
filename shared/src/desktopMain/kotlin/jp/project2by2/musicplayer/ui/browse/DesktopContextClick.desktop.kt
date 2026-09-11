package jp.project2by2.musicplayer.ui.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.onClick
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton

@OptIn(ExperimentalFoundationApi::class)
actual fun Modifier.desktopContextClick(enabled: Boolean, onClick: () -> Unit): Modifier =
    onClick(enabled = enabled, matcher = PointerMatcher.mouse(PointerButton.Secondary), onClick = onClick)
