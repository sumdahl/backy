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
    fun edit(f: (Settings) -> Settings) = c.edit(f)

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { AdaptiveTopBar("Settings", scroll) },
    ) { pad ->
        ReadableLazyColumn(pad, bottom = 32.dp) {
            item { SectionHeader("Backup") }
            item {
                SegmentGroup(listOfNotNull(
                    { SwitchRow("Back up new photos automatically", s.autoBackup, { v -> edit { it.copy(autoBackup = v) } },
                        "Off: backups only run when you tap Back up now", Icons.Outlined.Autorenew) },
                    if (s.autoBackup) {
                        { SwitchRow("Only while charging", s.autoChargingOnly, { v -> edit { it.copy(autoChargingOnly = v) } },
                            "Automatic backups wait until the phone is plugged in", Icons.Outlined.BatteryChargingFull) }
                    } else null,
                    { SwitchRow("Wi-Fi only", s.wifiOnly, { v -> edit { it.copy(wifiOnly = v) } }, "Wait for Wi-Fi instead of using mobile data", Icons.Outlined.Wifi) },
                    {
                        ChoiceRow("What to back up", MediaTypes.entries, s.mediaTypes,
                            { when (it) { MediaTypes.All -> "Everything"; MediaTypes.Photos -> "Photos"; MediaTypes.Videos -> "Videos" } }) { v -> edit { it.copy(mediaTypes = v) } }
                    },
                    {
                        ChoiceRow("Skip files larger than", listOf(0, 100, 500, 1024, 4096), s.maxFileMb,
                            { if (it == 0) "No limit" else if (it >= 1024) "${it / 1024} GB" else "$it MB" }) { v -> edit { it.copy(maxFileMb = v) } }
                    },
                    { SliderRow("Retries when Google is busy", s.retries, 0..5, { v -> edit { it.copy(retries = v) } }) },
                ))
            }

            item { SectionHeader("Quality & storage") }
            item {
                SegmentGroup(listOf(
                    { SwitchRow("Storage saver", s.saver, { v -> edit { it.copy(saver = v) } }, "Slightly lower quality, smaller files", Icons.Outlined.HighQuality) },
                    { SwitchRow("Count against my storage", s.useQuota, { v -> edit { it.copy(useQuota = v) } },
                        "Off uploads as a Pixel would. On behaves like any other phone.", Icons.Outlined.Storage) },
                ))
            }

            item { SectionHeader("Uploading") }
            item {
                SegmentGroup(listOf(
                    { SliderRow("Parallel uploads", s.threads, 1..8, { v -> edit { it.copy(threads = v) } }) },
                    { SwitchRow("Date from file name", s.dateFromFilename, { v -> edit { it.copy(dateFromFilename = v) } },
                        "Use IMG_20250101_… when the photo has no date inside", Icons.Outlined.DateRange) },
                    { SwitchRow("Re-upload duplicates", s.force, { v -> edit { it.copy(force = v) } },
                        "Send items again even if Google Photos already has them", Icons.Outlined.Refresh) },
                    { TextRow("Skip files matching", s.excludePattern, "Nothing skipped", { v -> edit { it.copy(excludePattern = v) } },
                        Icons.Outlined.FilterAlt, help = "A file-name pattern, e.g. *.gif or Screenshot_*") },
                    { SwitchRow("Try unsupported file types", s.allowUnsupported, { v -> edit { it.copy(allowUnsupported = v) } },
                        "Upload formats Google Photos may reject", Icons.Outlined.FileUpload) },
                ))
            }

            item { SectionHeader("Albums") }
            item {
                SegmentGroup(listOf(
                    { TextRow("Add uploads to album", s.albumName, "No album", { v -> edit { it.copy(albumName = v) } }, Icons.Outlined.PhotoAlbum,
                        help = "An album name (created if needed) or an album key.") },
                    { SwitchRow("One album per folder", s.albumPerFolder, { v -> edit { it.copy(albumPerFolder = v) } },
                        "Camera goes to “Camera”, WhatsApp Images to “WhatsApp Images”…") },
                ))
            }

            item { SectionHeader("Live & motion photos") }
            item {
                SegmentGroup(listOf(
                    { SwitchRow("Pair Live Photos", s.pairLive, { v -> edit { it.copy(pairLive = v) } },
                        "Upload an iPhone photo and its video as one item", Icons.Outlined.MotionPhotosOn) },
                    { SwitchRow("Skip incomplete Live Photos", s.skipIncompleteLive, { v -> edit { it.copy(skipIncompleteLive = v) } },
                        "Leave out a still whose video is missing") },
                    { SwitchRow("Add motion to existing photos", s.updateExistingToLive, { v -> edit { it.copy(updateExistingToLive = v) } },
                        "Attach the video when the still is already in Google Photos") },
                ))
            }

            item { SectionHeader("Network") }
            item {
                SegmentGroup(listOf(
                    { TextRow("Proxy", s.proxy, "Direct connection", { v -> edit { it.copy(proxy = v) } }, Icons.Outlined.Router,
                        help = "http://host:port or socks5://host:port") },
                ))
            }

            item { SectionHeader("Appearance") }
            item {
                SegmentGroup(listOf(
                    {
                        ChoiceRow("Theme", ThemeMode.entries, s.theme, { it.name }) { v -> edit { it.copy(theme = v) } }
                    },
                    { SwitchRow("Wallpaper colours", s.dynamicColor, { v -> edit { it.copy(dynamicColor = v) } }, "Match your phone's colours", Icons.Outlined.Palette) },
                    { SwitchRow("Pure black", s.pureBlack, { v -> edit { it.copy(pureBlack = v) } }, "Darker dark theme for OLED screens", Icons.Outlined.DarkMode) },
                ))
            }

            item { SectionHeader("Advanced") }
            item {
                SegmentGroup(listOf(
                    {
                        NavRow("Export settings", "Share them as JSON", Icons.Outlined.Share) {
                            val json = Engine.json.encodeToString(Settings.serializer(), s)
                            context.startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, json), "Export settings"))
                        }
                    },
                    { NavRow("Clear excluded items", "Let “Never back up” items back in", Icons.Outlined.Block) {
                        c.scope.launch { c.db.uploads().clearNever() }
                    } },
                    { NavRow("Reset upload history", "Forget what's been backed up; Google Photos keeps it", Icons.Outlined.DeleteForever) { confirmReset = true } },
                ))
            }

            item { SectionHeader("About") }
            item {
                SegmentGroup(listOf(
                    { NavRow("Backy", "Version 0.1.0 · uses the ptg upload core", Icons.Outlined.Info) {} },
                    { NavRow("Unofficial client",
                        "Talks to Google Photos' private mobile API. Google may change it, count uploads later, or restrict accounts.",
                        Icons.Outlined.WarningAmber) {} },
                ))
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
