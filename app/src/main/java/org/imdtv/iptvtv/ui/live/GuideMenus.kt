package org.imdtv.iptvtv.ui.live

import org.imdtv.iptvtv.ui.hasCatchUp
import androidx.compose.ui.res.stringResource
import org.imdtv.iptvtv.R

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.imdtv.iptvtv.data.db.SortBy
import org.imdtv.iptvtv.data.catalogue.displayName
import org.imdtv.iptvtv.data.db.CategoryEntity
import org.imdtv.iptvtv.data.db.ChannelEntity
import org.imdtv.iptvtv.data.epg.ChannelOffset
import org.imdtv.iptvtv.data.epg.offsetChoices
import org.imdtv.iptvtv.data.epg.offsetText
import org.imdtv.iptvtv.data.repo.RailEntry
import org.imdtv.iptvtv.ui.ContextMenu
import org.imdtv.iptvtv.ui.TextPrompt

internal sealed interface GuideMenu {
    data class Channel(val ch: ChannelEntity) : GuideMenu
    data class AddTo(val ch: ChannelEntity) : GuideMenu
    data class NewGroup(val ch: ChannelEntity) : GuideMenu
    data class Rename(val ch: ChannelEntity) : GuideMenu
    data class ChangeNumber(val ch: ChannelEntity) : GuideMenu
    /** [n] is shown by [holder]: Confirm gives it to [ch] and moves [holder] to [to]. */
    data class NumberTaken(val ch: ChannelEntity, val n: Int, val holder: ChannelEntity, val to: Int) : GuideMenu
    data class ChangeLogo(val ch: ChannelEntity) : GuideMenu
    data class UseGuideFrom(val ch: ChannelEntity) : GuideMenu
    data class RecordLater(val ch: ChannelEntity) : GuideMenu
    /** Channel menu › Recording: record now (or stop), record a later programme. */
    data class Recording(val ch: ChannelEntity) : GuideMenu
    /** Channel menu › View merged channels: the channels playing through this one. */
    data class Merged(val ch: ChannelEntity) : GuideMenu
    /** Channel menu › Merge options: merge channel, view merged channels, unmerge all. */
    data class MergeOptions(val ch: ChannelEntity) : GuideMenu
    /** Channel menu › Guide options: use guide from, guide time offset. */
    data class GuideOptions(val ch: ChannelEntity) : GuideMenu
    /** The channel's past programmes still in its catch-up window; picking one plays it. */
    data class CatchUp(val ch: ChannelEntity) : GuideMenu
    data class EpgOffset(val ch: ChannelEntity) : GuideMenu
    /** Long OK on a past programme: jump to a day of the archive (or the channel's usual menu). */
    data class PickDay(val ch: ChannelEntity, val windowMin: Int) : GuideMenu
    /** The full-screen overlay's Settings card: subtitles, record, favourite, add to category. */
    data class Player(val ch: ChannelEntity) : GuideMenu
    /** OK on a later programme highlighted in the guide. */
    data class Programme(val ch: ChannelEntity, val p: org.imdtv.iptvtv.data.repo.GuideProgramme) : GuideMenu
    data class CancelRecording(val ch: ChannelEntity, val p: org.imdtv.iptvtv.data.repo.GuideProgramme) : GuideMenu
    /** [horizontal]: Celestial Plus tabs, so the steps read left/right. */
    data class Category(val cat: CategoryEntity, val horizontal: Boolean = false) : GuideMenu
    /** One of your categories (rename, delete and priority stay in Settings). */
    data class Group(val g: org.imdtv.iptvtv.data.db.CustomGroupEntity, val horizontal: Boolean = false) : GuideMenu
    data object Favourites : GuideMenu
    /** Sort by: Playlist and A–Z switches, and Sort A–Z once. */
    data class Sort(val entry: RailEntry) : GuideMenu
    /** Move chosen in a list that isn't sorted Manually: confirm the switch first. */
    data class ToManual(val ch: ChannelEntity, val entry: RailEntry) : GuideMenu
}

/** The long-press menus of a channel row and a rail category, shared by every theme. */
@Composable
fun GuideMenus(vm: LiveViewModel, state: GuideState) {
    val m = state.menu ?: return
    val strip by vm.strip.collectAsState()
    val entry by vm.entry.collectAsState()
    val favourites by vm.favouriteIds.collectAsState()
    val groups by vm.groups.collectAsState()
    val rail by vm.rail.collectAsState()
    val recordingOn by vm.recordingOn.collectAsState()
    val recordingOnChannel by vm.recordingOnChannel.collectAsState()
    val marks by vm.marks.collectAsState()
    when (m) {
        is GuideMenu.Channel -> {
            // Items opening another menu (a ›): Add to category, Recording, Catch-up, Guide options, Merge options, Change logo.
            val more = setOf(R.string.live_menu_add_to_category, R.string.live_menu_recording, R.string.live_menu_catchup, R.string.live_menu_guide_options,
                R.string.live_menu_merge_options, R.string.live_menu_change_logo).map { stringResource(it) }.toSet()
            ContextMenu(m.ch.displayName(strip), buildList {
            val fav = m.ch.id in favourites
            add((if (fav) stringResource(R.string.live_menu_fav_remove) else stringResource(R.string.live_menu_fav_add)) to {
                if (fav && entry == RailEntry.Favourites) state.refocusOnChange = true
                vm.toggleFavourite(m.ch); state.menu = null
            })
            add(stringResource(R.string.live_menu_add_to_category) to { state.menu = GuideMenu.AddTo(m.ch) })
            (entry as? RailEntry.Custom)?.let { e ->
                add(stringResource(R.string.live_menu_remove_from_group, e.group.name) to { state.refocusOnChange = true; vm.removeFromGroup(m.ch, e.group.id); state.menu = null })
            }
            if (recordingOn) add(stringResource(R.string.live_menu_recording) to { state.menu = GuideMenu.Recording(m.ch) })
            if (m.ch.hasCatchUp) add(stringResource(R.string.live_menu_catchup) to { state.menu = GuideMenu.CatchUp(m.ch) })
            add(stringResource(R.string.live_menu_guide_options) to { state.menu = GuideMenu.GuideOptions(m.ch) })
            if (state.hasGrid || m.ch.mergeHead) add(stringResource(R.string.live_menu_merge_options) to { state.menu = GuideMenu.MergeOptions(m.ch) })
            add(stringResource(R.string.live_menu_change_logo) to { state.menu = GuideMenu.ChangeLogo(m.ch) })
            add(stringResource(R.string.live_menu_change_number) to { state.menu = GuideMenu.ChangeNumber(m.ch) })
            if (vm.settings.value.parentalOn) add(stringResource(if (m.ch.parentalLock) R.string.live_menu_parental_off else R.string.live_menu_parental_on) to {
                vm.setChannelLock(m.ch, !m.ch.parentalLock); state.menu = null })
            // Picks the channel up in place, as categories move: arrows carry it, OK drops, Back cancels.
            if (state.hasGrid && entry != null && entry != RailEntry.Recent) add(stringResource(R.string.live_menu_move) to {
                if (vm.selectedSort.value == SortBy.MANUAL) { state.rowMove = RowMove(m.ch, vm.channels.value); state.menu = null } else entry?.let { state.menu = GuideMenu.ToManual(m.ch, it) }
            })
            add(stringResource(R.string.live_menu_rename) to { state.menu = GuideMenu.Rename(m.ch) })
            add(stringResource(R.string.live_menu_hide) to { state.refocusOnChange = true; vm.hide(m.ch); state.menu = null })
            }, 0, more) { state.menu = null }
        }
        is GuideMenu.MergeOptions -> {
            val merged by androidx.compose.runtime.produceState(0, m.ch.id, m.ch.mergeHead) { value = if (m.ch.mergeHead) vm.mergedMembers(m.ch).size else 0 }
            org.imdtv.iptvtv.ui.MenuFrame(stringResource(R.string.live_menu_merge_options_title, m.ch.displayName(strip)), { state.menu = GuideMenu.Channel(m.ch) }) { first ->
                // Lights the channel gold: arrows pick the channel to merge it into, OK merges, Back cancels.
                // Greyed on a channel that others are merged into: merging it would nest merges.
                if (state.hasGrid) org.imdtv.iptvtv.ui.MenuItem(stringResource(R.string.live_menu_merge), Modifier.focusRequester(first), enabled = !m.ch.mergeHead) { state.rowMerge = RowMerge(m.ch); state.menu = null }
                if (merged > 0) org.imdtv.iptvtv.ui.MenuItem(stringResource(R.string.live_menu_view_merged, merged), Modifier) { state.menu = GuideMenu.Merged(m.ch) }
                if (m.ch.mergeHead) org.imdtv.iptvtv.ui.MenuItem(stringResource(R.string.live_menu_unmerge), if (state.hasGrid) Modifier else Modifier.focusRequester(first)) { vm.unmerge(m.ch); state.menu = null }
            }
        }
        is GuideMenu.Merged -> MergedChannelsMenu(m.ch, strip, { vm.mergedMembers(m.ch) }, { c, on -> vm.setMerged(c, m.ch, on) }) { state.menu = GuideMenu.MergeOptions(m.ch) }
        is GuideMenu.Player -> {
            val subtitles = vm.settings.collectAsState().value.liveSubtitlesOn
            ContextMenu(m.ch.displayName(strip), buildList {
                // Stays open: the label shows the new state.
                add((if (subtitles) stringResource(R.string.live_menu_subtitles_on) else stringResource(R.string.live_menu_subtitles_off)) to { vm.setLiveSubtitles(!subtitles) })
                // Steps Auto → 16:9 → 4:3 → Fill; global and remembered.
                val aspect = vm.settings.collectAsState().value.videoAspect
                add(stringResource(R.string.live_menu_aspect, stringResource(aspect.labelRes)) to { vm.setVideoAspect(org.imdtv.iptvtv.data.settings.VideoAspect.entries.let { it[(aspect.ordinal + 1) % it.size] }) })
                if (recordingOn) {
                    val rec = recordingOnChannel[m.ch.id]
                    if (rec != null) add(stringResource(R.string.live_menu_stop_recording) to { vm.stopRecording(rec); state.menu = null })
                    else add(stringResource(R.string.live_menu_record_now) to { vm.recordNow(m.ch); state.menu = null })
                }
                if (m.ch.hasCatchUp) add(stringResource(R.string.live_menu_catchup) to { state.menu = GuideMenu.CatchUp(m.ch) })
                val fav = m.ch.id in favourites
                add((if (fav) stringResource(R.string.live_menu_fav_remove) else stringResource(R.string.live_menu_fav_add)) to { vm.toggleFavourite(m.ch); state.menu = null })
                add(stringResource(R.string.live_menu_add_to_category) to { state.menu = GuideMenu.AddTo(m.ch) })
            }) { state.menu = null }
        }
        // Back from a sub-menu returns to the channel's menu.
        is GuideMenu.Recording -> ContextMenu(stringResource(R.string.live_menu_recording_title, m.ch.displayName(strip)), buildList {
            val rec = recordingOnChannel[m.ch.id]
            if (rec != null) add(stringResource(R.string.live_menu_stop_recording) to { vm.stopRecording(rec); state.menu = null })
            else add(stringResource(R.string.live_menu_record_now) to { vm.recordNow(m.ch); state.menu = null })
            add(stringResource(R.string.live_menu_record_later) to { state.menu = GuideMenu.RecordLater(m.ch) })
        }) { state.menu = GuideMenu.Channel(m.ch) }
        is GuideMenu.GuideOptions -> {
            val off by androidx.compose.runtime.produceState(ChannelOffset(m.ch.epgOffsetMinutes, 0), m.ch.id) { value = vm.channelOffset(m.ch) }
            val none = stringResource(R.string.live_offset_none)
            val shown = offsetText(off.inUse, none).let { if (off.own == null && off.guide != 0) stringResource(R.string.live_offset_from_guide, it) else it }
            ContextMenu(stringResource(R.string.live_menu_guide_options_title, m.ch.displayName(strip)), listOf(
                stringResource(R.string.live_menu_use_guide_from) to { state.menu = GuideMenu.UseGuideFrom(m.ch) },
                stringResource(R.string.live_menu_epg_offset, shown) to { state.menu = GuideMenu.EpgOffset(m.ch) },
            )) { state.menu = GuideMenu.Channel(m.ch) }
        }
        is GuideMenu.AddTo -> ContextMenu(stringResource(R.string.live_menu_add_to_title, m.ch.displayName(strip)),
            groups.map { g -> g.name to { vm.addToGroup(m.ch, g.id); state.menu = null } } +
                (stringResource(R.string.live_menu_new_category_item) to { state.menu = GuideMenu.NewGroup(m.ch) })) { state.menu = null }
        is GuideMenu.NewGroup -> TextPrompt(stringResource(R.string.live_menu_new_category), "", stringResource(R.string.live_menu_category_name),
            onSave = { if (it.isNotEmpty()) vm.addToNewGroup(m.ch, it); state.menu = null }) { state.menu = null }
        is GuideMenu.Rename -> TextPrompt(stringResource(R.string.live_menu_rename_channel), m.ch.customName ?: m.ch.displayName(strip), stringResource(R.string.live_menu_rename_hint),
            onSave = { vm.rename(m.ch, it); state.menu = null }) { state.menu = null }
        is GuideMenu.ChangeNumber -> TextPrompt(stringResource(R.string.live_menu_change_number), (m.ch.customNumber ?: m.ch.number)?.toString().orEmpty(), stringResource(R.string.live_menu_number_hint),
            onSave = { t -> t.toIntOrNull()?.takeIf { it > 0 }?.let { n -> vm.changeNumber(m.ch, n) { h, to -> state.menu = GuideMenu.NumberTaken(m.ch, n, h, to) } }; state.menu = null },
            keyboard = androidx.compose.ui.text.input.KeyboardType.Number) { state.menu = null }
        is GuideMenu.NumberTaken -> ContextMenu(stringResource(R.string.live_number_taken, m.n, m.holder.displayName(strip), m.to), listOf(
            stringResource(R.string.set_b_confirm) to { vm.changeNumber(m.ch, m.n, confirmed = true); state.menu = null },
            stringResource(R.string.live_cancel) to { state.menu = null })) { state.menu = null }
        is GuideMenu.ChangeLogo -> LogoPicker(vm, m.ch, m.ch.displayName(strip)) { state.menu = null }
        is GuideMenu.UseGuideFrom -> GuidePicker(vm, m.ch, strip) { state.menu = null }
        is GuideMenu.PickDay -> {
            // Only days the guide still has aired programmes for (providers often keep just a day or so).
            val list by androidx.compose.runtime.produceState<List<org.imdtv.iptvtv.data.repo.GuideProgramme>?>(null, m.ch) { value = vm.catchUpProgrammes(m.ch) }
            val progs = list ?: return
            val days = progs.map { startOfDay(it.start) }.distinct()
            val today = startOfDay(System.currentTimeMillis())
            ContextMenu(stringResource(R.string.live_menu_pick_day, m.ch.displayName(strip)), listOf(stringResource(R.string.live_menu_channel_options) to { state.menu = GuideMenu.Channel(m.ch) }) +
                days.map { t -> (if (t == today) stringResource(R.string.live_today) else org.imdtv.iptvtv.ui.dayDate(t)) to {
                    vm.jumpToDay(m.ch, t, m.windowMin); state.menu = null } }) { state.menu = null }
        }
        is GuideMenu.EpgOffset -> {
            // −12 h … +12 h in 30-minute steps; overrides the EPG source's offset for this channel only. The value in use
            // (read afresh, synced ones included) is ticked and focused, and listed in its place when it isn't a step.
            val off = androidx.compose.runtime.produceState<ChannelOffset?>(null, m.ch.id) { value = vm.channelOffset(m.ch) }.value ?: return
            val none = stringResource(R.string.live_offset_none)
            val steps = offsetChoices(off.inUse)
            val mark = if (off.own == null) R.string.live_offset_current_guide else R.string.live_offset_current
            val items = listOf(stringResource(R.string.live_menu_use_guide_offset) to { vm.setEpgOffset(m.ch, null); state.menu = null }) +
                steps.map { s -> offsetText(s, none).let { if (s == off.inUse) stringResource(mark, it) else it } to { vm.setEpgOffset(m.ch, s); state.menu = null } }
            ContextMenu(stringResource(R.string.live_menu_epg_offset_title, m.ch.displayName(strip)), items, steps.indexOf(off.inUse) + 1) { state.menu = null }
        }
        is GuideMenu.CatchUp -> {
            val list by androidx.compose.runtime.produceState<List<org.imdtv.iptvtv.data.repo.GuideProgramme>?>(null, m.ch) { value = vm.catchUpProgrammes(m.ch) }
            val progs = list ?: return
            val today = org.imdtv.iptvtv.ui.dayDate(System.currentTimeMillis())
            ContextMenu(stringResource(R.string.live_menu_catchup_on, m.ch.displayName(strip)), if (progs.isEmpty()) listOf(stringResource(R.string.live_menu_no_past) to { state.menu = null })
                else progs.map { p ->
                    val day = org.imdtv.iptvtv.ui.dayDate(p.start).takeIf { it != today }?.let { "$it " }.orEmpty()
                    "$day${org.imdtv.iptvtv.ui.clockTime(p.start)}  ${p.title}" to { vm.playCatchUp(m.ch, p); state.menu = null }
                }) { state.menu = null }
        }
        is GuideMenu.RecordLater -> {
            val list by androidx.compose.runtime.produceState<List<org.imdtv.iptvtv.data.repo.GuideProgramme>?>(null, m.ch) { value = vm.upcoming(m.ch) }
            val progs = list ?: return
            val today = org.imdtv.iptvtv.ui.dayDate(System.currentTimeMillis())
            ContextMenu(stringResource(R.string.live_menu_record_on, m.ch.displayName(strip)), if (progs.isEmpty()) listOf(stringResource(R.string.live_menu_no_guide_channel) to { state.menu = null })
                else progs.map { p ->
                    val marked = (m.ch.id to p.start) in marks
                    val day = org.imdtv.iptvtv.ui.dayDate(p.start).takeIf { it != today }?.let { "$it " }.orEmpty()
                    "${if (marked) "● " else ""}$day${org.imdtv.iptvtv.ui.clockTime(p.start)}  ${p.title}" to {
                        if (marked) state.menu = GuideMenu.CancelRecording(m.ch, p) else { vm.schedule(m.ch, p); state.menu = null }
                    }
                }) { state.menu = null }
        }
        is GuideMenu.Programme -> {
            val marked = (m.ch.id to m.p.start) in marks
            val day = org.imdtv.iptvtv.ui.dayDate(m.p.start).takeIf { it != org.imdtv.iptvtv.ui.dayDate(System.currentTimeMillis()) }?.let { "$it " }.orEmpty()
            ContextMenu("$day${org.imdtv.iptvtv.ui.clockTime(m.p.start)}  ${m.p.title}", listOf(
                if (marked) stringResource(R.string.live_menu_cancel_recording) to { vm.cancelRecording(m.ch, m.p); state.menu = null }
                else stringResource(R.string.live_menu_record) to { vm.schedule(m.ch, m.p); state.menu = null },
            )) { state.menu = null }
        }
        is GuideMenu.CancelRecording -> ContextMenu(m.p.title, listOf(
            (if (m.p.start <= System.currentTimeMillis()) stringResource(R.string.live_menu_stop_recording) else stringResource(R.string.live_menu_cancel_recording)) to { vm.cancelRecording(m.ch, m.p); state.menu = null },
            stringResource(R.string.live_menu_keep_it) to { state.menu = null },
        )) { state.menu = null }
        is GuideMenu.Category -> {
            // Move picks the category up in place (arrows carry it, OK drops, Back cancels), as in Settings; no stepping in a box.
            val e = RailEntry.Provider(m.cat)
            val provider = rail.orEmpty().sameKindKeys(e)
            val key = e.key
            val at = provider.indexOf(key)
            ContextMenu(stringResource(R.string.live_menu_category_title, m.cat.name.ifEmpty { stringResource(R.string.live_uncategorised) }, at + 1, provider.size), listOf(
                stringResource(R.string.live_menu_move) to { state.railMove = RailMove(e, at); state.menu = null },
                stringResource(R.string.live_menu_hide) to { vm.hideCategory(m.cat); state.menu = null; state.railOpen = false; state.refocus() },
                stringResource(R.string.live_menu_sort_by) to { state.menu = GuideMenu.Sort(e) },
            ) + listOfNotNull(if (!vm.settings.value.parentalOn) null else
                stringResource(if (m.cat.parentalLock) R.string.live_menu_parental_off else R.string.live_menu_parental_on) to {
                    vm.setParentalLock(m.cat, !m.cat.parentalLock); state.menu = null; state.railFocusKey = key })) { state.menu = null; state.railFocusKey = key }
        }
        is GuideMenu.Group -> {
            val e = RailEntry.Custom(m.g)
            ContextMenu(m.g.name, listOf(
                stringResource(R.string.live_menu_move) to { state.railMove = RailMove(e, rail.orEmpty().sameKindKeys(e).indexOf(e.key)); state.menu = null },
                stringResource(R.string.live_menu_sort_by) to { state.menu = GuideMenu.Sort(e) },
            )) { state.menu = null; state.railFocusKey = e.key }
        }
        is GuideMenu.Sort -> {
            val by by androidx.compose.runtime.remember(m.entry.key) { vm.sortOf(m.entry) }.collectAsState(null)
            SortMenu(by, { vm.setSort(m.entry, it) }, { vm.sortAZ(m.entry) }) { state.menu = null; state.railFocusKey = m.entry.key }
        }
        is GuideMenu.ToManual -> ToManualConfirm({
            vm.setSort(m.entry, SortBy.MANUAL) // keeps the order shown, so the move starts from what's on screen
            state.rowMove = RowMove(m.ch, vm.channels.value); state.menu = null
        }) { state.menu = null }
        GuideMenu.Favourites -> {
            val e = RailEntry.Favourites
            ContextMenu(stringResource(e.titleRes), listOf(
                stringResource(R.string.live_menu_sort_by) to { state.menu = GuideMenu.Sort(e) },
            )) { state.menu = null; state.railFocusKey = e.key }
        }
    }
}

private fun startOfDay(t: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = t; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

/**
 * Sort by (issue #9): Playlist, A–Z and Manual switches (exactly one on) and Sort A–Z once. Leaving Manual (another
 * switch, or sorting once) replaces the order made by hand, so that asks first. Shared by the guide and Settings.
 */
@Composable
fun SortMenu(current: SortBy?, onPick: (SortBy) -> Unit, onSortOnce: () -> Unit, onDismiss: () -> Unit) {
    current ?: return
    var confirm by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<(() -> Unit)?>(null) }
    fun replacing(action: () -> Unit) { if (current == SortBy.MANUAL) confirm = action else action() }
    confirm?.let { action ->
        ConfirmBox(stringResource(R.string.live_sort_confirm_title), stringResource(R.string.live_sort_confirm_body), { confirm = null; action() }) { confirm = null }
        return
    }
    org.imdtv.iptvtv.ui.MenuFrame(stringResource(R.string.live_menu_sort_by), onDismiss) { first ->
        SortBy.entries.forEachIndexed { i, mode ->
            val on = current == mode
            org.imdtv.iptvtv.ui.FocusBox(onClick = { if (!on) { if (mode == SortBy.MANUAL) onPick(mode) else replacing { onPick(mode) } } },
                modifier = if (i == 0) androidx.compose.ui.Modifier.focusRequester(first) else androidx.compose.ui.Modifier) { f ->
                androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxWidth()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(if (f) Color.White else Color.Transparent)
                    .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.tv.material3.Text(sortLabel(mode), fontSize = 16.sp, color = if (f) Color.Black else Color.White, modifier = androidx.compose.ui.Modifier.weight(1f))
                    org.imdtv.iptvtv.ui.settings.Toggle(on, f)
                }
            }
        }
        org.imdtv.iptvtv.ui.MenuItem(stringResource(R.string.live_menu_sort_once), androidx.compose.ui.Modifier) { replacing { onSortOnce(); onDismiss() } }
    }
}

@Composable
fun sortLabel(by: SortBy) = stringResource(when (by) {
    SortBy.PLAYLIST -> R.string.live_menu_sort_playlist
    SortBy.AZ -> R.string.live_menu_sort_az_switch
    SortBy.MANUAL -> R.string.live_menu_sort_manual
})

/** Before the first move in a list that isn't Manual: moving sets it to Manual (keeping the order shown). */
@Composable
fun ToManualConfirm(onConfirm: () -> Unit, onDismiss: () -> Unit) =
    ConfirmBox(stringResource(R.string.live_manual_confirm_title), stringResource(R.string.live_manual_confirm_body), onConfirm, onDismiss)

@Composable
private fun ConfirmBox(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    org.imdtv.iptvtv.ui.MenuFrame(title, onDismiss) { first ->
        androidx.tv.material3.Text(body, fontSize = 14.sp, color = Color.White, modifier = androidx.compose.ui.Modifier.padding(bottom = 10.dp))
        org.imdtv.iptvtv.ui.MenuItem(stringResource(R.string.set_b_confirm), androidx.compose.ui.Modifier.focusRequester(first), onClick = onConfirm)
        org.imdtv.iptvtv.ui.MenuItem(stringResource(R.string.vod_cancel), androidx.compose.ui.Modifier, onClick = onDismiss)
    }
}

/**
 * The channels merged into [head], each with a switch: off unmerges it, on merges it back (it stays listed until the
 * menu closes). Shared by the guide's long OK and Settings › Manage channels (double-tap OK).
 */
@Composable
fun MergedChannelsMenu(head: ChannelEntity, strip: List<String>, load: suspend () -> List<ChannelEntity>,
                       setMerged: suspend (ChannelEntity, Boolean) -> Unit, onDismiss: () -> Unit) {
    val members by androidx.compose.runtime.produceState<List<ChannelEntity>?>(null, head.id) { value = load() }
    val unmerged = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<Long>() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val list = members ?: return
    org.imdtv.iptvtv.ui.MenuFrame(stringResource(R.string.live_menu_merged_title, head.displayName(strip)), onDismiss) { first ->
        list.forEachIndexed { i, c ->
            val on = c.id !in unmerged
            org.imdtv.iptvtv.ui.FocusBox(onClick = {
                if (on) unmerged.add(c.id) else unmerged.remove(c.id)
                scope.launch { setMerged(c, !on) }
            }, modifier = if (i == 0) androidx.compose.ui.Modifier.focusRequester(first) else androidx.compose.ui.Modifier) { f ->
                androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxWidth()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(if (f) Color.White else Color.Transparent)
                    .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.tv.material3.Text(c.displayName(strip), fontSize = 16.sp, color = if (f) Color.Black else Color.White, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = androidx.compose.ui.Modifier.weight(1f))
                    org.imdtv.iptvtv.ui.settings.Toggle(on, f)
                }
            }
        }
    }
}
