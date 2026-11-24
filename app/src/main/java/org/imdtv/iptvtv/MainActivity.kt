package org.imdtv.iptvtv

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import org.imdtv.iptvtv.ui.rootBack
import org.imdtv.iptvtv.ui.RootBack
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import org.imdtv.iptvtv.ui.theme.LocalTheme
import org.imdtv.iptvtv.ui.theme.themeOf
import org.imdtv.iptvtv.ui.theme.LocalGlassBackdrop
import org.imdtv.iptvtv.ui.theme.glassBackdrop
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import org.imdtv.iptvtv.data.catalogue.displayName
import org.imdtv.iptvtv.data.model.Kind
import org.imdtv.iptvtv.ui.AppViewModel
import org.imdtv.iptvtv.ui.PlayerScreen
import org.imdtv.iptvtv.ui.ProviderFormScreen
import org.imdtv.iptvtv.ui.Screen
import org.imdtv.iptvtv.ui.Tab
import org.imdtv.iptvtv.ui.TopNav
import org.imdtv.iptvtv.ui.live.LiveGuideScreen
import org.imdtv.iptvtv.ui.live.PlusGuideScreen
import org.imdtv.iptvtv.ui.retro.RetroMenuScreen
import org.imdtv.iptvtv.ui.retro.RetroGuideScreen
import org.imdtv.iptvtv.ui.xcube.XcubeCard
import org.imdtv.iptvtv.ui.xcube.bladeCard
import org.imdtv.iptvtv.ui.xcube.XcubeFrame
import org.imdtv.iptvtv.ui.xcube.XcubeLive
import org.imdtv.iptvtv.ui.xcube.XcubeVod
import org.imdtv.iptvtv.ui.xmb.XmbScreen
import org.imdtv.iptvtv.data.settings.ThemeId
import org.imdtv.iptvtv.ui.live.LiveViewModel
import org.imdtv.iptvtv.ui.vod.VodDetailScreen
import org.imdtv.iptvtv.ui.vod.VodHomeScreen
import org.imdtv.iptvtv.ui.vod.VodViewModel
import org.imdtv.iptvtv.ui.settings.SettingsScreen
import org.imdtv.iptvtv.ui.settings.SettingsViewModel
import org.imdtv.iptvtv.work.CatalogueSyncWorker

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(org.imdtv.iptvtv.ui.AppLanguage.wrap(newBase))
        org.imdtv.iptvtv.ui.namesLocale = resources.configuration.locales[0].let { if (it.language == "en") java.util.Locale.UK else it }
    }

    private val app: AppViewModel by viewModels()
    private val live: LiveViewModel by viewModels()
    private val vod: VodViewModel by viewModels()
    private val settingsVm: SettingsViewModel by viewModels()
    private val searchVm: org.imdtv.iptvtv.ui.search.SearchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CatalogueSyncWorker.runOnLaunch(this)
        lifecycleScope.launch { (application as IptvApp).container.updater.checkWhatsNew() }
        // Audio decoder (spec §4.4): the player rebuilds when idle, else from the next stream.
        lifecycleScope.launch {
            (application as IptvApp).container.settings.settings.map { it.audioDecoder }.distinctUntilChanged()
                .collect { app.player.setAudioDecoder(it) }
        }
        lifecycleScope.launch {
            (application as IptvApp).container.settings.settings.map { Triple(it.subtitlesOn, it.subtitleLanguage, it.liveSubtitlesOn) }.distinctUntilChanged()
                .collect { (on, language, live) -> app.player.setSubtitles(on, language, live) }
        }
        lifecycleScope.launch {
            (application as IptvApp).container.settings.settings.map { it.clock24h }.distinctUntilChanged()
                .collect { org.imdtv.iptvtv.ui.use24Hour = it }
        }
        setContent {
            val themeId = app.settings.collectAsState().value.theme
            val themeColour = app.settings.collectAsState().value.themeColour
            val themeDark = app.settings.collectAsState().value.themeDark
            val density = androidx.compose.ui.platform.LocalDensity.current
            val conf = androidx.compose.ui.platform.LocalConfiguration.current
            val backdrop = if (themeId != ThemeId.LIQUID_GLASS) null else remember(conf.screenWidthDp, conf.screenHeightDp, themeColour, themeDark) {
                with(density) { glassBackdrop(this, androidx.compose.ui.geometry.Size(conf.screenWidthDp.dp.toPx(), conf.screenHeightDp.dp.toPx()), themeColour.liquid.map { org.imdtv.iptvtv.ui.theme.themeStop(it, themeDark) }, themeDark) }
            }
            // Every screen is a fixed 10-foot layout in dp: a box's larger system text size (font scale) made text overrun it
            // (guide hero over the timeline, user photo 2026-11-18), so text keeps its designed size.
            // The same goes for density: layouts are designed on a 960x540 dp canvas (1080p at 320 dpi); a box reporting a lower
            // dpi (ugoos SK4, issue #3) drew everything smaller and the guide stopped short of the right edge.
            val fit = minOf(conf.screenWidthDp / 960f, conf.screenHeightDp / 540f) * density.density
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(fit, 1f),
                LocalTheme provides remember(themeId, themeColour, themeDark) { themeOf(themeId, themeColour, themeDark) }, LocalGlassBackdrop provides backdrop,
                org.imdtv.iptvtv.ui.LocalShowNumbers provides app.settings.collectAsState().value.showChannelNumbers,
                org.imdtv.iptvtv.ui.LocalTitleScale provides app.settings.collectAsState().value.titleSize.scale,
                org.imdtv.iptvtv.ui.LocalVodTitleScale provides app.settings.collectAsState().value.vodTitleSize.scale) {
            val catchUp = app.settings.collectAsState().value.catchUpOn
            androidx.compose.runtime.SideEffect { org.imdtv.iptvtv.ui.catchUpOn = catchUp }
            MaterialTheme(colorScheme = darkColorScheme()) {
            // Celestial Glass and Liquid Glass: every TV Text in Inter (the website's font). Inside MaterialTheme, which
            // otherwise sets its own sans-serif text style over anything provided outside it.
            CompositionLocalProvider(androidx.tv.material3.LocalTextStyle provides androidx.tv.material3.LocalTextStyle.current.copy(
                fontFamily = if (org.imdtv.iptvtv.ui.theme.usesInter(themeId)) org.imdtv.iptvtv.ui.theme.Inter else androidx.tv.material3.LocalTextStyle.current.fontFamily)) {
                val stack by app.stack.collectAsState()
                val tab by app.tab.collectAsState()
                val grid by vod.gridCategory.collectAsState()
                val top = stack.last()
                val settings by app.settings.collectAsState()
                // Pings run only while shown and the app is in the foreground (collection stops below STARTED).
                val lastPing by remember(settings.showPing) { if (settings.showPing) org.imdtv.iptvtv.data.net.pings() else kotlinx.coroutines.flow.emptyFlow() }
                    .collectAsStateWithLifecycle(null)
                val pingMs = lastPing.takeIf { settings.showPing }
                val startChannel by app.startChannel.collectAsState()
                LaunchedEffect(startChannel) { startChannel?.let { live.startOn(it); app.startChannel.value = null } }
                val menuFocus = remember { FocusRequester() }
                var menuFocused by remember { mutableStateOf(false) }
                var exitHint by remember { mutableStateOf(false) }
                LaunchedEffect(exitHint, menuFocused) {
                    if (!menuFocused) exitHint = false
                    if (exitHint) { delay(3_000); exitHint = false }
                }
                // The live guide with a channel playing (Retro: only inside its guide): where Back, hold Back and the timeout apply.
                val previewingId by live.previewingId.collectAsState()
                val inGuide = top == Screen.Live && tab == Tab.LIVE && previewingId != null &&
                    (settings.theme != ThemeId.CELESTIAL_RETRO || live.retroGuideOpen)
                val guideToFull = {
                    live.previewing?.let { playing ->
                        live.focused.value = playing; live.watched(playing)
                        app.playFullScreen(playing.displayName(live.strip.value), live = true)
                    }
                    Unit
                }
                val guideToMenu = { if (settings.theme == ThemeId.CELESTIAL_RETRO) live.retroGuideOpen = false else runCatching { menuFocus.requestFocus() }; Unit }
                // Uptime of the last key press, for the guide timeout; plain, so a key press doesn't recompose this screen.
                val lastKey = remember { longArrayOf(0) }
                // Hold Back: 1 = pressed, 2 = held (the other action done; its release must not also be a Back).
                val backHold = remember { IntArray(1) }
                LaunchedEffect(inGuide, settings.guideTimeoutMin) {
                    if (!inGuide || settings.guideTimeoutMin == 0) return@LaunchedEffect
                    val timeout = settings.guideTimeoutMin * 60_000L
                    lastKey[0] = android.os.SystemClock.uptimeMillis()
                    while (true) {
                        val wait = lastKey[0] + timeout - android.os.SystemClock.uptimeMillis()
                        if (wait <= 0) { guideToFull(); break }
                        delay(wait)
                    }
                }
                BackHandler {
                    when {
                        // Back in the live guide opens the playing channel (menu focused keeps the usual Back).
                        inGuide && settings.backToFullScreen && !menuFocused -> guideToFull()
                        top == Screen.Vod && grid != null && settings.theme != ThemeId.XCUBE_180 && settings.theme != ThemeId.GAMESTATION -> vod.gridCategory.value = null
                        top == Screen.Live && live.retroGuideOpen && settings.theme == ThemeId.CELESTIAL_RETRO -> live.retroGuideOpen = false
                        app.back() -> Unit
                        else -> when (rootBack(top, menuFocused, exitHint, settings.confirmExit)) {
                            RootBack.FOCUS_MENU -> runCatching { menuFocus.requestFocus() }
                            RootBack.SHOW_HINT -> exitHint = true
                            RootBack.EXIT -> finish()
                        }
                    }
                }
                val selectTab = { t: Tab ->
                    if (t == Tab.MOVIES) vod.setKind(Kind.MOVIE)
                    if (t == Tab.SERIES) vod.setKind(Kind.SERIES)
                    app.selectTab(t)
                }
                val nav = @androidx.compose.runtime.Composable {
                    TopNav(tab, onSelect = selectTab, Modifier.onFocusChanged { menuFocused = it.hasFocus }, menuFocus,
                        showClock = settings.showDateTime,
                        showDate = settings.showDate, pingMs = pingMs,
                        tabs = org.imdtv.iptvtv.ui.visibleTabs(settings.recordingOn))
                }
                val themeId = LocalTheme.current.id
                // A channel from search: its category becomes the guide's, so zapping and Back behave as from the guide.
                val searchChannel = { ch: org.imdtv.iptvtv.data.db.ChannelEntity ->
                    live.startOn(ch); app.playFullScreen(ch.displayName(live.strip.value), live = true)
                }
                // Recording switched off while its tab is open: back to Live.
                LaunchedEffect(settings.recordingOn) { if (!settings.recordingOn && tab == Tab.RECORDINGS) selectTab(Tab.LIVE) }
                val retroMenu = @androidx.compose.runtime.Composable { t: Tab ->
                    RetroMenuScreen(t, live, vod, selectTab,
                        recordings = if (settings.recordingOn) { { org.imdtv.iptvtv.recording.RecordingsScreen(null, app::playRecording, textColor = LocalTheme.current.colors.panel) } } else null,
                        onLiveCategory = { e -> live.select(e); live.retroGuideOpen = true },
                        onVodCategory = { c -> vod.gridCategory.value = c },
                        menuFocus = menuFocus, onMenuFocused = { menuFocused = it },
                        search = { org.imdtv.iptvtv.ui.search.SearchScreen(searchVm, {}, searchChannel, app::open, live = live, lists = true, textColor = LocalTheme.current.colors.panel) })
                }
                Box(Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { e ->
                    lastKey[0] = android.os.SystemClock.uptimeMillis()
                    if (e.key != Key.Back) return@onPreviewKeyEvent false
                    when {
                        e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> { backHold[0] = 1; false }
                        e.type == KeyEventType.KeyDown -> {
                            if (backHold[0] == 1 && settings.holdBackOther && inGuide && !menuFocused) {
                                backHold[0] = 2
                                if (settings.backToFullScreen) guideToMenu() else guideToFull()
                            }
                            backHold[0] == 2
                        }
                        else -> (backHold[0] == 2).also { backHold[0] = 0 }
                    }
                }) {
                    // A catch-up programme picked in a guide menu opens with the movie controls (pause, seek).
                    androidx.compose.runtime.LaunchedEffect(live) { live.openCatchUp.collect { app.playFullScreen(it, live = false) } }
                    app.resumeLive = { live.focused.value?.let(live::preview) }
                    val full: (org.imdtv.iptvtv.data.db.ChannelEntity) -> Unit = { ch ->
                        live.preview(ch)
                        live.watched(ch)
                        app.playFullScreen(ch.displayName(live.strip.value), live = true)
                    }
                    val screens = @androidx.compose.runtime.Composable { card: XcubeCard? ->
                    when (top) {
                        Screen.Loading -> Unit
                        is Screen.Setup -> Box(Modifier.fillMaxSize().background(LocalTheme.current.colors.vodBg)) { ProviderFormScreen(app, top.editId) }
                        is Screen.Settings -> SettingsScreen(settingsVm, onAddPlaylist = app::addPlaylist, onEditPlaylist = app::editPlaylist,
                            onDeletePlaylist = app::deletePlaylist, onUsePlaylist = { live.resetForPlaylistSwitch(); app.usePlaylist(it) }, initialGroup = top.group)
                        Screen.Live, Screen.Vod -> if (themeId == ThemeId.GAMESTATION) {
                            XmbScreen(live, vod, full, app::open, app::openSettings, menuFocus, { menuFocused = it }, searchVm, searchChannel, app::playRecording)
                        } else if (top == Screen.Live) when (themeId) {
                            ThemeId.CELESTIAL_PLUS -> PlusGuideScreen(live, nav, full, pingMs)
                            ThemeId.CELESTIAL_RETRO -> if (live.retroGuideOpen) RetroGuideScreen(live, full) else retroMenu(Tab.LIVE)
                            else -> if (card != null) XcubeLive(live, card, full) else LiveGuideScreen(live, nav, full)
                        }
                        else when {
                            card != null -> XcubeVod(vod, card, app::open, onUsePlaylist = { live.usePlaylistAt(it.providerId, null) })
                            themeId == ThemeId.CELESTIAL_RETRO && grid == null -> retroMenu(tab)
                            else -> VodHomeScreen(vod, nav, onOpen = app::open, twoRows = settings.vodTwoRows, onUsePlaylist = { live.usePlaylistAt(it.providerId, null) })
                        }
                        Screen.Search -> when {
                            card != null -> org.imdtv.iptvtv.ui.search.SearchScreen(searchVm, {}, searchChannel, app::open, live = live, lists = true,
                                fieldModifier = { typing -> Modifier.bladeCard(card, typing) }, openKeyboard = false)
                            themeId == ThemeId.CELESTIAL_RETRO -> retroMenu(Tab.SEARCH)
                            else -> org.imdtv.iptvtv.ui.search.SearchScreen(searchVm, nav, searchChannel, app::open, live = live)
                        }
                        Screen.Recordings -> when {
                            card != null -> org.imdtv.iptvtv.recording.RecordingsScreen(null, app::playRecording, Modifier.bladeCard(card))
                            themeId == ThemeId.CELESTIAL_RETRO -> retroMenu(Tab.RECORDINGS)
                            else -> org.imdtv.iptvtv.recording.RecordingsScreen(nav, app::playRecording)
                        }
                        is Screen.Detail -> VodDetailScreen(vod, top.item, onPlay = { url, title, episode, startMs ->
                            // Full screen once it starts: a Stremio title first shows its stream list here.
                            vod.play(url, top.item, episode, startMs, title) { app.playFullScreen(title, live = false) }
                        }, onOpen = app::open)
                        is Screen.Player -> PlayerScreen(app.player, top.title, live.takeIf { top.live }, onGuide = { app.back() }, showClock = settings.playerClock == org.imdtv.iptvtv.data.settings.PlayerClock.ALL ||
                            settings.playerClock == org.imdtv.iptvtv.data.settings.PlayerClock.LIVE && top.live, aspect = settings.videoAspect)
                    }
                    }
                    // Xcube 180: Live, Movies, Series and Settings are blades inside one frame (spec §6A.7).
                    if (themeId == ThemeId.XCUBE_180 && (top == Screen.Live || top == Screen.Vod || top == Screen.Search || top == Screen.Recordings || top is Screen.Settings)) {
                        XcubeFrame(if (top is Screen.Settings) Tab.SETTINGS else tab, onBlade = selectTab, cardFocus = menuFocus,
                            onCardFocused = { menuFocused = it }, tabs = org.imdtv.iptvtv.ui.visibleTabs(settings.recordingOn)) { card -> screens(card) }
                    } else screens(null)
                    // Connection questions and recording notices, over every screen.
                    org.imdtv.iptvtv.recording.PromptHost((application as IptvApp).container.recordings) {
                        if ((app.stack.value.last() as? Screen.Player)?.live == true) app.back()
                    }
                    org.imdtv.iptvtv.ui.live.NumberEntryBox(live)
                    org.imdtv.iptvtv.ui.settings.PairingPrompt((application as IptvApp).container.sync)
                    org.imdtv.iptvtv.ui.ParentalPrompt((application as IptvApp).container.parental, settings)
                    org.imdtv.iptvtv.data.update.UpdatePrompt((application as IptvApp).container.updater, (application as IptvApp).container.background)
                    org.imdtv.iptvtv.data.update.WhatsNewPrompt((application as IptvApp).container.updater)
                    if (exitHint) org.imdtv.iptvtv.ui.InfoBar(
                        buildAnnotatedString {
                            val hint = androidx.compose.ui.res.stringResource(org.imdtv.iptvtv.R.string.live_exit_hint, "\u0000")
                            val at = hint.indexOf('\u0000')
                            append(hint.substring(0, at))
                            withStyle(SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold)) { append("↩") }
                            append(hint.substring(at + 1))
                        },
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
                    )
                }
            }
            }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Don't keep streaming (or holding the provider's connection) after the user leaves the app.
        app.player.toBackground()
        (application as IptvApp).container.parental.relock() // "Until the app closes"
    }

    override fun onStart() {
        super.onStart()
        app.player.toForeground()
        // TV boxes keep the app alive for days: check on every return to it, at most once an hour.
        lifecycleScope.launch { (application as IptvApp).container.updater.check() }
        (application as IptvApp).container.sync.onForeground()
    }
}
