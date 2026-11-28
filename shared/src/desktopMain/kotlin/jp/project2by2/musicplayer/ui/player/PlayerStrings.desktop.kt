package jp.project2by2.musicplayer.ui.player
import androidx.compose.runtime.Composable
import java.util.Locale
@Composable actual fun playerString(key: String): String =
    (if (Locale.getDefault().language == "ja") japanesePlayerStrings else englishPlayerStrings)[key] ?: key
