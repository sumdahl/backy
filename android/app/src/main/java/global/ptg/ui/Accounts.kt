package global.ptg.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import global.ptg.container
import global.ptg.data.Engine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AccountsScreen(go: (Route) -> Unit) {
    val c = LocalContext.current.container
    val accounts by c.accounts.collectAsStateWithLifecycle()
    val lib by c.library.collectAsStateWithLifecycle()
    val perAccount = lib.doneByAccount
    var removing by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Accounts") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { go(Route.Login) }, icon = { Icon(Icons.Outlined.PersonAdd, null) }, text = { Text("Add account") })
        },
    ) { pad ->
        LazyColumn(contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 96.dp)) {
            if (accounts.accounts.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ShapeIcon(Icons.Outlined.PersonAdd, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, 112.dp)
                    Spacer(Modifier.size(20.dp))
                    Text("No accounts yet", style = MaterialTheme.typography.titleLarge)
                    Text("Add the Google account your photos should go to. You can add more and send each folder to a different one.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            itemsIndexed(accounts.accounts, key = { _, a -> a.email }) { i, a ->
                val isDefault = a.email == accounts.selected
                var menu by remember { mutableStateOf(false) }
                Surface(
                    shape = segmentShape(i, accounts.accounts.size),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp),
                    onClick = { c.scope.launch { Engine.select(a.email); c.refreshAccounts() } },
                ) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { Avatar(a.email, 48.dp) },
                        headlineContent = { Text(a.email) },
                        supportingContent = {
                            Text((if (isDefault) "Default · " else "") + plural(perAccount[a.email] ?: 0, "item") + " backed up")
                        },
                        trailingContent = {
                            Box {
                                if (isDefault) Icon(Icons.Outlined.CheckCircle, "Default", tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 40.dp))
                                IconButton({ menu = true }, Modifier.align(Alignment.CenterEnd)) {
                                    Icon(Icons.Outlined.MoreVert, "More options")
                                }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text("Remove from this app") }, onClick = { menu = false; removing = a.email },
                                        leadingIcon = { Icon(Icons.Outlined.Delete, null) })
                                }
                            }
                        },
                    )
                }
            }
            item {
                Notice(Icons.Outlined.Info, "How this works",
                    "Uploads go through Google Photos' own mobile protocol, as a Pixel phone. It's unofficial: Google could change it, " +
                        "count uploads against storage later, or restrict accounts that use it. Keep your own copy.",
                    "Add account", { go(Route.Login) })
            }
        }
    }

    removing?.let { email ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove $email?") },
            text = { Text("The app forgets this sign-in. Photos already in Google Photos stay there.") },
            confirmButton = {
                TextButton({
                    c.scope.launch { Engine.logout(email); c.refreshAccounts() }
                    removing = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton({ removing = null }) { Text("Cancel") } },
        )
    }
}

private const val SIGN_IN_URL = "https://accounts.google.com/EmbeddedSetup"

/**
 * Google's EmbeddedSetup page in a WebView. After sign-in it sets a single-use
 * `oauth_token` cookie; we read it, exchange it in Go, and forget it.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onDone: () -> Unit) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    var paste by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    suspend fun exchange(token: String) {
        busy = true
        try {
            c.engine()
            Engine.login(token)
            c.refreshAccounts()
            onDone()
        } catch (e: Exception) {
            busy = false
            snack.showSnackbar("Sign-in didn't finish: ${e.message ?: "unknown error"}")
            CookieManager.getInstance().removeAllCookies(null)
            webView?.loadUrl(SIGN_IN_URL)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(600)
            if (busy) continue
            val token = CookieManager.getInstance().getCookie("https://accounts.google.com")
                ?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("oauth_token=") }?.substringAfter('=')
            if (!token.isNullOrEmpty()) exchange(token)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign in to Google") },
                navigationIcon = { IconButton(onDone) { Icon(Icons.Outlined.Close, "Close") } },
                actions = { IconButton({ paste = true }) { Icon(Icons.Outlined.ContentPaste, "Paste a token") } },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    CookieManager.getInstance().removeAllCookies(null)
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = WebViewClient()
                        loadUrl(SIGN_IN_URL)
                        webView = this
                    }
                },
                onRelease = { it.destroy() },
            )
            if (busy) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                    ContainedLoadingIndicator(Modifier.size(72.dp))
                    Spacer(Modifier.size(16.dp))
                    Text("Finishing sign-in…", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    if (paste) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { paste = false },
            icon = { Icon(Icons.Outlined.Add, null) },
            title = { Text("Use a token") },
            text = {
                Column {
                    Text("Paste an oauth_token (starts with oauth2_4/) or a credential string from the ptg CLI.",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(12.dp))
                    OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("oauth2_4/…") })
                }
            },
            confirmButton = { TextButton({ paste = false; scope.launch { exchange(text) } }, enabled = text.isNotBlank()) { Text("Sign in") } },
            dismissButton = { TextButton({ paste = false }) { Text("Cancel") } },
        )
    }
}
