package jp.project2by2.musicplayer.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import jp.project2by2.musicplayer.resources.Res
import jp.project2by2.musicplayer.resources.logo_image

@Composable
fun PlayerLogo() {
    Image(painterResource(Res.drawable.logo_image), playerString("app_logo"),
        modifier = Modifier.height(48.dp), colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary))
}
