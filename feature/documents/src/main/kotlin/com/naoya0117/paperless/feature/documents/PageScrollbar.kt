package com.naoya0117.paperless.feature.documents

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Where a list of pages is scrolled to, estimated from the pages in view (their heights differ and the others are
 * not measured).
 *
 * @property fraction from 0 (at the top) to 1 (at the bottom)
 * @property visible how much of the whole is in view, from 0 to 1 (the length of the scrollbar's thumb)
 */
internal data class ScrollPosition(
    val fraction: Float,
    val visible: Float,
)

/**
 * @param first the first page in view, [scrolled] pixels of it above the view
 * @param pageSize the average height of a page in view, with the space after it
 * @param viewport the height of the view
 * @return `null` when everything is in view (nothing to scroll)
 */
internal fun scrollPosition(
    count: Int,
    first: Int,
    scrolled: Int,
    firstSize: Int,
    pageSize: Float,
    viewport: Int,
    atStart: Boolean,
    atEnd: Boolean,
): ScrollPosition? {
    if (count == 0 || pageSize <= 0f || (atStart && atEnd)) return null
    val inView = viewport / pageSize
    val pages = first + (scrolled.toFloat() / firstSize.coerceAtLeast(1)).coerceIn(0f, 1f)
    val max = (count - inView).coerceAtLeast(0f)
    val fraction =
        when {
            atStart -> 0f
            atEnd || max == 0f -> 1f
            else -> (pages / max).coerceIn(0f, 1f)
        }
    return ScrollPosition(fraction, (inView / count).coerceIn(0f, 1f))
}

/** Where to scroll for [fraction] of a list of [count] pages: the page, and how far into it (0 to 1). */
internal fun scrollTarget(
    fraction: Float,
    count: Int,
    inView: Float,
): Pair<Int, Float> {
    val pages = fraction.coerceIn(0f, 1f) * (count - inView).coerceAtLeast(0f)
    val page = floor(pages).toInt().coerceIn(0, (count - 1).coerceAtLeast(0))
    return page to (pages - page)
}

/**
 * The page shown (0-based), of the pages in view, each from its top to its bottom (in the list's pixels): the one
 * in the middle of the view; at the top of the list the first, at its end the last (a short last page may never
 * reach the middle).
 */
internal fun currentPage(
    pages: List<Triple<Int, Int, Int>>,
    middle: Int,
    atStart: Boolean,
    atEnd: Boolean,
): Int? =
    when {
        pages.isEmpty() -> null
        atEnd -> pages.last().first
        atStart -> pages.first().first
        else ->
            pages.firstOrNull { (_, top, bottom) -> middle in top until bottom }?.first
                // In the space between two pages: the nearer one.
                ?: pages.minBy { (_, top, bottom) -> minOf(abs(top - middle), abs(bottom - middle)) }.first
    }

/** How long the scrollbar and the page number stay after the pages stop moving. */
private const val SHOWN_MILLIS = 1500L

/**
 * A scrollbar at the end edge, shown while the pages move (and a while after) and draggable to move through them
 * quickly; and the page shown out of how many ("3 / 12"), also shown while [barsShown].
 *
 * @param spacing the space between pages (in pixels)
 * @param space kept free around the pages (behind the bars), which the scrollbar and the page number stay clear of
 */
@Composable
internal fun BoxScope.PageScrollbar(
    listState: LazyListState,
    count: Int,
    spacing: Float,
    barsShown: Boolean,
    space: PaddingValues,
) {
    var active by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }

    /** While dragging: where the thumb is (it follows the finger, not the estimate). */
    var dragFraction by remember { mutableStateOf(0f) }
    LaunchedEffect(listState) {
        // Not when the viewer opens: only when the pages move.
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .drop(1)
            .collect {
                active = true
            }
    }
    LaunchedEffect(active, dragging, listState.isScrollInProgress) {
        if (active && !dragging && !listState.isScrollInProgress) {
            delay(SHOWN_MILLIS)
            active = false
        }
    }
    val shown = active || dragging
    val position by remember(listState, count, spacing) {
        derivedStateOf {
            val info = listState.layoutInfo
            val items = info.visibleItemsInfo
            if (items.isEmpty()) return@derivedStateOf null
            val pageSize = items.map { it.size }.average().toFloat() + spacing
            scrollPosition(
                count = count,
                first = listState.firstVisibleItemIndex,
                scrolled = listState.firstVisibleItemScrollOffset,
                firstSize = items.firstOrNull { it.index == listState.firstVisibleItemIndex }?.size ?: 0,
                pageSize = pageSize,
                viewport = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding,
                atStart = !listState.canScrollBackward,
                atEnd = !listState.canScrollForward,
            )
        }
    }
    val page by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            currentPage(
                pages = info.visibleItemsInfo.map { Triple(it.index, it.offset, it.offset + it.size) },
                middle = (info.viewportStartOffset + info.viewportEndOffset) / 2,
                atStart = !listState.canScrollBackward,
                atEnd = !listState.canScrollForward,
            )
        }
    }
    val scope = rememberCoroutineScope()
    val current = position
    if (current != null) {
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        top = space.calculateTopPadding(),
                        bottom = space.calculateBottomPadding(),
                        end = space.calculateEndPadding(LocalLayoutDirection.current),
                    ).fillMaxHeight(),
        ) {
            BoxWithConstraints(Modifier.fillMaxHeight().width(THUMB_TOUCH_WIDTH)) {
                val density = LocalDensity.current
                val track = constraints.maxHeight.toFloat()
                val thumb =
                    maxOf(
                        with(density) { THUMB_MIN_HEIGHT.toPx() },
                        current.visible * track,
                    ).coerceAtMost(track)
                val fraction = if (dragging) dragFraction else current.fraction
                val thumbWidth by animateDpAsState(if (dragging) 8.dp else 4.dp, label = "thumb")
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(THUMB_TOUCH_WIDTH)
                        // Hidden from TalkBack, which scrolls the list itself; the page number is read instead.
                        .clearAndSetSemantics {}
                        .pointerInput(listState, count, track, thumb) {
                            var grab = 0f

                            fun scrollTo(y: Float) {
                                dragFraction = ((y - grab) / (track - thumb)).coerceIn(0f, 1f)
                                val info = listState.layoutInfo
                                val items = info.visibleItemsInfo
                                val pageSize =
                                    items
                                        .map { it.size }
                                        .average()
                                        .toFloat()
                                        .takeIf { it > 0f } ?: return
                                val viewport =
                                    info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
                                val (index, into) = scrollTarget(dragFraction, count, viewport / (pageSize + spacing))
                                scope.launch {
                                    listState.scrollToItem(
                                        index,
                                        (into * (pageSize + spacing)).roundToInt(),
                                    )
                                }
                            }
                            detectVerticalDragGestures(
                                onDragStart = { start ->
                                    dragging = true
                                    val top = fraction * (track - thumb)
                                    // On the thumb, it moves with the finger; elsewhere it jumps under the finger.
                                    grab = if (start.y in top..top + thumb) start.y - top else thumb / 2
                                    dragFraction = fraction
                                    scrollTo(start.y)
                                },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                                onVerticalDrag = { change, _ ->
                                    change.consume()
                                    scrollTo(change.position.y)
                                },
                            )
                        },
                ) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset { IntOffset(0, (fraction * (track - thumb)).roundToInt()) }
                            .padding(end = 4.dp)
                            .width(thumbWidth)
                            .height(with(density) { thumb.toDp() })
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)),
                    )
                }
            }
        }
    }
    val shownPage = page
    AnimatedVisibility(
        visible = shownPage != null && (shown || barsShown),
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = space.calculateBottomPadding()),
    ) {
        val number = (shownPage ?: 0) + 1
        val description = stringResource(R.string.viewer_page, number, count)
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.padding(bottom = 12.dp).clearAndSetSemantics { contentDescription = description },
        ) {
            Text(
                stringResource(R.string.viewer_page_short, number, count),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** The thumb's touch area (wider than it looks). */
private val THUMB_TOUCH_WIDTH = 32.dp

private val THUMB_MIN_HEIGHT = 48.dp
