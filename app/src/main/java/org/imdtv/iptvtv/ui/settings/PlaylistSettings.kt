package org.imdtv.iptvtv.ui.settings

import androidx.compose.ui.res.stringResource
import org.imdtv.iptvtv.R

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.imdtv.iptvtv.data.db.ProviderEntity
import org.imdtv.iptvtv.data.db.ProviderType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.imdtv.iptvtv.ui.use24Hour

private val time = DateTimeFormatter.ofPattern("h.mma", Locale.UK)
private val dayTime get() = DateTimeFormatter.ofPattern("d MMM, h.mma", org.imdtv.iptvtv.ui.namesLocale)
private val time24 = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
private val dayTime24 get() = DateTimeFormatter.ofPattern("d MMM, HH:mm", org.imdtv.iptvtv.ui.namesLocale)

@Composable
fun updatedLabel(lastSync: Long?): String {
    val t = Instant.ofEpochMilli(lastSync ?: return stringResource(R.string.set_a_not_updated_yet)).atZone(ZoneId.systemDefault())
    return stringResource(R.string.set_a_updated_at, (if (t.toLocalDate() == LocalDate.now()) (if (use24Hour) time24 else time) else (if (use24Hour) dayTime24 else dayTime)).format(t).lowercase(Locale.UK))
}

private fun ProviderEntity.typeLabel() = when (type) { ProviderType.XTREAM -> "Xtream"; ProviderType.M3U -> "M3U"; ProviderType.STALKER -> "Stalker"; ProviderType.PLEX -> "Plex"; ProviderType.JELLYFIN -> "Jellyfin"; ProviderType.STREMIO -> "Stremio"; ProviderType.DISPATCHARR -> "Dispatcharr" }

/** The Playlists group: Add playlist, then one row per playlist opening its page. */
@Composable
fun PlaylistGroupRows(vm: SettingsViewModel, first: Modifier, openers: MutableMap<Long, FocusRequester>, onAdd: () -> Unit, onOpen: (Long) -> Unit) {
    val providers by vm.providers.collectAsState()
    val activeId by vm.providerId.collectAsState()
    SettingRow(stringResource(R.string.set_a_add_playlist), stringResource(R.string.set_a_add_playlist_expl), null, first, onClick = onAdd)
    PlaylistUpdateRow(vm)
    providers.forEach { p ->
        SettingRow(p.name, stringResource(if (p.id == activeId) R.string.set_a_playlist_row_in_use else R.string.set_a_playlist_row, p.typeLabel(), updatedLabel(p.lastSync)), "›",
            Modifier.focusRequester(openers.getOrPut(p.id) { FocusRequester() })) { onOpen(p.id) }
    }
}

/** Settings › Playlists and Media servers: one interval for every playlist (CatalogueSyncWorker). */
@Composable
fun PlaylistUpdateRow(vm: SettingsViewModel) {
    val s by vm.settings.collectAsState()
    ChoiceRow(stringResource(R.string.set_a_update_playlists_every), stringResource(R.string.set_a_update_playlists_every_expl), org.imdtv.iptvtv.data.settings.EPG_INTERVALS,
        s.playlistUpdateHours, { androidx.compose.ui.res.pluralStringResource(R.plurals.set_a_hours, it, it) }, default = org.imdtv.iptvtv.data.settings.AppSettings().playlistUpdateHours) { h ->
        vm.update { it.copy(playlistUpdateHours = h) }
    }
}

/** One playlist: edit, use, update, delete (its EPG sources are under TV guide › EPG sources). Back closes it. */
@Composable
fun PlaylistPage(
    vm: SettingsViewModel, id: Long, modifier: Modifier, first: FocusRequester,
    onEdit: (Long) -> Unit, onDelete: (Long) -> Unit, onUse: (Long) -> Unit, onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val providers by vm.providers.collectAsState()
    // Shown as last seen once deleted, until it closes: the focused row vanishing threw focus to the group list.
    val seen = providers.firstOrNull { it.id == id }
    val last = remember { arrayOfNulls<ProviderEntity>(1) }
    val p = seen?.also { last[0] = it } ?: last[0]
    LaunchedEffect(seen == null) { if (seen == null && last[0] != null) onClose() } // deleted
    val deleting = id in vm.deletingPlaylists.collectAsState().value
    val update by vm.playlistUpdate.collectAsState()
    val activeId by vm.providerId.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(id) { withFrameNanos { }; runCatching { first.requestFocus() } }
    if (p == null) return
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(p.name, fontSize = 20.sp, color = Color.White, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
        val plex = p.type.mediaServer // Plex, Jellyfin: no form to edit (signed in under Media servers), no live
        if (!plex) SettingRow(stringResource(R.string.set_a_edit), stringResource(R.string.set_a_edit_expl), null, Modifier.focusRequester(first)) { onEdit(id) }
        // Off leaves it out of the categories list's other playlists. The one in use hands over to another switched-on
        // playlist first; when it's the only one, it stays on.
        SwitchRow(stringResource(R.string.set_a_use_playlist), stringResource(when { !p.enabled -> R.string.set_a_use_playlist_off; activeId == id -> R.string.set_a_use_playlist_on; else -> R.string.set_a_use_playlist_listed }),
            p.enabled, if (plex) Modifier.focusRequester(first) else Modifier) { on ->
            if (on || activeId != id) vm.setEnabled(id, on)
            else providers.filter { it.id != id && it.enabled }.minByOrNull { it.type.mediaServer }?.let { vm.setEnabled(id, false); onUse(it.id) }
        }
        SettingRow(stringResource(R.string.set_a_update_playlist), update[id] ?: updatedLabel(p.lastSync), null) { vm.updatePlaylist(id) }
        listOf(org.imdtv.iptvtv.data.model.Kind.LIVE to R.string.set_a_kind_live, org.imdtv.iptvtv.data.model.Kind.MOVIE to R.string.set_a_kind_movies, org.imdtv.iptvtv.data.model.Kind.SERIES to R.string.set_a_kind_series).filter { !plex || it.first != org.imdtv.iptvtv.data.model.Kind.LIVE }.forEach { (kind, labelRes) ->
            val on = p.loads(kind)
            val label = stringResource(labelRes)
            // Greyed out and shown off while the playlist is switched off; the saved switches come back with it.
            SwitchRow(label, if (on) stringResource(R.string.set_a_load_from_playlist, label) else stringResource(R.string.set_a_not_loaded_from_playlist), on && p.enabled,
                enabled = p.enabled) { vm.setLoads(p, kind, it) }
        }
        SettingRow(
            stringResource(R.string.set_a_delete_playlist),
            when { deleting -> stringResource(R.string.set_a_deleting); confirmDelete -> stringResource(R.string.set_a_delete_playlist_confirm, p.name)
                else -> stringResource(R.string.set_a_delete_playlist_expl) },
            null, busy = deleting,
        ) { if (deleting) Unit else if (confirmDelete) onDelete(id) else confirmDelete = true }

    }
}
