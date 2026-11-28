package jp.project2by2.musicplayer.ui.player

import androidx.compose.runtime.Composable
import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

/** The first preferred language decides, as iOS does for localized apps ("ja", "ja-JP"). */
internal val iosUsesJapanese: Boolean by lazy {
    (NSLocale.preferredLanguages.firstOrNull() as? String)?.startsWith("ja") == true
}

@Composable actual fun playerString(key: String): String =
    (if (iosUsesJapanese) japanesePlayerStrings else englishPlayerStrings)[key] ?: key
