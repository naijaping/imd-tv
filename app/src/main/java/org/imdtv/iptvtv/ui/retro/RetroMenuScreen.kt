package org.imdtv.iptvtv.ui.retro

import org.imdtv.iptvtv.ui.live.titleText
import org.imdtv.iptvtv.ui.live.toggled
import org.imdtv.iptvtv.R
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import org.imdtv.iptvtv.data.db.CategoryEntity
import org.imdtv.iptvtv.data.repo.RailEntry
import org.imdtv.iptvtv.ui.AppLogo
import org.imdtv.iptvtv.ui.FocusBox
import org.imdtv.iptvtv.ui.Tab
import org.imdtv.iptvtv.ui.live.LiveViewModel
import org.imdtv.iptvtv.ui.theme.LocalTheme
import org.imdtv.iptvtv.ui.theme.ThemeColors
import org.imdtv.iptvtv.ui.vod.VodViewModel

private val RETRO_TABS = listOf(Tab.SEARCH to R.string.vod_retro_tab_search, Tab.LIVE to R.string.vod_retro_tab_guide, Tab.MOVIES to R.string.vod_retro_tab_movies,
    Tab.SERIES to R.string.vod_retro_tab_series, Tab.RECORDINGS to R.string.vod_retro_tab_recordings, Tab.SETTINGS to R.string.vod_retro_tab_settings)

/** One bar's title and what picking it opens. */
/** [other]: a category of another playlist (picking it switches playlist); [toggle]: that playlist's header bar. */
private class Bar(val title: String, val live: RailEntry?, val vod: CategoryEntity?, val other: CategoryEntity? = null, val toggle: Long? = null)

/**
 * Celestial Retro menu (spec §6A.5): icon tabs, then nine numbered category bars per page, 0 MORE… and BACK.
 * [menuFocus]/[onMenuFocused] make the tabs the screen's "menu" for the app's root Back handling.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun RetroMenuScreen(
    tab: Tab, live: LiveViewModel, vod: VodViewModel, onTab: (Tab) -> Unit, onLiveCategory: (RailEntry) -> Unit,
    onVodCategory: (CategoryEntity) -> Unit, menuFocus: FocusRequester, onMenuFocused: (Boolean) -> Unit,
    /** The SEARCH tab's content, shown under the tabs in place of the bars. */
    search: @Composable () -> Unit = {},
    /** The RECORDINGS tab's content; the tab shows only while recording is on. */
    recordings: (@Composable () -> Unit)? = null,
) {
    val colors = LocalTheme.current.colors
    val uncategorised = stringResource(R.string.vod_uncategorised)
    val rail by live.rail.collectAsState()
    val cats by vod.categories.collectAsState()
    val entry by live.entry.collectAsState()
    val others by (if (tab == Tab.LIVE) live.otherPlaylists else vod.otherPlaylists).collectAsState()
    val expandedFlow = if (tab == Tab.LIVE) live.expandedPlaylists else vod.expandedPlaylists
    val expanded by expandedFlow.collectAsState()
    val all = (if (tab == Tab.LIVE) rail.orEmpty().map { Bar(it.entry.titleText(), it.entry, null) }
        else cats.map { Bar(it.category.name.ifEmpty { uncategorised }, null, it.category) }) +
        // Then the other playlists, each a header bar with its categories under it when opened.
        others.flatMap { o ->
            val open = o.provider.id in expanded
            listOf(Bar("${o.provider.name} ${if (open) "⌃" else "⌄"}", null, null, toggle = o.provider.id)) +
                if (open) o.categories.map { (c, _) -> Bar("   " + c.name.ifEmpty { uncategorised }, null, null, other = c) } else emptyList()
        }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var page by remember(tab) { mutableIntStateOf(-1) } // -1: not placed yet (the list may still be loading)
    var focusRow by remember(tab) { mutableIntStateOf(0) }
    var refocus by remember { mutableIntStateOf(0) }
    // Open on the page and bar of the category the guide came from.
    LaunchedEffect(tab, all.isNotEmpty()) {
        if (all.isEmpty() || page >= 0) return@LaunchedEffect
        val i = if (tab == Tab.LIVE) all.indexOfFirst { it.live?.key == entry?.key }.coerceAtLeast(0) else 0
        page = i / RETRO_PAGE; focusRow = i % RETRO_PAGE; refocus++
    }
    val pg = retroPage(all, page.coerceAtLeast(0))
    val bars = remember { List(RETRO_PAGE + 2) { FocusRequester() } }
    LaunchedEffect(refocus) {
        if (refocus == 0) return@LaunchedEffect
        withFrameNanos { }
        runCatching { bars[focusRow.coerceAtMost((pg.items.size - 1).coerceAtLeast(0))].requestFocus() }
    }
    // Launching into the last channel and pressing Back lands here: nothing should keep playing unseen.
    LaunchedEffect(Unit) { live.player.stop() }
    fun turn(to: Int) { page = to; focusRow = 0; refocus++ }
    BackHandler(enabled = pg.page > 0) { turn(pg.page - 1) }

    Box(Modifier.fillMaxSize().drawWithCache {
        val img = retroSky(colors, this, size)
        onDrawBehind { drawImage(img) }
    }) {
        // Entering the tabs from below lands on the current tab, not the nearest one.
        Row(Modifier.padding(start = 60.dp, top = 30.dp).onFocusChanged { onMenuFocused(it.hasFocus) }
            .focusProperties { enter = { menuFocus } }.focusGroup(),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AppLogo(Modifier.size(52.dp))
            RETRO_TABS.filter { it.first != Tab.RECORDINGS || recordings != null }.forEach { (t, labelRes) ->
                FocusBox(onClick = { onTab(t) }, modifier = if (t == tab) Modifier.focusRequester(menuFocus) else Modifier) { f ->
                    Column(
                        Modifier.background(when { f -> colors.focus; t == tab -> colors.cell; else -> Color.Transparent }, RoundedCornerShape(4.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val fg = if (f) colors.onFocus else if (t == tab) Color.White else colors.panel
                        Icon(painterResource(t.icon), contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
                        Text(stringResource(labelRes), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = fg)
                    }
                }
            }
        }

        if (tab == Tab.SEARCH) Box(Modifier.padding(start = 60.dp, end = 60.dp, top = 110.dp, bottom = 24.dp)) { search() }
        else if (tab == Tab.RECORDINGS) Box(Modifier.padding(start = 60.dp, end = 60.dp, top = 110.dp, bottom = 24.dp)) { recordings?.invoke() }
        else if (all.isEmpty() && tab == Tab.LIVE && rail != null) {
            Text(stringResource(R.string.vod_live_categories_hidden), fontSize = 16.sp,
                color = colors.panel, modifier = Modifier.padding(start = 60.dp, top = 160.dp))
        }
        if (tab != Tab.SEARCH && tab != Tab.RECORDINGS) Column(Modifier.align(Alignment.TopCenter).padding(top = 120.dp).width(520.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            pg.items.forEachIndexed { i, b ->
                RetroBar("${i + 1}", b.title.uppercase(), bars[i], colors) {
                    b.live?.let(onLiveCategory); b.vod?.let(onVodCategory)
                    b.toggle?.let { id -> expandedFlow.value = expandedFlow.value.toggled(id) }
                    b.other?.let { c ->
                        if (tab == Tab.LIVE) {
                            live.usePlaylistAt(c.providerId, c.id)
                            // Opens the guide on that category once the switched-to playlist's rail has it.
                            scope.launch { live.entry.first { it?.key == "c${c.id}" }?.let(onLiveCategory) }
                        } else if (vod.openOther(c)) live.usePlaylistAt(c.providerId, null) // the grid opens when it loads
                    }
                }
            }
            if (pg.hasMore) RetroBar("0", stringResource(R.string.vod_retro_more), bars[RETRO_PAGE], colors) { turn(pg.page + 1) }
            if (pg.hasBack) RetroBar("", stringResource(R.string.vod_retro_back), bars[RETRO_PAGE + 1], colors) { turn(pg.page - 1) }
        }
    }
}

@Composable
private fun RetroBar(number: String, title: String, focus: FocusRequester, colors: ThemeColors, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = Modifier.focusRequester(focus)) { f ->
        Row(Modifier.height(27.dp)) {
            Box(Modifier.width(44.dp).fillMaxHeight().background(if (f) colors.focus else colors.panel), contentAlignment = Alignment.Center) {
                Text(number, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (f) colors.onFocus else colors.muted)
            }
            Box(Modifier.weight(1f).fillMaxHeight().background(if (f) colors.focus else colors.cell).padding(start = 12.dp),
                contentAlignment = Alignment.CenterStart) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (f) colors.onFocus else Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Pale sky with soft clouds, rasterised once per size (spec §6A.2 performance rule). */
internal fun retroSky(colors: ThemeColors, density: Density, size: Size): ImageBitmap {
    val img = ImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(img), size) {
        drawRect(Brush.verticalGradient(colors.bg))
        listOf(Triple(.18f, .78f, .35f), Triple(.72f, .25f, .3f), Triple(.88f, .85f, .28f)).forEach { (x, y, r) ->
            drawCircle(Brush.radialGradient(listOf(Color(0x99FFFFFF), Color.Transparent), Offset(size.width * x, size.height * y), size.width * r),
                radius = size.width * r, center = Offset(size.width * x, size.height * y))
        }
    }
    return img
}
