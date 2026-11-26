package org.imdtv.iptvtv.ui.live

import org.imdtv.iptvtv.R

import org.imdtv.iptvtv.data.db.EpgChannelEntity
import org.imdtv.iptvtv.data.epg.SourceChannel
import org.imdtv.iptvtv.data.db.ProgrammeEntity
import org.imdtv.iptvtv.data.epg.sourceChannelChoices
import org.imdtv.iptvtv.data.catalogue.displayName
import org.imdtv.iptvtv.data.db.login

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.imdtv.iptvtv.data.parental.parentalLocked
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import androidx.room.withTransaction
import org.imdtv.iptvtv.IptvApp
import org.imdtv.iptvtv.data.catalogue.parseStripList
import org.imdtv.iptvtv.data.db.CategoryEntity
import org.imdtv.iptvtv.data.db.ChannelEntity
import org.imdtv.iptvtv.data.db.EpgLinkEntity
import org.imdtv.iptvtv.data.catalogue.isHeaderChannel
import org.imdtv.iptvtv.data.epg.guideDonors
import org.imdtv.iptvtv.data.epg.guideSiblings
import org.imdtv.iptvtv.data.db.CustomGroupEntity
import org.imdtv.iptvtv.data.db.FavouriteEntity
import org.imdtv.iptvtv.data.db.ProviderEntity
import org.imdtv.iptvtv.data.model.Kind
import org.imdtv.iptvtv.data.repo.GuideProgramme
import org.imdtv.iptvtv.data.repo.CategoryRepository
import org.imdtv.iptvtv.data.repo.GuideRepository
import org.imdtv.iptvtv.data.repo.RailEntry
import org.imdtv.iptvtv.data.repo.RailItem
import org.imdtv.iptvtv.data.repo.RailRepository
import org.imdtv.iptvtv.data.repo.ChannelSort
import org.imdtv.iptvtv.data.db.SortBy
import org.imdtv.iptvtv.data.settings.AppSettings
import org.imdtv.iptvtv.data.settings.LogoKind

data class RailCategory(val category: CategoryEntity, val count: Int)

/** Where the guide list was scrolled when it was left, so coming back from full screen doesn't move it. */
data class GuidePos(val channelId: Long?, val index: Int, val offset: Int)

@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as IptvApp).container
    private val guide = GuideRepository(c.db)
    private val railRepo = RailRepository(c.db)
    val player get() = c.playerManager

    private val provider: StateFlow<ProviderEntity?> = c.providerRepository.activeProviderId
        .flatMapLatest { id -> id?.let { c.db.providerDao().observe(it) } ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Name clean-up list of the active playlist; edits in Settings show up in the guide at once. */
    val strip: StateFlow<List<String>> = combine(provider, c.settings.settings.map { it.removeNameSymbols }.distinctUntilChanged()) { p, sym ->
        org.imdtv.iptvtv.data.catalogue.channelStrip(p?.nameStrip, sym) }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Keyed on the id only: the provider row also changes on every sync (lastSync) and name-strip edit.
    private val providerId = provider.map { it?.id }.distinctUntilChanged().filterNotNull()

    // Counts are observed too: a background re-sync can add categories while the guide is open.
    /** Null until first loaded, so the guide can tell "everything hidden" from "not loaded yet". */
    val rail: StateFlow<List<RailItem>?> = combine(providerId.flatMapLatest { railRepo.rail(it) }, c.settings.settings.map { it.showRecent }.distinctUntilChanged()) { r, recent ->
        if (recent) r else r.filter { it.entry != org.imdtv.iptvtv.data.repo.RailEntry.Recent }
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val entry = MutableStateFlow<RailEntry?>(null)

    /** The rail's sections for the other playlists (Live on), and which are opened (this session only). */
    val otherPlaylists: StateFlow<List<org.imdtv.iptvtv.data.repo.OtherPlaylist>> =
        org.imdtv.iptvtv.data.repo.otherPlaylists(c.db, c.providerRepository.activeProviderId, flowOf(Kind.LIVE))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()) // only while a rail shows: counts re-run on every sync write
    val expandedPlaylists = MutableStateFlow<Set<Long>>(emptySet())
    /** The rail entry to open once the playlist switched to by [usePlaylistAt] has loaded its rail. */
    private var pendingEntryKey: String? = null

    /** Makes [providerId] the active playlist (as Settings › Use this playlist does) and opens its live category [categoryId]. */
    fun usePlaylistAt(providerId: Long, categoryId: Long?) {
        player.stop()
        resetForPlaylistSwitch()
        pendingEntryKey = categoryId?.let { "c$it" }
        viewModelScope.launch { c.providerRepository.setActive(providerId) }
    }

    /** The selected entry's channels tagged with its key, so the guide can tell a stale list from the new one. */
    // Keyed on the provider too: "fav" is the same key in every playlist.
    private val hideHeaders = c.settings.settings.map { it.hideHeaderChannels }.distinctUntilChanged()

    /** How the selected entry is sorted (Sort by); a channel moves only in a Manual one. */
    val selectedSort: StateFlow<SortBy> = combine(providerId, entry) { p, e -> p to e }
        .flatMapLatest { (p, e) -> if (e == null) flowOf(SortBy.PLAYLIST) else ChannelSort.observe(c.db, c.settings, p, e) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SortBy.PLAYLIST)

    /**
     * The last list each recent category showed (by playlist, key and sort), so going back to one while browsing the
     * category panel shows it at once; the live query replaces it a moment later.
     */
    // Bounded by rows, not lists (a row is ~0.5 KB): 40 lists of a few thousand channels held ~100 MB and made the box
    // lag. Lists over 3,000 channels aren't kept; they load from the database as before.
    private val listingCache = org.imdtv.iptvtv.data.SizedLru<String, List<ChannelEntity>>(LISTING_CACHE_ROWS, maxItem = 3_000) { it.size }

    val listing: StateFlow<Pair<String?, List<ChannelEntity>>> = combine(providerId, entry, selectedSort) { p, e, by -> Triple(p, e, by) }
        .distinctUntilChangedBy { (p, e, by) -> Triple(p, e?.key, by) }
        // Latest: moving to another category cancels the load still running for the last one.
        .flatMapLatest { (p, e, by) ->
            if (e == null) flowOf(null to emptyList())
            else {
                val k = "$p|${e.key}|$by"
                combine(railRepo.channels(p, e), hideHeaders, strip) { list, hide, s ->
                    e.key to ChannelSort.sorted(if (hide) list.filterNot { isHeaderChannel(it.name) } else list, by, s)
                }.onEach { listingCache[k] = it.second }
                    .onStart { listingCache[k]?.let { emit(e.key to it) } }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null to emptyList())

    /** The selected category's channels haven't arrived yet (the guide shows "Loading channels…"). */
    val listingLoading: StateFlow<Boolean> = combine(entry, listing) { e, l -> e != null && l.first != e.key }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** For the Sort by menu: how [e] is sorted, following changes. */
    fun sortOf(e: RailEntry): kotlinx.coroutines.flow.Flow<SortBy> = providerId.flatMapLatest { ChannelSort.observe(c.db, c.settings, it, e) }

    fun setSort(e: RailEntry, by: SortBy) {
        viewModelScope.launch { moveLock.withLock { ChannelSort.set(c.db, c.settings, providerId.first(), e, by, strip.value) } }
    }

    val channels: StateFlow<List<ChannelEntity>> = listing.map { it.second }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Ticks every 30s for the "now" line and progress bars. */
    val now: StateFlow<Long> = flow { while (true) { emit(System.currentTimeMillis()); delay(30_000) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, System.currentTimeMillis())

    /** Half-hours the guide is scrolled forward from the current half-hour. */
    val timeShift = MutableStateFlow(0)

    val windowStart: StateFlow<Long> = combine(now.map { Timeline.windowStart(it) }.distinctUntilChanged(), timeShift) { w, s ->
        w + s * HALF_HOUR
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Timeline.windowStart(System.currentTimeMillis()))

    /** Covers the visible window and "now" (for the hero) even when scrolled forward. */
    // observeGuideVersion counts links; replacing one channel's link doesn't change it, so Use guide from… bumps this.
    private val guideBump = MutableStateFlow(0)
    /** True while the cursor is on a past programme of a catch-up channel (Left held on now). */
    val inPast = MutableStateFlow(false)


    val programmes: StateFlow<Map<Long, List<GuideProgramme>>> =
        combine(channels, windowStart, c.db.epgDao().observeGuideVersion(), guideBump, inPast) { ch, w, _, _, past -> Triple(ch, w, past) } // reload after an EPG import
        .mapLatest { (ch, w, past) ->
            if (provider.value == null) return@mapLatest emptyMap()
            // In the past, Left needs the programme before the window too.
            val from = minOf(w, System.currentTimeMillis()) - if (past) 6 * 3_600_000L else 0
            // By playlist: Favourites hold other playlists' channels too, each with its own guide sources.
            ch.groupBy { it.providerId }.map { (p, list) -> guide.programmes(p, list, from, w + WINDOW) }.fold(emptyMap<Long, List<GuideProgramme>>()) { a, b -> a + b }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val focused = MutableStateFlow<ChannelEntity?>(null)

    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val guideUpdating: StateFlow<Boolean> = org.imdtv.iptvtv.work.EpgRefreshWorker.updating(app, visibleOnly = true).stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val logoSource: StateFlow<List<LogoKind>> = c.settings.settings.map { it.logoOrder }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings().logoOrder)

    private val previewed = MutableStateFlow<ChannelEntity?>(null)
    /** The link [previewed] plays: its own, or one of the channels merged into it. */
    private val previewedUrl = MutableStateFlow<String?>(null)

    /** The channel the player is showing; cleared when the player stops or plays something else (a movie). */
    val previewingId: StateFlow<Long?> = combine(previewed, previewedUrl, player.playingUrl) { ch, u, url -> ch?.id?.takeIf { u == url } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The channel the guide's preview is playing, or null. */
    val previewing: ChannelEntity? get() = previewed.value?.takeIf { it.id == previewingId.value }

    var guidePos: GuidePos? = null
    /** Celestial Retro: the guide is open (else its category menu shows). Kept here so full screen and back returns to it. */
    var retroGuideOpen by androidx.compose.runtime.mutableStateOf(false)
    /** Gamestation: where the XMB is (column, item, open folder). Kept here so full screen, detail and Settings return to it. */
    var xmb by androidx.compose.runtime.mutableStateOf(org.imdtv.iptvtv.ui.xmb.XmbState())
    /** Gamestation has opened at the playing channel once (or the user moved first). */
    var xmbRestored = false

    init {
        viewModelScope.launch {
            // A recreated activity (e.g. reopened after Back-exit) gets a fresh view model while the process-wide
            // player resumes the old stream: open the guide on that channel, or the preview would jump to the top row.
            val p = provider.filterNotNull().first()
            // A merged source plays through the channel it's merged into, which is the one the guide shows.
            val playing = player.currentUrl?.let { c.db.channelDao().byStreamUrl(p.id, it) }?.let { s -> s.mergedInto?.let { c.db.channelDao().get(it) } ?: s }
            if (playing != null) { focused.value = playing; previewedUrl.value = player.currentUrl; previewed.value = playing }
            // Also moves off an entry that vanished or belongs to a previously active provider.
            rail.filterNotNull().collect { list ->
                val entries = list.map { it.entry }
                entry.value = entries.firstOrNull { it.key == pendingEntryKey }?.also { pendingEntryKey = null }
                    ?: entries.firstOrNull { it.key == entry.value?.key }
                    ?: entries.firstOrNull { it is RailEntry.Provider && it.category.id == playing?.categoryId }
                    ?: entries.firstOrNull()
            }
        }
    }

    /** Before switching playlists: forget the old one's position so the guide opens the new one from the top. */
    fun resetForPlaylistSwitch() {
        // Entry null until the new rail arrives: keys such as "recent" exist in every playlist and would keep the old list.
        entry.value = null
        focused.value = null
        previewed.value = null
        history.clear()
        guidePos = null
        leavePast()
        retroGuideOpen = false
        xmb = org.imdtv.iptvtv.ui.xmb.XmbState()
    }

    fun select(e: RailEntry) {
        entry.value = e
        leavePast()
    }

    /** Start of the guide's highlighted programme; null = the one on now. Right/Left move it and the window follows. */
    val cursor = MutableStateFlow<Long?>(null)

    /** Back to the programme on now. */
    fun leavePast() { inPast.value = false; cursor.value = null; timeShift.value = 0 }

    /**
     * Right/Left on a guide row ([progs] = its programmes). False when it can't move (Left on now). In the past, Left stops
     * [archiveDays] back and Right onto the programme on now leaves the past.
     */
    fun moveCursor(progs: List<GuideProgramme>, dir: Int, windowMin: Int, archiveDays: Int? = null): Boolean {
        val now = System.currentTimeMillis()
        if (inPast.value) {
            val to = Timeline.stepPast(progs, cursor.value ?: now, now, dir, now - (archiveDays ?: 0) * DAY)
            if (to == null) leavePast() else if (to != cursor.value) showCursor(to, windowMin) else return false // at the archive limit
            return true
        }
        val to = Timeline.stepCursor(progs, cursor.value, now, dir)
        if (to == cursor.value) return false
        return showCursor(to, windowMin)
    }

    /** Left held on now (catch-up channel): the cursor goes to the programme before the one on now. */
    fun enterPast(ch: ChannelEntity, windowMin: Int) {
        viewModelScope.launch {
            val p = catchUpProgrammes(ch).firstOrNull() ?: return@launch
            inPast.value = true
            showCursor(p.start, windowMin)
        }
    }

    /** "Pick a day": the cursor jumps to the first catch-up programme of the day starting at [dayStart]. */
    fun jumpToDay(ch: ChannelEntity, dayStart: Long, windowMin: Int) {
        viewModelScope.launch {
            val p = catchUpProgrammes(ch).lastOrNull { it.start >= dayStart } ?: return@launch
            inPast.value = true
            showCursor(p.start, windowMin)
        }
    }

    private fun showCursor(to: Long?, windowMin: Int): Boolean {
        val now = System.currentTimeMillis()
        val base = Timeline.windowStart(now)
        val ws = base + timeShift.value * HALF_HOUR
        // Keep the highlighted programme's start on screen, clear of the last half-hour.
        val shift = when {
            to == null -> 0
            to < ws || to >= ws + windowMin * 60_000L - HALF_HOUR -> Math.floorDiv(to - base, HALF_HOUR).toInt()
            else -> timeShift.value
        }
        if (shift > MAX_SHIFT) return false
        cursor.value = to
        timeShift.value = shift
        return true
    }

    // --- Catch-up (Xtream) ---

    /** [ch]'s past programmes still in its catch-up window, newest first. */
    suspend fun catchUpProgrammes(ch: ChannelEntity): List<org.imdtv.iptvtv.data.repo.GuideProgramme> {
        val days = ch.archiveDays?.takeIf { org.imdtv.iptvtv.ui.catchUpOn } ?: return emptyList()
        val now = System.currentTimeMillis()
        return guide.programmes(ch.providerId, listOf(ch), now - days * 86_400_000L, now)[ch.id].orEmpty()
            .filter { it.stop <= now }.sortedByDescending { it.start }
    }

    /** Title for the full-screen player a catch-up programme opens in (movie controls: it can be paused and seeked). */
    val openCatchUp = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** [startMs] = position in the programme. A programme still on air keeps its full length: the stream ends at now. */
    fun playCatchUp(ch: ChannelEntity, p: org.imdtv.iptvtv.data.repo.GuideProgramme, startMs: Long = 0) {
        viewModelScope.launch {
            if (!c.parental.allow(lockedNow(ch, p), "c${ch.id}@${p.start}", p.stop, ask = true)) return@launch
            val prov = c.db.providerDao().get(ch.providerId) ?: return@launch
            if (prov.type == org.imdtv.iptvtv.data.db.ProviderType.M3U) {
                val url = org.imdtv.iptvtv.data.source.m3uCatchUpUrl(ch.streamUrl, ch.catchupSource, p.start, p.stop, System.currentTimeMillis())
                if (url != null) {
                    previewed.value = null
                    player.play(url, prov.userAgent, prov.login(), vod = true, startMs = startMs)
                    openCatchUp.tryEmit("${ch.displayName(strip.value)} · ${p.title}")
                    return@launch
                }
                if (ch.catchupSource?.substringBefore('|') != "xc") return@launch
            }
            // Start times are in the server's time zone; the TV's is the best guess when the server doesn't say.
            val zone = (c.sourceFor(prov) as? org.imdtv.iptvtv.data.source.XtreamSource)?.serverTimeZone() ?: java.util.TimeZone.getDefault()
            val urls = org.imdtv.iptvtv.data.source.catchUpUrls(ch.streamUrl, p.start, p.stop, zone)
            if (urls.isEmpty()) return@launch
            previewed.value = null
            // vod: seekable, and not the live .ts reconnect path or the live stall watchdog.
            player.play(urls[0], prov.userAgent, prov.login(), vod = true, startMs = startMs, fallbackUrl = urls[1])
            openCatchUp.tryEmit("${ch.displayName(strip.value)} · ${p.title}")
        }
    }

    /** Full screen, Left held on a catch-up channel: catch-up of the programme on now, 30 s behind live. */
    fun jumpBack(ch: ChannelEntity) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val p = guide.programmes(ch.providerId, listOf(ch), now, now + 1)[ch.id].orEmpty().firstOrNull { it.start <= now && now < it.stop } ?: return@launch
            playCatchUp(ch, p, (now - 30_000 - p.start).coerceAtLeast(0))
        }
    }

    /** Channels watched full screen, for the "View Previous Channel With" keys. */
    private val history = ChannelHistory<ChannelEntity> { it.id }

    /** [dir] 1 = one further back (Right/Down), -1 = towards the newest again (Left/Up). */
    fun toPreviousChannel(dir: Int) { history.step(dir)?.let(::switchTo) }

    private var previewJob: kotlinx.coroutines.Job? = null

    /** Parental controls: whether [ch] showing [on] needs the PIN (its category is locked, or [on] is rated at the limit or above). */
    private suspend fun lockedNow(ch: ChannelEntity, on: GuideProgramme?): Boolean {
        val s = c.settings.settings.first() // not settings.value: at app start it still holds the defaults (parental off)
        if (!s.parentalOn) return false
        return s.parentalLocked(ch.parentalLock || ch.categoryId?.let { c.db.categoryDao().get(it) }?.parentalLock == true, on?.ageRating)
    }
    private suspend fun onNow(ch: ChannelEntity): GuideProgramme? = System.currentTimeMillis().let { now ->
        guide.programmes(ch.providerId, listOf(ch), now, now + 1)[ch.id].orEmpty().firstOrNull { it.start <= now && now < it.stop }
    }
    /** The channel and programme start last let through: a new programme on the same channel is checked again. */
    private var allowedOn: Pair<Long, Long?>? = null
    /** A channel parental controls kept from playing (PIN cancelled, or autoplay): full screen says so, OK asks again. */
    val lockedChannel = MutableStateFlow<ChannelEntity?>(null)

    /** [ask] false (autoplay while scrolling): a locked channel just doesn't play, the PIN is asked only on OK. */
    fun preview(ch: ChannelEntity, ask: Boolean = true) {
        previewed.value = ch
        previewJob?.cancel() // a latency check for the channel scrolled past stops here
        previewJob = viewModelScope.launch {
            val on = if (c.settings.settings.first().parentalOn) onNow(ch) else null
            val locked = lockedNow(ch, on)
            val until = on?.stop ?: (System.currentTimeMillis() + 3_600_000L)
            if (!c.parental.allow(locked, "c${ch.id}", until, ask = false)) {
                // Not unlocked yet: stop first, so the locked channel (still playing after the app reopens on it) isn't seen behind the PIN dialog.
                player.stop()
                if (!ask || !c.parental.allow(locked, "c${ch.id}", until, ask = true)) {
                    if (previewed.value?.id == ch.id) { previewed.value = null; lockedChannel.value = ch }
                    return@launch
                }
            }
            lockedChannel.value = null
            allowedOn = ch.id to on?.start
            // The playlist's login fills the link. At app start (last channel) it may not have loaded yet, and a link
            // without it is refused (401) on every retry. Loaded (every other time), this doesn't suspend.
            // Another playlist's channel (Favourites span playlists): its own login, from the database.
            val p = if (ch.providerId == c.providerRepository.activeProviderId.first()) provider.first { it?.id == ch.providerId }!!
                else c.db.providerDao().get(ch.providerId) ?: return@launch
            // Merged channels play their best source (Settings › Channels › Merge by); others play themselves at once.
            val src = if (ch.mergeHead) org.imdtv.iptvtv.data.repo.ChannelMerge.pick(c.db, c.http, ch,
                settings.value.mergeBy, p.userAgent, p.login()) else ch
            if (previewed.value?.id == ch.id) { previewedUrl.value = src.streamUrl; player.play(src.streamUrl, p.userAgent, p.login(), drm = src.drm()) }
            c.settings.update { it.copy(lastLiveChannelId = ch.id) }
        }
    }

    init {
        // Parental controls: a rated programme starting on the channel being watched is checked like a new channel.
        viewModelScope.launch {
            now.collect {
                val ch = previewed.value ?: return@collect
                if (!settings.value.parentalOn || previewJob?.isActive == true) return@collect
                val on = onNow(ch)
                if (allowedOn == ch.id to on?.start) return@collect
                if (lockedNow(ch, on)) preview(ch) else allowedOn = ch.id to on?.start
            }
        }
    }

    /** Recently watched channels of the active playlist, newest first (spec §4.2). */
    val recents: StateFlow<List<ChannelEntity>> = providerId.flatMapLatest { c.db.recentDao().observe(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** A channel started full screen (or zapped to): Recents and the last channel. Previews only set the last channel. */
    fun watched(ch: ChannelEntity) {
        history.played(ch)
        viewModelScope.launch {
            c.db.recentDao().record(ch.providerId, ch.id, System.currentTimeMillis())
            c.settings.update { it.copy(lastLiveChannelId = ch.id) }
        }
    }

    /** Now and next for the overlay's Recents cards, which may be outside the selected category. */
    val recentProgrammes: StateFlow<Map<Long, List<GuideProgramme>>> =
        combine(recents, now, c.db.epgDao().observeGuideVersion()) { r, n, _ -> r to n }
            .mapLatest { (r, n) -> provider.value?.let { guide.programmes(it.id, r, n, n + 12 * 3_600_000L) }.orEmpty() } // now and next
            // Only while the full-screen overlay shows it: Eagerly, it queried the database every 30 s all day, in the background too.
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Full screen Up/Down (spec §4.2): the next/previous channel of the selected category, wrapping. */
    fun zapBy(delta: Int) {
        if (listing.value.first != entry.value?.key) return // a just-switched category's list hasn't loaded: don't zap in the old one
        val list = channels.value
        val id = zap(list.map { it.id }, focused.value?.id ?: return, delta) ?: return
        val ch = list.first { it.id == id }
        if (ch.id == focused.value?.id) return // a category of one zaps to itself
        zapTo(ch)
    }

    private fun zapTo(ch: ChannelEntity) {
        history.played(ch) // also when zapping Recents, which skips watched()
        focused.value = ch
        preview(ch) // also writes lastLiveChannelId
        if (zapRecordsRecent(entry.value)) watched(ch)
    }

    // --- Channel number entry (remote's number keys) ---

    private val digits = DigitBuffer()
    /** The number box's text: digits so far ("75_"), or "No channel 754" for a moment; empty when hidden. */
    val typed = MutableStateFlow("")
    private var typedJob: kotlinx.coroutines.Job? = null
    private var typedFullScreen = false
    private var swallowOkUp = false // the OK that committed must not also open the overlay / click a row on key-up
    /** Guide: a typed number found this channel; the guide moves its focus there. */
    val jumps = kotlinx.coroutines.flow.MutableSharedFlow<ChannelEntity>(extraBufferCapacity = 1)

    /** Displayed number [n] (custom first) among the active playlist's visible channels; the selected list wins, else any category. */
    suspend fun channelByNumber(n: Int): ChannelEntity? {
        val found = c.db.channelDao().byNumber(provider.value?.id ?: return null, n)
        val here = channels.value.mapTo(HashSet()) { it.id }
        return found.firstOrNull { it.id in here } ?: found.firstOrNull()
    }

    /** Number keys for full screen ([fullScreen]) or the guide: true when the key was used. */
    fun numberKey(e: KeyEvent, fullScreen: Boolean): Boolean {
        val down = e.type == KeyEventType.KeyDown
        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
        val d = digitOf(e.key)
        if (d == null) {
            if (!ok) return false
            if (!down && swallowOkUp) { swallowOkUp = false; return true }
            if (!down || !digits.active) return false
            swallowOkUp = true
            commitNumber()
            return true
        }
        if (down && e.nativeKeyEvent.repeatCount == 0) {
            typedFullScreen = fullScreen
            typedJob?.cancel()
            digits.append(d)?.let { commitNumber(it); return true }
            typed.value = digits.text + "_"
            typedJob = viewModelScope.launch { delay(2_000); commitNumber() }
        }
        return true
    }

    fun cancelNumber() { typedJob?.cancel(); digits.cancel(); typed.value = "" }

    private fun commitNumber(number: Int? = digits.commit()) {
        typedJob?.cancel()
        val n = number ?: return
        typed.value = "$n"
        typedJob = viewModelScope.launch {
            val ch = channelByNumber(n)
            if (ch == null) { typed.value = getApplication<Application>().getString(R.string.live_no_channel, n); delay(1_500); typed.value = ""; return@launch }
            typed.value = ""
            if (typedFullScreen) {
                if (ch.id != focused.value?.id) if (ch.id in channels.value.map { it.id }) zapTo(ch) else switchTo(ch)
            } else {
                selectCategoryOf(ch)
                focused.value = ch
                jumps.tryEmit(ch)
            }
        }
    }

    private fun selectCategoryOf(ch: ChannelEntity) {
        if (ch.id in channels.value.map { it.id }) return
        rail.value?.map { it.entry }?.find { it is RailEntry.Provider && it.category.id == ch.categoryId }?.let { select(it) }
    }

    /** A Recents card: play it and select its category, so Up/Down then zaps there and Back lands on it in the guide. */
    fun switchTo(ch: ChannelEntity) {
        rail.value?.map { it.entry }?.find { it is RailEntry.Provider && it.category.id == ch.categoryId }?.let { select(it) }
        focused.value = ch
        preview(ch)
        watched(ch)
    }

    /** Launch on the last channel full screen: select its category and focus it so Back lands on it in the guide. */
    fun startOn(ch: ChannelEntity) {
        focused.value = ch
        preview(ch)
        watched(ch)
        viewModelScope.launch {
            rail.filterNotNull().first { it.isNotEmpty() }.map { it.entry }
                .find { it is RailEntry.Provider && it.category.id == ch.categoryId }?.let { select(it) }
        }
    }

    // --- Long-press menu actions (spec §4.3) ---

    val favouriteIds: StateFlow<Set<Long>> = c.db.favouriteDao().observeLiveIds().map { it.toHashSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val groups: StateFlow<List<CustomGroupEntity>> = providerId.flatMapLatest { c.db.customGroupDao().observe(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun toggleFavourite(ch: ChannelEntity) {
        viewModelScope.launch {
            val dao = c.db.favouriteDao()
            if (ch.id in favouriteIds.value) dao.remove(ch.providerId, Kind.LIVE, ch.id)
            else dao.add(FavouriteEntity(ch.providerId, Kind.LIVE, ch.id, System.currentTimeMillis()))
        }
    }

    fun addToGroup(ch: ChannelEntity, groupId: Long) { viewModelScope.launch { c.db.customGroupDao().addChannel(groupId, ch.id) } }

    fun removeFromGroup(ch: ChannelEntity, groupId: Long) { viewModelScope.launch { c.db.customGroupDao().removeChannel(groupId, ch.id) } }

    fun addToNewGroup(ch: ChannelEntity, name: String) {
        viewModelScope.launch { c.db.customGroupDao().run { addChannel(create(ch.providerId, name), ch.id) } }
    }

    /** Blank resets to the provider's name. */
    fun rename(ch: ChannelEntity, name: String?) {
        viewModelScope.launch { c.db.channelDao().setCustomName(ch.id, name?.trim()?.takeIf { it.isNotEmpty() }) }
    }

    /** Hold OK on a category › Enable / Disable Parental Lock; unlocking takes the PIN. */
    /** Hold OK on a channel › Enable / Disable Parental Lock; unlocking takes the PIN. */
    fun setChannelLock(ch: ChannelEntity, on: Boolean) {
        viewModelScope.launch { if (on || c.parental.askPin()) c.db.channelDao().setParentalLock(ch.id, on) }
    }

    fun setParentalLock(cat: org.imdtv.iptvtv.data.db.CategoryEntity, on: Boolean) {
        viewModelScope.launch { if (on || c.parental.askPin()) c.db.categoryDao().setParentalLock(cat.id, on) }
    }

    /** Gives [ch] number [n]; a number another channel shows first asks [onTaken] (unless [confirmed]), then swaps. */
    fun changeNumber(ch: ChannelEntity, n: Int, confirmed: Boolean = false, onTaken: (ChannelEntity, Int) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val dao = c.db.channelDao()
            val holder = dao.numberHolders(ch.providerId, ch.id, n).firstOrNull()
            if (holder != null && !confirmed) onTaken(holder, dao.swapTarget(ch, n)) else dao.changeNumber(ch, n)
        }
    }

    // Change logo… (spec §4.3)
    suspend fun logoCandidates(ch: ChannelEntity): List<String> = c.logos.candidatesFor(ch)
    fun setCustomLogoUrl(ch: ChannelEntity, url: String) { viewModelScope.launch { c.logos.setCustomUrl(ch.id, url) } }
    fun setCustomLogoFile(ch: ChannelEntity, uri: android.net.Uri, ext: String) {
        viewModelScope.launch {
            runCatching { c.logos.setCustomFromFile(ch.id, { getApplication<android.app.Application>().contentResolver.openInputStream(uri)!! }, ext) }
                .onFailure { android.util.Log.w("LiveViewModel", "custom logo: $it") }
        }
    }
    fun useAutomaticLogo(ch: ChannelEntity) { viewModelScope.launch { c.logos.clearCustom(ch.id) } }

    // Use guide from… (spec §5.4)
    /** Donors with their category name, for "<name> · <category>". */
    /** Playlist channels with a guide, then channels of the playlist's EPG sources, labelled by source. */
    suspend fun donors(ch: ChannelEntity, query: String): List<GuideChoice> {
        // The channel list and its same-name siblings (~3 s on a TV box) are worked out once per channel, not per search.
        val pool = donorPool?.takeIf { it.channelId == ch.id } ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            // Only guides with programmes still to come, and not the one this channel already has: offering a linked channel
            // whose guide was empty (often the very same link) meant picking it changed nothing (#19).
            val live = c.db.programmeDao().liveKeys(System.currentTimeMillis()).toHashSet()
            val keys = c.db.epgDao().links(ch.providerId).associate { it.channelId to ProgrammeEntity.key(it.sourceId, it.xmltvId) }
            val useful = { k: String? -> k != null && k != keys[ch.id] && k in live }
            val list = c.db.channelDao().withGuide(ch.providerId).filter { useful(keys[it.id]) }
            // Every guide of this playlist (its own, open-source, yours), so a name the matcher missed can be searched (#19).
            val sources = c.db.epgDao().sources(ch.providerId)
            DonorPool(ch.id, list, guideSiblings(ch, list), c.db.categoryDao().allKinds(ch.providerId).associate { it.id to it.name },
                sources.flatMap { c.db.epgDao().channels(it.id) }.filter { useful(ProgrammeEntity.key(it.sourceId, it.xmltvId)) },
                sources.associate { it.id to org.imdtv.iptvtv.ui.settings.sourceLabel(it) })
        }.also { donorPool = it }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            guideDonors(ch, pool.list, query, siblings = pool.siblings).map { GuideChoice.Channel(it, it.categoryId?.let(pool.cats::get)) } +
                sourceChannelChoices(ch.name, pool.sourceRows, pool.sourceLabels, query).map { GuideChoice.Source(it) }
        }
    }
    private class DonorPool(val channelId: Long, val list: List<ChannelEntity>, val siblings: List<ChannelEntity>, val cats: Map<Long, String>,
                            val sourceRows: List<EpgChannelEntity>, val sourceLabels: Map<Long, String>)
    private var donorPool: DonorPool? = null
    /** The picker closed: links may change before it opens again. */
    fun clearDonors() { donorPool = null }
    suspend fun hasManualGuide(ch: ChannelEntity): Boolean = c.db.epgDao().link(ch.id)?.manual == true
    fun useGuideFrom(ch: ChannelEntity, donor: ChannelEntity) {
        viewModelScope.launch {
            val l = c.db.epgDao().link(donor.id) ?: return@launch
            c.db.epgDao().upsertLinks(listOf(EpgLinkEntity(ch.id, l.sourceId, l.xmltvId, manual = true)))
            c.db.epgDao().refreshEpgLogos(ch.providerId)
            guideBump.value++
        }
    }
    /** A manual link to a channel of one of Your EPG sources: the matcher never touches it. */
    fun useGuideFrom(ch: ChannelEntity, s: SourceChannel) {
        viewModelScope.launch {
            c.db.epgDao().upsertLinks(listOf(EpgLinkEntity(ch.id, s.sourceId, s.xmltvId, manual = true)))
            c.db.epgDao().refreshEpgLogos(ch.providerId)
            guideBump.value++
        }
    }
    /**
     * The channel's offset read afresh from the store the guide uses (a sync may have changed it since the list loaded):
     * its own override, and its linked guide source's.
     */
    suspend fun channelOffset(ch: ChannelEntity): org.imdtv.iptvtv.data.epg.ChannelOffset {
        val own = (c.db.channelDao().get(ch.id) ?: ch).epgOffsetMinutes
        val source = c.db.epgDao().linksFor(listOf(ch.id)).firstOrNull()?.let { c.db.epgDao().source(it.sourceId) }
        return org.imdtv.iptvtv.data.epg.ChannelOffset(own, source?.offsetMinutes ?: 0)
    }
    /** null = follow the EPG source's offset. Only reloads the guide rows from the database: no guide download. */
    fun setEpgOffset(ch: ChannelEntity, minutes: Int?) {
        viewModelScope.launch { c.db.channelDao().setEpgOffset(ch.id, minutes); guideBump.value++ }
    }
    fun useAutomaticGuide(ch: ChannelEntity) {
        viewModelScope.launch {
            c.db.epgDao().deleteLink(ch.id)
            c.epgMatcher.rematch(ch.providerId)  // follows Guide source and Borrow settings
            guideBump.value++
        }
    }

    // --- Recording (channel menu) ---

    val recordingOn: StateFlow<Boolean> = c.settings.settings.map { it.recordingOn }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    /** (channelId, programme start) of scheduled/recording programmes: the guide's dot and the menu's mark. */
    val marks: StateFlow<Set<Pair<Long, Long>>> = c.recordings.marks().stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    /** Channel id → the recording running on it. */
    val recordingOnChannel: StateFlow<Map<Long, Long>> = c.recordings.running
        .map { m -> m.values.mapNotNull { r -> r.channelId?.let { it to r.id } }.toMap() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Now and the next programmes (up to 8, next 24 h) for "Record a later programme…". */
    suspend fun upcoming(ch: ChannelEntity): List<GuideProgramme> {
        val t = System.currentTimeMillis()
        return guide.programmes(ch.providerId, listOf(ch), t, t + 24 * 3_600_000L)[ch.id].orEmpty().take(8)
    }

    fun recordNow(ch: ChannelEntity) {
        viewModelScope.launch {
            val t = System.currentTimeMillis()
            c.recordings.recordNow(ch, ch.displayName(strip.value), upcoming(ch).firstOrNull { it.start <= t && t < it.stop })
        }
    }

    fun schedule(ch: ChannelEntity, p: GuideProgramme) { viewModelScope.launch { c.recordings.schedule(ch, ch.displayName(strip.value), p) } }

    /** The scheduled (or running) recording of [p] on [ch]: cancelled, or stopped if it has started. */
    fun cancelRecording(ch: ChannelEntity, p: GuideProgramme) {
        viewModelScope.launch {
            val r = c.db.recordingDao().onChannel(ch.id, listOf(org.imdtv.iptvtv.recording.RecStatus.SCHEDULED, org.imdtv.iptvtv.recording.RecStatus.RECORDING))
                .firstOrNull { it.programmeStart == p.start } ?: return@launch
            if (r.status == org.imdtv.iptvtv.recording.RecStatus.RECORDING) c.recordings.stop(r.id) else c.recordings.cancel(r.id)
        }
    }

    fun stopRecording(id: Long) = c.recordings.stop(id)

    fun hide(ch: ChannelEntity) { viewModelScope.launch { c.db.channelDao().setHidden(ch.id, true) } }

    /** Long OK › Merge channel, then OK on [into]: [src] becomes one of [into]'s sources. */
    fun merge(src: ChannelEntity, into: ChannelEntity) { viewModelScope.launch { org.imdtv.iptvtv.data.repo.ChannelMerge.merge(c.db, src, into) } }
    suspend fun mergedMembers(head: ChannelEntity) = org.imdtv.iptvtv.data.repo.ChannelMerge.members(c.db.channelDao(), head)
    /** View merged channels' switch: [member] out of [head], or back in. */
    suspend fun setMerged(member: ChannelEntity, head: ChannelEntity, on: Boolean) =
        if (on) org.imdtv.iptvtv.data.repo.ChannelMerge.merge(c.db, member, head) else org.imdtv.iptvtv.data.repo.ChannelMerge.unmergeOne(c.db, member)
    fun unmerge(head: ChannelEntity) { viewModelScope.launch { org.imdtv.iptvtv.data.repo.ChannelMerge.unmerge(c.db, head) } }

    fun hideCategory(cat: CategoryEntity) { viewModelScope.launch { c.db.categoryDao().setHidden(cat.id, true) } }

    /** Moves [delta] places among the categories the rail shows, so a step never vanishes behind a hidden one. */
    private val moveLock = kotlinx.coroutines.sync.Mutex()
    fun setLiveSubtitles(on: Boolean) { viewModelScope.launch { c.settings.update { it.copy(liveSubtitlesOn = on) } } }
    fun setVideoAspect(a: org.imdtv.iptvtv.data.settings.VideoAspect) { viewModelScope.launch { c.settings.update { it.copy(videoAspect = a) } } }

    /** Sort A–Z once (issue #9): saved as the entry's own order, so Move still works after. */
    fun sortAZ(e: RailEntry) {
        viewModelScope.launch { moveLock.withLock { ChannelSort.sortOnce(c.db, c.settings, providerId.first(), e, strip.value) } }
    }

    /** A channel moved in the guide: [order] is the list as shown after the move, [before] as it was. */
    fun saveGuideOrder(ch: ChannelEntity, before: List<ChannelEntity>, order: List<ChannelEntity>) {
        val at = entry.value ?: return
        viewModelScope.launch { moveLock.withLock {
            when (at) {
                RailEntry.Favourites -> c.db.withTransaction { order.forEachIndexed { n, x -> c.db.favouriteDao().setSortOrder(x.providerId, Kind.LIVE, x.id, n) } }
                is RailEntry.Custom -> c.db.withTransaction { order.forEachIndexed { n, x -> c.db.customGroupDao().setMemberOrder(at.group.id, x.id, n) } }
                // The category also holds hidden channels: land on the place of the channel it was moved to.
                is RailEntry.Provider -> {
                    val target = before.getOrNull(order.indexOfFirst { it.id == ch.id }) ?: return@withLock
                    val all = c.db.channelDao().allByCategory(at.category.id)
                    CategoryRepository(c.db).moveChannel(at.category.id, ch.id, all.indexOfFirst { it.id == target.id } - all.indexOfFirst { it.id == ch.id })
                }
                RailEntry.Recent -> Unit
            }
        } }
    }

    fun moveRail(e: RailEntry, delta: Int) = when (e) {
        is RailEntry.Provider -> moveCategory(e.category, delta)
        is RailEntry.Custom -> moveGroup(e.group, delta)
        else -> Unit
    }

    /** Moves one of your categories [delta] steps among those shown in the rail with the same placement. */
    private fun moveGroup(g: CustomGroupEntity, delta: Int) {
        viewModelScope.launch { moveLock.withLock {
            val dao = c.db.customGroupDao()
            val all = dao.observe(g.providerId).first().sortedBy { it.placement.ordinal } // the rail's order
            val placement = all.associate { it.id to it.placement }
            val shown = rail.value.orEmpty().mapNotNullTo(HashSet()) { (it.entry as? RailEntry.Custom)?.group?.id }
            val order = stepAmong(all.map { it.id }, g.id, delta) { it in shown && placement[it] == placement[g.id] } ?: return@withLock
            c.db.withTransaction { order.forEachIndexed { n, id -> dao.setSortOrder(id, n) } }
        } }
    }

    fun moveCategory(cat: CategoryEntity, delta: Int) {
        viewModelScope.launch { moveLock.withLock {
            val all = c.db.categoryDao().all(cat.providerId, Kind.LIVE)
            val visible = all.filter { !it.hidden || it.id == cat.id }
            val i = visible.indexOfFirst { it.id == cat.id }.takeIf { it >= 0 } ?: return@withLock
            val target = visible[(i + delta).coerceIn(0, visible.lastIndex)]
            CategoryRepository(c.db).move(cat.providerId, Kind.LIVE, cat.id,
                all.indexOfFirst { it.id == target.id } - all.indexOfFirst { it.id == cat.id })
        } }
    }

    companion object {
        /** Category lists kept for the panel's instant preview: about 3 MB of channel rows at most. */
        const val LISTING_CACHE_ROWS = 6_000
        const val HALF_HOUR = 30 * 60_000L
        const val WINDOW = 5 * HALF_HOUR // the widest guide (Glass, Liquid: 2½ hours)
        const val MAX_SHIFT = 7 * 48 // 7 days of half-hours
        const val DAY = 86_400_000L
    }
}

/** A "Use guide from…" row: a playlist channel's guide, or a channel of one of Your EPG sources. */
sealed interface GuideChoice {
    data class Channel(val channel: ChannelEntity, val category: String?) : GuideChoice
    data class Source(val channel: SourceChannel) : GuideChoice
}

/** [all] with [item] moved [delta] steps among its [peer]s (others are stepped over); null when it can't move. */
internal fun <T> stepAmong(all: List<T>, item: T, delta: Int, peer: (T) -> Boolean): List<T>? {
    val peers = all.filter { it == item || peer(it) }
    val i = peers.indexOf(item)
    val j = (i + delta).coerceIn(peers.indices)
    if (i < 0 || i == j) return null
    val rest = all.filter { it != item }
    return rest.toMutableList().apply { add(rest.indexOf(peers[j]) + if (delta > 0) 1 else 0, item) }
}
