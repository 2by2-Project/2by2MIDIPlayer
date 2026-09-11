#!/bin/sh
# Run only when explicitly selected by the desktop user, never from package post-install.
set -eu

report() {
    printf '%s\n' "$1"
    if command -v zenity >/dev/null 2>&1; then
        zenity --info --title=2by2MIDIPlayer --text="$1" || true
    elif command -v kdialog >/dev/null 2>&1; then
        kdialog --title 2by2MIDIPlayer --msgbox "$1" || true
    elif command -v notify-send >/dev/null 2>&1; then
        notify-send 2by2MIDIPlayer "$1" || true
    fi
}

if [ "$(id -u)" -eq 0 ]; then
    report 'Run this action as your desktop user, without sudo.'
    exit 1
fi
if ! command -v xdg-mime >/dev/null 2>&1; then
    report 'Install xdg-utils, then try again.'
    exit 1
fi

desktop_id=project2by2-midiplayer-project2by2-midiplayer.desktop
for mime in audio/midi audio/x-midi; do
    if ! xdg-mime default "$desktop_id" "$mime"; then
        report 'Could not change the default player. Please use your desktop file manager instead.'
        exit 1
    fi
    if [ "$(xdg-mime query default "$mime")" != "$desktop_id" ]; then
        report 'Your desktop kept another default player. Please select 2by2MIDIPlayer in the file manager.'
        exit 1
    fi
done
report '2by2MIDIPlayer is now your default MIDI player. You can change this in your file manager at any time.'
