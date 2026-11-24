package org.imdtv.iptvtv.ui.vod

import org.imdtv.iptvtv.ui.theme.LocalTheme
import org.imdtv.iptvtv.R
import androidx.compose.ui.res.stringResource
import org.imdtv.iptvtv.ui.theme.themeBackground

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import org.imdtv.iptvtv.data.db.CategoryEntity
import org.imdtv.iptvtv.data.model.Episode
import org.imdtv.iptvtv.data.model.Kind
import org.imdtv.iptvtv.player.nextEpisode
import org.imdtv.iptvtv.player.resumeAt
import org.imdtv.iptvtv.ui.ContextMenu
import org.imdtv.iptvtv.ui.onLongOk
import org.imdtv.iptvtv.ui.FocusBox
import org.imdtv.iptvtv.ui.duration
import org.imdtv.iptvtv.ui.live.RailCategory
import org.imdtv.iptvtv.ui.live.SectionRowBox
import org.imdtv.iptvtv.ui.live.otherPlaylistItems
import org.imdtv.iptvtv.ui.live.railTitle
import org.imdtv.iptvtv.ui.live.toggled

private val leftKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft }

/** Movies/Series home (mockup 02 screen 1): backdrop + hero for the focused item, rows per category, ← opens the rail. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
/** [twoRows]: Appearance › Movies & Series rows = 2 rows (smaller posters). [onUsePlaylist]: a category of another
 *  playlist was picked in the rail; switch to that playlist. */
fun VodHomeScreen(vm: VodViewModel, nav: @Composable () -> Unit, onOpen: (VodItem) -> Unit, twoRows: Boolean = false,
                  onUsePlaylist: (CategoryEntity) -> Unit = {}) {
    val kind by vm.kind.collectAsState()
    val cats by vm.categories.collectAsState()
    val favs by vm.favourites.collectAsState()
    val gridCat by vm.gridCategory.collectAsState()
    val grid by vm.grid.collectAsState()
    val focused by vm.focused.collectAsState()
    val details by vm.details.collectAsState()
    val query by vm.query.collectAsState()
    val matches by vm.matches.collectAsState()
    val others by vm.otherPlaylists.collectAsState()
    var railOpen by remember { mutableStateOf(false) }
    val firstItem = remember { FocusRequester() }
    val railFocus = remember { FocusRequester() }
    var refocus by remember { mutableStateOf(0) }
    // Rows load asynchronously, so the first poster grabs focus itself once it exists (per tab / grid).
    val wantFirst = remember(kind, gridCat?.id) { mutableStateOf(true) }

    LaunchedEffect(focused?.id) { val f = focused ?: return@LaunchedEffect; delay(500); vm.loadDetail(f) }
    LaunchedEffect(refocus, railOpen, gridCat?.id, kind) {
        withFrameNanos { }
        runCatching { if (railOpen) railFocus.requestFocus() else firstItem.requestFocus() }
    }
    val claim: @Composable () -> Unit = {
        LaunchedEffect(Unit) { if (wantFirst.value) { withFrameNanos { }; runCatching { firstItem.requestFocus(); wantFirst.value = false } } }
    }

    Box(Modifier.fillMaxSize().themeBackground(LocalTheme.current.colors.vodBg)) {
        Backdrop(focused?.let { details[it.id]?.backdrop ?: it.poster })
        Box(Modifier.padding(start = 50.dp, top = 22.dp)) { nav() }
        // On the search bar's line: the nav's clock and ping take the top right.
        Row(Modifier.align(Alignment.TopEnd).padding(top = 66.dp, end = 50.dp), verticalAlignment = Alignment.CenterVertically) {
            if (vm.updating.collectAsState().value) {
                org.imdtv.iptvtv.ui.Spinner(size = 14.dp)
                Text(stringResource(if (kind == Kind.MOVIE) R.string.vod_updating_movies else R.string.vod_updating_series), fontSize = 11.sp, color = Color.White,
                    modifier = Modifier.padding(start = 6.dp, end = 16.dp))
            }
            Text(stringResource(R.string.vod_category), fontSize = 11.sp, color = LocalTheme.current.colors.muted)
            Text(stringResource(R.string.vod_category_pill, gridCat?.name ?: stringResource(if (kind == Kind.MOVIE) R.string.vod_all_movies else R.string.vod_all_series)), fontSize = 11.sp, color = Color.White,
                fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color(0x1AFFFFFF)).padding(horizontal = 8.dp, vertical = 3.dp))
        }
        // In-category search sits just above the title (search spec); it searches what the rows/grid show.
        LaunchedEffect(kind) { vm.warmSearch(); vm.refreshResume() }
        org.imdtv.iptvtv.ui.search.SearchBar(query, { vm.query.value = it },
            stringResource(R.string.vod_search_in, gridCat?.name ?: stringResource(if (kind == Kind.MOVIE) R.string.vod_all_movies else R.string.vod_all_series)),
            Modifier.padding(start = 50.dp, top = 62.dp).width(410.dp))
        focused?.let { Hero(it, details[it.id], Modifier.padding(start = 50.dp, top = 100.dp).width(410.dp), titleLines = 1) }

        val openRail = { railOpen = true }
        val rowToTop = with(androidx.compose.ui.platform.LocalDensity.current) { remember { rowToTop(6.dp.toPx()) } }
        Box(Modifier.padding(start = 50.dp, top = 260.dp).fillMaxSize()) {
            val found = matches
            if (found != null) {
                if (found.isEmpty()) Text(stringResource(R.string.vod_no_matches), fontSize = 15.sp, color = LocalTheme.current.colors.muted)
                else androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides rowToTop) { LazyVerticalGrid(GridCells.Fixed(7), verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, top = 6.dp, end = 42.dp, bottom = 20.dp),
                    modifier = Modifier.fillMaxSize().offset(x = (-8).dp)) {
                    // Back from detail refocuses the opened match (the screen's first-item focus lands here).
                    val home = found.indexOfFirst { it.id == focused?.id }.coerceAtLeast(0)
                    itemsIndexed(found, key = { _, it -> it.id }) { i, item ->
                        Poster(item, Modifier.then(if (i == home) Modifier.focusRequester(firstItem) else Modifier).onPreviewKeyEvent { if (i % 7 == 0 && leftKey(it)) { openRail(); true } else false }, onFocused = { vm.focused.value = item }, onClick = { onOpen(item) })
                    }
                } }
            } else if (gridCat != null) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides rowToTop) { LazyVerticalGrid(GridCells.Fixed(7), verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, top = 6.dp, end = 42.dp, bottom = 20.dp),
                    modifier = Modifier.fillMaxSize().offset(x = (-8).dp)) {
                    itemsIndexed(grid, key = { _, it -> it.id }) { i, item ->
                        if (i == 0) claim()
                        Poster(item, Modifier.then(if (i == 0) Modifier.focusRequester(firstItem) else Modifier)
                            .onPreviewKeyEvent { if (i % 7 == 0 && leftKey(it)) { openRail(); true } else false },
                            onFocused = { vm.focused.value = item }, onClick = { onOpen(item) })
                    }
                } }
            } else {
                // The focused row scrolls to the top, whole: the TV pivot left the row above half showing under the hero.
                // Bottom padding lets the last row reach the top too. Rows keep the TV scroll sideways ([sideways]).
                val sideways = androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
                val poster = if (twoRows) 90.dp else 165.dp
                val gap = if (twoRows) 8.dp else 18.dp
                val density = androidx.compose.ui.platform.LocalDensity.current
                val rowSpec = remember(density) { with(density) { rowToTop((ROW_TITLE + 12.dp).toPx()) } }
                BoxWithConstraints(Modifier.fillMaxSize()) { androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides rowSpec) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    bottom = (maxHeight - rowHeight(poster, gap)).coerceAtLeast(0.dp))) {
                    if (favs.isNotEmpty()) item(key = "fav") {
                        PosterRow(stringResource(R.string.vod_favourites_row), favs, firstItem, vm, onOpen, openRail, claim, poster = poster, gap = gap, sideways = sideways)
                    }
                    itemsIndexed(cats, key = { _, c -> c.category.id }) { i, rc ->
                        val cont = rc.category.id == VodViewModel.CONTINUE_ID
                        val items by if (cont) vm.continueWatching.collectAsState()
                            else produceState<List<VodItem>?>(null, rc.category.id) { value = vm.inCategory(rc.category.id) }
                        val rows by vm.progress.collectAsState()
                        PosterRow(rc.category.name.ifEmpty { stringResource(R.string.vod_uncategorised) }, items, firstItem.takeIf { i == 0 && favs.isEmpty() }, vm, onOpen, openRail, claim,
                            progress = { if (cont) progressFraction(it, rows) else null }, poster = poster, gap = gap, sideways = sideways)
                    }
                    // All covers every playlist in use: the other playlists' categories follow, "Plex (Home) › Films".
                    others.forEach { o ->
                        items(o.categories, key = { "o${it.first.id}" }) { (cat, _) ->
                            val items by produceState<List<VodItem>?>(null, cat.id) { value = vm.inCategory(cat.id) }
                            PosterRow("${o.provider.railTitle()} › ${cat.name.ifEmpty { stringResource(R.string.vod_uncategorised) }}", items, firstItem.takeIf { favs.isEmpty() && cats.isEmpty() && o === others.first() && cat == o.categories.first().first },
                                vm, onOpen, openRail, claim, poster = poster, gap = gap, sideways = sideways)
                        }
                    }
                } } }
            }
        }
        var lockMenu by remember { mutableStateOf<CategoryEntity?>(null) }
        lockMenu?.let { cat -> org.imdtv.iptvtv.ui.ContextMenu(cat.name, listOf(
            stringResource(if (cat.parentalLock) R.string.live_menu_parental_off else R.string.live_menu_parental_on) to { vm.setParentalLock(cat, !cat.parentalLock); lockMenu = null })) { lockMenu = null } }
        if (railOpen) VodRail(cats, gridCat?.id, railFocus, onLongPress = { c -> if (vm.parentalOn.value) lockMenu = c },
            onPick = { vm.gridCategory.value = it; railOpen = false },
            onClose = { railOpen = false; refocus++ },
            others = others, expanded = vm.expandedPlaylists.collectAsState().value,
            onToggle = { id -> vm.expandedPlaylists.value = vm.expandedPlaylists.value.toggled(id) },
            onPickOther = { cat -> if (vm.openOther(cat)) onUsePlaylist(cat); railOpen = false },
            activeTitle = vm.activeProvider.collectAsState().value?.railTitle())
    }
}

private const val PLACEHOLDERS = 6

/** Poster grids put the focused row at the top, whole; Android TV's default pivot left half a row above it. [padPx]: room for the focus scale. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun rowToTop(padPx: Float) = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = offset - padPx
}

private val ROW_TITLE = 18.dp
/** Title + gap + the LazyRow's 6dp focus padding top and bottom + poster with its 3dp frame + [gap] below. */
private fun rowHeight(poster: androidx.compose.ui.unit.Dp, gap: androidx.compose.ui.unit.Dp) = ROW_TITLE + 6.dp + 12.dp + poster + 6.dp + gap

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PosterRow(title: String, items: List<VodItem>?, focus: FocusRequester?, vm: VodViewModel, onOpen: (VodItem) -> Unit, openRail: () -> Unit, claim: @Composable () -> Unit,
                      progress: (VodItem) -> Float? = { null }, poster: androidx.compose.ui.unit.Dp = 165.dp, gap: androidx.compose.ui.unit.Dp = 18.dp,
                      sideways: androidx.compose.foundation.gestures.BringIntoViewSpec) {
    Column(Modifier.padding(bottom = gap)) {
        // Fixed line height: the column's scroll-to-top offset counts on it ([ROW_TITLE]).
        Text(title, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 1, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.height(ROW_TITLE))
        Spacer(Modifier.height(6.dp))
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides sideways) {
        // Padding leaves room for the focused poster's 1.06× scale and outline, which a LazyRow would clip.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(11.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 50.dp, top = 6.dp, bottom = 6.dp),
            modifier = Modifier.padding(start = 0.dp).offset(x = (-8).dp)) {
            // While loading (null) the row shows focusable placeholders: an empty row has nothing to focus, so a fast
            // Down made the focus search lay out every remaining row in one key event (ANR). Keyed by position so the
            // placeholder that has focus becomes the real poster in the same slot and keeps it.
            itemsIndexed(items ?: List(PLACEHOLDERS) { null }, key = { i, _ -> i }) { i, item ->
                if (i == 0 && focus != null) claim()
                Poster(item, Modifier.then(if (i == 0 && focus != null) Modifier.focusRequester(focus) else Modifier)
                    .onPreviewKeyEvent { if (i == 0 && leftKey(it)) { openRail(); true } else false },
                    progress = item?.let(progress), height = poster, onFocused = { item?.let { vm.focused.value = it } }, onClick = { item?.let(onOpen) })
            }
        }
        }
    }
}

/** [item] null = a placeholder card while its row loads. */
@Composable
/** [progress] 0–1 draws a watched-so-far bar along the bottom. */
fun Poster(item: VodItem?, modifier: Modifier = Modifier, caption: Boolean = false, progress: Float? = null,
           height: androidx.compose.ui.unit.Dp = 165.dp, onFocused: () -> Unit, onClick: () -> Unit) {
    val width = height * 2 / 3
    FocusBox(onClick = onClick, onFocused = onFocused, modifier = modifier) { f -> Column(Modifier.width(width + 6.dp)) {
        // A placeholder that already has focus becomes this item without a focus change: report it now.
        if (f && item != null) LaunchedEffect(item.id) { onFocused() }
        Box(
            Modifier.graphicsLayer { val s = if (f) 1.06f else 1f; scaleX = s; scaleY = s }
                .border(2.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(8.dp)).padding(3.dp)
                .size(width, height).clip(RoundedCornerShape(6.dp)).background(LocalTheme.current.colors.vodCard),
            contentAlignment = Alignment.BottomStart,
        ) {
            if (item != null) {
                Text(item.name, fontSize = 11.sp * org.imdtv.iptvtv.ui.LocalVodTitleScale.current, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 3, modifier = Modifier.padding(7.dp))
                if (item.poster != null) AsyncImage(item.poster, item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (progress != null) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(Color(0x66FFFFFF))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(LocalTheme.current.colors.accent))
                }
            }
        }
        if (caption && item != null) Text(item.name, fontSize = 12.sp * org.imdtv.iptvtv.ui.LocalVodTitleScale.current, lineHeight = 15.sp * org.imdtv.iptvtv.ui.LocalVodTitleScale.current, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 3.dp, top = 6.dp))
    } }
}

@Composable
fun Backdrop(url: String?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Box(Modifier.size(700.dp, 410.dp)) {
            if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to LocalTheme.current.colors.vodBg, .22f to LocalTheme.current.colors.vodBg.copy(alpha = .85f), .6f to LocalTheme.current.colors.vodBg.copy(alpha = .2f), 1f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.45f to Color.Transparent, .75f to LocalTheme.current.colors.vodBg.copy(alpha = .6f), 1f to LocalTheme.current.colors.vodBg)))
        }
    }
}

@Composable
private fun Meta(item: VodItem, d: VodDetail?, extra: String? = null, resolution: String? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 11.dp)) {
        item.year?.let { Text(it.toString(), fontSize = 12.sp, color = Color(0xFFDDDDDD)) }
        extra?.let { Text(it, fontSize = 12.sp, color = Color(0xFFDDDDDD)) }
        resolution?.let { Text(it, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
            modifier = Modifier.border(1.dp, Color(0xB3FFFFFF), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) }
        d?.durationSecs?.takeIf { it > 0 }?.let { Text(duration(it * 1000L), fontSize = 12.sp, color = Color(0xFFDDDDDD)) }
        item.rating?.takeIf { it > 0 }?.let { Text("★ ${"%.1f".format(it)}", fontSize = 12.sp, color = LocalTheme.current.colors.accent) }
        d?.genre?.let { Text(it.replace(", ", " · "), fontSize = 12.sp, color = Color(0xFFDDDDDD), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun Hero(item: VodItem, d: VodDetail?, modifier: Modifier, extra: String? = null, titleLines: Int = 2, resolution: String? = null) {
    Column(modifier) {
        Text(item.name, fontSize = 40.sp * org.imdtv.iptvtv.ui.LocalVodTitleScale.current, lineHeight = 42.sp * org.imdtv.iptvtv.ui.LocalVodTitleScale.current, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = titleLines, overflow = TextOverflow.Ellipsis)
        Meta(item, d, extra, resolution)
        d?.plot?.let {
            Text(it, fontSize = 12.5.sp, lineHeight = 18.sp, color = Color(0xFFD6D6D6), maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/**
 * All and Continue watching (every playlist's) on top; then this playlist as a section header ([activeTitle], OK opens
 * or closes it) with its categories under it, then the other playlists' sections.
 */
@Composable
private fun VodRail(cats: List<RailCategory>, selectedId: Long?, focus: FocusRequester, onLongPress: (CategoryEntity) -> Unit, onPick: (CategoryEntity?) -> Unit, onClose: () -> Unit,
                    others: List<org.imdtv.iptvtv.data.repo.OtherPlaylist>, expanded: Set<Long>, onToggle: (Long) -> Unit, onPickOther: (CategoryEntity) -> Unit,
                    activeTitle: String?) {
    var open by remember { mutableStateOf(true) }
    val (lead, own) = (listOf<RailCategory?>(null) + cats).partition { it == null || it.category.id == VodViewModel.CONTINUE_ID }
    fun key(rc: RailCategory?) = "v${rc?.category?.id ?: -1L}"
    val shownOwn = if (open || activeTitle == null) own else emptyList()
    // Hold Up/Down jumps by section: the top rows, this playlist, then each other playlist, as on the Live rail.
    val keys = remember(cats, others, expanded, open, activeTitle) {
        lead.map(::key) + listOfNotNull(activeTitle?.let { "pa" }) + shownOwn.map(::key) + org.imdtv.iptvtv.ui.live.otherPlaylistKeys(others, expanded)
    }
    val starts = remember(keys) { keys.indices.filter { it == 0 || keys[it].startsWith("p") } }
    val selectedKey = "v${selectedId ?: -1L}".takeIf { it in keys } ?: "v-1"
    val state = rememberLazyListState(keys.indexOf(selectedKey).coerceAtLeast(0))
    val railKeys = org.imdtv.iptvtv.ui.live.rememberRailKeys(state, keys, starts)
    Column(
        Modifier.fillMaxHeight().width(260.dp).shadow(30.dp).background(Color(0xF515171D)).padding(horizontal = 20.dp, vertical = 35.dp)
            .onPreviewKeyEvent { e -> if (railKeys.onKey(e)) true else if (e.type == KeyEventType.KeyDown && (e.key == Key.Back || e.key == Key.DirectionLeft && e.nativeKeyEvent.repeatCount == 0)) { onClose(); true } else false },
    ) {
        Text(stringResource(R.string.vod_categories_header), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = LocalTheme.current.colors.muted, letterSpacing = 2.sp, modifier = Modifier.padding(bottom = 12.dp))
        if (others.isNotEmpty()) Text(stringResource(R.string.live_rail_jump_hint), fontSize = 11.sp, color = LocalTheme.current.colors.muted, modifier = Modifier.padding(bottom = 8.dp))
        @Composable
        fun CatRow(rc: RailCategory?, nested: Boolean) {
            // Right picks, as on the Live rail; unhandled it moved focus behind the rail and left the rail open.
            FocusBox(onClick = { onPick(rc?.category) }, modifier = (if (key(rc) == selectedKey) Modifier.focusRequester(focus) else Modifier).then(railKeys.track(key(rc)))
                .then(rc?.category?.takeIf { it.id != VodViewModel.CONTINUE_ID }?.let { c -> Modifier.onLongOk { onLongPress(c) } } ?: Modifier)
                .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { onPick(rc?.category); true } else false }) { f ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)).background(if (f) Color.White else Color.Transparent)
                    .padding(start = if (nested) 25.dp else 11.dp, end = 11.dp, top = 8.dp, bottom = 8.dp)) {
                    val fg = if (f) Color.Black else Color.White
                    Text(rc?.category?.name?.ifEmpty { stringResource(R.string.vod_uncategorised) } ?: stringResource(R.string.vod_all), fontSize = 14.sp, color = fg, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    rc?.let { Text(it.count.toString(), fontSize = 11.sp, color = if (f) fg else LocalTheme.current.colors.muted) }
                }
            }
        }
        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(lead, key = ::key) { CatRow(it, nested = false) }
            if (activeTitle != null) item(key = "pa") {
                Box(railKeys.track("pa")) { SectionRowBox(activeTitle, if (open) "⌃" else "⌄", true, false, { open = !open }, Color.White, Color.Black) }
            }
            items(shownOwn, key = ::key) { CatRow(it, nested = activeTitle != null) }
            otherPlaylistItems(others, expanded, onToggle, onPickOther, railKeys::track) { title, trailing, header, nested, onClick ->
                SectionRowBox(title, trailing, header, nested, onClick, Color.White, Color.Black)
            }
        }
    }
}

@Composable
private fun Btn(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = modifier) { f ->
        Text(
            text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (f) Color.Black else Color.White,
            modifier = Modifier.graphicsLayer { val s = if (f) 1.05f else 1f; scaleX = s; scaleY = s }
                .clip(RoundedCornerShape(6.dp)).background(if (f) Color.White else Color(0x1FFFFFFF))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** Movie detail (mockup 02 screen 3) and series detail with season tabs and episode cards (screen 2). */
@Composable
fun VodDetailScreen(vm: VodViewModel, item: VodItem, onPlay: (url: String, title: String, episode: String, startMs: Long) -> Unit, onOpen: (VodItem) -> Unit) {
    val details by vm.details.collectAsState()
    val favs by vm.favourites.collectAsState()
    val rows by vm.progress.collectAsState()
    val d = details[item.id]
    val context = androidx.compose.ui.platform.LocalContext.current
    fun episodeTitle(ep: Episode) = context.getString(R.string.vod_episode_title, item.name, ep.season, ep.number)
    val play = remember { FocusRequester() }
    LaunchedEffect(item.id) { vm.loadDetail(item); withFrameNanos { }; runCatching { play.requestFocus() } }
    val fav = favs.any { it.id == item.id }
    vm.learnedHeights.collectAsState().value // recompose when a height is learned
    val resolution = (d?.height ?: vm.learnedHeight(item))?.let { org.imdtv.iptvtv.player.resolutionLabel(it) }

    Box(Modifier.fillMaxSize().themeBackground(LocalTheme.current.colors.vodBg)) {
        Backdrop(d?.backdrop ?: item.poster)
        if (item.kind == Kind.MOVIE) {
            Column(Modifier.padding(start = 50.dp, top = 55.dp).width(430.dp)) {
                Hero(item, d, Modifier, resolution = resolution)
                listOfNotNull(d?.director?.let { stringResource(R.string.vod_director, it) }, d?.cast?.let { stringResource(R.string.vod_cast, it) }).takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString("  ·  "), fontSize = 10.5.sp, color = LocalTheme.current.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp).wrapContentWidth(Alignment.Start, unbounded = true)) { // four buttons may run past the column
                    val row = rows.find { it.kind == item.kind && it.itemId == item.id && it.episode == "" }
                    val at = resumeAt(row)
                    if (at > 0) {
                        Btn(stringResource(R.string.vod_btn_resume_from, duration(at)), Modifier.focusRequester(play)) { item.streamUrl?.let { onPlay(it, item.name, "", at) } }
                        Btn(stringResource(R.string.vod_btn_from_start)) { item.streamUrl?.let { onPlay(it, item.name, "", 0) } }
                    } else Btn(stringResource(R.string.vod_btn_play), Modifier.focusRequester(play)) { item.streamUrl?.let { onPlay(it, item.name, "", 0) } }
                    Btn(stringResource(if (fav) R.string.vod_btn_favourited else R.string.vod_btn_favourite)) { vm.toggleFavourite(item) }
                    Btn(stringResource(if (row?.completed == true) R.string.vod_btn_watched else R.string.vod_btn_mark_watched)) { vm.setWatched(item, row?.completed != true) }
                }
            }
            item.categoryId?.let { catId ->
                val more by produceState(emptyList<VodItem>(), catId) { value = vm.inCategory(catId).filter { it.id != item.id } }
                // Bottom-anchored inside the TV-safe area; padding leaves room for the focused poster's scale and outline.
                Column(Modifier.align(Alignment.BottomStart).padding(start = 50.dp, bottom = 20.dp)) {
                    Text(stringResource(R.string.vod_more_like_this), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(bottom = 2.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(11.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 50.dp, top = 6.dp, bottom = 6.dp),
                        modifier = Modifier.offset(x = (-8).dp)) {
                        items(more, key = { it.id }) { m -> Poster(m, onFocused = {}, onClick = { onOpen(m) }) }
                    }
                }
            }
        } else {
            val eps = d?.episodes.orEmpty()
            val seasons = eps.map { it.season }.distinct().sorted()
            var season by remember(item.id) { mutableStateOf<Int?>(null) }
            val seriesRows = rows.filter { it.kind == item.kind && it.itemId == item.id }
            val next = nextEpisode(eps, seriesRows)
            val sel = season ?: next?.episode?.season ?: seasons.firstOrNull()
            var menuFor by remember(item.id) { mutableStateOf<Episode?>(null) }
            Column(Modifier.padding(start = 50.dp, top = 55.dp).width(430.dp)) {
                Hero(item, d, Modifier, extra = seasons.size.takeIf { it > 0 }?.let { androidx.compose.ui.res.pluralStringResource(R.plurals.vod_seasons, it, it) }, resolution = resolution)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp).wrapContentWidth(Alignment.Start, unbounded = true)) { // four buttons may run past the column
                    val ne = next?.episode
                    val ntitle = ne?.let { episodeTitle(it) }
                    if (ne == null) Btn(stringResource(R.string.vod_btn_play), Modifier.focusRequester(play)) { }
                    else if (next.resume) {
                        Btn(stringResource(R.string.vod_btn_resume_episode, ne.season, ne.number), Modifier.focusRequester(play)) { onPlay(ne.streamUrl, ntitle!!, ne.remoteId, next.startMs) }
                        Btn(stringResource(R.string.vod_btn_from_start)) { onPlay(ne.streamUrl, ntitle!!, ne.remoteId, 0) }
                    } else Btn(stringResource(R.string.vod_btn_play_episode, ne.season, ne.number), Modifier.focusRequester(play)) { onPlay(ne.streamUrl, ntitle!!, ne.remoteId, 0) }
                    Btn(stringResource(if (fav) R.string.vod_btn_favourited else R.string.vod_btn_favourite)) { vm.toggleFavourite(item) }
                    if (ne != null) Btn(stringResource(R.string.vod_btn_mark_episode_watched, ne.number)) { vm.setWatched(item, true, ne.remoteId) }
                }
            }
            Column(Modifier.padding(start = 50.dp, top = 300.dp)) {
                if (d == null) Text(stringResource(R.string.vod_loading_episodes), fontSize = 12.sp, color = LocalTheme.current.colors.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 13.dp)) {
                    seasons.forEach { s ->
                        FocusBox(onClick = { season = s }, onFocused = { season = s }) { f ->
                            Text(stringResource(R.string.vod_season_tab, s), fontSize = 11.5.sp, fontWeight = FontWeight.Medium,
                                color = if (s == sel) Color.Black else LocalTheme.current.colors.muted,
                                modifier = Modifier.border(1.5.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(17.dp)).padding(2.dp)
                                    .clip(RoundedCornerShape(15.dp)).background(if (s == sel) Color.White else Color(0x14FFFFFF))
                                    .padding(horizontal = 13.dp, vertical = 5.dp))
                        }
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(13.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(end = 50.dp)) {
                    items(eps.filter { it.season == sel }, key = { it.remoteId }) { ep ->
                        val r = seriesRows.find { it.episode == ep.remoteId }
                        FocusBox(onClick = { onPlay(ep.streamUrl, episodeTitle(ep), ep.remoteId, resumeAt(r)) },
                            modifier = Modifier.width(216.dp).onLongOk { menuFor = ep }) { f ->
                            Column {
                                Box(
                                    Modifier.graphicsLayer { val s = if (f) 1.06f else 1f; scaleX = s; scaleY = s }
                                        .border(2.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(8.dp)).padding(3.dp)
                                        .size(210.dp, 118.dp).clip(RoundedCornerShape(6.dp)).background(LocalTheme.current.colors.vodCard),
                                ) {
                                    (ep.thumb ?: d?.backdrop)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                    Chip(stringResource(R.string.vod_episode_short, ep.number), Modifier.align(Alignment.BottomStart))
                                    ep.durationSecs?.takeIf { it > 0 }?.let { Chip(duration(it * 1000L), Modifier.align(Alignment.BottomEnd)) }
                                    if (r != null && !r.completed && r.durationMs > 0) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(Color(0x66FFFFFF))) {
                                        Box(Modifier.fillMaxHeight().fillMaxWidth((r.positionMs.toFloat() / r.durationMs).coerceIn(0f, 1f)).background(LocalTheme.current.colors.accent))
                                    }
                                    if (r?.completed == true) Chip("✓", Modifier.align(Alignment.TopEnd))
                                }
                                Text(ep.title, fontSize = 12.sp * org.imdtv.iptvtv.ui.LocalVodTitleScale.current, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, bottom = 3.dp))
                                ep.plot?.let { Text(it, fontSize = 9.5.sp, lineHeight = 13.sp, color = LocalTheme.current.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
            }
            menuFor?.let { ep ->
                val r = seriesRows.find { it.episode == ep.remoteId }
                val items = listOfNotNull<Pair<String, () -> Unit>>(
                    if (r != null && !r.completed && r.positionMs > 0) stringResource(R.string.vod_menu_play_from_start) to { menuFor = null; onPlay(ep.streamUrl, episodeTitle(ep), ep.remoteId, 0) } else null,
                    if (r?.completed == true) stringResource(R.string.vod_menu_mark_unwatched) to { menuFor = null; vm.setWatched(item, false, ep.remoteId) }
                    else stringResource(R.string.vod_btn_mark_watched) to { menuFor = null; vm.setWatched(item, true, ep.remoteId) },
                )
                ContextMenu(stringResource(R.string.vod_season_episode, ep.season, ep.number), items) { menuFor = null }
            }
        }
    }
}

@Composable
private fun Chip(text: String, modifier: Modifier) {
    Text(text, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White,
        modifier = modifier.padding(7.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x99000000)).padding(horizontal = 5.dp, vertical = 1.5.dp))
}
