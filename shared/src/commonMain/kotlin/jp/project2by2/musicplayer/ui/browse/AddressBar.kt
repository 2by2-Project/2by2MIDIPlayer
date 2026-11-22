package jp.project2by2.musicplayer.ui.browse

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import jp.project2by2.musicplayer.ui.player.playerString

/** One level of the address bar; [key] identifies the folder (or virtual location) it opens. */
data class AddressSegment(val key: String, val label: String)

/**
 * Browse's address bar on every platform: [folders] from the root to the open folder, followed by the
 * sample music when it is open. Hosts supply the folder levels and open a tapped folder.
 */
@Composable
fun LibraryAddressBar(
    folders: List<AddressSegment>,
    demoOpen: Boolean,
    onOpenFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val demo = playerString("folder_demo_name")
    val segments = folders + listOfNotNull(if (demoOpen) AddressSegment(DEMO_ADDRESS, demo) else null)
    AddressBar(segments, modifier = modifier, onSegmentClick = { segment ->
        if (segment.key != DEMO_ADDRESS) onOpenFolder(segment.key)
    })
}

private const val DEMO_ADDRESS = "address:demo"

/**
 * Explorer-style location bar below the app bar: one pill per level, separated by chevrons. The
 * current level (last) uses the accent color; tapping an ancestor opens it, so no back button is needed.
 */
@Composable
fun AddressBar(
    segments: List<AddressSegment>,
    onSegmentClick: (AddressSegment) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Deep paths overflow the width; keep the current level in view, also while the pane is resized.
    LaunchedEffect(segments) {
        if (segments.isEmpty()) return@LaunchedEffect
        listState.animateScrollToItem(segments.lastIndex)
        snapshotFlow { listState.layoutInfo.viewportSize.width }.collect { listState.scrollToItem(segments.lastIndex) }
    }
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(segments, key = { _, segment -> segment.key }) { index, segment ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (index > 0) Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.padding(horizontal = 2.dp).size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
                AddressPill(segment.label, current = index == segments.lastIndex) { onSegmentClick(segment) }
            }
        }
    }
}

@Composable
private fun AddressPill(label: String, current: Boolean, onClick: () -> Unit) {
    val colors = if (current) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = onClick,
        // Slimmer than a regular button; widthIn replaces the 58dp default minimum so "/" stays compact.
        modifier = Modifier.height(32.dp).widthIn(min = 40.dp),
        colors = colors,
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
    }
}
