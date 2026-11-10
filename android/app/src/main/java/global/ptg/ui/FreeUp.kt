package global.ptg.ui

import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import global.ptg.ItemState
import global.ptg.Library
import global.ptg.container
import global.ptg.data.MediaItem
import global.ptg.data.MediaTypes
import kotlinx.coroutines.launch

/** How old an item must be before Free up space offers to delete it. */
private val AGES = listOf(0, 30, 90, 365)
internal const val DAY = 86_400_000L

internal data class FreeUpPlan(
    val items: List<MediaItem> = emptyList(),
    val bytes: Long = 0,
    /** bucketId → (name, items, bytes), every folder holding something deletable. */
    val folders: List<Triple<String, String, Pair<Int, Long>>> = emptyList(),
)

internal fun plan(lib: Library, olderThanDays: Int, types: MediaTypes, excluded: Set<String>): FreeUpPlan {
    val cutoff = System.currentTimeMillis() - olderThanDays * DAY
    val eligible = lib.items.filter { item ->
        lib.state[item.id] == ItemState.Done &&
            (olderThanDays == 0 || (item.taken.takeIf { it > 0 } ?: (item.modified * 1000)) < cutoff) &&
            when (types) { MediaTypes.All -> true; MediaTypes.Photos -> !item.isVideo; MediaTypes.Videos -> item.isVideo }
    }
    val folders = eligible.groupBy { it.bucketId }.map { (id, list) ->
        Triple(id, list.first().bucket, list.size to list.sumOf { it.size })
    }.sortedByDescending { it.third.second }
    val chosen = eligible.filter { it.bucketId !in excluded }
    return FreeUpPlan(chosen, chosen.sumOf { it.size }, folders)
}

/**
 * Preview before deleting anything: only items Google Photos already has (and that haven't
 * changed since), narrowed by age, type and folder. Android asks for the final confirmation.
 */
@Composable
fun FreeUpScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val lib by c.library.collectAsStateWithLifecycle()
    var age by rememberSaveable { mutableIntStateOf(30) }
    var types by rememberSaveable { mutableStateOf(MediaTypes.All) }
    var excluded by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val p = rememberComputed(Triple(lib, age, types to excluded), FreeUpPlan()) { (l, a, te) -> plan(l, a, te.first, te.second) }

    // Deletes go through Android's own confirmation, in batches: one request with tens of
    // thousands of URIs would overflow the binder (TransactionTooLargeException).
    val queue = remember { ArrayDeque<List<android.net.Uri>>() }
    lateinit var next: () -> Unit
    val delete = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) next() else queue.clear()
        c.scope.launch { c.rescan() }
    }
    next = {
        queue.removeFirstOrNull()?.let { batch ->
            val req = MediaStore.createDeleteRequest(context.contentResolver, batch)
            delete.launch(IntentSenderRequest.Builder(req.intentSender).build())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Free up space") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    enabled = p.items.isNotEmpty(),
                    onClick = {
                        queue.clear()
                        queue.addAll(p.items.map { it.uri }.chunked(2_000))
                        next()
                    },
                    modifier = Modifier.fillMaxWidth().wrapContentWidth().widthIn(max = 720.dp).fillMaxWidth().navigationBarsPadding().padding(16.dp)
                        .heightIn(min = ButtonDefaults.MediumContainerHeight),
                ) {
                    Icon(Icons.Outlined.DeleteSweep, null)
                    Spacer(Modifier.size(8.dp))
                    Text(if (p.items.isEmpty()) "Nothing to delete" else "Delete ${plural(p.items.size, "item")} from this phone")
                }
            }
        },
    ) { pad ->
        ReadableLazyColumn(pad, bottom = pad.calculateBottomPadding() + 16.dp) {
            item(key = "hero") {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Text(formatBytes(p.bytes), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary)
                    Text("${plural(p.items.size, "item")} already safe in Google Photos. They stay there; only the copy on this phone goes.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (p.items.isNotEmpty()) item(key = "preview") {
                LazyRow(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(p.items.take(40), key = { it.id }) { item ->
                        AsyncImage(
                            ImageRequest.Builder(context).data(Thumb(item.uri)).crossfade(false).build(), item.name,
                            contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)),
                        )
                    }
                }
            }
            item(key = "age-h") { SectionHeader("Keep recent photos") }
            item(key = "age") {
                LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(AGES) { d ->
                        FilterChip(selected = age == d, onClick = { age = d }, label = {
                            Text(when (d) { 0 -> "Delete any age"; 365 -> "Older than 1 year"; else -> "Older than $d days" })
                        })
                    }
                }
            }
            item(key = "type-h") { SectionHeader("What to delete") }
            item(key = "type") {
                LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(MediaTypes.entries) { t ->
                        FilterChip(selected = types == t, onClick = { types = t }, label = {
                            Text(when (t) { MediaTypes.All -> "Photos and videos"; MediaTypes.Photos -> "Photos"; MediaTypes.Videos -> "Videos" })
                        })
                    }
                }
            }
            if (p.folders.isNotEmpty()) item(key = "folders-h") { SectionHeader("Folders") }
            items(p.folders, key = { "f" + it.first }) { (id, name, stats) ->
                val on = id !in excluded
                ListItem(
                    modifier = Modifier.padding(horizontal = 8.dp).toggleable(on, role = Role.Checkbox) {
                        excluded = if (it) excluded - id else excluded + id
                    },
                    headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${plural(stats.first, "item")} · ${formatBytes(stats.second)}") },
                    trailingContent = { Checkbox(on, onCheckedChange = null) },
                )
            }
        }
    }
}
