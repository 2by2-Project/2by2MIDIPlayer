package jp.project2by2.musicplayer.ui.player
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
@Composable actual fun playerString(key: String): String {
    LocalConfiguration.current
    val context = LocalContext.current
    val id = context.resources.getIdentifier(key, "string", context.packageName)
    return if (id != 0) context.getString(id) else key
}
