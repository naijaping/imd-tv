package org.imdtv.iptvtv.data.backup

import org.imdtv.iptvtv.data.security.Login
import org.imdtv.iptvtv.data.security.m3uBackupUrl
import org.imdtv.iptvtv.data.security.m3uLogin
import org.imdtv.iptvtv.data.security.withLogin

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import org.imdtv.iptvtv.data.db.AppDatabase
import org.imdtv.iptvtv.data.db.EpgSourceEntity
import org.imdtv.iptvtv.data.db.FavouriteEntity
import org.imdtv.iptvtv.data.db.ORIGIN_PLAYLIST
import org.imdtv.iptvtv.data.db.ProviderEntity
import org.imdtv.iptvtv.data.db.ProviderType
import org.imdtv.iptvtv.data.model.Kind
import org.imdtv.iptvtv.data.model.StreamFormat
import org.imdtv.iptvtv.data.repo.ProviderRepository
import org.imdtv.iptvtv.data.settings.SettingsRepository

/** [applied]/[skipped] count categories, group members and favourites; skipped = remoteId no longer in the playlist. */
data class RestoreResult(val applied: Int, val skipped: Int)

/**
 * [added]: new playlists' ids (they need a guide import); [failed]: playlists that couldn't be downloaded, with why;
 * [guideChanged]: something the guide matcher reads changed, so channels need rematching.
 */
data class MirrorResult(val added: List<Long>, val failed: List<String>, val guideChanged: Boolean)

class BackupRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val providers: ProviderRepository,
) {
    suspend fun create(): Backup = Backup(
        // shortcut: media servers (Plex, Jellyfin) aren't in file backups (a backup holds no logins, they have no form to ask for one); add them again after a restore.
        playlists = db.providerDao().all().filter { !it.type.mediaServer }.map { export(it) },
        settings = BSettings.of(settings.settings.first()),
    )

    private suspend fun export(p: ProviderEntity): BPlaylist {
        val own = providers.ownEpgUrls(p)
        val m3u = m3uLogin(p.url)
        val login = listOfNotNull(p.username, p.password, m3u?.username, m3u?.password).filter { it.isNotBlank() }
        val sources = db.epgDao().sources(p.id).filter { it.origin == ORIGIN_PLAYLIST }
            .filter { s -> s.url in own || !org.imdtv.iptvtv.data.security.urlHoldsAny(s.url, login) } // a hand-added URL with the login in it is left out
            .map { BEpgSource(it.url.takeUnless { u -> u in own }, it.name, it.priority, it.offsetMinutes) }
        val favourites = Kind.entries.flatMap { kind ->
            val ids = db.favouriteDao().list(p.id, kind).map { it.itemId } // newest first
            val remote: Map<Long, String> = when (kind) {
                Kind.LIVE -> ids.mapNotNull { db.channelDao().get(it) }.associate { it.id to it.remoteId }
                Kind.MOVIE -> db.movieDao().byIds(ids).associate { it.id to it.remoteId }
                Kind.SERIES -> db.seriesDao().byIds(ids).associate { it.id to it.remoteId }
            }
            ids.mapNotNull { remote[it] }.map { BFavourite(kind.name, it) }
        }
        val liveCats = db.categoryDao().allKinds(p.id).filter { it.kind == Kind.LIVE }.associate { it.id to it.remoteId }
        val chans = db.channelDao().byProvider(p.id)
        val remoteOf = chans.associate { it.id to it.remoteId }
        val channelNumbers = chans.mapNotNull { c -> c.customNumber?.let { BChannelNumber(c.remoteId, it) } }
        val channelOrder = chans.filter { it.sortOrder != null && it.categoryId != null }.groupBy { it.categoryId!! }
            .mapNotNull { (cid, chs) -> liveCats[cid]?.let { BChannelOrder(it, chs.sortedBy { c -> c.sortOrder }.map { c -> c.remoteId }) } }
        return BPlaylist(
            // Never a login in a backup: an M3U address carrying one keeps placeholders, filled in on restore.
            name = p.name, type = p.type.name, url = if (p.type == ProviderType.M3U) m3uBackupUrl(p.url) else p.url, userAgent = p.userAgent, streamFormat = p.streamFormat.name,
            nameStrip = p.nameStrip, epgSources = sources,
            categories = db.categoryDao().allKinds(p.id).map { BCategory(it.kind.name, it.remoteId, it.hidden, it.sortOrder, it.pinned, it.sortBy.name, it.parentalLock) },
            groups = db.customGroupDao().observe(p.id).first().map { BGroup(it.name, it.sortOrder, db.customGroupDao().memberRemoteIds(it.id), it.placement.name, it.sortBy.name) },
            favourites = favourites, categoryStrip = p.categoryStrip, channelOrder = channelOrder, contentOff = p.contentOff, enabled = p.enabled, channelNumbers = channelNumbers, lockedChannels = chans.filter { it.parentalLock }.map { it.remoteId },
            hiddenChannels = chans.filter { it.hidden }.map { it.remoteId },
            channelEdits = chans.filter { it.customName != null || it.epgOffsetMinutes != null }.map { BChannelEdit(it.remoteId, it.customName, it.epgOffsetMinutes) },
            merges = chans.filter { !it.mergedAuto }.mapNotNull { c -> c.mergedInto?.let(remoteOf::get)?.let { BMerge(c.remoteId, it) } }, mergeOn = p.mergeOn,
            progress = db.progressDao().all(p.id).let { rows ->
                val movies = rows.filter { it.kind == Kind.MOVIE }.map { it.itemId }.distinct().chunked(900).flatMap { db.movieDao().byIds(it) }.associate { it.id to it.remoteId }
                val series = rows.filter { it.kind == Kind.SERIES }.map { it.itemId }.distinct().chunked(900).flatMap { db.seriesDao().byIds(it) }.associate { it.id to it.remoteId }
                rows.mapNotNull { r -> (if (r.kind == Kind.MOVIE) movies else series)[r.itemId]?.let {
                    BProgress(r.kind.name, it, r.episode, r.positionMs, r.durationMs, r.updatedAt, r.completed) } }
            },
        )
    }

    /**
     * Finds the playlist by (type, url) or adds it, sets the login and name strip, replaces its own EPG sources,
     * syncs, then applies categories, groups and favourites by remoteId. Throws what the sync throws.
     */
    suspend fun restorePlaylist(b: BPlaylist, username: String?, password: String?, onProgress: (String) -> Unit): RestoreResult =
        put(b, username, password, mirror = false, onProgress).second

    /** The backup plus each playlist's login (M3U: the one in its address), for a sync follower. */
    suspend fun syncSnapshot(): org.imdtv.iptvtv.data.sync.SyncSnapshot {
        val all = db.providerDao().all()
        val active = providers.activeProviderId.first()
        return org.imdtv.iptvtv.data.sync.SyncSnapshot(
            Backup(playlists = all.map { export(it) }, settings = BSettings.of(settings.settings.first())),
            all.map { p ->
                if (p.type == ProviderType.M3U) m3uLogin(p.url)?.let { org.imdtv.iptvtv.data.sync.SLogin(it.username, it.password) }
                else p.username?.let { org.imdtv.iptvtv.data.sync.SLogin(it, p.password.orEmpty()) }
            },
            all.indexOfFirst { it.id == active }.takeIf { it >= 0 },
        )
    }

    /**
     * Sync follower: makes this box match [s]. Playlists already here keep their catalogue (no re-download); their
     * favourites, groups and channel order are replaced, not merged; playlists [s] lacks are removed. A playlist that
     * can't be downloaded here (e.g. a localhost address) is left out and named in [MirrorResult.failed]; the rest go on.
     */
    suspend fun mirror(s: org.imdtv.iptvtv.data.sync.SyncSnapshot, onProgress: (String) -> Unit): MirrorResult {
        val before = db.providerDao().ids().toSet()
        val failed = mutableListOf<String>()
        val guideBefore = guideInputs()
        val wasActive = providers.activeProviderId.first()
        val ids = s.backup.playlists.mapIndexedNotNull { i, b ->
            try { s.logins.getOrNull(i).let { i to put(b, it?.username, it?.password, mirror = true, onProgress).first } }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failed += "${b.name} (${e.message ?: e::class.simpleName})"; null
            }
        }.toMap()
        val keep = ids.values
        // The playlist in use here is going: switch to the master's (else remove() would pick the first by name).
        if (wasActive !in keep) s.active?.let(ids::get)?.let { providers.setActive(it) }
        db.providerDao().ids().filter { it !in keep }.forEach { providers.remove(it) }
        restoreSettings(s.backup.settings)
        return MirrorResult(keep.filter { it !in before }, failed, guideInputs() != guideBefore)
    }

    /** What EpgMatcher.rematch reads besides the catalogue: each playlist's guide sources and the guide settings. */
    private suspend fun guideInputs(): List<Any> = settings.settings.first().let { listOf(it.guideOrder, it.borrowSiblingGuide, it.guidesOff) } +
        db.providerDao().ids().map { id -> id to db.epgDao().sources(id).map { it.url to it.priority }.sortedBy { it.first } }

    private suspend fun put(b: BPlaylist, username: String?, password: String?, mirror: Boolean, onProgress: (String) -> Unit): Pair<Long, RestoreResult> {
        val type = ProviderType.entries.find { it.name == b.type } ?: throw BackupException("Unknown playlist type ${b.type}")
        val format = StreamFormat.entries.find { it.name == b.streamFormat } ?: StreamFormat.TS
        val existing = db.providerDao().all().find { it.type == type && (it.url == b.url || m3uBackupUrl(it.url) == b.url) }
        // M3U: the login goes back into the address (it never had separate fields); Xtream keeps it in its fields.
        val m3uLogin = if (type == ProviderType.M3U && username != null && password != null) Login(username, password) else null
        val url = m3uLogin?.let { withLogin(b.url, it) } ?: existing?.url ?: b.url
        val id = if (existing != null) {
            providers.update(existing.copy(name = b.name, url = url,
                username = if (m3uLogin != null) existing.username else username ?: existing.username,
                password = if (m3uLogin != null) existing.password else password ?: existing.password, userAgent = b.userAgent, streamFormat = format))
            existing.id
        } else {
            providers.add(ProviderEntity(name = b.name, type = type, url = url,
                username = username.takeIf { m3uLogin == null }, password = password.takeIf { m3uLogin == null },
                userAgent = b.userAgent, streamFormat = format, epgOverrideUrls = emptyList(), lastSync = null))
        }
        db.providerDao().setNameStrip(id, b.nameStrip)
        db.providerDao().setCategoryStrip(id, b.categoryStrip)
        db.providerDao().setContentOff(id, b.contentOff) // before the sync below, which follows it
        db.providerDao().setEnabled(id, b.enabled)
        restoreEpgSources(id, b.epgSources)
        if (!mirror || existing?.lastSync == null || existing.contentOff != b.contentOff) providers.syncNow(id, onProgress)
        return id to db.withTransaction {
            if (mirror) {
                db.favouriteDao().deleteForProvider(id)
                db.customGroupDao().deleteForProvider(id)
                db.channelDao().clearSortOrders(id)
                db.channelDao().clearUserEdits(id) // hidden, renamed, numbers, offsets, locks, hand merges: the master's replace them
            }
            applyUserData(id, b)
        }
    }

    /** Sources kept by URL keep their id (and imported guide); the rest are added or removed. epgshare01 ones are untouched. */
    private suspend fun restoreEpgSources(id: Long, wanted: List<BEpgSource>) {
        val own = providers.ownEpgUrls(db.providerDao().get(id)!!).iterator()
        val resolved = wanted.sortedBy { it.priority }.mapNotNull { s -> (s.url ?: own.takeIf { it.hasNext() }?.next())?.let { it to s } }
        val current = db.epgDao().sources(id).filter { it.origin == ORIGIN_PLAYLIST }
        current.filter { c -> resolved.none { it.first == c.url } }.forEach { providers.removeEpgSource(it.id) }
        resolved.forEach { (url, s) ->
            val c = current.find { it.url == url }
            if (c != null) db.epgDao().updateSource(c.copy(name = s.name, priority = s.priority, offsetMinutes = s.offsetMinutes))
            else db.epgDao().insertSource(EpgSourceEntity(providerId = id, url = url, priority = s.priority, offsetMinutes = s.offsetMinutes, name = s.name))
        }
    }

    private suspend fun applyUserData(id: Long, b: BPlaylist): RestoreResult {
        var applied = 0
        var skipped = 0
        val cats = db.categoryDao().allKinds(id).associateBy { it.kind.name to it.remoteId }
        db.categoryDao().update(b.categories.mapNotNull { bc ->
            cats[bc.kind to bc.remoteId]?.copy(hidden = bc.hidden, sortOrder = bc.sortOrder, pinned = bc.pinned, parentalLock = bc.parentalLock,
                // A backup from before Sort by: a category with a saved channel order was manual.
                sortBy = org.imdtv.iptvtv.data.db.SortBy.entries.find { it.name == bc.sortBy }
                    ?: if (b.channelOrder.any { it.category == bc.remoteId } && bc.kind == Kind.LIVE.name) org.imdtv.iptvtv.data.db.SortBy.MANUAL else org.imdtv.iptvtv.data.db.SortBy.PLAYLIST)
        })
        b.categories.forEach { if (cats.containsKey(it.kind to it.remoteId)) applied++ else skipped++ }

        val channels = db.channelDao().remoteIds(id).associate { it.remoteId to it.id }
        // Movies and series loaded at most once, and only when a favourite or progress row needs them.
        var movieIds: Map<String, Long>? = null
        var seriesIds: Map<String, Long>? = null
        suspend fun movies() = movieIds ?: db.movieDao().remoteIds(id).associate { it.remoteId to it.id }.also { movieIds = it }
        suspend fun series() = seriesIds ?: db.seriesDao().remoteIds(id).associate { it.remoteId to it.id }.also { seriesIds = it }
        b.channelOrder.forEach { o ->
            if (cats[Kind.LIVE.name to o.category] == null) { skipped += o.channels.size; return@forEach }
            o.channels.forEachIndexed { i, r -> channels[r]?.let { db.channelDao().setSortOrder(it, i); applied++ } ?: skipped++ }
        }
        b.channelNumbers.forEach { n -> channels[n.channel]?.let { db.channelDao().setCustomNumber(it, n.number); applied++ } ?: skipped++ }
        b.lockedChannels.forEach { r -> channels[r]?.let { db.channelDao().setParentalLock(it, true); applied++ } ?: skipped++ }
        channels.let { m -> b.hiddenChannels.mapNotNull(m::get) }.chunked(900).forEach { db.channelDao().setHiddenForIds(it, true) }
        b.hiddenChannels.forEach { if (it in channels) applied++ else skipped++ }
        b.channelEdits.forEach { e -> channels[e.channel]?.let { cid ->
            e.name?.let { db.channelDao().setCustomName(cid, it) }; e.offsetMinutes?.let { db.channelDao().setEpgOffset(cid, it) }; applied++ } ?: skipped++ }
        b.merges.groupBy { it.head }.forEach { (head, ms) ->
            val into = channels[head]
            val members = ms.mapNotNull { channels[it.member] }
            if (into == null) skipped += ms.size else { db.channelDao().setMergedInto(members, into, false); applied += members.size; skipped += ms.size - members.size }
        }
        db.channelDao().refreshMergeHeads(id) // merges above, or hand merges a mirror cleared
        db.providerDao().setMergeOn(id, b.mergeOn)

        val groups = db.customGroupDao().observe(id).first().associateBy { it.name }
        b.groups.sortedBy { it.sortOrder }.forEach { g ->
            // Every member gone from the playlist: don't make an empty group (one saved empty is restored).
            if (g.channels.isNotEmpty() && g.channels.none { it in channels } && g.name !in groups) { skipped += g.channels.size; return@forEach }
            val gid = groups[g.name]?.id ?: db.customGroupDao().create(id, g.name)
            org.imdtv.iptvtv.data.db.GroupPlacement.entries.find { it.name == g.placement }?.let { db.customGroupDao().setPlacement(gid, it) }
            // From before Sort by: your categories were always in their own order.
            db.customGroupDao().setSortBy(gid, org.imdtv.iptvtv.data.db.SortBy.entries.find { it.name == g.sortBy } ?: org.imdtv.iptvtv.data.db.SortBy.MANUAL)
            g.channels.forEach { r -> channels[r]?.let { db.customGroupDao().addChannel(gid, it); applied++ } ?: skipped++ }
        }

        val byKind = b.favourites.groupBy { it.kind }
        val now = System.currentTimeMillis()
        var n = 0
        byKind.forEach { (kindName, favs) ->
            val kind = Kind.entries.find { it.name == kindName }
            val ids: Map<String, Long> = when (kind) {
                Kind.LIVE -> channels
                Kind.MOVIE -> movies()
                Kind.SERIES -> series()
                null -> emptyMap()
            }
            favs.forEach { f ->
                val item = ids[f.remoteId]
                if (kind != null && item != null) { db.favouriteDao().add(FavouriteEntity(id, kind, item, now - n++)); applied++ } else skipped++
            }
        }

        if (b.progress.isNotEmpty()) {
            b.progress.forEach { bp ->
                val kind = Kind.entries.find { it.name == bp.kind }
                val item = when (kind) { Kind.MOVIE -> movies()[bp.remoteId]; Kind.SERIES -> series()[bp.remoteId]; else -> null }
                if (kind != null && item != null) {
                    db.progressDao().upsert(org.imdtv.iptvtv.data.db.ProgressEntity(id, kind, item, bp.positionMs, bp.durationMs, bp.updatedAt, bp.completed, bp.episode))
                    applied++
                } else skipped++
            }
        }
        return RestoreResult(applied, skipped)
    }

    suspend fun restoreSettings(s: BSettings) {
        settings.update { s.toAppSettings(it.lastLiveChannelId).copy(backupFolder = it.backupFolder, recordUsbPath = it.recordUsbPath, smbPassword = it.smbPassword) }
        // Merging follows the restored settings; a mirror skips the playlist sync that would have run it (#22).
        val m = settings.settings.first()
        val opts = org.imdtv.iptvtv.data.repo.MergeOptions(m.mergeChannels, m.mergeAcross, m.mergeBy == org.imdtv.iptvtv.data.settings.MergeBy.RESOLUTION)
        db.providerDao().ids().forEach { org.imdtv.iptvtv.data.repo.ChannelMerge.applyAuto(db, it, opts) }
    }
}
