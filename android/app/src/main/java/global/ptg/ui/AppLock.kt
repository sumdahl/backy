package global.ptg.ui

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import global.ptg.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow

/** Fingerprint, face, or the phone's own PIN, pattern or password as the fallback. */
private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

/**
 * Process-wide lock state, shared by every activity (the app and the share sheet). Starts locked;
 * relocks once the app has been out of sight for longer than the chosen delay.
 */
object AppLock {
    val unlocked = MutableStateFlow(false)
    private var hiddenAt = 0L

    fun hidden() { if (unlocked.value) hiddenAt = SystemClock.elapsedRealtime() }

    fun shown(lockAfterSec: Int) {
        if (unlocked.value && SystemClock.elapsedRealtime() - hiddenAt > lockAfterSec * 1000L) unlocked.value = false
    }

    /**
     * False if the phone has no screen lock at all. The lock then steps aside: there's nothing to
     * check against, and anyone holding an unlocked phone could change that anyway.
     */
    fun available(context: Context) =
        context.getSystemService(BiometricManager::class.java).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun authenticate(activity: Activity, title: String, onSuccess: () -> Unit) {
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle("Use your fingerprint, face or screen lock")
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                unlocked.value = true
                onSuccess()
            }
            // Errors (cancelled, too many attempts) leave the lock screen up with its Unlock button.
        })
    }
}

/**
 * Wraps a screen in the app lock. The content stays composed underneath (so where you were
 * survives a relock) but is covered, untouchable and hidden from accessibility services while
 * locked, and left out of the Recents thumbnail.
 */
@Composable
fun LockGate(settings: Settings, content: @Composable () -> Unit) {
    val activity = LocalActivity.current ?: return content()
    val unlocked by AppLock.unlocked.collectAsState()
    var available by remember { mutableStateOf(AppLock.available(activity)) }
    val locked = settings.appLock && available && !unlocked
    val unlock = { AppLock.authenticate(activity, "Unlock Backy") {} }

    LifecycleStartEffect(settings.appLock, settings.lockAfterSec) {
        available = AppLock.available(activity)
        if (settings.appLock) {
            AppLock.shown(settings.lockAfterSec)
            if (available && !AppLock.unlocked.value) unlock()
        }
        // A rotation recreates the activity; that isn't leaving the app.
        onStopOrDispose { if (!activity.isChangingConfigurations) AppLock.hidden() }
    }
    if (Build.VERSION.SDK_INT >= 33) {
        DisposableEffect(settings.appLock) {
            activity.setRecentsScreenshotEnabled(!settings.appLock)
            onDispose { }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(if (locked) Modifier.clearAndSetSemantics { } else Modifier) { content() }
        if (locked) LockScreen(unlock)
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    // A Surface blocks touches from reaching anything underneath.
    Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ShapeIcon(Icons.Outlined.Lock, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
                96.dp, MaterialShapes.Cookie9Sided.toShape())
            Spacer(Modifier.size(24.dp))
            Text("Backy is locked", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.size(32.dp))
            Button(onUnlock, Modifier.heightIn(min = ButtonDefaults.MediumContainerHeight)) {
                Icon(Icons.Outlined.Fingerprint, null)
                Spacer(Modifier.size(8.dp))
                Text("Unlock")
            }
        }
    }
}
