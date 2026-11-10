package global.ptg.ui

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import global.ptg.backup.Backup
import global.ptg.backup.Selection
import global.ptg.container
import global.ptg.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Share → Back up with Backy" from any app. Pick the account and an optional album, then the
 * same backup job runs. Gallery items upload in place; anything else is copied to the cache
 * first (the share grant ends when this screen closes) and deleted after.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent)
        if (uris.isEmpty()) { finish(); return }
        setContent {
            val settings by container.settings.data.collectAsStateWithLifecycle(initialValue = Settings())
            PtgTheme(settings) { ShareSheet(uris, settings, onDone = ::finish) }
        }
    }

    private fun sharedUris(i: Intent): List<Uri> = when (i.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }
}

@Composable
private fun ShareSheet(uris: List<Uri>, settings: Settings, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val c = context.container
    val accounts by c.accounts.collectAsStateWithLifecycle()
    val run by Backup.state.collectAsStateWithLifecycle()
    var account by remember(accounts) { mutableStateOf(accounts.selected) }
    var album by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDone, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Back up ${plural(uris.size, "item")}", style = MaterialTheme.typography.headlineSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(uris.take(30)) { u ->
                    AsyncImage(u, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(84.dp).clip(RoundedCornerShape(16.dp)))
                }
            }
            when {
                accounts.accounts.isEmpty() -> Text("Open Backy and sign in first.", color = MaterialTheme.colorScheme.error)
                run.active -> Text("A backup is already running. Try again when it's done.", color = MaterialTheme.colorScheme.error)
                else -> {
                    Text("Account", style = MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
                        items(accounts.accounts) { a ->
                            FilterChip(selected = a.email == account, onClick = { account = a.email },
                                label = { Text(a.email) }, leadingIcon = { Avatar(a.email, 20.dp) })
                        }
                    }
                    OutlinedTextField(album, { album = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Album (optional)") }, leadingIcon = { Icon(Icons.Outlined.PhotoAlbum, null) })
                }
            }
            Button(
                enabled = !busy && !run.active && account.isNotEmpty(),
                onClick = {
                    busy = true
                    scope.launch {
                        Backup.start(context, toSelection(context, uris, account, album), settings.wifiOnly)
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
            ) {
                if (busy) ContainedLoadingIndicator(Modifier.size(24.dp)) else {
                    Icon(Icons.Outlined.CloudUpload, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Back up")
                }
            }
        }
    }
}

/** Gallery URIs become gallery items; anything else (or a mix) is copied so the job can read it later. */
private suspend fun toSelection(context: android.content.Context, uris: List<Uri>, account: String, album: String): Selection =
    withContext(Dispatchers.IO) {
        if (uris.all { it.authority == "media" }) {
            Selection.Items(uris.map { ContentUris.parseId(it) }.toSet(), account, album)
        } else {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val paths = uris.mapIndexedNotNull { i, u ->
                runCatching {
                    val name = context.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "shared-$i"
                    val out = File(dir, "${System.nanoTime()}-${name.replace('/', '_')}")
                    context.contentResolver.openInputStream(u)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
                    out.path
                }.getOrNull()
            }
            Selection.Files(paths, account, album)
        }
    }
