package jp.project2by2.musicplayer.desktop;

import io.github.vinceglb.filekit.dialogs.FileKitDialogException;
import io.github.vinceglb.filekit.dialogs.platform.xdg.XdgPortalResponseException;

/** Compatibility boundary for FileKit 0.16.0's JVM-only portal exception. */
final class FileKitPortalCompatibility {
    private FileKitPortalCompatibility() {}

    static boolean isDismissal(FileKitDialogException failure) {
        // GTK portal 1.15.1 maps the title-bar close button to response 2 (ended),
        // whereas its Cancel button returns 1. FileKit only accepts 1 as cancellation.
        // https://github.com/flatpak/xdg-desktop-portal-gtk/blob/1.15.1/src/filechooser.c
        // FileKit does not expose the response on its public Kotlin exception API.
        // Keep this version-specific JVM access here so dependency changes fail at compile time.
        Throwable cause = failure.getCause();
        return cause instanceof XdgPortalResponseException
                && ((XdgPortalResponseException) cause).getResponse$FileKit_filekit_dialogs() == 2;
    }
}
