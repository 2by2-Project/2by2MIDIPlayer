package jp.project2by2.musicplayer.desktop;

import io.github.vinceglb.filekit.dialogs.FileKitDialogException;
import io.github.vinceglb.filekit.dialogs.FileKitPickerException;
import io.github.vinceglb.filekit.dialogs.platform.xdg.XdgFilePickerPortalKt;
import io.github.vinceglb.filekit.dialogs.platform.xdg.XdgPortalResponseException;
import java.io.IOException;
import java.util.Collections;
import org.freedesktop.dbus.exceptions.DBusExecutionException;
import org.junit.Test;
import static org.junit.Assert.*;

public class FileKitPortalCompatibilityTest {
    static FileKitPickerException closedPicker() {
        XdgPortalResponseException ended = assertThrows(XdgPortalResponseException.class,
                () -> XdgFilePickerPortalKt.resolveXdgPortalResponse(2, Collections.emptyMap()));
        return new FileKitPickerException("The XDG file picker could not complete the operation.", ended);
    }

    @Test public void titleBarCloseFromGtkIsRecognizedForAllDialogTypes() {
        // Reproduce the response from GTK 1.15.1 through the actual FileKit parser.
        FileKitPickerException closed = closedPicker();
        Throwable ended = closed.getCause();
        assertTrue(FileKitPortalCompatibility.isDismissal(closed));
        assertTrue(FileKitPortalCompatibility.isDismissal(new FileKitDialogException(
                "The XDG directory picker could not complete the operation.", ended)));
        assertTrue(FileKitPortalCompatibility.isDismissal(new FileKitDialogException(
                "The XDG file saver could not complete the operation.", ended)));
    }

    @Test public void ordinaryCancelAlreadyReturnsNullInFileKit() {
        assertNull(XdgFilePickerPortalKt.resolveXdgPortalResponse(1, Collections.emptyMap()));
    }

    @Test public void actualFailuresAreNotSilencedEvenWithTheSameMessage() {
        String message = "The XDG file picker could not complete the operation.";
        assertFalse(FileKitPortalCompatibility.isDismissal(new FileKitPickerException(message)));
        assertFalse(FileKitPortalCompatibility.isDismissal(new FileKitPickerException(message,
                new DBusExecutionException("Portal service unavailable"))));
        assertFalse(FileKitPortalCompatibility.isDismissal(new FileKitPickerException(message,
                new IOException("Permission denied"))));
        assertFalse(FileKitPortalCompatibility.isDismissal(new FileKitPickerException(message,
                new XdgPortalResponseException(99))));
        assertFalse(FileKitPortalCompatibility.isDismissal(new FileKitPickerException(message,
                new RuntimeException("XDG portal request failed with response 2"))));
    }
}
