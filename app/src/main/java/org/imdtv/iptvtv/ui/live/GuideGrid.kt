@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package org.imdtv.iptvtv.ui.live

import org.imdtv.iptvtv.ui.hasCatchUp
import androidx.compose.ui.res.stringResource
import org.imdtv.iptvtv.R

import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.imdtv.iptvtv.data.catalogue.displayName
import org.imdtv.iptvtv.data.db.ChannelEntity
import org.imdtv.iptvtv.data.repo.GuideProgramme
import org.imdtv.iptvtv.ui.FocusBox
import org.imdtv.iptvtv.ui.clockTime
import org.imdtv.iptvtv.ui.onLongOk
import kotlinx.coroutines.launch
import org.imdtv.iptvtv.ui.theme.LocalTheme

/** How a theme draws the timeline grid. A field is added by the first theme that needs it. */
data class GridStyle(
    val rowHeight: Dp, val channelCol: Dp, val dpPerMin: Dp, val windowMin: Int, val gap: Dp, val font: FontWeight,
    /** Background and text of the focused row's current programme (flat cells add a light border). */
    val focusedProgramme: Color, val focusedProgrammeText: Color = Color.White,
    /** Opaque separate boxes (Plus/Retro) instead of flat translucent cells that touch (Glass). */
    val boxed: Boolean = false,
    /** Channel column reads "101 BBC One" instead of "BBC One   101". */
    val numberFirst: Boolean = false,
    /** ◀ ▶ marks where a programme runs past the window's start or end. */
    val overflowArrows: Boolean = false,
    /** "Today" and the times; null = white and muted (for a grid on a dark background). */
    val headerColor: Color? = null,
    /** A thin light line under each row (Xcube: rows touch, gap 0). */
    val rowDividers: Boolean = false,
    /** Flat cells with slightly rounded corners and a small gap instead of touching (Glass, Liquid). */
    val roundedCells: Boolean = false,
    /** Set by [forGuide]: scales the guide's fonts to the row height; [lines] is the max lines for names and titles. */
    val textScale: Float = 1f, val lines: Int = 1,
) {
    fun sp(size: Float) = (size * textScale).sp
}

/** Keeps the focused row one row below the top, so rows line up with the list's edges. (The TV default keeps it 30% down,
 *  which shows a sliver of a row at the top and cuts the last one off.) */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun wholeRowScroll(pitchPx: Int) = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = offset - pitchPx
}

/** Whole-pixel row height for [count] rows plus their gaps in [areaPx]: rows that fill the list exactly scroll by whole
 *  rows (fractional ones left a sliver at the top and cut the last row off). */
fun guideRowPx(areaPx: Int, count: Int, gapPx: Int): Int = (areaPx - gapPx * (count - 1)) / count

/**
 * Fonts shrink once the rows are shorter than ~21dp (14sp text), never below 65%. With [multiLine], names and titles get 2 lines
 * when the font can still be at least 70% size and fit both (rows of 27dp and up); shorter rows keep 1 line.
 */
fun GridStyle.forGuide(multiLine: Boolean, epgScale: Float = 1f): GridStyle {
    val h = rowHeight.value
    val fit = (h - 2) / 33.6f // two lines of 14sp at 1.2 line height
    val two = multiLine && fit >= .7f
    // EPG text size, capped at what one line of the row holds (the 1f case is the old coerceIn(.65f, 1f)).
    val shrink = minOf(epgScale, (h - 4) / 17.5f).coerceAtLeast(.65f)
    return copy(textScale = if (two) minOf(shrink, fit) else shrink, lines = if (two) 2 else 1)
}

/** Celestial Glass: mockup 01, 420px per half hour (1dp = 2px on a 1080p TV). */
// 150 min (195 + 5 + 150 × 5.9 = 1085dp) runs off the right edge of the screen on purpose (user, 2026-11-16).
val GLASS_GRID = GridStyle(32.dp, 195.dp, 5.9.dp, 150, 4.dp, FontWeight.Medium, focusedProgramme = Color(0x8C7891AA), roundedCells = true) // frosted glass

/** Liquid Glass: the Glass grid inside one glass panel; the focused programme is a brighter tint (spec §6A.6). */
// Like Glass, the panel runs off the right edge.
val LIQUID_GRID = GLASS_GRID.copy(focusedProgramme = Color(0x59FFFFFF), dpPerMin = 5.75.dp)

/** Xcube 180 (spec §6A.7): flat rows with thin light dividers, pale-green focus, ~6 rows × 1½ hours. */
val XCUBE_GRID = GridStyle(30.dp, 200.dp, 6.dp, 90, 0.dp, FontWeight.Medium, focusedProgramme = Color(0xFFE8F5D8),
    focusedProgrammeText = Color(0xFF1A3A0E), rowDividers = true)

/** Celestial Plus (spec §6A.4): boxed cells, number first, yellow focus, overflow arrows, 1½ hours. */
val PLUS_GRID = GridStyle(30.dp, 215.dp, 7.75.dp, 90, 3.dp, FontWeight.SemiBold, focusedProgramme = Color(0xFFF2C94C),
    focusedProgrammeText = Color(0xFF0A1A5C), boxed = true, numberFirst = true, overflowArrows = true)

/** Celestial Retro (spec §6A.5): 10 rows × 1½ hours, bordered royal-blue boxes, bold, yellow focus, navy header on sky. */
val RETRO_GRID = GridStyle(27.dp, 180.dp, 7.5.dp, 90, 3.dp, FontWeight.Bold, focusedProgramme = Color(0xFFFFCC33),
    focusedProgrammeText = Color(0xFF15257A), boxed = true, numberFirst = true, overflowArrows = true, headerColor = Color(0xFF15257A))

private val PILL = RoundedCornerShape(5.dp)
/** A long channel name scrolls this many times, then stops: scrolling forever redrew the whole screen ~40 times a second
 *  while the guide sat still, which on a 4K stream made the Thomson 140G's UI crawl (25-50 ms a frame). */
private const val MARQUEE_TIMES = 3
private val BOX = RoundedCornerShape(2.dp)
private val CELL = RoundedCornerShape(4.dp)
private val SEPARATOR = Color(0x40FFFFFF)
private val FOCUS_BORDER = Color(0xCCFFFFFF)

/** Header (Today + times), the channel rows and the now line. Left at the window start calls [onLeftAtStart];
 *  with [onUpAtTop], Up on the first row calls it instead of moving focus. */
@Composable
fun GuideGrid(
    vm: LiveViewModel, state: GuideState, base: GridStyle, onFullScreen: (ChannelEntity) -> Unit, onLeftAtStart: () -> Unit,
    modifier: Modifier = Modifier, onUpAtTop: (() -> Unit)? = null, rows: Int = 0,
) {
    val multiLine = vm.settings.collectAsState().value.multiLineGuide
    val epg = vm.settings.collectAsState().value.epgTextSize.scale
    val logoSource by vm.logoSource.collectAsState()
    val display = vm.settings.collectAsState().value.guideDisplay
    val logos = logoSource.takeIf { display != org.imdtv.iptvtv.data.settings.GuideChannelDisplay.NAME }
    val style = remember(base, multiLine, epg) { base.forGuide(multiLine, epg) }
    val listed by vm.channels.collectAsState()
    val move = state.rowMove
    val channels = move?.order ?: listed
    androidx.activity.compose.BackHandler(enabled = move != null && !move.dropped) { state.rowMove = null }
    val merging = state.rowMerge
    state.hasGrid = true
    androidx.activity.compose.BackHandler(enabled = merging != null) { state.rowMerge = null }
    androidx.compose.runtime.LaunchedEffect(listed) { if (state.rowMove?.dropped == true) state.rowMove = null }
    // Keep the moved row clear of the list's edges, so the channel it is passing shows. Measured after the move is laid
    // out: the list keeps its first visible item by key, so a row moving up past the top drags the view with it.
    androidx.compose.runtime.LaunchedEffect(move?.order) {
        val m = move?.takeIf { !it.dropped } ?: return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        val j = m.order.indexOfFirst { it.id == m.ch.id }
        val seen = state.list.layoutInfo.visibleItemsInfo.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        when {
            // At the very top (j = 0) too: the list may still be scrolled down, leaving the row above the screen.
            j <= seen.first().index -> state.list.scrollToItem((j - 1).coerceAtLeast(0))
            j >= seen.last().index -> state.list.scrollToItem(seen.first().index + (j - seen.last().index) + if (j < m.order.lastIndex) 1 else 0)
        }
    }
    if (move != null && !move.dropped) androidx.compose.ui.window.Popup(alignment = Alignment.BottomCenter) {
        org.imdtv.iptvtv.ui.InfoBar(stringResource(R.string.live_row_move_hint), Modifier.padding(bottom = 40.dp))
    }
    if (merging != null) androidx.compose.ui.window.Popup(alignment = Alignment.BottomCenter) {
        org.imdtv.iptvtv.ui.InfoBar(stringResource(R.string.live_row_merge_hint, merging.ch.displayName(vm.strip.value)), Modifier.padding(bottom = 40.dp))
    }
    val programmes by vm.programmes.collectAsState()
    val now by vm.now.collectAsState()
    val windowStart by vm.windowStart.collectAsState()
    val cursor by vm.cursor.collectAsState()
    val past by vm.inPast.collectAsState()
    val at = Timeline.cursorAt(cursor, now, past) // the focused row highlights the programme on at
    // Left on now: 1 = held down on a catch-up channel (key-up opens the rail, holding goes into the past), 2 = went into the past.
    val leftHold = remember { IntArray(1) }
    // Right: 1 = pressed (key-up steps the cursor), 2 = held (jumped to the top channel).
    val rightHold = remember { IntArray(1) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val recordingOn by vm.recordingOn.collectAsState()
    val focused by vm.focused.collectAsState()
    val strip by vm.strip.collectAsState()
    val marks by vm.marks.collectAsState()
    val recordingOnChannel by vm.recordingOnChannel.collectAsState()
    val gs = vm.settings.collectAsState().value
    Column(modifier) {
        Row(Modifier.height(25.dp).drawBehind { drawRect(Color(0x33FFFFFF), Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) },
            verticalAlignment = Alignment.Bottom) {
            val today = stringResource(R.string.live_today).let { t ->
                // Show date › In the Today cell: "Today · Mon 23/11".
                if (gs.showDate && gs.datePlace == org.imdtv.iptvtv.data.settings.DatePlace.TODAY_CELL) stringResource(R.string.live_today_date, org.imdtv.iptvtv.ui.dayDate(now)) else t
            }
            Text(if (windowStart - now > 12 * 3600_000) stringResource(R.string.live_later) else if (past && org.imdtv.iptvtv.ui.dayDate(windowStart) != org.imdtv.iptvtv.ui.dayDate(now)) org.imdtv.iptvtv.ui.dayDate(windowStart) else today, fontSize = 13.sp * epg, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = style.headerColor ?: Color.White, modifier = Modifier.width(style.channelCol).padding(bottom = 5.dp))
            for (i in 0 until style.windowMin / 30) {
                Text(clockTime(windowStart + i * LiveViewModel.HALF_HOUR), fontSize = 11.sp * epg, color = style.headerColor ?: LocalTheme.current.colors.muted,
                    fontWeight = if (style.headerColor != null) FontWeight.Bold else null,
                    modifier = Modifier.width(style.dpPerMin * 30).padding(bottom = 5.dp))
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
            // rows > 0: [rows] whole-pixel rows fill the measured height (5–9 in Glass and Liquid); else the style's row height.
            val density = LocalDensity.current
            val area = constraints.maxHeight
            val (style, listHeight, scrollSpec) = remember(style, rows, area, epg) {
                if (rows <= 0) Triple(style, null, null) else with(density) {
                    val gap = style.gap.roundToPx()
                    val row = guideRowPx(area, rows, gap)
                    Triple(base.copy(rowHeight = row.toDp()).forGuide(multiLine, epg), (row * rows + gap * (rows - 1)).toDp(), wholeRowScroll(row + gap))
                }
            }
            // A category with no channels (once its list has arrived) says so instead of leaving the grid blank.
            val loading by vm.listingLoading.collectAsState()
            if (channels.isEmpty() && !loading && vm.entry.collectAsState().value != null) {
                Text(stringResource(R.string.live_no_channels_in_category), fontSize = 16.sp, color = Color.White,
                    modifier = Modifier.padding(start = 12.dp, top = 24.dp))
            }
            CompositionLocalProvider(LocalBringIntoViewSpec provides (scrollSpec ?: LocalBringIntoViewSpec.current)) {
            LazyColumn(state = state.list, verticalArrangement = Arrangement.spacedBy(style.gap),
                modifier = if (listHeight != null) Modifier.height(listHeight) else Modifier.fillMaxSize()) {
                itemsIndexed(channels, key = { _, ch -> ch.id }) { i, ch ->
                    val moving = move?.ch?.id == ch.id && !move.dropped || merging?.ch?.id == ch.id
                    GuideRow(
                        ch, (if (moving && merging == null) "↕ " else "") + ch.displayName(strip), programmes[ch.id].orEmpty(), now, at, windowStart, style, isFocused = focused?.id == ch.id && !state.railOpen && state.heldFocus == null, moving = moving, logos = logos, display = display,
                        marked = { p -> (ch.id to p.start) in marks || ch.id in recordingOnChannel && p.start <= now && now < p.stop },
                        modifier = (if (i == state.focusIndex) Modifier.focusRequester(state.rowFocus) else Modifier)
                            .onPreviewKeyEvent { e ->
                                if (moving && move != null) return@onPreviewKeyEvent moveKey(e, move, i, state, vm)
                                if (merging != null && e.key in OK_KEYS) return@onPreviewKeyEvent mergeKey(e, merging, ch, state, vm)
                                if (state.railOpen) return@onPreviewKeyEvent true // rail is taking focus; don't move the guide behind it
                                if (vm.numberKey(e, false)) return@onPreviewKeyEvent true
                                if (e.key == Key.DirectionLeft) return@onPreviewKeyEvent leftKey(e, leftHold, ch, programmes[ch.id].orEmpty(), style.windowMin, vm) { state.focusIndex = i; onLeftAtStart() }
                                if (e.key == Key.DirectionRight) {
                                    // Tap steps the cursor on key-up; hold jumps to the top channel, as hold Left opens the rail.
                                    val repeat = e.nativeKeyEvent.repeatCount > 0
                                    when {
                                        e.type == KeyEventType.KeyDown && !repeat -> rightHold[0] = 1
                                        e.type == KeyEventType.KeyDown -> if (rightHold[0] == 1) {
                                            rightHold[0] = 2; state.focusIndex = 0
                                            scope.launch { state.list.scrollToItem(0); state.refocus() }
                                        }
                                        e.type == KeyEventType.KeyUp -> {
                                            if (rightHold[0] == 1) vm.moveCursor(programmes[ch.id].orEmpty(), 1, style.windowMin, ch.archiveDays)
                                            rightHold[0] = 0
                                        }
                                    }
                                    return@onPreviewKeyEvent true
                                }
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (e.key) {
                                    Key.DirectionUp -> if (i == 0 && onUpAtTop != null) { onUpAtTop(); true } else false
                                    // A screen of rows at a time, focus keeping its place on screen (issue #10).
                                    else -> pageKey(e.key)?.let { d ->
                                        val info = state.list.layoutInfo
                                        val page = if (rows > 0) rows else info.visibleItemsInfo.count { it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset }
                                        val j = (i + d * page.coerceAtLeast(1)).coerceIn(0, channels.lastIndex)
                                        if (j != i) { state.focusIndex = j; scope.launch { state.list.scrollToItem((state.list.firstVisibleItemIndex + j - i).coerceAtLeast(0)); state.refocus() } }
                                        true
                                    } ?: false
                                }
                            }
                            .onLongOk { state.focusIndex = i; state.menu = if (past && ch.hasCatchUp) GuideMenu.PickDay(ch, style.windowMin) else GuideMenu.Channel(ch) },
                        onFocused = { vm.focused.value = ch; state.focusIndex = i; if (past && !ch.hasCatchUp) vm.leavePast() },
                        onClick = {
                            // A past programme highlighted (catch-up): OK plays it.
                            val aired = if (past) programmes[ch.id].orEmpty().firstOrNull { it.start <= at && at < it.stop && it.stop <= now } else null
                            // A later programme highlighted: OK offers to record it (or cancel) instead of playing.
                            val later = programmes[ch.id].orEmpty().firstOrNull { at > now && it.start <= at && at < it.stop }
                            if (aired != null) vm.playCatchUp(ch, aired)
                            else if (later != null && recordingOn) { state.focusIndex = i; state.menu = GuideMenu.Programme(ch, later) } else state.onOk(ch, onFullScreen)
                        },
                    )
                }
            }
            }
            val nowMin = (now - windowStart) / 60_000
            if (nowMin in 0..style.windowMin) {
                Box(Modifier.offset(x = style.channelCol + style.dpPerMin * nowMin.toInt()).width(1.dp).fillMaxHeight().background(Color(0xB3FFFFFF)))
            }
        }
    }
}

/**
 * Left on a guide row, on key-up so a hold can be told from a tap. A tap steps the cursor back one programme; on now, a
 * catch-up channel goes into the past, any other opens the rail (as does Left at the archive limit). Held, it opens the
 * rail once and nothing else.
 */
private fun leftKey(e: androidx.compose.ui.input.key.KeyEvent, hold: IntArray, ch: ChannelEntity, progs: List<GuideProgramme>, windowMin: Int,
                    vm: LiveViewModel, openRail: () -> Unit): Boolean {
    val repeat = e.nativeKeyEvent.repeatCount > 0
    when {
        e.type == KeyEventType.KeyDown && !repeat -> hold[0] = 1
        e.type == KeyEventType.KeyDown -> if (hold[0] == 1) { hold[0] = 2; openRail() }
        e.type == KeyEventType.KeyUp -> {
            if (hold[0] == 1 && !vm.moveCursor(progs, -1, windowMin, ch.archiveDays)) {
                if (ch.hasCatchUp && !vm.inPast.value) vm.enterPast(ch, windowMin) else openRail()
            }
            hold[0] = 0
        }
    }
    return true
}

/** A key on the picked-up row: Up/Down carry it, OK (on release, so the press isn't also a click) drops it; the rest is swallowed. */
private fun moveKey(e: androidx.compose.ui.input.key.KeyEvent, m: RowMove, i: Int, state: GuideState, vm: LiveViewModel): Boolean {
    val down = e.type == KeyEventType.KeyDown
    when (e.key) {
        Key.DirectionUp, Key.DirectionDown -> if (down) {
            val j = i + if (e.key == Key.DirectionUp) -1 else 1
            if (j in m.order.indices) {
                state.rowMove = m.copy(order = m.order.toMutableList().apply { add(j, removeAt(i)) })
                state.focusIndex = j
            }
        }
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (!down) { vm.saveGuideOrder(m.ch, m.before, m.order); state.rowMove = m.copy(dropped = true) }
        Key.Back, Key.Escape -> return false // to the BackHandler, which cancels the move
        else -> {}
    }
    return true
}

/** Channel Up/Down and Page Up/Down page through a list: -1 up, 1 down, null for any other key. */
internal fun pageKey(key: Key): Int? = when (key) { Key.ChannelUp, Key.PageUp -> -1; Key.ChannelDown, Key.PageDown -> 1; else -> null }

private val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/** OK while merging: on release of a fresh press, merges the gold channel into [ch] (on itself, just cancels). */
private fun mergeKey(e: androidx.compose.ui.input.key.KeyEvent, m: RowMerge, ch: ChannelEntity, state: GuideState, vm: LiveViewModel): Boolean {
    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) m.armed = true
    else if (e.type == KeyEventType.KeyUp && m.armed) { if (ch.id != m.ch.id) vm.merge(m.ch, ch); state.rowMerge = null }
    return true
}

@Composable
private fun GuideRow(
    ch: ChannelEntity, name: String, progs: List<GuideProgramme>, now: Long, at: Long, windowStart: Long, style: GridStyle, isFocused: Boolean,
    marked: (GuideProgramme) -> Boolean, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit,
    /** Picked up by Move: the gold of every move (Settings, the category rail). */
    moving: Boolean = false,
    /** Non-null: the channel's logo replaces its name (Settings › Channels › Logos instead of names, issue #13). */
    logos: List<org.imdtv.iptvtv.data.settings.LogoKind>? = null,
    display: org.imdtv.iptvtv.data.settings.GuideChannelDisplay = org.imdtv.iptvtv.data.settings.GuideChannelDisplay.NAME,
) {
    val colors = LocalTheme.current.colors
    Row(Modifier.height(style.rowHeight).then(if (style.rowDividers) Modifier.drawBehind {
        drawRect(Color(0x40FFFFFF), Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
    } else Modifier)) {
        FocusBox(onClick = onClick, onFocused = onFocused, modifier = modifier.width(style.channelCol - 5.dp).fillMaxHeight()) { focusedBox ->
            val f = focusedBox && !moving
            // No logo for this channel: the name, as with the setting off.
            val logo = logos?.takeIf { org.imdtv.iptvtv.data.logos.shownLogos(ch, it).isNotEmpty() }
            // A logo keeps the dark cell and gets a focus-coloured border: white logos vanished on the light fill (#23).
            val fill = f && logo == null
            Row(
                Modifier.fillMaxSize()
                    // A bordered logo row stays its size: grown, its border's left edge was clipped by the column.
                    .graphicsLayer { val s = if (fill && !style.boxed) 1.03f else 1f; scaleX = s; scaleY = s }
                    .then(if (fill && !style.boxed) Modifier.shadow(14.dp, RoundedCornerShape(5.dp), ambientColor = Color(0xFFA0BEFF), spotColor = Color(0xFFA0BEFF)) else Modifier)
                    .background(if (moving) Color(0xFFE5A00D) else if (fill) colors.focus else colors.cell, if (style.boxed) BOX else PILL)
                    .then(if (f && !fill) Modifier.border(1.5.dp, colors.focus, if (style.boxed) BOX else PILL) else Modifier)
                    // Logo only: the logo's left edge on the programme titles' (10dp in), as a name's nearly is.
                    .padding(start = if (logo != null && display == org.imdtv.iptvtv.data.settings.GuideChannelDisplay.LOGO) 10.dp else 11.dp, end = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val fg = if (moving) Color.Black else if (fill) colors.onFocus else Color.White
                // The focused row scrolls a name too long for its box (issue #5); multi-line names wrap instead.
                val marquee = f && style.lines == 1
                val number = (ch.customNumber ?: ch.number)?.takeIf { org.imdtv.iptvtv.ui.LocalShowNumbers.current }
                val nameText = @Composable { Text(name, fontSize = style.sp(14f) * org.imdtv.iptvtv.ui.LocalTitleScale.current, fontWeight = style.font, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (logo != null && display == org.imdtv.iptvtv.data.settings.GuideChannelDisplay.BOTH) {
                    // Logo in a fixed box, then the name (marquee when focused, as the plain name).
                    if (style.numberFirst) number?.let { Text("$it  ", fontSize = style.sp(14f) * org.imdtv.iptvtv.ui.LocalTitleScale.current, fontWeight = style.font, color = fg) }
                    Box(Modifier.fillMaxHeight().padding(vertical = 3.dp).aspectRatio(1.6f), contentAlignment = Alignment.Center) {
                        org.imdtv.iptvtv.ui.ChannelLogo(ch, logo, Modifier.fillMaxSize(), fallback = {})
                    }
                    Text(name, fontSize = style.sp(14f) * org.imdtv.iptvtv.ui.LocalTitleScale.current, fontWeight = style.font, color = fg, maxLines = style.lines,
                        overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 6.dp).then(if (marquee) Modifier.basicMarquee(MARQUEE_TIMES) else Modifier))
                    if (ch.hasCatchUp) CatchUpClock(fg, Modifier.padding(start = 6.dp))
                    if (!style.numberFirst) number?.let { Text("  $it", fontSize = style.sp(12f), color = fg.copy(alpha = .85f)) }
                } else if (logo != null) {
                    if (style.numberFirst) number?.let { Text("$it  ", fontSize = style.sp(14f) * org.imdtv.iptvtv.ui.LocalTitleScale.current, fontWeight = style.font, color = fg) }
                    // Every logo in the same box on the column's left edge, trimmed and fitted; the name there if it has none.
                    val (boxW, boxH) = org.imdtv.iptvtv.ui.ListLogoSize.box(style.rowHeight, style.channelCol - 5.dp)
                    org.imdtv.iptvtv.ui.ListLogo(ch, logo, boxW, boxH, Modifier.weight(1f).fillMaxHeight().testTag("logoCell"), fallback = nameText)
                    if (ch.hasCatchUp) CatchUpClock(fg, Modifier.padding(start = 6.dp))
                    if (!style.numberFirst) number?.let { Text("  $it", fontSize = style.sp(12f), color = fg.copy(alpha = .85f)) }
                } else if (style.numberFirst) {
                    Text(listOfNotNull(number, name).joinToString("  "), fontSize = style.sp(14f) * org.imdtv.iptvtv.ui.LocalTitleScale.current, fontWeight = style.font, color = fg, maxLines = style.lines,
                        overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).then(if (marquee) Modifier.basicMarquee(MARQUEE_TIMES) else Modifier))
                    if (ch.hasCatchUp) CatchUpClock(fg, Modifier.padding(start = 6.dp))
                } else {
                    // Name and clock fill the box up to the number; a weighted spacer beside the name capped it at half the box.
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, fontSize = style.sp(14f) * org.imdtv.iptvtv.ui.LocalTitleScale.current, fontWeight = style.font, color = fg, maxLines = style.lines,
                            overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).then(if (marquee) Modifier.basicMarquee(MARQUEE_TIMES) else Modifier))
                        if (ch.hasCatchUp) CatchUpClock(fg, Modifier.padding(start = 6.dp))
                    }
                    number?.let { Text("  $it", fontSize = style.sp(12f), color = fg.copy(alpha = .85f)) }
                }
            }
        }
        Spacer(Modifier.width(5.dp))
        Box(Modifier.fillMaxSize().clipToBounds()) {
            val end = windowStart + style.windowMin * 60_000L
            if (progs.none { it.stop > windowStart && it.start < end }) {
                // Shaped and spaced like a programme cell of this style.
                Box(Modifier.width(style.dpPerMin * style.windowMin - when { style.boxed -> 3.dp; style.roundedCells -> 2.dp; else -> 0.dp })
                    .fillMaxHeight().background(colors.cellDim, when { style.boxed -> BOX; style.roundedCells -> CELL; else -> RectangleShape }),
                    contentAlignment = Alignment.CenterStart) {
                    Text(if (progs.isEmpty()) stringResource(R.string.live_no_guide_info) else "", fontSize = style.sp(12f), color = colors.muted, modifier = Modifier.padding(horizontal = 10.dp))
                }
            }
            for (p in progs) {
                val span = Timeline.span(p.start, p.stop, windowStart, end) ?: continue
                val isNow = p.start <= now && now < p.stop
                val past = p.stop <= now
                val focusedNow = isFocused && p.start <= at && at < p.stop // the highlighted programme (now, or Right's pick)
                val cell = when {
                    // Boxed: separate opaque boxes with a small gap (spec §6A.4).
                    style.boxed -> Modifier.width(style.dpPerMin * span.lengthMin - 3.dp)
                        .background(if (focusedNow) style.focusedProgramme else colors.cell, BOX)
                    // Flat cells: rounded with a small gap (Glass, Liquid) or touching (Xcube).
                    focusedNow -> Modifier.width(style.dpPerMin * span.lengthMin - if (style.roundedCells) 2.dp else 0.dp)
                        .background(style.focusedProgramme, if (style.roundedCells) CELL else RectangleShape)
                        .border(1.dp, FOCUS_BORDER, if (style.roundedCells) CELL else RectangleShape)
                    // Glass/Liquid: the theme's cell colour plus a faint edge, so boxes stay distinct on the lighter right of the background.
                    style.roundedCells -> Modifier.width(style.dpPerMin * span.lengthMin - 2.dp)
                        .background(colors.cellDim.copy(alpha = if (past) .19f else .35f), CELL)
                        .border(1.dp, Color.White.copy(alpha = if (past) .08f else .16f), CELL)
                    else -> Modifier.width(style.dpPerMin * span.lengthMin)
                        .background(colors.cellDim.copy(alpha = if (past) .19f else .35f), RectangleShape)
                }
                val runsBefore = style.overflowArrows && p.start < windowStart
                val runsAfter = style.overflowArrows && p.stop > end
                val rec = marked(p)
                Box(
                    Modifier.offset(x = style.dpPerMin * span.offsetMin).fillMaxHeight().then(cell)
                        // Separator (flat) or ◀ ▶ overflow marks (boxed), drawn in the same pass: no extra nodes.
                        .drawBehind {
                            if (!style.boxed && !style.roundedCells && p.stop <= end && size.width > 1.dp.toPx()) drawRect(SEPARATOR, Offset(size.width - 1.dp.toPx(), 0f), Size(1.dp.toPx(), size.height))
                            val a = 4.dp.toPx(); val mid = size.height / 2
                            val mark = if (focusedNow) style.focusedProgrammeText else Color.White
                            if (runsBefore) drawPath(Path().apply { moveTo(1f, mid); lineTo(1f + a, mid - a); lineTo(1f + a, mid + a); close() }, mark)
                            if (runsAfter) drawPath(Path().apply { val x = size.width - 1f; moveTo(x, mid); lineTo(x - a, mid - a); lineTo(x - a, mid + a); close() }, mark)
                            // Scheduled or recording: a small red dot top-right.
                            if (rec) drawCircle(REC_DOT, 3.dp.toPx(), Offset(size.width - 7.dp.toPx(), 7.dp.toPx()))
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(p.title, fontSize = style.sp(12.5f) * org.imdtv.iptvtv.ui.LocalTitleScale.current, fontWeight = if (focusedNow) FontWeight.SemiBold else FontWeight.Normal,
                        color = (if (focusedNow) style.focusedProgrammeText else Color.White).copy(alpha = if (past && !focusedNow) .55f else 1f), maxLines = style.lines, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp))
                }
            }
        }
    }
}

private val REC_DOT = Color(0xFFE53935)


/** Marks a channel with catch-up (guide channel column, full-screen overlay). */
@Composable
fun CatchUpClock(color: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.then(Modifier.width(11.dp).height(11.dp))) {
        val r = size.minDimension / 2
        val w = 1.3.dp.toPx()
        drawCircle(color, r - w / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        drawLine(color, center, Offset(center.x, center.y - r * .6f), w)
        drawLine(color, center, Offset(center.x + r * .45f, center.y), w)
    }
}
