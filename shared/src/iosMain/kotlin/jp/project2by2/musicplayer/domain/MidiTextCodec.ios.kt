package jp.project2by2.musicplayer

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFStringConvertEncodingToNSStringEncoding
import platform.CoreFoundation.kCFStringEncodingDOSJapanese
import platform.CoreFoundation.kCFStringEncodingEUC_JP
import platform.CoreFoundation.kCFStringEncodingISOLatin1
import platform.CoreFoundation.kCFStringEncodingISO_2022_JP
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.Foundation.precomposedStringWithCompatibilityMapping

/** The JVM charset names used by the shared metadata reader, mapped to Core Foundation encodings. */
@OptIn(ExperimentalForeignApi::class)
private fun foundationEncoding(charset: String): ULong? = when (charset.uppercase()) {
    "UTF-8" -> kCFStringEncodingUTF8
    // MS932 is Windows-31J, Core Foundation's DOS Japanese code page (932).
    "MS932", "WINDOWS-31J", "SHIFT_JIS" -> kCFStringEncodingDOSJapanese.toUInt()
    "EUC-JP" -> kCFStringEncodingEUC_JP.toUInt()
    "ISO-2022-JP" -> kCFStringEncodingISO_2022_JP.toUInt()
    "ISO-8859-1" -> kCFStringEncodingISOLatin1.toUInt()
    else -> null
}?.let { CFStringConvertEncodingToNSStringEncoding(it) }

/** Strict decoding like the JVM's REPORT action: invalid input returns null instead of replacement text. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun decodeWithCharsetOrNull(data: ByteArray, charset: String): String? {
    val encoding = foundationEncoding(charset) ?: return null
    val bytes = if (data.isEmpty()) NSData() else data.usePinned { NSData.dataWithBytes(it.addressOf(0), data.size.toULong()) }
    return NSString.create(data = bytes, encoding = encoding)?.toString()
}

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun normalizeMidiSearch(text: String): String = (text as NSString).precomposedStringWithCompatibilityMapping
    .lowercase().replace(Regex("[\\p{P}\\p{S}\\s]+"), " ").trim()
