package global.ptg.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import global.ptg.AppContainer
import global.ptg.backup.Backup
import global.ptg.backup.Phase
import global.ptg.backup.Selection
import global.ptg.container
import global.ptg.data.Settings
import kotlinx.coroutines.launch

sealed interface Route {
    data object Main : Route
    data object Login : Route
    data object Activity : Route
    data object Stats : Route
    data class Folder(val id: String) : Route
}

enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Home("Backup", Icons.Outlined.Backup, Icons.Filled.Backup),
    Photos("Photos", Icons.Outlined.PhotoLibrary, Icons.Filled.PhotoLibrary),
    Accounts("Accounts", Icons.Outlined.AccountCircle, Icons.Filled.AccountCircle),
    Settings("Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
}

enum class Access { Full, Partial, None }

fun mediaAccess(context: Context): Access {
    fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    return when {
        has(Manifest.permission.READ_MEDIA_IMAGES) || has(Manifest.permission.READ_MEDIA_VIDEO) -> Access.Full
        has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.Partial
        else -> Access.None
    }
}

val mediaPermissions = arrayOf(
    Manifest.permission.READ_MEDIA_IMAGES,
    Manifest.permission.READ_MEDIA_VIDEO,
    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    Manifest.permission.ACCESS_MEDIA_LOCATION,
    Manifest.permission.POST_NOTIFICATIONS,
)

/** Settings edits are fire-and-forget; DataStore serialises them. */
fun AppContainer.edit(change: (Settings) -> Settings) {
    scope.launch { settings.updateData(change) }
}

/** Starts a backup, after asking for notifications if needed (the job must show one). */
fun startBackup(context: Context, settings: Settings, selection: Selection = Selection.All) =
    Backup.start(context, selection, settings.wifiOnly)

@Composable
fun App(settings: Settings) {
    val context = LocalContext.current
    val c = context.container
    var access by remember { mutableStateOf(mediaAccess(context)) }
    var resumes by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = mediaAccess(context)
    }

    LifecycleResumeEffect(Unit) {
        access = mediaAccess(context)
        resumes++
        onPauseOrDispose { }
    }

    if (access == Access.None || !settings.onboarded) {
        Onboarding(
            hasAccess = access != Access.None,
            onAllow = { ask.launch(mediaPermissions) },
            onDone = { c.edit { it.copy(onboarded = true) } },
        )
        return
    }

    // A fresh look at the gallery every time the app comes back, and after each run.
    val run by Backup.state.collectAsStateWithLifecycle()
    LaunchedEffect(resumes, access) { c.rescan() }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(run.phase) {
        if (run.phase == Phase.Finished && run.total > 0) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        if (run.phase == Phase.Error) haptics.performHapticFeedback(HapticFeedbackType.Reject)
    }

    val backStack = remember { mutableStateListOf<Route>(Route.Main) }
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val go: (Route) -> Unit = { backStack.add(it) }
    val back: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

    NavDisplay(
        backStack = backStack,
        onBack = back,
        transitionSpec = { sharedAxis(forward = true) },
        popTransitionSpec = { sharedAxis(forward = false) },
        predictivePopTransitionSpec = { _ -> sharedAxis(forward = false) },
        entryProvider = entryProvider {
            entry<Route.Main> {
                NavigationSuiteScaffold(
                    navigationSuiteItems = {
                        Tab.entries.forEach { t ->
                            item(
                                selected = t == tab,
                                onClick = { tab = t },
                                icon = { Icon(if (t == tab) t.selectedIcon else t.icon, null) },
                                label = { Text(t.label) },
                            )
                        }
                    },
                ) {
                    when (tab) {
                        Tab.Home -> HomeScreen(settings, access, go, onAllowAll = { ask.launch(mediaPermissions) }, onTab = { tab = it })
                        Tab.Photos -> PhotosScreen(settings)
                        Tab.Accounts -> AccountsScreen(go)
                        Tab.Settings -> SettingsScreen(settings)
                    }
                }
            }
            entry<Route.Login> { LoginScreen(onDone = back) }
            entry<Route.Activity> { ActivityScreen(settings, onBack = back) }
            entry<Route.Stats> { StatsScreen(onBack = back) }
            entry<Route.Folder> { FolderScreen(it.id, settings, onBack = back) }
        },
    )
}

// Material "shared axis X": the new screen slides a short way in while fading, the old one
// slides the other way and fades out. Both move together, so nothing pops or cuts.
private val emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val NAV_MS = 400

private fun sharedAxis(forward: Boolean): ContentTransform {
    val dir = if (forward) 1 else -1
    val enter = slideInHorizontally(tween(NAV_MS, easing = emphasized)) { dir * it / 5 } +
        fadeIn(tween(NAV_MS / 2, delayMillis = NAV_MS / 8, easing = LinearEasing))
    val exit = slideOutHorizontally(tween(NAV_MS, easing = emphasized)) { -dir * it / 5 } +
        fadeOut(tween(NAV_MS / 4, easing = LinearEasing))
    return enter togetherWith exit
}
