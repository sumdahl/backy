package global.ptg.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.map
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.clickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
import global.ptg.data.UploadRecord
import kotlinx.coroutines.launch

enum class Filter(val label: String) { All("All"), Pending("Not backed up"), Done("Backed up"), Failed("Failed"), Videos("Videos"), Excluded("Excluded") }

/** A filtered view of the gallery, grouped by day, ready to draw. */
@androidx.compose.runtime.Immutable
data class GridData(val shown: List<MediaItem> = emptyList(), val days: List<Pair<Long, List<MediaItem>>> = emptyList(), val ready: Boolean = false)

fun Library.grid(filter: Filter, bucketId: String? = null, query: String = ""): GridData {
    val shown = filtered(filter, bucketId, query)
    return GridData(shown, shown.groupBy { dayKey(it.taken) }.toList(), loaded)
}

/** [query] matches the file name or the folder name (see [Query]). */
fun Library.filtered(filter: Filter, bucketId: String? = null, query: String = ""): List<MediaItem> {
    val q = Query(query)
    return items.filter { item ->
    (bucketId == null || item.bucketId == bucketId) &&
        (q.isEmpty || q.matches(item.name, item.bucket)) &&
        when (filter) {
        Filter.All -> true
        Filter.Pending -> state[item.id] == ItemState.Pending
        Filter.Done -> state[item.id] == ItemState.Done
        Filter.Failed -> state[item.id] == ItemState.Failed
        Filter.Videos -> item.isVideo
        Filter.Excluded -> state[item.id] == ItemState.Never
    }
    }
}

@Composable
fun PhotosScreen(settings: Settings) {
    val lib by LocalContext.current.container.library.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    val selected = remember { mutableListOf<Long>().toMutableStateList() }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val grid = rememberComputed(Triple(lib, filter, query), GridData()) { (l, f, q) -> l.grid(f, query = q) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val closeSearch = { searching = false; query = "" }
    androidx.activity.compose.BackHandler(searching && selected.isEmpty(), closeSearch)

    Scaffold(
        topBar = {
            when {
                selected.isNotEmpty() -> SelectionBar(selected, grid.shown, settings)
                searching -> TopAppBar(
                    navigationIcon = { IconButton(closeSearch) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close search") } },
                    title = {
                        SearchField(query, { query = it }, "Search by name or folder", Modifier.padding(end = 16.dp), focus)
                        LaunchedEffect(Unit) { focus.requestFocus() }
                    },
                )
                else -> TopAppBar(
                    title = { Text("Photos") },
                    actions = { IconButton({ searching = true }) { Icon(Icons.Outlined.Search, "Search") } },
                )
            }
        },
    ) { pad ->
        Column(Modifier.padding(top = pad.calculateTopPadding()).fillMaxSize()) {
            FilterRow(filter) { filter = it; selected.clear() }
            PhotoGrid(lib, grid, selected, Modifier.weight(1f))
        }
    }
}

@Composable
fun FilterRow(filter: Filter, onChange: (Filter) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(Filter.entries) { f ->
            FilterChip(selected = f == filter, onClick = { onChange(f) }, label = { Text(f.label) })
        }
    }
}

@Composable
fun SelectionBar(selected: SnapshotStateList<Long>, shown: List<MediaItem>, settings: Settings) {
    val context = LocalContext.current
    val c = context.container
    TopAppBar(
        title = { Text("${formatCount(selected.size)} selected") },
        navigationIcon = { IconButton({ selected.clear() }) { Icon(Icons.Outlined.Close, "Clear selection") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        actions = {
            IconButton({ selected.clear(); selected.addAll(shown.map { it.id }) }) { Icon(Icons.Outlined.SelectAll, "Select all") }
            IconButton({
                val ids = selected.toList()
                c.scope.launch { markNever(c, shown.filter { it.id in ids }) }
                selected.clear()
            }) { Icon(Icons.Outlined.Block, "Never back up") }
            IconButton({
                startBackup(context, settings, Selection.Items(selected.toSet()))
                selected.clear()
            }) { Icon(Icons.Outlined.CloudUpload, "Back up selected") }
        },
    )
}

suspend fun markNever(c: global.ptg.AppContainer, items: List<MediaItem>) {
    val now = System.currentTimeMillis()
    c.db.uploads().put(items.map { UploadRecord(it.id, it.path, it.size, it.modified, "never", "", "", now) })
}

/** Day-grouped grid with status badges, long-press to multi-select, tap for details. */
@Composable
fun PhotoGrid(lib: Library, grid: GridData, selected: SnapshotStateList<Long>, modifier: Modifier = Modifier, header: (LazyGridScope.() -> Unit)? = null) {
    var detail by remember { mutableStateOf<MediaItem?>(null) }
    val c = LocalContext.current.container
    val cellDp by remember { c.settings.data.map { it.gridCellDp } }.collectAsStateWithLifecycle(104)
    val state = rememberLazyGridState()
    var dragSelecting by remember { mutableStateOf(false) }
    // Drag-select ranges follow what's on screen: grouped by day, which can differ from grid.shown.
    val order = remember(grid) { grid.days.flatMap { (_, list) -> list.map { it.id } } }
    val byId = remember(grid) { grid.shown.associateBy { it.id } }
    val label = {
        when (val key = state.layoutInfo.visibleItemsInfo.firstOrNull()?.key) {
            is Long -> byId[key]?.taken
            is String -> key.removePrefix("d").toLongOrNull()?.let { java.time.LocalDate.ofEpochDay(it).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }
            else -> null
        }?.let { monthLabel(it) }
    }

    Box(modifier) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(cellDp.dp),
        state = state,
        userScrollEnabled = !dragSelecting,
        modifier = Modifier.fillMaxSize()
            .pinchToResize(cellDp) { v -> c.edit { it.copy(gridCellDp = v) } }
            .dragSelect(state, order, selected) { dragSelecting = it },
        contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        header?.invoke(this)
        if (grid.ready && grid.shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "empty") { EmptyState() }
        grid.days.forEach { (day, list) ->
            item(key = "d$day", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                Text(dayLabel(list.first().taken), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 14.dp, top = 20.dp, bottom = 8.dp))
            }
            items(list, key = { it.id }, contentType = { "media" }) { item ->
                val isSelected = item.id in selected
                // Long-press (and drag) is handled by dragSelect on the grid.
                Cell(item, lib.state[item.id], isSelected, selecting = selected.isNotEmpty()) {
                    when {
                        selected.isEmpty() -> detail = item
                        isSelected -> selected.remove(item.id)
                        else -> selected.add(item.id)
                    }
                }
            }
        }
    }
    FastScroller(state, label, Modifier.align(Alignment.TopEnd))
    }
    detail?.let { DetailSheet(it, lib.state[it.id], lib.records[it.id]) { detail = null } }
}

@Composable
private fun Cell(item: MediaItem, state: ItemState?, selected: Boolean, selecting: Boolean, onClick: () -> Unit) {
    val inset by animateDpAsState(if (selected) 12.dp else 0.dp, label = "inset")
    val corner by animateDpAsState(if (selected) 16.dp else 4.dp, label = "corner")
    Box(
        Modifier.aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "${item.name}, ${state?.label ?: ""}" },
    ) {
        val context = LocalContext.current
        val request = remember(item.id) {
            ImageRequest.Builder(context).data(Thumb(item.uri)).crossfade(false).build()
        }
        AsyncImage(
            model = request, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().padding(inset).clip(RoundedCornerShape(corner)),
        )
        if (item.isVideo) Text(
            formatClock(item.durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
        )
        Badge(state, Modifier.align(Alignment.BottomEnd).padding(6.dp))
        if (selecting) Icon(
            if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
            tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent, CircleShape),
        )
    }
}

private val ItemState.label get() = when (this) {
    ItemState.Done -> "backed up"
    ItemState.Pending -> "waiting"
    ItemState.Failed -> "failed"
    ItemState.Never -> "excluded"
    ItemState.Skipped -> "skipped"
    ItemState.Off -> "folder off"
}

@Composable
private fun Badge(state: ItemState?, modifier: Modifier) {
    val (icon, tint) = when (state) {
        ItemState.Done -> Icons.Filled.CloudDone to Color.White
        ItemState.Failed -> Icons.Filled.Error to MaterialTheme.colorScheme.error
        ItemState.Never -> Icons.Outlined.Block to Color.White
        ItemState.Pending -> Icons.Outlined.CloudUpload to Color.White
        else -> return
    }
    Icon(icon, null, tint = tint, modifier = modifier.size(22.dp)
        .background(Color.Black.copy(alpha = 0.35f), CircleShape).padding(3.dp))
}

private fun formatClock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ShapeIcon(Icons.Outlined.PhotoLibrary, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, 96.dp)
        Spacer(Modifier.size(16.dp))
        Text("Nothing here", style = MaterialTheme.typography.titleMedium)
        Text("Try another filter", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DetailSheet(item: MediaItem, state: ItemState?, record: UploadRecord?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val settings by c.settings.data.collectAsStateWithLifecycle(Settings())
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            AsyncImage(item.uri, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10).clip(RoundedCornerShape(24.dp)))
            Spacer(Modifier.size(16.dp))
            Text(item.name, style = MaterialTheme.typography.titleLarge)
            Text("${item.bucket} · ${formatBytes(item.size)} · ${dayLabel(item.taken)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(12.dp))
            Text(
                when (state) {
                    ItemState.Done -> "In Google Photos" + (record?.account?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
                    ItemState.Failed -> "Couldn't back up: ${record?.detail.orEmpty()}"
                    ItemState.Never -> "You chose not to back this up"
                    ItemState.Skipped -> "Skipped: ${record?.detail.orEmpty()}"
                    ItemState.Pending -> "Waiting for the next backup"
                    else -> "Its folder isn't being backed up"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.size(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state != ItemState.Done) FilledTonalButton({
                    startBackup(context, settings, Selection.Items(setOf(item.id))); onDismiss()
                }) { Text("Back up this") }
                if (state == ItemState.Never) OutlinedButton({
                    c.scope.launch { c.db.uploads().delete(listOf(item.id)) }; onDismiss()
                }) { Text("Allow backup") }
                else if (state != ItemState.Done) OutlinedButton({
                    c.scope.launch { markNever(c, listOf(item)) }; onDismiss()
                }) { Text("Never back up") }
            }
        }
    }
}
