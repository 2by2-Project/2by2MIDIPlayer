package jp.project2by2.musicplayer.ui.player

import androidx.compose.ui.Modifier

/** Platform-specific hover feedback; does not change the splitter's drag handling. */
internal expect fun Modifier.horizontalResizePointer(): Modifier
