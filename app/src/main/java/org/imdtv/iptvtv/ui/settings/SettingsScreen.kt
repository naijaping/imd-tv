package org.imdtv.iptvtv.ui.settings

import androidx.compose.ui.draw.alpha
import org.imdtv.iptvtv.R
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import org.imdtv.iptvtv.data.settings.AppSettings
import org.imdtv.iptvtv.ui.theme.LocalTheme
import org.imdtv.iptvtv.ui.theme.LocalGlassBackdrop
import org.imdtv.iptvtv.ui.theme.glass
import org.imdtv.iptvtv.ui.theme.themeBackground

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.imdtv.iptvtv.data.settings.AudioDecoder
import org.imdtv.iptvtv.ui.AppLogo
import androidx.compose.ui.input.nestedscroll.nestedScroll
import org.imdtv.iptvtv.ui.FocusBox

enum class SettingsGroup(val title: String, @StringRes val labelRes: Int) {
    PLAYLISTS("Playlists", R.string.set_b_group_playlists), MEDIA_SERVERS("Media servers", R.string.set_b_group_media_servers), ADDONS("Addons", R.string.set_c_group_addons), CHANNELS("Channels & categories", R.string.set_b_group_channels),
    EPG("TV guide (EPG)", R.string.set_b_group_epg), VOD("Movies & Series", R.string.set_b_group_vod), PLAYBACK("Playback", R.string.set_b_group_playback),
    APPEARANCE("Appearance", R.string.set_b_group_appearance), BEHAVIOUR("App behaviour", R.string.set_b_group_behaviour),
    BACKUP("Backup & sync", R.string.set_b_group_backup), ABOUT("About", R.string.set_b_group_about),
}

@OptIn(ExperimentalComposeUiApi::class)
/** Left: groups. Right: the focused group's rows. Back in the rows returns to the groups; Back there pops the screen. */
@Composable
fun SettingsScreen(vm: SettingsViewModel, onAddPlaylist: () -> Unit, onEditPlaylist: (Long) -> Unit, onDeletePlaylist: (Long) -> Unit, onUsePlaylist: (Long) -> Unit,
                   initialGroup: SettingsGroup = SettingsGroup.PLAYLISTS) {
    val s by vm.settings.collectAsState()
    // Back from Edit playlist (Settings left composition meanwhile): reopen that playlist's page.
    val editedPlaylist = remember { vm.playlistToReopen.also { vm.playlistToReopen = null } }
    var group by remember { mutableStateOf(if (editedPlaylist != null) SettingsGroup.PLAYLISTS else initialGroup) }
    var inRows by remember { mutableStateOf(false) }
    // Focus parked on the group while a page swaps: don't draw it focused (it flashed white).
    var parked by remember { mutableStateOf(false) }
    LaunchedEffect(parked) { if (parked) { repeat(15) { withFrameNanos { } }; parked = false } } // fallback if focus stays on the group
    var page by remember { mutableStateOf<ChannelPage?>(null) }
    var reopen by remember { mutableStateOf<ChannelPage?>(null) }
    val groupFocus = remember { FocusRequester() }
    val rowsFocus = remember { FocusRequester() }
    var playlist by remember { mutableStateOf(editedPlaylist) }
    var sourcesOpen by remember { mutableStateOf(false) }
    var recordingOpen by remember { mutableStateOf(false) }
    var reopenRecording by remember { mutableStateOf(false) }
    val recordingOpener = remember { FocusRequester() }
    var syncOpen by remember { mutableStateOf(false) }
    // Settings › Addons: the add-on whose page is open (its manifest URL), and the one to refocus on closing it.
    var addonOpen by remember { mutableStateOf<String?>(null) }
    var reopenAddon by remember { mutableStateOf<String?>(null) }
    var reopenSync by remember { mutableStateOf(false) }
    val syncOpener = remember { FocusRequester() }
    var backupOpen by remember { mutableStateOf(false) }
    var reopenBackup by remember { mutableStateOf(false) }
    val backupOpener = remember { FocusRequester() }
    var reopenSources by remember { mutableStateOf(false) }
    val sourcesOpener = remember { FocusRequester() }
    var reopenPlaylist by remember { mutableStateOf<Long?>(null) }
    var server by remember { mutableStateOf<org.imdtv.iptvtv.data.db.ProviderType?>(null) } // Media servers › Plex / Jellyfin
    var reopenServer by remember { mutableStateOf<org.imdtv.iptvtv.data.db.ProviderType?>(null) }
    val serverOpeners = remember { SERVER_TYPES.associateWith { FocusRequester() } }
    val openers = remember { ChannelPage.entries.associateWith { FocusRequester() } }
    /** Where focus goes when it next enters the rows pane (null = its first row). Not state: read only by `enter`. */
    val enterTarget = remember { object { var value: FocusRequester? = null } }
    val playlistOpeners = remember { mutableMapOf<Long, FocusRequester>() }
    // Closing a page puts focus back on the row that opened it.
    LaunchedEffect(page) {
        val back = reopen?.takeIf { page == it.parent } ?: return@LaunchedEffect
        // The opener row may take a few frames to appear (the parent page is rebuilt); until then requestFocus throws.
        // Focus enters the pane from the group list, and the pane's `enter` rule would send it to the first row.
        enterTarget.value = openers.getValue(back)
        for (i in 0 until 10) { withFrameNanos { }; if (runCatching { openers.getValue(back).requestFocus() }.isSuccess) break }
        enterTarget.value = null
        reopen = null
    }
    LaunchedEffect(sourcesOpen) {
        if (sourcesOpen || !reopenSources) return@LaunchedEffect
        enterTarget.value = sourcesOpener
        withFrameNanos { }
        runCatching { sourcesOpener.requestFocus() }
        enterTarget.value = null
        reopenSources = false
    }
    LaunchedEffect(recordingOpen) {
        if (recordingOpen || !reopenRecording) return@LaunchedEffect
        enterTarget.value = recordingOpener
        withFrameNanos { }
        runCatching { recordingOpener.requestFocus() }
        enterTarget.value = null
        reopenRecording = false
    }
    LaunchedEffect(syncOpen) {
        if (syncOpen || !reopenSync) return@LaunchedEffect
        enterTarget.value = syncOpener
        withFrameNanos { }
        runCatching { syncOpener.requestFocus() }
        enterTarget.value = null
        reopenSync = false
    }
    LaunchedEffect(backupOpen) {
        if (backupOpen || !reopenBackup) return@LaunchedEffect
        enterTarget.value = backupOpener
        withFrameNanos { }
        runCatching { backupOpener.requestFocus() }
        enterTarget.value = null
        reopenBackup = false
    }
    LaunchedEffect(server) {
        val back = reopenServer?.takeIf { server == null } ?: return@LaunchedEffect
        enterTarget.value = serverOpeners.getValue(back)
        withFrameNanos { }
        runCatching { serverOpeners.getValue(back).requestFocus() }
        enterTarget.value = null
        reopenServer = null
    }
    LaunchedEffect(playlist) {
        val back = reopenPlaylist?.takeIf { playlist == null } ?: return@LaunchedEffect
        withFrameNanos { }
        val exists = vm.providers.value.any { it.id == back }
        val target = if (exists) playlistOpeners.getValue(back) else rowsFocus // a deleted playlist has no row
        enterTarget.value = target
        runCatching { target.requestFocus() }
        enterTarget.value = null
        reopenPlaylist = null
    }
    // Opened on a group (Gamestation XMB): focus its rows; otherwise the group list.
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { if (initialGroup != SettingsGroup.PLAYLISTS || editedPlaylist != null) rowsFocus.requestFocus() else groupFocus.requestFocus() } }
    BackHandler(enabled = inRows) { runCatching { groupFocus.requestFocus() } }

    // How far the open page has scrolled (px, <= 0): the logo shows only at the top, so rows never pass under it.
    // Every page's list reports through nested scroll; a new page starts at the top.
    val scrolled = remember { mutableStateOf(0f) }
    LaunchedEffect(group, page, playlist, sourcesOpen, recordingOpen, syncOpen, backupOpen, server) { scrolled.value = 0f }
    val scrollWatch = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                scrolled.value = (scrolled.value + consumed.y).coerceAtMost(0f)
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }

    Box(Modifier.fillMaxSize().themeBackground(LocalTheme.current.colors.vodBg)) {
        Row(Modifier.fillMaxSize().padding(48.dp).nestedScroll(scrollWatch), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            // Scrolls: Xcube's short frame clipped the last group (About).
            Column(Modifier.width(260.dp).glass().padding(if (LocalGlassBackdrop.current != null) 12.dp else 0.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.set_b_settings), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(bottom = 16.dp))
                SettingsGroup.entries.forEach { g ->
                    FocusBox(
                        onClick = { runCatching { rowsFocus.requestFocus() } },
                        modifier = if (g == group) Modifier.focusRequester(groupFocus) else Modifier,
                        onFocused = { if (g != group) { page = null; playlist = null; sourcesOpen = false; recordingOpen = false; syncOpen = false; backupOpen = false; server = null; addonOpen = null }; group = g },
                    ) { f ->
                        Text(
                            stringResource(g.labelRes), fontSize = 15.sp, color = if (f && !parked) Color.Black else if (g == group) Color.White else LocalTheme.current.colors.muted,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (f && !parked) Color.White else if (g == group) Color(0x1FFFFFFF) else Color.Transparent)
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                        )
                    }
                }
            }
            val pane = Modifier.weight(1f).glass().padding(if (LocalGlassBackdrop.current != null) 16.dp else 0.dp).onFocusChanged { inRows = it.hasFocus; if (it.hasFocus) parked = false }
                    // Enter on the first row; Up/Down past the ends stay put instead of jumping into the group list.
                    .focusProperties {
                        enter = { enterTarget.value ?: rowsFocus }
                        exit = {
                            when (it) {
                                FocusDirection.Up, FocusDirection.Down -> FocusRequester.Cancel
                                FocusDirection.Left -> groupFocus
                                else -> FocusRequester.Default // Back's requestFocus must still leave
                            }
                        }
                    }
                    .focusGroup()
            // Removing the focused node hands focus to the first focusable (Playlists), so park it on this group first.
            fun park() { parked = true; runCatching { groupFocus.requestFocus() } }
            page?.let { p ->
                key(p) {
                    ChannelPageView(p, vm, pane, first = rowsFocus, openers, restoring = reopen?.parent == p,
                        onOpen = { park(); reopen = null; page = it }) { park(); reopen = p; page = p.parent }
                }
            }
            playlist?.let { id ->
                PlaylistPage(vm, id, pane, rowsFocus, { vm.playlistToReopen = it; onEditPlaylist(it) }, onDeletePlaylist, onUsePlaylist) { park(); reopenPlaylist = id; playlist = null }
            }
            // A server's playlist page opens over its Media servers page, which comes back on closing it.
            server?.takeIf { playlist == null }?.let { t ->
                key(t) {
                    MediaServerPage(vm, t, pane, rowsFocus, focusFirst = reopenPlaylist == null, playlistOpeners, { park(); playlist = it }) { park(); reopenServer = t; server = null }
                }
            }
            if (sourcesOpen) EpgSourcesPage(vm, pane, rowsFocus, { park() }) { park(); reopenSources = true; sourcesOpen = false }
            if (recordingOpen) org.imdtv.iptvtv.recording.RecordingPage(vm, pane, rowsFocus) { park(); reopenRecording = true; recordingOpen = false }
            if (syncOpen) SyncPage(vm, pane, rowsFocus) { park(); reopenSync = true; syncOpen = false }
            if (backupOpen) BackupPage(vm, pane, rowsFocus) { park(); reopenBackup = true; backupOpen = false }
            addonOpen?.let { url -> key(url) { AddonPage(vm, url, pane, rowsFocus) { park(); reopenAddon = url; addonOpen = null } } }
            if (page == null && playlist == null && server == null && addonOpen == null && !sourcesOpen && !recordingOpen && !syncOpen && !backupOpen) Column(pane.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val syncNow by vm.sync.state.collectAsState()
                syncNow.master?.takeIf { syncNow.role == org.imdtv.iptvtv.data.sync.SyncRole.FOLLOWER }?.let {
                    Text(stringResource(R.string.set_b_following, it.name), fontSize = 14.sp, color = LocalTheme.current.colors.muted)
                }
                Text(stringResource(group.labelRes), fontSize = 20.sp, color = Color.White, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
                // The first row of a group takes rowsFocus, so OK/Right on a group lands on it.
                val first = Modifier.focusRequester(rowsFocus)
                when (group) {
                    SettingsGroup.PLAYLISTS -> PlaylistGroupRows(vm, first, playlistOpeners, onAddPlaylist) { park(); playlist = it }
                    SettingsGroup.MEDIA_SERVERS -> MediaServerRows(vm, first, serverOpeners) { park(); server = it }
                    SettingsGroup.ADDONS -> AddonRows(vm, first, reopenAddon, { reopenAddon = null }) { park(); addonOpen = it }
                    SettingsGroup.EPG -> EpgGroupRows(vm, first, Modifier.focusRequester(sourcesOpener)) { park(); sourcesOpen = true }
                    SettingsGroup.CHANNELS -> ChannelGroupRows(vm, first, openers) { park(); page = it }
                    SettingsGroup.VOD -> ChoiceRow(stringResource(R.string.set_b_vod_sort), stringResource(R.string.set_b_vod_sort_expl), org.imdtv.iptvtv.data.settings.VodSort.entries, s.vodSort,
                        { stringResource(it.labelRes) }, first, default = AppSettings().vodSort) { v -> vm.update { it.copy(vodSort = v) } }
                    SettingsGroup.PLAYBACK -> {
                        SwitchRow(stringResource(R.string.set_b_autoplay_title), stringResource(R.string.set_b_autoplay_expl),
                            s.autoplayOnScroll, first) { on -> vm.update { it.copy(autoplayOnScroll = on) } }
                        ChoiceRow(stringResource(R.string.set_b_audio_decoder), stringResource(R.string.set_b_audio_decoder_expl),
                            listOf(AudioDecoder.SOFTWARE, AudioDecoder.HARDWARE), s.audioDecoder,
                            { if (it == AudioDecoder.HARDWARE) stringResource(R.string.set_b_hardware) else stringResource(R.string.set_b_software) }, default = AppSettings().audioDecoder) { d -> vm.update { it.copy(audioDecoder = d) } }
                        ChoiceRow(stringResource(R.string.set_b_change_channel), stringResource(R.string.set_b_change_channel_expl),
                            listOf(false, true), s.zapLeftRight, { if (it) stringResource(R.string.set_b_left_right) else stringResource(R.string.set_b_up_down) }, default = AppSettings().zapLeftRight) { v -> vm.update { it.copy(zapLeftRight = v) } }
                        // Always the other pair of arrows: picking one here flips Change channel with.
                        ChoiceRow(stringResource(R.string.set_b_prev_channel), stringResource(R.string.set_b_prev_channel_expl),
                            listOf(true, false), !s.zapLeftRight, { if (it) stringResource(R.string.set_b_left_right) else stringResource(R.string.set_b_up_down) }, default = !AppSettings().zapLeftRight) { v -> vm.update { it.copy(zapLeftRight = !v) } }
                        SettingRow(stringResource(R.string.set_b_recording), stringResource(R.string.set_b_recording_expl),
                            if (s.recordingOn) stringResource(R.string.set_b_on_arrow) else stringResource(R.string.set_b_off_arrow), Modifier.focusRequester(recordingOpener)) { park(); recordingOpen = true }
                        SwitchRow(stringResource(R.string.set_b_catch_up), stringResource(R.string.set_b_catch_up_expl), s.catchUpOn) { on -> vm.update { it.copy(catchUpOn = on) } }
                        val subs = ChannelPage.SUBTITLES
                        SettingRow(stringResource(subs.titleRes), stringResource(subs.explanationRes), "›", Modifier.focusRequester(openers.getValue(subs))) { park(); page = subs }
                    }
                    SettingsGroup.APPEARANCE -> PageRows(listOf(ChannelPage.APPEARANCE_APP, ChannelPage.APPEARANCE_LIVE, ChannelPage.APPEARANCE_VOD), vm, rowsFocus, openers) { park(); page = it }
                    SettingsGroup.BEHAVIOUR -> {
                        SwitchRow(stringResource(R.string.set_b_last_channel), stringResource(R.string.set_b_last_channel_expl), s.lastChannelOnStart, first) { on ->
                            vm.update { it.copy(lastChannelOnStart = on) }
                        }
                        ChoiceRow(stringResource(R.string.set_b_back_guide), stringResource(R.string.set_b_back_guide_expl),
                            listOf(false, true), s.backToFullScreen, { if (it) stringResource(R.string.set_b_go_fullscreen) else stringResource(R.string.set_b_go_menu) }, default = AppSettings().backToFullScreen) { v -> vm.update { it.copy(backToFullScreen = v) } }
                        SwitchRow(stringResource(R.string.set_b_hold_back), stringResource(R.string.set_b_hold_back_expl), s.holdBackOther) { on -> vm.update { it.copy(holdBackOther = on) } }
                        ChoiceRow(stringResource(R.string.set_b_guide_timeout), stringResource(R.string.set_b_guide_timeout_expl), listOf(0, 1, 2, 5, 10, 30), s.guideTimeoutMin,
                            { if (it == 0) stringResource(R.string.set_b_off) else stringResource(R.string.set_b_minutes, it) }, default = 0) { v -> vm.update { it.copy(guideTimeoutMin = v) } }
                        // Not an AppSettings value: Android (13+) or AppLanguage keeps it, and the screen restarts in the new language.
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        val activity = remember(ctx) { generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().first() }
                        ChoiceRow(stringResource(R.string.set_b_language), stringResource(R.string.set_b_language_expl), listOf("") + org.imdtv.iptvtv.ui.AppLanguage.TAGS,
                            remember(activity) { org.imdtv.iptvtv.ui.AppLanguage.get(activity).substringBefore('-').substringBefore(',') },
                            { if (it.isEmpty()) stringResource(R.string.set_b_system_default) else org.imdtv.iptvtv.ui.AppLanguage.nativeName(it) }, default = "") { tag ->
                            org.imdtv.iptvtv.ui.AppLanguage.set(activity, tag)
                        }
                        // Once on, the page itself takes the PIN (or backup word): it can turn the controls off.
                        val parental = ChannelPage.PARENTAL
                        val scope = androidx.compose.runtime.rememberCoroutineScope()
                        SettingRow(stringResource(parental.titleRes), stringResource(parental.explanationRes),
                            if (s.parentalOn) stringResource(R.string.set_b_on_arrow) else stringResource(R.string.set_b_off_arrow), Modifier.focusRequester(openers.getValue(parental))) {
                            scope.launch { if (!s.parentalOn || vm.parental.askPin()) { park(); page = parental } }
                        }
                        SwitchRow(stringResource(R.string.set_b_confirm_exit), stringResource(R.string.set_b_confirm_exit_expl), s.confirmExit) { on ->
                            vm.update { it.copy(confirmExit = on) }
                        }
                        StartOnBootRow(s.startOnBoot) { on -> vm.update { it.copy(startOnBoot = on) } }
                        SwitchRow(stringResource(R.string.set_b_show_ping), stringResource(R.string.set_b_show_ping_expl), s.showPing) { on ->
                            vm.update { it.copy(showPing = on) }
                        }
                    }
                    SettingsGroup.BACKUP -> {
                        SettingRow(stringResource(R.string.set_b_backup_restore), stringResource(R.string.set_b_backup_restore_expl), "›",
                            first.focusRequester(backupOpener)) { park(); backupOpen = true }
                        SettingRow(stringResource(R.string.set_b_sync), stringResource(R.string.set_b_sync_expl),
                            syncRoleLabel(syncNow.role) + " ›", Modifier.focusRequester(syncOpener)) { park(); syncOpen = true }
                    }
                    SettingsGroup.ABOUT -> AboutRows(first)
                }
            }
        }
        // In the view model: a reset can change the theme, which rebuilds this screen.
        val notice by vm.notice.collectAsState()
        LaunchedEffect(notice) { if (notice != null) { kotlinx.coroutines.delay(3_000); vm.notice.value = null } }
        // The same bar as Manage channels' "5 channels hidden"; not focusable, so it never takes focus from the rows.
        notice?.let { androidx.compose.ui.window.Popup(alignment = Alignment.BottomCenter) { org.imdtv.iptvtv.ui.InfoBar(it, Modifier.padding(bottom = 40.dp)) } }
        // Drawn last so a glass pane (Liquid Glass) never covers it.
        if (scrolled.value > -1f) AppLogo(Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 48.dp).size(64.dp))
    }
}

/** Playback › Subtitles: movies & series (with their language) and live TV. */
@Composable
fun SubtitleRows(vm: SettingsViewModel, first: FocusRequester) {
    val s by vm.settings.collectAsState()
    SwitchRow(stringResource(R.string.set_b_movies_series), stringResource(R.string.set_b_movies_series_expl), s.subtitlesOn, Modifier.focusRequester(first)) { on ->
        vm.update { it.copy(subtitlesOn = on) }
    }
    if (s.subtitlesOn) {
        val tv = java.util.Locale.getDefault().displayLanguage
        ChoiceRow(stringResource(R.string.set_b_movies_series_lang), stringResource(R.string.set_b_movies_series_lang_expl),
            listOf<String?>(null) + org.imdtv.iptvtv.data.settings.SUBTITLE_LANGUAGES.sortedBy { java.util.Locale(it).displayLanguage },
            s.subtitleLanguage, { c -> c?.let { java.util.Locale(it).displayLanguage } ?: stringResource(R.string.set_b_tv_language, tv) }, default = AppSettings().subtitleLanguage) { l ->
            vm.update { it.copy(subtitleLanguage = l) }
        }
    }
    SwitchRow(stringResource(R.string.set_b_live_tv), stringResource(R.string.set_b_live_tv_expl), s.liveSubtitlesOn) { on ->
        vm.update { it.copy(liveSubtitlesOn = on) }
    }
}

/** A setting: name, one-line grey explanation, and its current value on the right. */
/** The row being moved, in every Settings list. */
val MOVING = Color(0xFFE5A00D)

@Composable
fun SettingRow(
    title: String, explanation: String, value: String?, modifier: Modifier = Modifier, busy: Boolean = false,
    /** Non-null: a slider switch in that state instead of [value]. */
    toggle: Boolean? = null,
    /** Picked up to move: the gold row of every move in Settings (as provider categories). */
    moving: Boolean = false,
    /** False: greyed out, still focusable (so the list scrolls past it), OK does nothing. */
    enabled: Boolean = true, onClick: () -> Unit,
) {
    FocusBox(onClick = { if (enabled) onClick() }, modifier = modifier.then(if (enabled) Modifier else Modifier.alpha(.4f))) { focused ->
        val f = focused || moving
        androidx.compose.foundation.layout.BoxWithConstraints {
        val valueMax = minOf(320.dp, maxWidth * .45f)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (moving) MOVING else if (f) Color.White else Color(0x14FFFFFF))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, color = if (f) Color.Black else Color.White)
                Text(explanation, fontSize = 12.sp, color = if (f) Color(0xFF555555) else LocalTheme.current.colors.muted)
            }
            if (value != null) {
                Spacer(Modifier.width(16.dp))
                // One line, at most 45% of the row (and 320 dp): a long value (a clean-up list, a URL in narrow Xcube) must not squeeze the title to nothing.
                Text(value, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (f) Color.Black else Color.White,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = valueMax))
            }
            if (toggle != null) { Spacer(Modifier.width(16.dp)); Toggle(toggle, f) }
            if (busy) { Spacer(Modifier.width(16.dp)); org.imdtv.iptvtv.ui.Spinner(size = 20.dp, color = if (f) Color.Black else Color.White) }
        }
        }
    }
}

@Composable
fun SwitchRow(title: String, explanation: String, checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onToggle: (Boolean) -> Unit) =
    SettingRow(title, explanation, null, modifier, toggle = checked, enabled = enabled) { onToggle(!checked) }

/** Slider switch (TiviMate style): the theme accent fills the track when on; the white knob sits at that end. */
@Composable
fun Toggle(on: Boolean, focused: Boolean) {
    val off = if (focused) Color(0xFFB0B0B0) else Color(0x40FFFFFF)
    Box(Modifier.size(44.dp, 24.dp).clip(RoundedCornerShape(12.dp)).background(if (on) LocalTheme.current.colors.accent else off).padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(18.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.White))
    }
}

/** On without "Display over other apps", Android drops the boot start: the row says so and OK opens that setting. */
@Composable
private fun StartOnBootRow(on: Boolean, onToggle: (Boolean) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var allowed by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
    val switchFocus = remember { FocusRequester() }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e != androidx.lifecycle.Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            val now = android.provider.Settings.canDrawOverlays(context)
            // The Allow row is about to go: move focus to the switch first, or it falls to the group list.
            if (now && !allowed) runCatching { switchFocus.requestFocus() }
            allowed = now
        }
        lifecycle.addObserver(o)
        onDispose { lifecycle.removeObserver(o) }
    }
    SwitchRow(stringResource(R.string.set_b_start_boot), stringResource(R.string.set_b_start_boot_expl), on, Modifier.focusRequester(switchFocus), onToggle = onToggle)
    if (on && !allowed) {
        // A row of its own, so the switch above can still be turned off.
        SettingRow(stringResource(R.string.set_b_allow_overlay), stringResource(R.string.set_b_allow_overlay_expl), null) {
            val i = android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
            runCatching { context.startActivity(i) }
        }
    }
}
