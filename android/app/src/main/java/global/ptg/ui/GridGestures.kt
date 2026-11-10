package global.ptg.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** Thumbnail sizes a pinch steps through. A size, not a column count, so landscape gets more columns. */
val CELL_SIZES = listOf(64, 80, 104, 136, 180, 240)

/** The next size up or down; a size that isn't on the scale snaps to its neighbour. */
fun nextCell(current: Int, bigger: Boolean): Int =
    if (bigger) CELL_SIZES.firstOrNull { it > current } ?: CELL_SIZES.last()
    else CELL_SIZES.lastOrNull { it < current } ?: CELL_SIZES.first()

/**
 * Pinch out for bigger thumbnails, in for smaller ones: one step per pinch, like Google Photos.
 * Watches the Initial pass so a two-finger pinch never also scrolls the grid; one finger is left alone.
 */
fun Modifier.pinchToResize(cellDp: Int, onChange: (Int) -> Unit): Modifier = pointerInput(cellDp) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var zoom = 1f
        var changed = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                zoom *= event.calculateZoom()
                if (!changed && (zoom > 1.25f || zoom < 0.8f)) {
                    val next = nextCell(cellDp, bigger = zoom > 1f)
                    if (next != cellDp) onChange(next)
                    changed = true
                }
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * Long-press a photo, then drag: everything between it and your finger is selected (or, if it
 * started on a selected photo, deselected). Dragging near the top or bottom edge scrolls.
 * [onActive] reports when a drag-select starts and ends: the grid must stop scrolling by hand
 * meanwhile, or its own drag handling takes the gesture away.
 */
@Composable
fun Modifier.dragSelect(state: LazyGridState, order: List<Long>, selected: SnapshotStateList<Long>, onActive: (Boolean) -> Unit): Modifier {
    val haptics = LocalHapticFeedback.current
    val edge = with(LocalDensity.current) { 64.dp.toPx() }
    val currentOrder by rememberUpdatedState(order)
    var autoScroll by remember { mutableFloatStateOf(0f) }
    var lastPointer by remember { mutableStateOf(Offset.Zero) }

    fun idAt(p: Offset): Long? = state.layoutInfo.visibleItemsInfo.firstOrNull {
        p.x >= it.offset.x && p.x < it.offset.x + it.size.width && p.y >= it.offset.y && p.y < it.offset.y + it.size.height
    }?.key as? Long

    var anchor by remember { mutableStateOf<Int?>(null) }
    var base by remember { mutableStateOf(emptySet<Long>()) }
    var adding by remember { mutableStateOf(true) }

    fun extendTo(p: Offset) {
        val a = anchor ?: return
        val id = idAt(p) ?: return
        val i = currentOrder.indexOf(id).takeIf { it >= 0 } ?: return
        val range = currentOrder.subList(minOf(a, i), maxOf(a, i) + 1)
        val next = if (adding) base + range else base - range.toSet()
        selected.removeAll { it !in next }
        next.forEach { if (it !in selected) selected.add(it) }
    }

    LaunchedEffect(autoScroll) {
        while (isActive && autoScroll != 0f) {
            state.scrollBy(autoScroll)
            extendTo(lastPointer)
            delay(16)
        }
    }

    // Watches the Initial pass, ahead of the photos' own tap handling and the grid's scrolling.
    // Until the long-press fires it only looks; from then on it consumes every event of the touch,
    // release included, so the photo underneath never also sees a tap (which used to undo the
    // selection) and the grid never scrolls by itself.
    return pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val slop = viewConfiguration.touchSlop
            val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val c = e.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                    // Lifted, a second finger (pinch), or moved like a scroll: not a long-press.
                    if (!c.pressed || e.changes.size > 1 || (c.position - down.position).getDistance() > slop) return@withTimeoutOrNull false
                }
                @Suppress("UNREACHABLE_CODE") false
            } ?: true
            if (!held) return@awaitEachGesture
            val id = idAt(down.position) ?: return@awaitEachGesture

            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onActive(true)
            anchor = currentOrder.indexOf(id).takeIf { it >= 0 }
            adding = id !in selected
            base = selected.toSet()
            lastPointer = down.position
            extendTo(down.position)
            try {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val c = e.changes.firstOrNull { it.id == down.id }
                    e.changes.forEach { it.consume() }
                    if (c == null || !c.pressed) break
                    lastPointer = c.position
                    extendTo(c.position)
                    val y = c.position.y
                    autoScroll = when {
                        y < edge -> -(edge - y) / 3
                        y > size.height - edge -> (y - (size.height - edge)) / 3
                        else -> 0f
                    }
                }
            } finally {
                anchor = null; autoScroll = 0f; onActive(false)
            }
        }
    }
}

/**
 * A grabbable thumb on the right edge with a bubble naming where you are ("March 2025"). Shows
 * while scrolling and fades after; only the thumb itself takes touches, so scrolling near the
 * edge still scrolls the grid.
 */
@Composable
fun FastScroller(state: LazyGridState, label: () -> String?, modifier: Modifier = Modifier) {
    val total = state.layoutInfo.totalItemsCount
    if (total < 60) return
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val shown = dragging || state.isScrollInProgress
    val alpha by animateFloatAsState(
        if (shown) 1f else 0f,
        if (shown) tween(150) else tween(400, delayMillis = 1200), label = "scroller",
    )
    val thumbH = 56.dp

    BoxWithConstraints(modifier.fillMaxHeight()) {
        val trackPx = with(LocalDensity.current) { (maxHeight - thumbH).toPx() }.coerceAtLeast(1f)
        val fraction = if (dragging) dragFraction else state.firstVisibleItemIndex.toFloat() / (total - 1).coerceAtLeast(1)
        val y = (fraction * trackPx).roundToInt()
        Row(
            Modifier.align(Alignment.TopEnd).offset { IntOffset(0, y) }.alpha(alpha),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dragging) label()?.let {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer, shadowElevation = 2.dp) {
                    Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                }
            }
            Box(
                Modifier.size(48.dp, thumbH)
                    .then(if (alpha > 0f) Modifier.pointerInput(total) {
                        detectVerticalDragGestures(
                            onDragStart = { dragging = true; dragFraction = fraction },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                        ) { change, dy ->
                            change.consume()
                            dragFraction = (dragFraction + dy / trackPx).coerceIn(0f, 1f)
                            scope.launch { state.scrollToItem((dragFraction * (total - 1)).roundToInt()) }
                        }
                    } else Modifier),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(Modifier.padding(end = 4.dp).size(8.dp, 40.dp)
                    .background(if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(4.dp)))
            }
        }
    }
}
