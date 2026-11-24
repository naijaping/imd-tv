package org.imdtv.iptvtv.ui.xcube

import org.imdtv.iptvtv.ui.live.otherPlaylistItems
import androidx.compose.ui.res.stringResource
import org.imdtv.iptvtv.ui.live.SectionRowBox
import org.imdtv.iptvtv.ui.live.toggled
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import org.imdtv.iptvtv.R
import org.imdtv.iptvtv.ui.FocusBox
import org.imdtv.iptvtv.ui.vod.Poster
import org.imdtv.iptvtv.ui.vod.VodItem
import org.imdtv.iptvtv.ui.vod.VodViewModel

private val CARD_TEXT = Color(0xFF1A3A0E)

/** Xcube 180 Movies/Series blade (spec §6A.7): title card, backdrop + plot, category list, 3-column poster grid. */
@Composable
/** [onUsePlaylist]: OK on a category of another playlist; switch to that playlist (its grid opens when loaded). */
fun XcubeVod(vm: VodViewModel, card: XcubeCard, onOpen: (VodItem) -> Unit, onUsePlaylist: (org.imdtv.iptvtv.data.db.CategoryEntity) -> Unit = {}) {
    val others by vm.otherPlaylists.collectAsState()
    val expanded by vm.expandedPlaylists.collectAsState()
    val cats by vm.categories.collectAsState()
    val selected by vm.gridCategory.collectAsState()
    val grid by vm.grid.collectAsState()
    val focused by vm.focused.collectAsState()
    val details by vm.details.collectAsState()
    val query by vm.query.collectAsState()
    val matches by vm.matches.collectAsState()
    val kind by vm.kind.collectAsState()
    LaunchedEffect(kind) { vm.warmSearch() }
    val shown = matches ?: grid
    // Start on the first category, also after Movies ↔ Series (setKind clears it); the detail page keeps the one picked.
    // Only a category of the current kind: right after Movies ↔ Series, cats can still be the other kind's (blank Series tab bug).
    LaunchedEffect(cats, selected, kind) {
        if (selected == null) cats.firstOrNull()?.category?.takeIf { it.kind == kind }?.let { vm.gridCategory.value = it }
    }
    val item = focused?.takeIf { f -> shown.any { it.id == f.id } } ?: shown.firstOrNull()
    val d = item?.let { details[it.id] }
    LaunchedEffect(item?.id) { item?.let(vm::loadDetail) }
    // Back from the detail page lands on the poster that was opened.
    val restoreId = remember { focused?.id.takeUnless { card.arrivedBySwitch } } // switched to: the card keeps focus
    val restore = remember { FocusRequester() }
    LaunchedEffect(grid.any { it.id == restoreId }) {
        if (restoreId != null && grid.any { it.id == restoreId }) { withFrameNanos { }; runCatching { restore.requestFocus() } }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(204.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            org.imdtv.iptvtv.ui.search.SearchBar(query, { vm.query.value = it }, stringResource(R.string.vod_search_in, selected?.name ?: stringResource(R.string.vod_all)),
                Modifier.width(360.dp), color = Color.White, background = Color(0x33000000), onSide = card.onBlade)
            FocusBox(onClick = { item?.let(onOpen) }, modifier = Modifier.bladeCard(card)) { f ->
                Column(Modifier.width(360.dp).clip(RoundedCornerShape(10.dp)).border(2.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFFF4F8F0), Color(0xFFD5E8C8))))) {
                    Text(item?.name ?: "—", fontSize = 16.sp * org.imdtv.iptvtv.ui.LocalVodTitleScale.current, fontWeight = FontWeight.SemiBold, color = CARD_TEXT, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth()
                            .background(Brush.horizontalGradient(listOf(Color(0xFFE9D58A), Color(0xFFF8F3D0)))).padding(horizontal = 12.dp, vertical = 5.dp))
                    Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(64.dp, 96.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF3A4A30))) {
                            item?.poster?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        }
                        Column {
                            item?.year?.let { Text("$it", fontSize = 14.sp, color = CARD_TEXT) }
                            item?.rating?.takeIf { it > 0 }?.let { Text("★ %.1f".format(it), fontSize = 14.sp, color = CARD_TEXT) }
                            d?.genre?.let { Text(it, fontSize = 12.sp, color = CARD_TEXT.copy(alpha = .8f), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
          }
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(118.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x33000000))) {
                    d?.backdrop?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Text(d?.plot.orEmpty(), fontSize = 12.sp, lineHeight = 16.sp, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp))
            }
        }
        Row(Modifier.fillMaxSize().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            // Right with an empty grid (empty category, no matches) has nothing to land on: stay, don't switch blade.
            LazyColumn(Modifier.width(300.dp).fillMaxSize().onPreviewKeyEvent { e ->
                e.key == Key.DirectionRight && shown.isEmpty()
            }) {
                items(cats, key = { it.category.id }) { c ->
                    FocusBox(onClick = {}, onFocused = { if (selected?.id != c.category.id) vm.gridCategory.value = c.category }) { f ->
                        Row(Modifier.fillMaxWidth().background(if (f) Color(0xFFE8F5D8) else Color.Transparent, RoundedCornerShape(6.dp))
                            .drawBehind { drawRect(Color(0x40FFFFFF), Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
                            .padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            val fg = if (f) CARD_TEXT else if (selected?.id == c.category.id) Color.White else Color.White.copy(alpha = .8f)
                            Icon(painterResource(R.drawable.ic_cat_folder), null, tint = fg, modifier = Modifier.size(16.dp))
                            Text("  " + c.category.name, fontSize = 14.sp, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                // Other playlists switch on OK only: these rows don't select on focus like the ones above.
                otherPlaylistItems(others, expanded, { id -> vm.expandedPlaylists.value = vm.expandedPlaylists.value.toggled(id) },
                    { c -> if (vm.openOther(c)) onUsePlaylist(c) }) { title, trailing, header, nested, onClick ->
                    SectionRowBox(title, trailing, header, nested, onClick, Color(0xFFE8F5D8), CARD_TEXT)
                }
            }
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = PaddingValues(6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (matches?.isEmpty() == true) item { Text(stringResource(R.string.vod_no_matches), fontSize = 14.sp, color = Color.White) }
                items(shown, key = { it.id }) { v ->
                    Poster(v, if (v.id == restoreId) Modifier.focusRequester(restore) else Modifier,
                        onFocused = { vm.focused.value = v }, onClick = { onOpen(v) })
                }
            }
        }
    }
}
