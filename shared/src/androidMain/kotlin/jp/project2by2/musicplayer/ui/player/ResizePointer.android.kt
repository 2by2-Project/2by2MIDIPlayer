package jp.project2by2.musicplayer.ui.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon

// API 24+, matching the application's minSdk. Touch input is unaffected.
internal actual fun Modifier.horizontalResizePointer(): Modifier =
    pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))
