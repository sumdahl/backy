package global.ptg.ui

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MotionPhotosOn
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.foundation.lazy.LazyListScope
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import global.ptg.container
import global.ptg.data.Engine
import global.ptg.data.MediaTypes
import global.ptg.data.Settings
import global.ptg.data.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(s: Settings) {
    val context = LocalContext.current
    val c = context.container
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var confirmReset by remember { mutableStateOf(false) }
    val snackbar = LocalSnackbar.current
    fun edit(f: (Settings) -> Settings) = c.edit(f)

    var searching by rememberSaveable { mutableStateOf(false) }
    var queryText by rememberSaveable { mutableStateOf("") }
    val query = Query(queryText)
    val focus = remember { FocusRequester() }
    val closeSearch = { searching = false; queryText = "" }
    BackHandler(searching, closeSearch)
    var matches = 0

    /** A titled group of rows; while searching, only rows (or whole sections) that match. */
    fun LazyListScope.section(title: String, rows: List<Pref>) {
        val shown = if (query.matches(title)) rows else rows.filter { query.matches(it.text) }
        if (shown.isEmpty()) return
        matches += shown.size
        item(key = "h-$title") { SectionHeader(title) }
        item(key = "s-$title") { SegmentGroup(shown.map { it.content }) }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            if (searching) TopAppBar(
                navigationIcon = { IconButton(closeSearch) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close search") } },
                title = {
                    SearchField(queryText, { queryText = it }, "Search settings", Modifier.padding(end = 16.dp), focus)
                    LaunchedEffect(Unit) { focus.requestFocus() }
                },
            ) else AdaptiveTopBar("Settings", scroll, actions = {
                IconButton({ searching = true }) { Icon(Icons.Outlined.Search, "Search settings") }
            })
        },
    ) { pad ->
        ReadableLazyColumn(pad, bottom = 32.dp) {
            matches = 0
            section("Backup", listOfNotNull(
                    Pref("Back up new photos automatically auto backup sync schedule background Off: backups only run when you tap Back up now") { SwitchRow("Back up new photos automatically", s.autoBackup, { v -> edit { it.copy(autoBackup = v) } },
                        "Off: backups only run when you tap Back up now", Icons.Outlined.Autorenew) },
                    if (s.autoBackup) {
                        Pref("Only while charging Automatic backups wait until the phone is plugged in") { SwitchRow("Only while charging", s.autoChargingOnly, { v -> edit { it.copy(autoChargingOnly = v) } },
                            "Automatic backups wait until the phone is plugged in", Icons.Outlined.BatteryChargingFull) }
                    } else null,
                    Pref("Wi-Fi only wifi mobile data cellular metered Wait for Wi-Fi instead of using mobile data") { SwitchRow("Wi-Fi only", s.wifiOnly, { v -> edit { it.copy(wifiOnly = v) } }, "Wait for Wi-Fi instead of using mobile data", Icons.Outlined.Wifi) },
                    Pref("What to back up Everything Photos Videos") {
                        ChoiceRow("What to back up", MediaTypes.entries, s.mediaTypes,
                            { when (it) { MediaTypes.All -> "Everything"; MediaTypes.Photos -> "Photos"; MediaTypes.Videos -> "Videos" } }) { v -> edit { it.copy(mediaTypes = v) } }
                    },
                    Pref("Skip files larger than No limit MB") {
                        ChoiceRow("Skip files larger than", listOf(0, 100, 500, 1024, 4096), s.maxFileMb,
                            { if (it == 0) "No limit" else if (it >= 1024) "${it / 1024} GB" else "$it MB" }) { v -> edit { it.copy(maxFileMb = v) } }
                    },
                    Pref("Retries when Google is busy retry errors") { SliderRow("Retries when Google is busy", s.retries, 0..5, { v -> edit { it.copy(retries = v) } }) },
                ))

            section("Quality & storage", listOf(
                    Pref("Storage saver quality compress high original Slightly lower quality, smaller files") { SwitchRow("Storage saver", s.saver, { v -> edit { it.copy(saver = v) } }, "Slightly lower quality, smaller files", Icons.Outlined.HighQuality) },
                    Pref("Count against my storage quota pixel free unlimited Off uploads as a Pixel would. On behaves like any other phone.") { SwitchRow("Count against my storage", s.useQuota, { v -> edit { it.copy(useQuota = v) } },
                        "Off uploads as a Pixel would. On behaves like any other phone.", Icons.Outlined.Storage) },
                ))

            section("Uploading", listOf(
                    Pref("Parallel uploads threads speed faster") { SliderRow("Parallel uploads", s.threads, 1..8, { v -> edit { it.copy(threads = v) } }) },
                    Pref("Date from file name Use IMG_20250101_… when the photo has no date inside") { SwitchRow("Date from file name", s.dateFromFilename, { v -> edit { it.copy(dateFromFilename = v) } },
                        "Use IMG_20250101_… when the photo has no date inside", Icons.Outlined.DateRange) },
                    Pref("Re-upload duplicates Send items again even if Google Photos already has them") { SwitchRow("Re-upload duplicates", s.force, { v -> edit { it.copy(force = v) } },
                        "Send items again even if Google Photos already has them", Icons.Outlined.Refresh) },
                    Pref("Skip files matching Nothing skipped A file-name pattern, e.g. *.gif or Screenshot_*") { TextRow("Skip files matching", s.excludePattern, "Nothing skipped", { v -> edit { it.copy(excludePattern = v) } },
                        Icons.Outlined.FilterAlt, help = "A file-name pattern, e.g. *.gif or Screenshot_*") },
                    Pref("Try unsupported file types Upload formats Google Photos may reject") { SwitchRow("Try unsupported file types", s.allowUnsupported, { v -> edit { it.copy(allowUnsupported = v) } },
                        "Upload formats Google Photos may reject", Icons.Outlined.FileUpload) },
                ))

            section("Albums", listOf(
                    Pref("Add uploads to album No album link existing") { TextRow("Add uploads to album", s.albumName, "No album", { v -> edit { it.copy(albumName = v) } }, Icons.Outlined.PhotoAlbum,
                        help = "A name creates that album. To use an album you already have, paste its link from photos.google.com.") },
                    Pref("One album per folder Camera goes to “Camera”, WhatsApp Images to “WhatsApp Images”…") { SwitchRow("One album per folder", s.albumPerFolder, { v -> edit { it.copy(albumPerFolder = v) } },
                        "Camera goes to “Camera”, WhatsApp Images to “WhatsApp Images”…") },
                ))

            section("Live & motion photos", listOf(
                    Pref("Pair Live Photos Upload an iPhone photo and its video as one item") { SwitchRow("Pair Live Photos", s.pairLive, { v -> edit { it.copy(pairLive = v) } },
                        "Upload an iPhone photo and its video as one item", Icons.Outlined.MotionPhotosOn) },
                    Pref("Skip incomplete Live Photos Leave out a still whose video is missing") { SwitchRow("Skip incomplete Live Photos", s.skipIncompleteLive, { v -> edit { it.copy(skipIncompleteLive = v) } },
                        "Leave out a still whose video is missing") },
                    Pref("Add motion to existing photos Attach the video when the still is already in Google Photos") { SwitchRow("Add motion to existing photos", s.updateExistingToLive, { v -> edit { it.copy(updateExistingToLive = v) } },
                        "Attach the video when the still is already in Google Photos") },
                ))

            section("Network", listOf(
                    Pref("Proxy socks http vpn network Direct connection http://host:port or socks5://host:port") { TextRow("Proxy", s.proxy, "Direct connection", { v -> edit { it.copy(proxy = v) } }, Icons.Outlined.Router,
                        help = "http://host:port or socks5://host:port") },
                ))

            section("Privacy", listOfNotNull(
                    Pref("App lock biometric fingerprint face pin password security privacy Set up a screen lock on your phone first Turn on app lock Turn off app lock Ask for your fingerprint, face or screen lock to open Backy") {
                        SwitchRow("App lock", s.appLock, { on ->
                            val activity = context as? android.app.Activity
                            when {
                                activity == null -> Unit
                                !AppLock.available(context) -> android.widget.Toast.makeText(context,
                                    "Set up a screen lock on your phone first", android.widget.Toast.LENGTH_LONG).show()
                                // Confirm it's you both ways, so someone holding the unlocked app can't switch it off.
                                else -> AppLock.authenticate(activity, if (on) "Turn on app lock" else "Turn off app lock") {
                                    edit { it.copy(appLock = on) }
                                }
                            }
                        }, "Ask for your fingerprint, face or screen lock to open Backy", Icons.Outlined.Fingerprint)
                    },
                    if (s.appLock) {
                        Pref("Lock after Immediately 1 minute 5 minutes 15 minutes") {
                            ChoiceRow("Lock after", listOf(0, 60, 300, 900), s.lockAfterSec,
                                { when (it) { 0 -> "Immediately"; 60 -> "1 minute"; 300 -> "5 minutes"; else -> "15 minutes" } }) { v ->
                                edit { it.copy(lockAfterSec = v) }
                            }
                        }
                    } else null,
                ))

            section("Appearance", listOf(
                    Pref("Theme dark light mode night system") {
                        ChoiceRow("Theme", ThemeMode.entries, s.theme, { it.name }) { v -> edit { it.copy(theme = v) } }
                    },
                    Pref("Wallpaper colours Match your phone's colours") { SwitchRow("Wallpaper colours", s.dynamicColor, { v -> edit { it.copy(dynamicColor = v) } }, "Match your phone's colours", Icons.Outlined.Palette) },
                    Pref("Pure black Darker dark theme for OLED screens") { SwitchRow("Pure black", s.pureBlack, { v -> edit { it.copy(pureBlack = v) } }, "Darker dark theme for OLED screens", Icons.Outlined.DarkMode) },
                ))

            section("Advanced", listOf(
                    Pref("Export settings Share them as JSON backup") {
                        NavRow("Export settings", "Share them as JSON", Icons.Outlined.Share) {
                            val json = Engine.json.encodeToString(Settings.serializer(), s)
                            context.startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, json), "Export settings"))
                        }
                    },
                    Pref("Clear excluded items Let “Never back up” items back in") { NavRow("Clear excluded items", "Let “Never back up” items back in", Icons.Outlined.Block) {
                        c.scope.launch {
                            val before = c.db.uploads().never()
                            c.db.uploads().clearNever()
                            if (before.isNotEmpty()) undoable(c.scope, snackbar, "${plural(before.size, "item")} can be backed up again") {
                                c.db.uploads().put(before)
                            }
                        }
                    } },
                    Pref("Reset upload history clear delete data Forget what's been backed up; Google Photos keeps it") { NavRow("Reset upload history", "Forget what's been backed up; Google Photos keeps it", Icons.Outlined.DeleteForever) { confirmReset = true } },
                ))

            section("About", listOf(
                    Pref("Backy Version 0.1.0 · uses the ptg upload core") { NavRow("Backy", "Version 0.1.0 · uses the ptg upload core", Icons.Outlined.Info) {} },
                    Pref("Unofficial client Talks to Google Photos' private mobile API. Google may change it, count uploads later, or restrict accounts.") { NavRow("Unofficial client",
                        "Talks to Google Photos' private mobile API. Google may change it, count uploads later, or restrict accounts.",
                        Icons.Outlined.WarningAmber) {} },
                ))
            if (!query.isEmpty && matches == 0) item(key = "none") {
                Text("No settings match “${queryText.trim()}”", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp))
            }
        }
    }

    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset upload history?") },
        text = { Text("The next backup checks every item again. Google Photos recognises what it already has, so nothing is duplicated.") },
        confirmButton = { TextButton({ c.scope.launch { c.db.clearAllTables() }; confirmReset = false }) { Text("Reset") } },
        dismissButton = { TextButton({ confirmReset = false }) { Text("Cancel") } },
    )
}

/** One settings row and the words it can be found by. */
class Pref(val text: String, val content: @Composable () -> Unit)
