package global.ptg.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import global.ptg.ItemState
import global.ptg.Library
import global.ptg.backup.Selection
import global.ptg.container
import global.ptg.data.MediaItem
import global.ptg.data.Settings
import kotlinx.coroutines.launch

/**
 * Full-screen viewer over [items] in grid order, starting at [start]. Swipe between items,
 * double-tap or pinch to zoom, tap to hide the controls. The panel shows where the item stands
 * with Google Photos and offers the same actions as the grid.
 */
@Composable
fun Viewer(items: List<MediaItem>, start: Int, lib: Library, onDismiss: () -> Unit) {
    if (items.isEmpty()) return onDismiss()
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Light icons over the black background, whatever the app theme.
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { w ->
                WindowCompat.getInsetsController(w, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
        val pager = rememberPagerState(start.coerceIn(0, items.lastIndex)) { items.size }
        var chrome by remember { mutableStateOf(true) }
        val item = items[pager.currentPage.coerceIn(0, items.lastIndex)]
        val context = LocalContext.current

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { items[it].id }) { page ->
                Page(items[page], onTap = { chrome = !chrome })
            }

            AnimatedVisibility(chrome, Modifier.align(Alignment.TopCenter), enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
                Row(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .statusBarsPadding().padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onDismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close", tint = Color.White) }
                    Text("${pager.currentPage + 1} of ${items.size}", color = Color.White, modifier = Modifier.weight(1f))
                    IconButton({
                        context.startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, if (item.isVideo) "video/*" else "image/*")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                    }) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, "Open with", tint = Color.White) }
                }
            }

            AnimatedVisibility(chrome, Modifier.align(Alignment.BottomCenter), enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
                Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))) {
                    Info(item, lib, Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 48.dp, bottom = 16.dp))
                }
            }
        }
    }
}

/** One item: zoomable photo, or a video's poster with a play button that hands off to a player. */
@Composable
private fun Page(item: MediaItem, onTap: () -> Unit) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoom = rememberTransformableState { z, pan, _ ->
        scale = (scale * z).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f },
                )
            }
            // Only pan while zoomed in, so a one-finger swipe at 1x still changes page.
            .transformable(zoom, canPan = { scale > 1f }),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            // Videos show their poster frame; photos load the real image, fitted to the screen.
            ImageRequest.Builder(context).data(if (item.isVideo) Thumb(item.uri) else item.uri).crossfade(150).build(),
            item.name, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            },
        )
        if (item.isVideo) IconButton(
            {
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, "video/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            },
            Modifier.size(72.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape),
        ) { Icon(Icons.Filled.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(40.dp)) }
    }
}

@Composable
private fun Info(item: MediaItem, lib: Library, modifier: Modifier) {
    val context = LocalContext.current
    val c = context.container
    val settings by c.settings.data.collectAsStateWithLifecycle(Settings())
    val state = lib.state[item.id]
    val record = lib.records[item.id]
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(item.name, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${item.bucket} · ${formatBytes(item.size)} · ${dayLabel(item.taken)}", color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.bodyMedium)
        Text(
            when (state) {
                ItemState.Done -> "✓ In Google Photos" + (record?.account?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
                ItemState.Failed -> "Couldn't back up: ${record?.detail.orEmpty()}"
                ItemState.Never -> "You chose not to back this up"
                ItemState.Skipped -> "Skipped: ${record?.detail.orEmpty()}"
                ItemState.Pending -> "Waiting for the next backup"
                else -> "Its folder isn't being backed up"
            },
            color = if (state == ItemState.Failed) MaterialTheme.colorScheme.error else Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (state != ItemState.Done) {
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton({ startBackup(context, settings, Selection.Items(setOf(item.id))) }) { Text("Back up this") }
                if (state == ItemState.Never) OutlinedButton({ c.scope.launch { c.db.uploads().delete(listOf(item.id)) } }) {
                    Text("Allow backup", color = Color.White)
                } else OutlinedButton({ c.scope.launch { markNever(c, listOf(item)) } }) {
                    Text("Never back up", color = Color.White)
                }
            }
        }
    }
}
