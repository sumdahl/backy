package global.ptg.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun Onboarding(hasAccess: Boolean, onAllow: () -> Unit, onDone: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        val short = isShortWindow()
        AnimatedContent(hasAccess, label = "onboarding") { granted ->
            // Scrolls when the window is short (landscape), centred and button-at-bottom otherwise.
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).widthIn(max = 560.dp)
                    .padding(horizontal = 24.dp, vertical = if (short) 16.dp else 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Spacer(Modifier.size(0.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ShapeIcon(
                    if (granted) Icons.Outlined.CloudUpload else Icons.Outlined.PhotoLibrary,
                    MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
                    size = if (short) 72.dp else 120.dp,
                    shape = (if (granted) MaterialShapes.Cookie9Sided else MaterialShapes.Sunny).toShape(),
                )
                Spacer(Modifier.size(if (short) 16.dp else 32.dp))
                Text(
                    if (granted) "You're in control" else "Back up your gallery",
                    style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    if (granted) "Nothing uploads until you say so. Pick folders, tap Back up now, and watch it go."
                    else "Copy your photos and videos to Google Photos at original quality, from any folder, to any account.",
                    style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(32.dp))
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (granted) {
                        Point(Icons.Outlined.TouchApp, "Manual by default", "Nothing runs in the background. A backup starts only when you tap it.")
                        Point(Icons.Outlined.Lock, "Stays on this phone", "Your sign-in is kept in the app's private storage and never backed up.")
                    } else {
                        Point(Icons.Outlined.HighQuality, "Original quality", "Full-resolution photos and videos, with their dates and location.")
                        Point(Icons.Outlined.PhotoLibrary, "Your folders, your rules", "Camera, screenshots, WhatsApp — choose each one.")
                    }
                }
                }
                Button(
                    onClick = if (granted) onDone else onAllow,
                    modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
                    contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                ) {
                    Text(if (granted) "Get started" else "Allow access to photos",
                        style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
                }
            }
            }
        }
    }
}

@Composable
private fun Point(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.size(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
