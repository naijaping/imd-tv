package org.imdtv.iptvtv.ui.live

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import org.imdtv.iptvtv.data.db.ChannelEntity

/** The guide's UI state shared by every theme's layout: focus, scroll, preview, rail and menu (spec §6A.2). */
class GuideState internal constructor(
    private val vm: LiveViewModel, startRow: Int, val list: LazyListState, previewing: Boolean,
    /** False for a layout with no preview (Retro): OK goes straight to full screen and autoplay is off. */
    private val previews: Boolean,
) {
    var focusIndex by mutableStateOf(startRow)
    internal var refocusTick by mutableStateOf(0)
    // Set when an action removes the focused row; once the list updates, focus the row now at that index.
    var refocusOnChange by mutableStateOf(false)
    var previewing by mutableStateOf(previewing)
    var railOpen by mutableStateOf(false)
    /** The category panel holds focus (set by its own focus changes): a refocus then leaves it where the highlight is. */
    internal var railHasFocus = false
    /** Picked in the category panel: the guide opens on the category's first channel, not the one focused before. */
    internal var atTop = false
    internal var menu by mutableStateOf<GuideMenu?>(null)
    internal var railMove by mutableStateOf<RailMove?>(null)
    /** A channel picked up from its long-press menu: the guide shows [RowMove.order] until OK drops it or Back cancels. */
    internal var rowMove by mutableStateOf<RowMove?>(null)
    /** Long OK › Merge channel: this channel stays gold while focus moves; OK on another merges it there, Back cancels. */
    internal var rowMerge by mutableStateOf<RowMerge?>(null)
    /** Set by GuideGrid: only a guide grid can carry a Move or show a Merge, so other menus (search, XMB, overlay) leave them out. */
    internal var hasGrid = false
    /** A rail category to focus once its menu closes (it may have moved). */
    internal var railFocusKey by mutableStateOf<String?>(null)
    internal var restored = false
    /** Set while a layout keeps focus outside the grid (Plus tabs): refocus goes there instead of to the row. */
    var heldFocus by mutableStateOf<FocusRequester?>(null)
    val rowFocus = FocusRequester()
    val railFocus = FocusRequester()

    /** Put focus back on the current row (or the rail when it is open) after the next frame. */
    fun refocus() { refocusTick++ }

    /** Down from the category tabs: the playing channel's row when [ids] holds it, else the top row. */
    suspend fun enterFromTabs(ids: List<Long>) {
        focusIndex = ids.indexOf(vm.previewingId.value).coerceAtLeast(0)
        list.scrollToItem(focusIndex)
        heldFocus = null
        refocus()
    }

    fun onOk(ch: ChannelEntity, onFullScreen: (ChannelEntity) -> Unit) {
        if (!previews) { onFullScreen(ch); return }
        when (okAction(vm.settings.value.autoplayOnScroll, vm.previewingId.value, ch.id)) {
            OkAction.PREVIEW -> { vm.preview(ch); previewing = true }
            OkAction.FULL_SCREEN -> onFullScreen(ch)
        }
    }
}

/** [dropped]: saved, shown until the saved order arrives (else the list jumps back for a moment). */
/** [armed]: an OK press began after picking, so its release (not the menu's) does the merge. */
internal class RowMerge(val ch: ChannelEntity) { var armed = false }
internal data class RowMove(val ch: ChannelEntity, val before: List<ChannelEntity>, val order: List<ChannelEntity> = before, val dropped: Boolean = false)

@Composable
fun rememberGuideState(vm: LiveViewModel, previews: Boolean = true): GuideState {
    val entry by vm.entry.collectAsState()
    val channels by vm.channels.collectAsState()
    val focused by vm.focused.collectAsState()
    val autoplay = vm.settings.collectAsState().value.autoplayOnScroll
    // Back from full screen: restore the exact scroll position, else start on the watched row. Either way the
    // first frame is already in place instead of showing the top of the list and then jumping.
    val saved = remember { vm.guidePos?.takeIf { it.channelId == focused?.id } }
    val startRow = remember { Timeline.rowFor(vm.listing.value.takeIf { it.first == entry?.key }?.second.orEmpty().map { it.id }, focused?.id) }
    val list = rememberLazyListState(saved?.index ?: startRow, saved?.offset ?: 0)
    // Coming back from full screen the stream is still playing: show it straight away.
    val state = remember { GuideState(vm, startRow, list, vm.player.player.currentMediaItem != null, previews).also { it.restored = saved != null } }

    LaunchedEffect(channels) {
        if (!state.refocusOnChange) return@LaunchedEffect
        state.refocusOnChange = false
        state.focusIndex = state.focusIndex.coerceAtMost((channels.size - 1).coerceAtLeast(0))
        state.refocus()
    }
    DisposableEffect(Unit) {
        onDispose { vm.guidePos = GuidePos(vm.focused.value?.id, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) }
    }
    // Autoplay on: preview after focus has rested ~700ms (spec §4.1), so scrolling doesn't thrash the stream.
    LaunchedEffect(focused?.id, autoplay) {
        if (!autoplay || !previews) return@LaunchedEffect
        val ch = focused ?: return@LaunchedEffect
        delay(700)
        vm.preview(ch, ask = false)
        state.previewing = true
    }
    // A number typed on the remote: focus that channel's row (once its category's list is showing); the autoplay effect above previews it.
    LaunchedEffect(Unit) {
        vm.jumps.collectLatest { ch ->
            val ids = vm.listing.first { l -> l.first == vm.entry.value?.key && l.second.any { it.id == ch.id } }.second.map { it.id }
            state.heldFocus = null
            state.focusIndex = ids.indexOf(ch.id)
            list.scrollToItem(state.focusIndex)
            state.refocus()
        }
    }
    // Re-entering from full screen recreates this state, so start on the channel being watched, not the top row.
    LaunchedEffect(entry?.key) {
        val ids = vm.listing.first { it.first == entry?.key }.second.map { it.id } // not the previous entry's list
        // Previewed from the category panel, or picked there: its first channel.
        state.focusIndex = if (state.railOpen || state.atTop) 0 else Timeline.rowFor(ids, vm.focused.value?.id)
        state.atTop = false
        if (state.restored) state.restored = false else list.scrollToItem(state.focusIndex)
        // Panel open (previewing while the highlight moves): the grid updates behind it and takes no focus.
        if (!state.railOpen) state.refocus()
    }
    LaunchedEffect(state.refocusTick, channels.isNotEmpty(), state.railOpen) {
        withFrameNanos { }
        runCatching {
            val held = state.heldFocus
            when {
                // Only when focus isn't in the panel yet: a list arriving while the highlight moves must not pull it back.
                state.railOpen -> if (!state.railHasFocus) state.railFocus.requestFocus()
                held != null -> held.requestFocus()
                channels.isNotEmpty() -> state.rowFocus.requestFocus()
            }
        }
    }
    return state
}
