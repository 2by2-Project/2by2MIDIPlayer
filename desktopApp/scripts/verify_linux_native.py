"""Run after :desktopApp:test to check the same PCM fixture against Linux binaries.

    python3 desktopApp/scripts/verify_linux_native.py

No audio device, package installation or external SoundFont is required.
"""
import ctypes as c
from pathlib import Path

root = Path(__file__).resolve().parents[2]
native = root / "proprietary/linux-x64"
fixtures = root / "desktopApp/build/native-fixtures"
bass = c.CDLL(str(native / "libbass.so"), mode=c.RTLD_GLOBAL)
midi = c.CDLL(str(native / "libbassmidi.so"))


def bind(lib, name, result, *args):
    fn = getattr(lib, name)
    fn.restype = result
    fn.argtypes = args
    return fn


init = bind(bass, "BASS_Init", c.c_int, c.c_int, c.c_uint, c.c_uint, c.c_void_p, c.c_void_p)
free = bind(bass, "BASS_Free", c.c_int)
free_stream = bind(bass, "BASS_StreamFree", c.c_int, c.c_uint)
error = bind(bass, "BASS_ErrorGetCode", c.c_int)
font_init = bind(midi, "BASS_MIDI_FontInit", c.c_uint, c.c_char_p, c.c_uint)
font_free = bind(midi, "BASS_MIDI_FontFree", c.c_int, c.c_uint)
create = bind(midi, "BASS_MIDI_StreamCreateFile", c.c_uint, c.c_uint, c.c_char_p, c.c_uint64, c.c_uint64, c.c_uint, c.c_uint)
set_fonts = bind(midi, "BASS_MIDI_StreamSetFonts", c.c_int, c.c_uint, c.c_void_p, c.c_uint)
get_data = bind(bass, "BASS_ChannelGetData", c.c_uint, c.c_uint, c.c_void_p, c.c_uint)
set_pos = bind(bass, "BASS_ChannelSetPosition", c.c_int, c.c_uint, c.c_uint64, c.c_uint)
get_pos = bind(bass, "BASS_ChannelGetPosition", c.c_uint64, c.c_uint, c.c_uint)
seconds = bind(bass, "BASS_ChannelBytes2Seconds", c.c_double, c.c_uint, c.c_uint64)

assert init(0, 44100, 0, None, None), error()
font = stream = 0
try:
    font = font_init(str(fixtures / "テスト.sf2").encode(), 0)
    assert font, error()
    stream = create(0, str(fixtures / "テスト.mid").encode(), 0, 0, 0x200000 | 0x8000, 44100)
    assert stream, error()
    mapping = (c.c_int32 * 3)(font, -1, 0)
    assert set_fonts(stream, mapping, 1), error()
    pcm = c.create_string_buffer(32768)
    count = get_data(stream, pcm, len(pcm))
    assert 0 < count <= len(pcm), error()
    assert any(pcm.raw[:count]), "Only silence rendered"
    assert set_pos(stream, 240, 2), error()
    position = seconds(stream, get_pos(stream, 0))
    assert 0.24 <= position <= 0.26, position
    print(f"Linux x64: UTF-8 paths, SoundFont, {count} PCM bytes and tick seek passed")
finally:
    if stream:
        free_stream(stream)
    if font:
        font_free(font)
    free()
