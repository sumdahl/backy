package global.ptg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/** Android 16 settings style: a group of rows with big outer corners and small inner ones. */
fun segmentShape(index: Int, count: Int): Shape {
    val big = 24.dp
    val small = 6.dp
    return RoundedCornerShape(
        topStart = if (index == 0) big else small, topEnd = if (index == 0) big else small,
        bottomStart = if (index == count - 1) big else small, bottomEnd = if (index == count - 1) big else small,
    )
}

/** A rounded group of rows separated by 2 dp gaps. */
@Composable
fun SegmentGroup(rows: List<@Composable () -> Unit>, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { i, row ->
            Surface(shape = segmentShape(i, rows.size), color = MaterialTheme.colorScheme.surfaceContainer) { row() }
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 8.dp),
    )
}

private val transparentListItem
    @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, icon: ImageVector? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = icon?.let { { Icon(it, null) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = transparentListItem,
        modifier = Modifier.clickable(role = Role.Switch) { onChange(!checked) },
    )
}

@Composable
fun NavRow(title: String, subtitle: String? = null, icon: ImageVector? = null, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        leadingContent = icon?.let { { Icon(it, null) } },
        trailingContent = trailing,
        colors = transparentListItem,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun SliderRow(title: String, value: Int, range: IntRange, onChange: (Int) -> Unit, label: (Int) -> String = { it.toString() }) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(label(v.roundToInt()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = v,
            onValueChange = { v = it },
            onValueChangeFinished = { onChange(v.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
        )
    }
}

@Composable
fun <T> ChoiceRow(title: String, options: List<T>, selected: T, label: (T) -> String, onChange: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.size(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, o ->
                SegmentedButton(
                    selected = o == selected,
                    onClick = { onChange(o) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(label(o), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** A row that opens a dialog to edit one line of text. */
@Composable
fun TextRow(title: String, value: String, placeholder: String, onChange: (String) -> Unit, icon: ImageVector? = null, help: String? = null) {
    var open by remember { mutableStateOf(false) }
    NavRow(title, value.ifEmpty { placeholder }, icon) { open = true }
    if (open) {
        var text by remember { mutableStateOf(value) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column {
                    help?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp)) }
                    OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text(placeholder) },
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                }
            },
            confirmButton = { TextButton({ onChange(text.trim()); open = false }) { Text("Save") } },
            dismissButton = { TextButton({ open = false }) { Text("Cancel") } },
        )
    }
}

private val avatarColors = listOf(
    Color(0xFF1A73E8), Color(0xFFD93025), Color(0xFF188038), Color(0xFFE37400),
    Color(0xFF9334E6), Color(0xFF007B83), Color(0xFFC5221F), Color(0xFF1967D2),
)

/** Letter avatar in a Material expressive "cookie" shape, coloured by the email. */
@Composable
fun Avatar(email: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val color = avatarColors[abs(email.lowercase().hashCode()) % avatarColors.size]
    Box(
        modifier.size(size).clip(MaterialShapes.Cookie9Sided.toShape()).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            email.firstOrNull()?.uppercase() ?: "?",
            color = Color.White,
            fontWeight = FontWeight.Medium,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}

/** An icon inside an expressive shape: the hero glyph on cards and empty states. */
@Composable
fun ShapeIcon(icon: ImageVector, container: Color, content: Color, size: Dp = 64.dp, shape: Shape = MaterialShapes.Cookie12Sided.toShape()) {
    Box(Modifier.size(size).clip(shape).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = content, modifier = Modifier.size(size * 0.45f))
    }
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, color = color, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
