package jp.project2by2.musicplayer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal actual fun decodeWithCharsetOrNull(data: ByteArray, charset: String): String? = try {
    Charset.forName(charset).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
} catch (_: CharacterCodingException) { null }

internal actual fun normalizeMidiSearch(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
    .lowercase().replace(Regex("[\\p{P}\\p{S}\\s]+"), " ").trim()
