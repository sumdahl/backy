package global.ptg.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** The window in dp: works for phones in either orientation, foldables, tablets and split screen. */
@Composable
fun windowSize(): DpSize {
    val px = LocalWindowInfo.current.containerSize
    return with(LocalDensity.current) { DpSize(px.width.toDp(), px.height.toDp()) }
}

/** Too short for a large collapsing header: a phone in landscape, or a small split screen. */
@Composable
fun isShortWindow() = windowSize().height < 480.dp

/** Wide and landscape-shaped: room for two panes side by side. */
@Composable
fun isTwoPane() = windowSize().let { it.width >= 600.dp && it.width > it.height }

/** Large expressive header normally; a plain one when vertical space is scarce. */
@Composable
fun AdaptiveTopBar(
    title: String,
    scroll: TopAppBarScrollBehavior,
    subtitle: (@Composable () -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    if (isShortWindow()) {
        TopAppBar(title = { Text(title) }, actions = actions, scrollBehavior = scroll)
    } else {
        LargeFlexibleTopAppBar(title = { Text(title) }, subtitle = subtitle, actions = actions, scrollBehavior = scroll)
    }
}

/**
 * A full-width scrolling list whose content stays a readable width, centred. Scrolling works
 * anywhere on screen, not just over the content, because the margin is padding inside the list.
 */
@Composable
fun ReadableLazyColumn(
    pad: PaddingValues,
    bottom: Dp,
    modifier: Modifier = Modifier,
    maxContentWidth: Dp = 720.dp,
    content: LazyListScope.() -> Unit,
) {
    val dir = LocalLayoutDirection.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val side = ((maxWidth - maxContentWidth) / 2).coerceAtLeast(0.dp)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = pad.calculateStartPadding(dir) + side, end = pad.calculateEndPadding(dir) + side,
                top = pad.calculateTopPadding(), bottom = bottom,
            ),
            content = content,
        )
    }
}
