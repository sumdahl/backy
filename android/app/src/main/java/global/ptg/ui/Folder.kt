package global.ptg.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import global.ptg.backup.Selection
import global.ptg.container
import global.ptg.data.FolderPrefs
import global.ptg.data.Settings

/** One folder: its own backup choices (overriding the defaults) and its photos. */
@Composable
fun FolderScreen(id: String, settings: Settings, onBack: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val lib by c.library.collectAsStateWithLifecycle()
    val accounts by c.accounts.collectAsStateWithLifecycle()
    val folder = lib.folders.firstOrNull { it.id == id }
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    val selected = remember { mutableListOf<Long>().toMutableStateList() }
    val grid = rememberComputed(Triple(lib, filter, id), GridData()) { (l, f, b) -> l.grid(f, b) }
    val p = folder?.prefs ?: FolderPrefs()
    fun set(f: (FolderPrefs) -> FolderPrefs) = c.edit { s -> s.copy(folders = s.folders + (id to f(s.folder(id, folder?.name.orEmpty())))) }

    Scaffold(topBar = {
        if (selected.isEmpty()) TopAppBar(
            title = { Text(folder?.name ?: "Folder") },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
        ) else SelectionBar(selected, grid.shown, settings)
    }) { pad ->
        PhotoGrid(lib, grid, selected, Modifier.padding(top = pad.calculateTopPadding()).fillMaxSize()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "settings") {
                Column {
                    folder?.let {
                        Text(
                            "${formatCount(it.done)} of ${formatCount(it.items)} backed up · ${formatBytes(it.bytes)}",
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        )
                    }
                    SegmentGroup(listOf(
                        { SwitchRow("Back up this folder", p.enabled, { on -> set { it.copy(enabled = on) } }) },
                        {
                            val options = listOf<String?>(null) + accounts.accounts.map { it.email }
                            AccountPicker(options, p.account, accounts.selected) { a -> set { it.copy(account = a) } }
                        },
                        {
                            TextRow("Album", p.album ?: "", if (settings.albumPerFolder) "One album named after the folder" else "Default (${settings.albumName.ifEmpty { "none" }})",
                                { a -> set { it.copy(album = a.ifEmpty { null }) } }, Icons.Outlined.PhotoAlbum,
                                help = "A name creates that album. To use an album you already have, paste its link (a share link or the address from photos.google.com). Leave empty for the default.")
                        },
                        {
                            ChoiceRow("Quality", listOf<Boolean?>(null, false, true), p.saver,
                                { when (it) { null -> "Default"; false -> "Original"; true -> "Saver" } }) { v -> set { it.copy(saver = v) } }
                        },
                    ))
                    Button(
                        onClick = { startBackup(context, settings, Selection.Folder(id)) },
                        modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = ButtonDefaults.MediumContainerHeight),
                    ) {
                        Icon(Icons.Outlined.CloudUpload, null)
                        Spacer(Modifier.size(8.dp))
                        Text("Back up this folder now")
                    }
                    FilterRow(filter) { filter = it; selected.clear() }
                }
            }
        }
    }
}

@Composable
private fun AccountPicker(options: List<String?>, current: String?, defaultAccount: String, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    NavRow("Account", current ?: "Default (${defaultAccount.ifEmpty { "none" }})", Icons.Outlined.AccountCircle) { open = true }
    if (open) androidx.compose.material3.AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Back up to") },
        text = {
            Column {
                options.forEach { o ->
                    NavRow(o ?: "Default account", trailing = if (o == current) ({ Icon(Icons.Outlined.Check, "Selected", tint = MaterialTheme.colorScheme.primary) }) else null) {
                        onPick(o); open = false
                    }
                }
            }
        },
        confirmButton = {},
    )
}
