package org.imdtv.iptvtv.ui.vod

import org.imdtv.iptvtv.data.db.login
import org.imdtv.iptvtv.R

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import org.imdtv.iptvtv.data.parental.parentalLocked
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.MutableStateFlow
import org.imdtv.iptvtv.data.catalogue.cleaned
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.imdtv.iptvtv.IptvApp
import org.imdtv.iptvtv.data.db.CategoryEntity
import org.imdtv.iptvtv.data.db.FavouriteEntity
import org.imdtv.iptvtv.data.db.MovieEntity
import org.imdtv.iptvtv.data.db.ProgressEntity
import org.imdtv.iptvtv.data.db.ProviderEntity
import org.imdtv.iptvtv.data.db.ProviderType
import org.imdtv.iptvtv.data.db.SeriesEntity
import org.imdtv.iptvtv.data.model.Episode
import org.imdtv.iptvtv.data.model.Kind
import org.imdtv.iptvtv.data.source.PlaylistSource
import org.imdtv.iptvtv.ui.live.RailCategory

/** A movie or a series, as shown on posters and in the hero. */
data class VodItem(
    val id: Long, val kind: Kind, val remoteId: String, val name: String, val poster: String?,
    val year: Int?, val rating: Double?, val categoryId: Long?, val streamUrl: String?,
    /** Its playlist: Continue watching and Favourites show every playlist's. */
    val providerId: Long,
)

/** Fetched on demand from the provider (Xtream get_vod_info / get_series_info). */
data class VodDetail(
    val backdrop: String?, val plot: String?, val genre: String?, val director: String?, val cast: String?,
    val durationSecs: Int?, val episodes: List<Episode> = emptyList(), val height: Int? = null,
)

/** A title from a Stremio add-on on the Addons page (not in any playlist). */
val VodItem.isAddon get() = providerId == VodViewModel.ADDONS_PROVIDER

fun MovieEntity.item() = VodItem(id, Kind.MOVIE, remoteId, name, poster, year, rating, categoryId, streamUrl, providerId)
fun SeriesEntity.item() = VodItem(id, Kind.SERIES, remoteId, name, poster, year, rating, categoryId, null, providerId)

/** In-category matches: null for a blank query (normal view), else [search] of the scope (kind, category or null = all) after a 300 ms pause. */
@OptIn(kotlinx.coroutines.FlowPreview::class, ExperimentalCoroutinesApi::class)
fun vodMatches(query: kotlinx.coroutines.flow.Flow<String>, scope: kotlinx.coroutines.flow.Flow<Pair<Kind, Long?>>,
               version: kotlinx.coroutines.flow.Flow<Long> = flowOf(0L),
               search: suspend (Kind, Long?, String) -> List<VodItem>): kotlinx.coroutines.flow.Flow<List<VodItem>?> =
    combine(query, scope) { q, s -> q to s }
        .debounce { (q, _) -> if (org.imdtv.iptvtv.data.search.queryWords(q).isEmpty()) 0L else 300L }
        .distinctUntilChanged()
        .combine(version) { qs, _ -> qs } // a catalogue write re-runs the same query
        .mapLatest { (q, s) -> if (org.imdtv.iptvtv.data.search.queryWords(q).isEmpty()) null else search(s.first, s.second, q) }

/** A new kind or category starts with an empty search bar. */
suspend fun clearQueryOnScopeChange(scope: kotlinx.coroutines.flow.Flow<Pair<Kind, Long?>>, query: MutableStateFlow<String>) =
    scope.distinctUntilChanged().drop(1).collect { query.value = "" }

/** How far the latest play of [item] got (0–1), for a poster bar; null if never played or watched. [rows] newest first. */
fun progressFraction(item: VodItem, rows: List<ProgressEntity>): Float? =
    rows.firstOrNull { it.kind == item.kind && it.itemId == item.id }?.takeIf { !it.completed && it.durationMs > 0 }
        ?.let { (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f) }

@OptIn(ExperimentalCoroutinesApi::class)
class VodViewModel(private val app: Application) : AndroidViewModel(app) {
    private val c = (app as IptvApp).container
    val player get() = c.playerManager

    private val provider: StateFlow<ProviderEntity?> = c.providerRepository.activeProviderId
        .flatMapLatest { id -> id?.let { c.db.providerDao().observe(it) } ?: flowOf(null) } // an edit applies at once
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // One source per provider row: Xtream checks credentials once per instance; an edited row gets a new one.
    private var source: Pair<ProviderEntity, PlaylistSource>? = null
    private fun source(p: ProviderEntity) = source?.takeIf { it.first == p }?.second
        ?: c.sourceFor(p).also { source = p to it }

    /** The playlist in use: the rail's section header for its categories. */
    val activeProvider: StateFlow<ProviderEntity?> get() = provider
    val kind = MutableStateFlow(Kind.MOVIE)
    // Rows key on the id only: the provider row also changes on every sync and name clean-up edit.
    private val providerId = provider.filterNotNull().map { it.id }.distinctUntilChanged()

    private val categoryStrip = provider.map { org.imdtv.iptvtv.data.catalogue.parseStripList(it?.categoryStrip.orEmpty()) }.distinctUntilChanged()

    /** The playlist is updating and hasn't finished this tab's kind yet (it is loaded, and kinds sync in order). */
    val updating: StateFlow<Boolean> = combine(provider, c.providerRepository.syncing, kind) { p, s, k ->
        p != null && p.loads(k) && (s[p.id]?.let { it.ordinal <= k.ordinal } ?: false)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Every progress row of the playlists in use (movies and episodes; small), newest first. Item ids are unique across playlists. */
    val progress: StateFlow<List<ProgressEntity>> = providerId.flatMapLatest { c.db.progressDao().observeShown(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Movies left part-way, and series with an episode in progress or one watched in the last 30 days (the next is due). */
    val continueWatching: StateFlow<List<VodItem>> = combine(progress, kind) { rows, k -> rows to k }
        .mapLatest { (rows, k) ->
            val recent = System.currentTimeMillis() - 30L * 24 * 3600_000
            items(k, rows.filter { it.kind == k }.distinctBy { it.itemId } // newest row per movie/series
                .filter { !it.completed || (k == Kind.SERIES && it.updatedAt > recent) }.map { it.itemId })
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val categories: StateFlow<List<RailCategory>> = combine(providerId, kind) { p, k -> p to k }
        .flatMapLatest { (p, k) ->
            val counts = if (k == Kind.MOVIE) c.db.movieDao().observeCounts(p) else c.db.seriesDao().observeCounts(p)
            combine(c.db.categoryDao().observeVisible(p, k), counts, categoryStrip, continueWatching) { cats, n, strip, cont ->
                val byId = n.associate { it.categoryId to it.n }
                // Continue watching is a category too, so every theme's rows, rails and lists show it first.
                listOfNotNull(cont.takeIf { it.isNotEmpty() }?.let { RailCategory(CategoryEntity(CONTINUE_ID, p, k, "", app.getString(R.string.vod_continue_watching)), it.size) }) +
                    cats.map { RailCategory(it.cleaned(strip), byId[it.id] ?: 0) }.filter { it.count > 0 }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val favourites: StateFlow<List<VodItem>> = combine(providerId, kind) { p, k -> p to k }
        .flatMapLatest { (p, k) -> c.db.favouriteDao().observeShown(p, k) }
        .mapLatest { favs -> items(kind.value, favs.map(FavouriteEntity::itemId)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Category shown as a full poster grid (picked from the rail); null = home rows. */
    val gridCategory = MutableStateFlow<CategoryEntity?>(null)

    /** The rail's sections for the other playlists (this tab's kind on), and which are opened (this session only). */
    val otherPlaylists: StateFlow<List<org.imdtv.iptvtv.data.repo.OtherPlaylist>> =
        org.imdtv.iptvtv.data.repo.otherPlaylists(c.db, c.providerRepository.activeProviderId, kind)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()) // only while a rail shows: counts re-run on every sync write
    val expandedPlaylists = MutableStateFlow<Set<Long>>(emptySet())
    private var pendingGrid: CategoryEntity? = null

    /** Opens [cat]'s grid once its playlist (being switched to) has loaded its categories. */
    fun openAfterSwitch(cat: CategoryEntity) { pendingGrid = cat; gridCategory.value = null; focused.value = null }

    private var serverGrid: Long? = null // the media-server grid openOther opened, kept when the rail's categories change
    /**
     * OK on another playlist's category. A media server's grid opens right here: switching to it made it the active
     * playlist, which emptied Live TV and moved the open-source guide sync onto it (Reddit / Thomson report, 0.7.0).
     * True = the caller switches to that playlist (IPTV playlists, as before).
     */
    fun openOther(cat: CategoryEntity): Boolean {
        if (otherPlaylists.value.find { it.provider.id == cat.providerId }?.provider?.type?.mediaServer != true) {
            openAfterSwitch(cat); return true
        }
        serverGrid = cat.id; gridCategory.value = cat; focused.value = null
        return false
    }
    val grid: StateFlow<List<VodItem>> = gridCategory
        .flatMapLatest { cat -> if (cat?.id == CONTINUE_ID) continueWatching else kotlinx.coroutines.flow.flow { emit(cat?.let { inCategory(it.id, GRID_LIMIT) } ?: emptyList()) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The in-category search bar's text; [matches] null = no query, show the normal rows/grid. */
    val query = MutableStateFlow("")
    private val searchScope = combine(kind, gridCategory) { k, g -> k to g?.id }
    val matches: StateFlow<List<VodItem>?> = vodMatches(query, searchScope, c.search.version) { k, cat, q ->
        val p = provider.value ?: return@vodMatches emptyList()
        // All searches every playlist in use (this one's matches first); a category, only its own playlist.
        if (cat == null) inUse(k).flatMap { c.search.inCategory(it, k, null, q) }
        else c.search.inCategory(gridCategory.value?.takeIf { it.id == cat }?.providerId ?: p.id, k, cat.takeIf { it != CONTINUE_ID }, q)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private suspend fun inUse(k: Kind): List<Long> = provider.value?.id?.let { c.providerRepository.inUse(it, k) } ?: emptyList()

    /** Media servers' own part-watched titles into Continue watching (each at most every 5 min). */
    fun refreshResume() { viewModelScope.launch { inUse(kind.value).forEach { c.providerRepository.importResume(it) } } }

    fun warmSearch() { viewModelScope.launch { inUse(kind.value).forEach { c.search.warm(it, emptyList(), listOf(kind.value)) } } }

    val focused = MutableStateFlow<VodItem?>(null)
    val details = MutableStateFlow<Map<Long, VodDetail>>(emptyMap())

    init {
        viewModelScope.launch { clearQueryOnScopeChange(searchScope, query) }
        // Leave a grid whose category vanished or belongs to a previously active provider.
        viewModelScope.launch {
            categories.collect { list ->
                pendingGrid?.let { p -> list.find { it.category.id == p.id }?.let { gridCategory.value = it.category; pendingGrid = null } }
                // shortcut: a media server's grid (openOther) stays open even if that server is removed meanwhile; check otherPlaylists if that bites.
                gridCategory.value?.let { g -> if (g.id != serverGrid && list.none { it.category.id == g.id }) gridCategory.value = null }
            }
        }
    }

    fun setKind(k: Kind) {
        if (kind.value == k) return
        kind.value = k
        gridCategory.value = null
        focused.value = null
    }

    suspend fun inCategory(categoryId: Long, limit: Int = ROW_LIMIT): List<VodItem> {
        if (categoryId == CONTINUE_ID) return continueWatching.value.take(limit)
        val sort = c.settings.settings.first().vodSort.name // Settings › Movies & Series › Sort by
        return if (kind.value == Kind.MOVIE) c.db.movieDao().inCategory(categoryId, limit, sort).map { it.item() }
        else c.db.seriesDao().inCategory(categoryId, limit, sort).map { it.item() }
    }

    private suspend fun items(k: Kind, ids: List<Long>): List<VodItem> {
        if (ids.isEmpty()) return emptyList()
        val byId = if (k == Kind.MOVIE) c.db.movieDao().byIds(ids).associate { it.id to it.item() }
        else c.db.seriesDao().byIds(ids).associate { it.id to it.item() }
        return ids.mapNotNull { byId[it] }
    }

    /** Loads plot/backdrop/episodes once per item; failures are logged and leave the hero without them. */
    fun loadDetail(item: VodItem) {
        if (details.value.containsKey(item.id)) return
        viewModelScope.launch {
            // An add-on title: details from the first add-on serving meta for its type and id.
            val src = if (item.isAddon) c.addonSource else {
                val p = providerOf(item) ?: return@launch
                // M3U has no movie details, and asking would download and hold the whole playlist; Stalker has no detail call.
                if (item.kind == Kind.MOVIE && !p.type.xtream && !p.type.mediaServer) return@launch
                source(p)
            }
            runCatching {
                if (item.kind == Kind.MOVIE) src.movieDetail(item.remoteId).let {
                    VodDetail(it.backdrop, it.plot, it.genre, it.director, it.cast, it.durationSecs, height = it.height)
                } else src.seriesDetail(item.remoteId).let {
                    VodDetail(it.backdrop, it.plot, it.genre, null, null, null, it.episodes)
                }
            }.onSuccess { d -> details.value = details.value + (item.id to d) }
                .onFailure { Log.w("IptvVod", "Detail failed for ${item.name}", it) }
        }
    }

    /** The item's own playlist (another one's, from Continue watching or Favourites). */
    private suspend fun providerOf(item: VodItem): ProviderEntity? =
        provider.value?.takeIf { it.id == item.providerId } ?: c.db.providerDao().get(item.providerId)

    fun toggleFavourite(item: VodItem) {
        if (item.isAddon) return // no playlist to keep it under; the detail page hides the button
        viewModelScope.launch {
            if (favourites.value.any { it.id == item.id }) c.db.favouriteDao().remove(item.providerId, item.kind, item.id)
            else c.db.favouriteDao().add(FavouriteEntity(item.providerId, item.kind, item.id, System.currentTimeMillis()))
        }
    }

    /** This movie's progress row, or one of this series' episodes ([episode] = its remoteId). */
    fun progressOf(item: VodItem, episode: String = ""): ProgressEntity? =
        progress.value.find { it.kind == item.kind && it.itemId == item.id && it.episode == episode }

    /** Mark watched (Play then starts over) or unwatched (forgets the position too). */
    fun setWatched(item: VodItem, watched: Boolean, episode: String = "") {
        if (item.isAddon) return
        viewModelScope.launch {
            val row = ProgressEntity(item.providerId, item.kind, item.id, 0, progressOf(item, episode)?.durationMs ?: 0,
                System.currentTimeMillis(), completed = watched, episode = episode)
            if (watched) c.db.progressDao().upsert(row) else c.db.progressDao().delete(item.providerId, item.kind, item.id, episode)
            c.providerRepository.reportProgress(row) // unwatched: position 0, not completed = the server forgets it too
        }
    }

    // shortcut: learned heights live in a small prefs file, not Room; move them to the DB if the list grows large.
    private val heightPrefs = app.getSharedPreferences("vod_heights", android.content.Context.MODE_PRIVATE)
    private fun heightKey(item: VodItem) = "${item.providerId}:${item.kind}:${item.remoteId}"
    /** Heights read from streams as they played (B1), so the detail page can show them next time. */
    val learnedHeights = MutableStateFlow<Map<String, Int>>(emptyMap())
    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { // a file read: off the main thread
            learnedHeights.value = heightPrefs.all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap() + learnedHeights.value
        }
    }
    fun learnedHeight(item: VodItem): Int? = learnedHeights.value[heightKey(item)]
    private var learn: kotlinx.coroutines.Job? = null

    /**
     * Plays [url] from [startMs]; for [item] (the movie, or the series [episode] belongs to: its remoteId) remembers
     * the picture height once known, and where it's left (see WatchProgress).
     */
    fun play(url: String, item: VodItem? = null, episode: String = "", startMs: Long = 0, title: String = item?.name.orEmpty(), onStart: () -> Unit = {}) {
        viewModelScope.launch {
            // An add-on title has no playlist: nothing of the one in use (login, progress) applies to it.
            val p = if (item?.isAddon == true) null else item?.let { providerOf(it) } ?: provider.value
            // Parental controls: a locked category's titles need the PIN. shortcut: no VOD age ratings (list APIs carry none), add them if a provider field proves reliable.
            val cat = item?.categoryId
            if (cat != null) {
                val locked = c.settings.settings.first().let { s -> s.parentalOn && s.parentalLocked(c.db.categoryDao().get(cat)?.parentalLock == true, null) }
                if (!c.parental.allow(locked, "v${item.kind}${item.id}", Long.MAX_VALUE, ask = true)) return@launch
            }
            if (url.startsWith(org.imdtv.iptvtv.data.source.STREMIO_PLAY)) {
                // A Stremio account's titles ask its add-ons; an add-on title (or no Stremio playlist) the Addons page's.
                val src = (p?.let { source(it) } as? org.imdtv.iptvtv.data.source.StremioSource) ?: c.addonSource
                chooseStream(src, url, title, p, item, episode, startMs, onStart)
                return@launch
            }
            startPlay(p, url, item, episode, startMs)
            onStart()
        }
    }

    // --- Stremio add-on streams: every add-on asked at once, the list filling in as they answer ---

    /** The stream list on the detail page: [groups] in add-on order; [auto]: "Play the first stream" is on. */
    /** [asked]: the add-ons serving streams are known ([groups] empty then = none of them does). */
    data class StreamChoice(val title: String, val groups: List<org.imdtv.iptvtv.data.addons.StreamGroup>, val auto: Boolean, val asked: Boolean = false)
    val streamChoice = MutableStateFlow<StreamChoice?>(null)
    private var choiceJob: kotlinx.coroutines.Job? = null
    private var onChosen: ((org.imdtv.iptvtv.data.addons.AddonStream) -> Unit)? = null

    private suspend fun chooseStream(src: org.imdtv.iptvtv.data.source.StremioSource, url: String, title: String, p: ProviderEntity?, item: VodItem?,
                             episode: String, startMs: Long, onStart: () -> Unit) {
        cancelStreams()
        val auto = c.settings.settings.first().autoPlayFirstStream
        // Subtitles are asked for at the same time; by the time a stream is picked they've usually answered.
        val subs = viewModelScope.async { runCatching { src.subtitles(url) }.getOrDefault(emptyList()) }
        var started = false
        onChosen = { s ->
            val link = s.playUrl
            if (!started && link != null) {
                started = true
                choiceJob?.cancel(); streamChoice.value = null; onChosen = null
                viewModelScope.launch {
                    val extra = kotlinx.coroutines.withTimeoutOrNull(1_500) { subs.await() }.orEmpty()
                    val all = (s.subtitles + extra).distinctBy { it.url }.map { org.imdtv.iptvtv.player.ExternalSubtitle(it.url, it.lang) }
                    startPlay(p, link, item, episode, startMs, all)
                    onStart()
                }
            }
        }
        streamChoice.value = StreamChoice(title, emptyList(), auto)
        choiceJob = viewModelScope.launch {
            src.streams(url).collect { groups ->
                if (streamChoice.value == null) return@collect // closed meanwhile
                streamChoice.value = StreamChoice(title, groups, auto, asked = true)
                if (auto) (org.imdtv.iptvtv.data.addons.autoPick(groups) as? org.imdtv.iptvtv.data.addons.AutoPick.Play)?.let { onChosen?.invoke(it.stream) }
            }
        }
    }

    /** OK on a stream in the list. */
    fun pickStream(s: org.imdtv.iptvtv.data.addons.AddonStream) { onChosen?.invoke(s) }

    /** Back on the list (or leaving the page): stops asking the add-ons. */
    fun cancelStreams() { choiceJob?.cancel(); choiceJob = null; streamChoice.value = null; onChosen = null }

    // --- Stremio add-on catalogs as home rows (Settings › Addons) ---

    /** A catalog row: "Cinemeta – Popular", in add-on order. */
    data class AddonRow(val key: String, val title: String, val addon: org.imdtv.iptvtv.data.source.StremioAddon, val catalog: org.imdtv.iptvtv.data.source.StremioCatalog)

    val addonRows: StateFlow<List<AddonRow>> = combine(c.addons.enabled, kind) { list, k ->
        list.flatMapIndexed { i, a -> a.catalogs.filter { it.kind == k }.map { AddonRow("$i/${it.key}", "${a.name} – ${it.name}", a, it) } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** One page of [row] from [skip]; the caller's scope cancels it when the row leaves the screen. Failures give an empty page. */
    suspend fun addonPage(row: AddonRow, skip: Int): List<VodItem> =
        try { c.addonClient.catalog(row.addon, row.catalog, skip).mapNotNull { addonItem(it, row.catalog.kind ?: kind.value) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Log.w("IptvVod", "${row.addon.name} – ${row.catalog.name}: ${e.message}"); emptyList() }

    private fun addonItem(m: kotlinx.serialization.json.JsonElement, k: Kind): VodItem? = c.addonSource.card(m)?.let { card ->
        VodItem(addonItemId(k, card.id), k, card.id, card.name, card.poster, card.year, card.rating, null,
            if (k == Kind.MOVIE) "${org.imdtv.iptvtv.data.source.STREMIO_PLAY}movie/${card.id}" else null, ADDONS_PROVIDER)
    }

    /** The search bar on Movies or Series (no category picked) also asks every add-on catalog that takes a search. */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val addonMatches: StateFlow<List<VodItem>?> = combine(query, kind, gridCategory) { q, k, g -> Triple(q, k, g) }
        .debounce(400).distinctUntilChanged()
        .flatMapLatest { (q, k, g) ->
            if (g != null || org.imdtv.iptvtv.data.search.queryWords(q).isEmpty()) flowOf<List<VodItem>?>(null)
            else kotlinx.coroutines.flow.flow<List<VodItem>?> {
                emit(emptyList()) // searching: an empty list (not null) while the add-ons answer
                emit(addonSearch(k, q.trim()))
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    /** Add-on searches running (a counter: a cancelled one finishing late mustn't clear the newer one's flag). */
    private val searchesRunning = java.util.concurrent.atomic.AtomicInteger()
    val addonSearching = MutableStateFlow(false)

    private suspend fun addonSearch(k: Kind, q: String): List<VodItem> = kotlinx.coroutines.coroutineScope {
        addonSearching.value = searchesRunning.incrementAndGet() > 0
        try {
            c.addons.enabledNow().flatMap { a -> a.searchCatalogs.filter { it.kind == k }.map { a to it } }.map { (a, cat) ->
                async {
                    kotlinx.coroutines.withTimeoutOrNull(ADDON_SEARCH_MS) {
                        try { c.addonClient.catalog(a, cat, search = q) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { Log.w("IptvVod", "${a.name} search failed: ${e.message}"); null }
                    }.orEmpty()
                }
            }.awaitAll().flatten().mapNotNull { addonItem(it, k) }.distinctBy { it.remoteId }
        } finally { addonSearching.value = searchesRunning.decrementAndGet() > 0 }
    }

    val parentalOn: StateFlow<Boolean> = c.settings.settings.map { it.parentalOn }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    /** Hold OK on a category in the rail › Enable / Disable Parental Lock; unlocking takes the PIN. */
    fun setParentalLock(cat: CategoryEntity, on: Boolean) {
        viewModelScope.launch { if (on || c.parental.askPin()) c.db.categoryDao().setParentalLock(cat.id, on) }
    }

    private fun startPlay(p: ProviderEntity?, url: String, item: VodItem?, episode: String, startMs: Long,
                          subtitles: List<org.imdtv.iptvtv.player.ExternalSubtitle> = emptyList()) {
        player.play(url, p?.userAgent, p?.login(), vod = true, startMs = startMs,
            parallel = p?.type == org.imdtv.iptvtv.data.db.ProviderType.PLEX || p?.type == org.imdtv.iptvtv.data.db.ProviderType.JELLYFIN,
            subtitles = subtitles)
        learn?.cancel()
        if (item == null) return
        p?.let { c.watchProgress.start(it.id, item.kind, item.id, episode, url) }
        val key = heightKey(item)
        learn = viewModelScope.launch {
            // Only while this stream is still the one playing: a live channel tuned after a failed start mustn't count.
            val h = player.formats.takeWhile { player.playingUrl.value == url }.firstOrNull { f -> f.height != null }?.height ?: return@launch
            if (learnedHeights.value[key] != h) { heightPrefs.edit().putInt(key, h).apply(); learnedHeights.value = learnedHeights.value + (key to h) }
        }
    }

    companion object {
        const val ROW_LIMIT = 30
        const val GRID_LIMIT = 600
        /** The Continue watching pseudo-category (not a row in the category table). */
        const val CONTINUE_ID = -2L
        /** The provider id of Stremio add-on titles (Settings › Addons): no playlist row, never the one in use. */
        const val ADDONS_PROVIDER = -7L
        const val ADDON_SEARCH_MS = 10_000L

        /** A stable id for an add-on title, far below every database id and the pseudo ids above. */
        fun addonItemId(k: Kind, remoteId: String): Long = -(1L shl 33) - (("${k.name}|$remoteId").hashCode().toLong() and 0xffffffffL)
    }
}
