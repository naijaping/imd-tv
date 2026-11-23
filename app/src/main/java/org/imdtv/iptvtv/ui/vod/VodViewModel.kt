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
        else c.search.inCategory(p.id, k, cat.takeIf { it != CONTINUE_ID }, q)
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
                gridCategory.value?.let { g -> if (list.none { it.category.id == g.id }) gridCategory.value = null }
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
            val p = providerOf(item) ?: return@launch
            // M3U has no movie details, and asking would download and hold the whole playlist; Stalker has no detail call.
            if (item.kind == Kind.MOVIE && !p.type.xtream && !p.type.mediaServer) return@launch
            runCatching {
                if (item.kind == Kind.MOVIE) source(p).movieDetail(item.remoteId).let {
                    VodDetail(it.backdrop, it.plot, it.genre, it.director, it.cast, it.durationSecs, height = it.height)
                } else source(p).seriesDetail(item.remoteId).let {
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
    fun play(url: String, item: VodItem? = null, episode: String = "", startMs: Long = 0) {
        viewModelScope.launch {
            val p = item?.let { providerOf(it) } ?: provider.value
            // Parental controls: a locked category's titles need the PIN. shortcut: no VOD age ratings (list APIs carry none), add them if a provider field proves reliable.
            val cat = item?.categoryId
            if (cat != null) {
                val locked = c.settings.settings.first().let { s -> s.parentalOn && s.parentalLocked(c.db.categoryDao().get(cat)?.parentalLock == true, null) }
                if (!c.parental.allow(locked, "v${item.kind}${item.id}", Long.MAX_VALUE, ask = true)) return@launch
            }
            val real = if (url.startsWith(org.imdtv.iptvtv.data.source.STREMIO_PLAY) && p != null) {
                runCatching { (source(p) as? org.imdtv.iptvtv.data.source.StremioSource)?.stream(url) }.getOrNull()
                    ?: return@launch run { android.widget.Toast.makeText(app, R.string.vod_stremio_no_stream, android.widget.Toast.LENGTH_LONG).show() }
            } else url
            startPlay(p, real, item, episode, startMs)
        }
    }

    val parentalOn: StateFlow<Boolean> = c.settings.settings.map { it.parentalOn }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    /** Hold OK on a category in the rail › Enable / Disable Parental Lock; unlocking takes the PIN. */
    fun setParentalLock(cat: CategoryEntity, on: Boolean) {
        viewModelScope.launch { if (on || c.parental.askPin()) c.db.categoryDao().setParentalLock(cat.id, on) }
    }

    private fun startPlay(p: ProviderEntity?, url: String, item: VodItem?, episode: String, startMs: Long) {
        player.play(url, p?.userAgent, p?.login(), vod = true, startMs = startMs)
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
    }
}
