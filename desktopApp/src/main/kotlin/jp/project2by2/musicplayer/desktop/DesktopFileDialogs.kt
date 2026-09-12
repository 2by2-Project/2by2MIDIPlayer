package jp.project2by2.musicplayer.desktop

import io.github.vinceglb.filekit.dialogs.FileKitDialogException

/** Normalize a portal dismissal only around the dialog, never around file I/O. */
internal suspend fun <T> fileKitDialogResult(open: suspend () -> T?): T? = try {
    open()
} catch (failure: FileKitDialogException) {
    if (!FileKitPortalCompatibility.isDismissal(failure)) throw failure
    // Response 2 cannot distinguish title-bar dismissal from other portal-side endings.
    // Keep diagnostic evidence while leaving the current selection and playback untouched.
    System.err.println("File dialog ended without a selection: ${failure.cause}")
    null
}
