package org.imdtv.iptvtv.data.repo

import org.imdtv.iptvtv.data.model.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.imdtv.iptvtv.data.db.login
import org.imdtv.iptvtv.data.security.PASS_TOKEN
import org.imdtv.iptvtv.data.security.USER_TOKEN
import org.imdtv.iptvtv.data.security.withoutLogin

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import org.imdtv.iptvtv.data.db.AppDatabase
import org.imdtv.iptvtv.data.db.EpgSourceEntity
import org.imdtv.iptvtv.data.db.NameRuleEntity
import org.imdtv.iptvtv.data.db.NameRuleType
import org.imdtv.iptvtv.data.db.ProviderEntity
import org.imdtv.iptvtv.data.epg.BuiltinRule
import org.imdtv.iptvtv.data.source.PlaylistSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

class ProviderRepository(
    private val db: AppDatabase,
    private val sync: CatalogueSync,
    private val sourceFor: (ProviderEntity) -> PlaylistSource,
    private val prefs: DataStore<Preferences>,
    private val now: () -> Long = System::currentTimeMillis,
    /** Extra work after each catalogue sync (tv-logos matching); a failure here never fails the sync. */
    private val afterSync: suspend (Long) -> Unit = {},
) {
    private val activeKey = longPreferencesKey("active_provider_id")

    /**
     * A stored id whose playlist is gone (a crash between delete and the switch) reads as the first playlist left.
     * A media server is active only when there is no IPTV playlist: 0.7.0's Movies rail switched to Plex, which emptied
     * Live TV and moved the open-source guide sync onto it; this also repairs boxes left that way.
     */
    val activeProviderId: Flow<Long?> = prefs.data.map { it[activeKey] }.distinctUntilChanged().map { id ->
        val ids = db.providerDao().liveIds().ifEmpty { db.providerDao().ids() }
        id?.takeIf { it in ids } ?: ids.firstOrNull()
    }

    /** [active], then the other playlists switched on with [k] loaded, by name: what All and Search cover. */
    suspend fun inUse(active: Long, k: Kind): List<Long> =
        listOf(active) + db.providerDao().all().filter { it.id != active && it.enabled && it.loads(k) }.sortedBy { it.name }.map { it.id }

    private val resumeChecked = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /**
     * Copies what a media server says is part-watched into Continue watching, so something started in Plex or Jellyfin
     * shows here. A row of ours that's newer wins. At most every [minGapMs] per playlist; failures only log.
     */
    // shortcut: one way only, the server never hears about what's watched here; report progress back if people ask.
    suspend fun importResume(providerId: Long, minGapMs: Long = 5 * 60_000L) {
        val now = now()
        if (now - (resumeChecked[providerId] ?: 0) < minGapMs) return
        resumeChecked[providerId] = now
        val p = db.providerDao().get(providerId) ?: return
        val points = try { sourceFor(p).resumePoints() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w("ProviderRepository", "resume points failed: $e"); return
        }
        if (points.isEmpty()) return
        fun ids(rows: List<org.imdtv.iptvtv.data.db.RemoteRow>) = rows.associate { it.remoteId to it.id }
        val movies = ids(db.movieDao().idsFor(providerId, points.filter { it.kind == Kind.MOVIE }.map { it.remoteId }.distinct()))
        val series = ids(db.seriesDao().idsFor(providerId, points.filter { it.kind == Kind.SERIES }.map { it.remoteId }.distinct()))
        val mine = db.progressDao().all(providerId).associateBy { Triple(it.kind, it.itemId, it.episode) }
        points.forEach { r ->
            val id = (if (r.kind == Kind.MOVIE) movies else series)[r.remoteId] ?: return@forEach
            if ((mine[Triple(r.kind, id, r.episode)]?.updatedAt ?: Long.MIN_VALUE) >= r.at) return@forEach
            db.progressDao().upsert(org.imdtv.iptvtv.data.db.ProgressEntity(providerId, r.kind, id, r.positionMs, r.durationMs, r.at, false, r.episode))
        }
    }

    /** Tells a media server where [row]'s title was left (Continue watching both ways); playlists and failures only log. */
    suspend fun reportProgress(row: org.imdtv.iptvtv.data.db.ProgressEntity) {
        val p = db.providerDao().get(row.providerId)?.takeIf { it.type.mediaServer } ?: return
        val (remoteId, title, poster) = if (row.kind == Kind.MOVIE) db.movieDao().byIds(listOf(row.itemId)).firstOrNull()?.let { Triple(it.remoteId, it.name, it.poster) } ?: return
            else db.seriesDao().byIds(listOf(row.itemId)).firstOrNull()?.let { Triple(it.remoteId, it.name, it.poster) } ?: return
        try {
            sourceFor(p).reportProgress(org.imdtv.iptvtv.data.source.ProgressReport(row.kind, remoteId, row.episode, row.positionMs, row.durationMs, row.completed, title, poster))
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w("ProviderRepository", "progress report failed: $e") }
    }

    suspend fun setActive(id: Long) {
        prefs.edit { it[activeKey] = id }
    }

    /**
     * Deletes the provider and the rows that have no FK to it (programmes, favourites, progress);
     * the cascade takes the rest. If it was active, the first remaining provider becomes active.
     */
    /** Playlists being deleted (Settings shows a spinner; a second press does nothing). */
    val removing = kotlinx.coroutines.flow.MutableStateFlow<Set<Long>>(emptySet())

    suspend fun remove(id: Long) = withContext(Dispatchers.Default) {
        if (id in removing.value) return@withContext
        removing.value = removing.value + id
        try { lockFor(id).withLock { removeNow(id) } } finally { removing.value = removing.value - id } // after a running sync, not into it
    }

    private suspend fun removeNow(id: Long) {
        db.withTransaction {
            db.epgDao().sources(id).forEach { db.programmeDao().deleteForSource(it.id) }
            db.favouriteDao().deleteForProvider(id)
            db.progressDao().deleteForProvider(id)
            // Items before categories: the provider's cascade deleted categories first, which set every movie's and
            // channel's category to null (a write per row) just before deleting those rows anyway. Twice the work.
            db.channelDao().deleteForProvider(id)
            db.movieDao().deleteForProvider(id)
            db.seriesDao().deleteForProvider(id)
            db.providerDao().get(id)?.let { db.providerDao().delete(it) }
        }
        val next = db.providerDao().all().firstOrNull()?.id
        prefs.edit { if (it[activeKey] == id) { if (next != null) it[activeKey] = next else it.remove(activeKey) } }
    }

    /**
     * Saves edited URL/login/user agent; the catalogue is kept until the next sync. EPG sources the provider
     * itself supplied (Xtream's xmltv.php carries the login) move to the new URLs, keeping id, name and offset.
     */
    suspend fun update(p: ProviderEntity) = db.withTransaction {
        val old = db.providerDao().get(p.id) ?: return@withTransaction
        db.providerDao().update(p)
        if (p.epgOverrideUrls.isNotEmpty()) return@withTransaction
        val moved = sourceFor(old).epgUrls().zip(sourceFor(p).epgUrls()).toMap()
        db.epgDao().sources(p.id).forEach { s -> moved[s.url]?.takeIf { it != s.url }?.let { db.epgDao().setUrl(s.id, it) } }
    }

    /**
     * Once, for playlists saved before encryption: their login comes out of saved stream and guide links (placeholders,
     * filled in at play/download time) and the URL, username and password are re-saved encrypted.
     */
    suspend fun sealOldRows() = db.withTransaction {
        val dao = db.providerDao()
        dao.unsealed().forEach { p ->
            p.login()?.let { login ->
                val from = "/" + segment(login.username) + "/" + segment(login.password) + "/"
                val to = "/$USER_TOKEN/$PASS_TOKEN/"
                dao.replaceInChannelUrls(p.id, from, to); dao.replaceInMovieUrls(p.id, from, to); dao.replaceInEpisodeUrls(p.id, from, to)
                db.epgDao().sources(p.id).forEach { s -> withoutLogin(s.url, login).takeIf { it != s.url }?.let { db.epgDao().setUrl(s.id, it) } }
            }
            dao.update(p)
        }
    }

    /** [s] percent-encoded as a URL path segment, the way the saved links were built. */
    private fun segment(s: String) = "http://x/".toHttpUrl().newBuilder().addPathSegment(s).build().encodedPath.removePrefix("/")

    suspend fun addEpgSource(providerId: Long, url: String, name: String?) {
        val priority = (db.epgDao().sources(providerId).maxOfOrNull { it.priority } ?: -1) + 1
        db.epgDao().insertSource(EpgSourceEntity(providerId = providerId, url = url, priority = priority, name = name))
    }

    /** XMLTV channels go by cascade; programmes and links have no FK to the source. */
    suspend fun removeEpgSource(id: Long) = db.withTransaction {
        db.programmeDao().deleteForSource(id)
        db.epgDao().deleteLinksForSource(id)
        db.epgDao().deleteSource(id)
    }

    /** The guide URLs the playlist itself supplies (Xtream's carry the login). */
    fun ownEpgUrls(p: ProviderEntity): List<String> = sourceFor(p).epgUrls()

    /** Guide URLs that count as the playlist's (Settings › TV guide priority "Playlist"): its own and any typed with it. */
    suspend fun playlistGuideUrls(providerId: Long): Set<String>? =
        db.providerDao().get(providerId)?.let { p -> (p.epgOverrideUrls + ownEpgUrls(p)).toSet() }

    fun observeAll(): Flow<List<ProviderEntity>> = db.providerDao().observeAll()

    /** Inserts the provider with its EPG sources and default name rules; becomes active if none is. */
    suspend fun add(p: ProviderEntity): Long {
        val epgUrls = p.epgOverrideUrls.ifEmpty { sourceFor(p).epgUrls() }
        val id = db.withTransaction {
            val id = db.providerDao().insert(p.copy(id = 0))
            epgUrls.forEachIndexed { i, url ->
                db.epgDao().insertSource(EpgSourceEntity(providerId = id, url = url, priority = i, offsetMinutes = 0))
            }
            db.ruleDao().insertNameRules(BuiltinRule.entries.mapIndexed { i, r ->
                NameRuleEntity(providerId = id, order = i, enabled = true, type = NameRuleType.BUILTIN, pattern = r.name)
            })
            id
        }
        if (prefs.data.first()[activeKey] == null) setActive(id)
        return id
    }

    suspend fun syncIfStale(providerId: Long, maxAge: Duration = 24.hours): SyncResult? {
        val p = db.providerDao().get(providerId) ?: return null
        val last = p.lastSync
        return if (last == null || now() - last >= maxAge.inWholeMilliseconds) syncNow(providerId) else null
    }

    /** Switches loading [kind] off for [providerId] and removes what it had, at once (a full sync would fetch the rest first). */
    suspend fun stopLoading(providerId: Long, kind: org.imdtv.iptvtv.data.model.Kind, contentOff: String) = withContext(Dispatchers.Default) {
        db.providerDao().setContentOff(providerId, contentOff)
        // A running sync re-reads the switches and removes this kind itself; clearing alongside it raced its reads (crash).
        val lock = lockFor(providerId)
        if (!lock.tryLock()) return@withContext
        try { sync.clear(providerId, kind) } finally { lock.unlock() }
        runAfterSync(providerId)
    }

    /** One catalogue job (sync or removal) per playlist at a time: two at once read rows the other is deleting. */
    private val locks = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.sync.Mutex>()
    private fun lockFor(providerId: Long) = locks.getOrPut(providerId) { kotlinx.coroutines.sync.Mutex() }

    /** Per playlist, the kind a running sync is on (kinds sync in [Kind] order); absent when none runs. */
    val syncing = kotlinx.coroutines.flow.MutableStateFlow<Map<Long, org.imdtv.iptvtv.data.model.Kind>>(emptyMap())

    // Off the caller's thread: Settings calls from Main, and mapping/diffing 185k movies there froze the app (ANR).
    suspend fun syncNow(providerId: Long, onProgress: (String) -> Unit = {}): SyncResult = withContext(Dispatchers.Default) {
        val p = db.providerDao().get(providerId) ?: throw NoSuchElementException("No provider $providerId")
        val result = lockFor(providerId).withLock { try {
            // Each kind reads the switch as saved now: it may change while the sync runs.
            // A day's overlap: the TV's and the server's clocks may differ.
            sync.sync(providerId, sourceFor(p), { k -> db.providerDao().get(providerId)?.loads(k) ?: false }, onProgress, { k ->
                syncing.value = syncing.value + (providerId to k)
            }, since = p.lastSync?.minus(24 * 3600_000L))
        } finally {
            syncing.value = syncing.value - providerId
        } }
        db.providerDao().setLastSync(providerId, now())
        importResume(providerId, minGapMs = 0)
        runAfterSync(providerId)
        result
    }

    private suspend fun runAfterSync(providerId: Long) {
        try {
            afterSync(providerId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ProviderRepository", "after-sync work failed: $e")
        }
    }
}
