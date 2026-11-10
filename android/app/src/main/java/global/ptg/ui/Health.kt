package global.ptg.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Things outside the app that decide whether a long backup survives. */
data class Health(val notifications: Boolean, val unrestricted: Boolean)

fun health(context: Context) = Health(
    notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
    unrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
)

/** Re-read on every resume, since the user fixes these in system settings and comes back. */
@Composable
fun rememberHealth(): Health {
    val context = LocalContext.current
    var h by remember { mutableStateOf(health(context)) }
    LifecycleResumeEffect(Unit) {
        h = health(context)
        onPauseOrDispose { }
    }
    return h
}

fun appNotificationSettings(context: Context) =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

/**
 * The one-tap system dialog where it exists. Some OEM skins (vivo included) hide it or add
 * their own background-power switch, so fall back to the app's info page, where that lives.
 */
@SuppressLint("BatteryLife") // sideloaded backup app: exactly the case this exemption is for
fun batteryExemption(context: Context): Intent {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    return if (direct.resolveActivity(context.packageManager) != null) direct
    else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
}
