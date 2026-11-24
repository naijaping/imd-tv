package org.imdtv.iptvtv.data.db

import org.imdtv.iptvtv.data.security.Secrets
import kotlinx.coroutines.flow.map

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.imdtv.iptvtv.data.model.Kind

/** SQLite on older Android caps bound variables at 999; chunk id lists below that. */
internal const val SQL_CHUNK = 900

data class CategoryCount(val categoryId: Long?, val n: Int)
data class GroupCount(val groupId: Long, val n: Int)
data class RemoteRow(val id: Long, val remoteId: String)
/** A stored title's remote id and its category's remote id (a media-server library). */
data class CategoryItem(val cat: String, val remoteId: String)

@Dao
abstract class ProviderDao {
    // URL, username and password are stored encrypted (Secrets); everything outside this DAO sees plain values.
    @Insert protected abstract suspend fun insertRaw(p: ProviderEntity): Long
    @Update protected abstract suspend fun updateRaw(p: ProviderEntity)
    @Query("SELECT * FROM provider WHERE id = :id") protected abstract suspend fun getRaw(id: Long): ProviderEntity?
    @Query("SELECT * FROM provider WHERE id = :id") protected abstract fun observeRaw(id: Long): Flow<ProviderEntity?>
    @Query("SELECT * FROM provider ORDER BY name") protected abstract fun observeAllRaw(): Flow<List<ProviderEntity>>
    @Query("SELECT * FROM provider ORDER BY name") protected abstract suspend fun allRaw(): List<ProviderEntity>

    suspend fun insert(p: ProviderEntity): Long = insertRaw(p.sealed())
    suspend fun update(p: ProviderEntity) = updateRaw(p.sealed())
    @Delete abstract suspend fun delete(p: ProviderEntity)
    suspend fun get(id: Long): ProviderEntity? = getRaw(id)?.opened()
    fun observe(id: Long): Flow<ProviderEntity?> = observeRaw(id).map { it?.opened() }
    fun observeAll(): Flow<List<ProviderEntity>> = observeAllRaw().map { l -> l.map { it.opened() } }
    suspend fun all(): List<ProviderEntity> = allRaw().map { it.opened() }
    @Query("SELECT id FROM provider ORDER BY name") abstract suspend fun ids(): List<Long>
    /** Playlists that can have live TV (not Plex, Jellyfin or Stremio), by name. */
    @Query("SELECT id FROM provider WHERE type NOT IN ('PLEX', 'JELLYFIN', 'STREMIO') ORDER BY name") abstract suspend fun liveIds(): List<Long>
    /** Rows whose login is still plain text (saved before encryption), as stored. */
    suspend fun unsealed(): List<ProviderEntity> = allRaw().filter { !Secrets.isEncrypted(it.url) }
    @Query("UPDATE provider SET nameStrip = :s WHERE id = :id") abstract suspend fun setNameStrip(id: Long, s: String)
    @Query("UPDATE provider SET mergeOn = :on WHERE id = :id") abstract suspend fun setMergeOn(id: Long, on: Boolean)
    @Query("UPDATE provider SET categoryStrip = :s WHERE id = :id") abstract suspend fun setCategoryStrip(id: Long, s: String)
    @Query("UPDATE provider SET contentOff = :s WHERE id = :id") abstract suspend fun setContentOff(id: Long, s: String)
    @Query("UPDATE provider SET enabled = :on WHERE id = :id") abstract suspend fun setEnabled(id: Long, on: Boolean)
    @Query("UPDATE provider SET lastSync = :t WHERE id = :id") abstract suspend fun setLastSync(id: Long, t: Long)
    @Query("UPDATE provider SET maxConnections = :n WHERE id = :id") abstract suspend fun setMaxConnections(id: Long, n: Int?)

    // One-off move of old saved links to login placeholders (ProviderRepository.sealOldRows): [from] = "/user/pass/" as encoded in the URL.
    @Query("UPDATE channel SET streamUrl = replace(streamUrl, :from, :to) WHERE providerId = :pid") abstract suspend fun replaceInChannelUrls(pid: Long, from: String, to: String)
    @Query("UPDATE movie SET streamUrl = replace(streamUrl, :from, :to) WHERE providerId = :pid") abstract suspend fun replaceInMovieUrls(pid: Long, from: String, to: String)
    @Query("UPDATE episode SET streamUrl = replace(streamUrl, :from, :to) WHERE seriesId IN (SELECT id FROM series WHERE providerId = :pid)")
    abstract suspend fun replaceInEpisodeUrls(pid: Long, from: String, to: String)
}


private fun ProviderEntity.sealed() = copy(url = Secrets.encrypt(url)!!, username = Secrets.encrypt(username), password = Secrets.encrypt(password))
private fun ProviderEntity.opened() = copy(url = Secrets.decrypt(url).orEmpty(), username = Secrets.decrypt(username), password = Secrets.decrypt(password))

@Dao
abstract class CategoryDao {
    @Query("SELECT * FROM category WHERE providerId = :providerId AND kind = :kind AND hidden = 0 ORDER BY sortOrder, name")
    abstract fun observeVisible(providerId: Long, kind: Kind): Flow<List<CategoryEntity>>

    /** All categories of a kind for a provider (used by sync to match on remoteId). */
    @Query("SELECT * FROM category WHERE providerId = :providerId AND kind = :kind")
    abstract suspend fun byRemoteIds(providerId: Long, kind: Kind): List<CategoryEntity>

    @Query("SELECT * FROM category WHERE id = :id") abstract suspend fun get(id: Long): CategoryEntity?
    @Query("UPDATE category SET parentalLock = :on WHERE id = :id") abstract suspend fun setParentalLock(id: Long, on: Boolean)
    @Upsert abstract suspend fun upsert(list: List<CategoryEntity>)
    @Update abstract suspend fun update(c: CategoryEntity)
    @Update abstract suspend fun update(list: List<CategoryEntity>)

    /** Hidden ones included, for the settings pages. */
    @Query("SELECT * FROM category WHERE providerId = :providerId AND kind = :kind ORDER BY sortOrder, name")
    abstract fun observeAll(providerId: Long, kind: Kind): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM category WHERE providerId = :providerId AND kind = :kind ORDER BY sortOrder, name")
    abstract suspend fun all(providerId: Long, kind: Kind): List<CategoryEntity>
    @Query("SELECT * FROM category WHERE providerId = :providerId") abstract fun observeAllKinds(providerId: Long): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM category WHERE providerId = :providerId") abstract suspend fun allKinds(providerId: Long): List<CategoryEntity>

    @Query("UPDATE category SET hidden = :hidden WHERE id = :id") abstract suspend fun setHidden(id: Long, hidden: Boolean)
    @Query("UPDATE category SET sortBy = :by WHERE id = :id") abstract suspend fun setSortBy(id: Long, by: SortBy)
    @Query("SELECT sortBy FROM category WHERE id = :id") abstract fun observeSortBy(id: Long): Flow<SortBy?>
    @Query("UPDATE category SET hidden = :hidden WHERE providerId = :providerId AND kind = :kind")
    abstract suspend fun setHiddenForKind(providerId: Long, kind: Kind, hidden: Boolean)
    @Query("UPDATE category SET hidden = :hidden WHERE providerId = :providerId AND country IN (:countries)")
    abstract suspend fun setHiddenForCountries(providerId: Long, countries: List<String>, hidden: Boolean)
    @Query("UPDATE category SET hidden = :hidden WHERE providerId = :providerId AND country IS NULL")
    abstract suspend fun setHiddenForNoCountry(providerId: Long, hidden: Boolean)
    @Query("SELECT DISTINCT country FROM category WHERE providerId = :providerId")
    abstract suspend fun countries(providerId: Long): List<String?>

    @Query("DELETE FROM category WHERE id IN (:ids)")
    protected abstract suspend fun deleteIds(ids: List<Long>)

    @Transaction
    open suspend fun deleteMissing(providerId: Long, kind: Kind, keepRemoteIds: List<String>): List<Long> {
        val keep = keepRemoteIds.toHashSet()
        val gone = byRemoteIds(providerId, kind).filter { it.remoteId !in keep }.map { it.id }
        gone.chunked(SQL_CHUNK).forEach { deleteIds(it) }
        return gone
    }
}

data class MergeCount(val id: Long, val n: Int)

/** A name to search; [number] is the shown channel number (custom first). */
data class SearchRow(val id: Long, val name: String, val customName: String?, val number: Int?, val categoryId: Long?)

@Dao
abstract class ChannelDao {
    @Query(
        """SELECT * FROM channel WHERE categoryId = :categoryId AND hidden = 0 AND mergedInto IS NULL
           ORDER BY sortOrder IS NULL, sortOrder, coalesce(customNumber, number) IS NULL, coalesce(customNumber, number), coalesce(customName, name)"""
    )
    abstract fun observeByCategory(categoryId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT categoryId, count(*) AS n FROM channel WHERE providerId = :providerId AND hidden = 0 AND mergedInto IS NULL GROUP BY categoryId")
    abstract suspend fun counts(providerId: Long): List<CategoryCount>
    @Query("SELECT categoryId, count(*) AS n FROM channel WHERE providerId = :providerId AND hidden = 0 AND mergedInto IS NULL GROUP BY categoryId")
    abstract fun observeCounts(providerId: Long): Flow<List<CategoryCount>>

    @Query("SELECT * FROM channel WHERE providerId = :providerId")
    abstract suspend fun byProvider(providerId: Long): List<ChannelEntity>
    /** Only the two columns a remoteId → id lookup needs (a big catalogue's full rows are slow to load on TV boxes). */
    @Query("SELECT id, remoteId FROM channel WHERE providerId = :providerId") abstract suspend fun remoteIds(providerId: Long): List<RemoteRow>

    @Query("SELECT * FROM channel WHERE id = :id") abstract suspend fun get(id: Long): ChannelEntity?
    @Query("SELECT * FROM channel WHERE id IN (:ids)") abstract suspend fun byIds(ids: List<Long>): List<ChannelEntity>

    /** Search: visible channels in visible (or no) categories, slim rows. */
    @Query(
        """SELECT ch.id, ch.name, ch.customName, coalesce(ch.customNumber, ch.number) AS number, ch.categoryId FROM channel ch
           LEFT JOIN category c ON c.id = ch.categoryId WHERE ch.providerId = :providerId AND ch.hidden = 0 AND ch.mergedInto IS NULL AND coalesce(c.hidden, 0) = 0"""
    )
    abstract suspend fun searchable(providerId: Long): List<SearchRow>

    /** Visible channels whose displayed number is [n]; a custom number comes before a provider number. */
    @Query("""SELECT ch.* FROM channel ch LEFT JOIN category cat ON cat.id = ch.categoryId
              WHERE ch.providerId = :providerId AND ch.hidden = 0 AND ch.mergedInto IS NULL AND coalesce(cat.hidden, 0) = 0 AND coalesce(ch.customNumber, ch.number) = :n
              ORDER BY ch.customNumber IS NULL, ch.id""")
    abstract suspend fun byNumber(providerId: Long, n: Int): List<ChannelEntity>

    @Query("SELECT * FROM channel WHERE providerId = :providerId AND streamUrl = :url LIMIT 1")
    abstract suspend fun byStreamUrl(providerId: Long, url: String): ChannelEntity?

    @Upsert abstract suspend fun upsert(list: List<ChannelEntity>)
    /** Provider columns only, so a sync never writes back a stale copy of the user's own columns. */
    @Update(entity = ChannelEntity::class) abstract suspend fun updateFromProvider(list: List<ChannelFromProvider>)
    @Update abstract suspend fun update(c: ChannelEntity)
    @Query("UPDATE channel SET builtinLogo = :path WHERE id = :id") abstract suspend fun setBuiltinLogo(id: Long, path: String?)
    @Query("""SELECT ch.* FROM channel ch JOIN epg_link l ON l.channelId = ch.id LEFT JOIN category cat ON cat.id = ch.categoryId
              WHERE ch.providerId = :p AND ch.hidden = 0 AND ch.mergedInto IS NULL AND (cat.hidden IS NULL OR cat.hidden = 0)""")
    abstract suspend fun withGuide(p: Long): List<ChannelEntity>
    @Query("UPDATE channel SET customLogo = :logo WHERE id = :id") abstract suspend fun setCustomLogo(id: Long, logo: String?)
    @Query("SELECT customLogo FROM channel WHERE customLogo IS NOT NULL") abstract suspend fun customLogos(): List<String>
    @Query("UPDATE channel SET packLogo = :path WHERE id = :id") abstract suspend fun setPackLogo(id: Long, path: String?)
    @Query("UPDATE channel SET packLogo = NULL WHERE providerId = :p") abstract suspend fun clearPackLogos(p: Long)
    @Query("SELECT count(DISTINCT packLogo) FROM channel WHERE providerId = :p AND packLogo IS NOT NULL")
    abstract fun observePackCount(p: Long): Flow<Int>
    @Query(
        // One Favourites for every playlist in use with Live on (issue #12); [providerId] is the active one, always included.
        """SELECT ch.* FROM channel ch JOIN favourite f ON f.itemId = ch.id AND f.providerId = ch.providerId AND f.kind = 'LIVE'
           JOIN provider p ON p.id = ch.providerId
           WHERE (ch.providerId = :providerId OR p.enabled = 1 AND instr(',' || p.contentOff || ',', ',LIVE,') = 0)
             AND ch.hidden = 0 AND ch.mergedInto IS NULL ORDER BY f.sortOrder, f.addedAt DESC"""
    )
    abstract fun observeFavourites(providerId: Long): Flow<List<ChannelEntity>>
    /** The largest guide time offset set on one of [providerId]'s channels, in minutes (0 if none). */
    @Query("SELECT coalesce(MAX(ABS(epgOffsetMinutes)), 0) FROM channel WHERE providerId = :providerId")
    abstract suspend fun maxEpgOffset(providerId: Long): Int

    @Query("UPDATE channel SET hidden = :hidden WHERE id = :id") abstract suspend fun setHidden(id: Long, hidden: Boolean)
    @Query("UPDATE channel SET hidden = :hidden WHERE id IN (:ids)") abstract suspend fun setHiddenForIds(ids: List<Long>, hidden: Boolean)
    /** Every channel of a category, hidden ones included (Settings › Manage channels), in guide order. */
    @Query(
        """SELECT * FROM channel WHERE categoryId = :categoryId AND mergedInto IS NULL
           ORDER BY sortOrder IS NULL, sortOrder, coalesce(customNumber, number) IS NULL, coalesce(customNumber, number), coalesce(customName, name)"""
    )
    abstract fun observeAllByCategory(categoryId: Long): Flow<List<ChannelEntity>>
    @Query(
        """SELECT * FROM channel WHERE categoryId = :categoryId AND mergedInto IS NULL
           ORDER BY sortOrder IS NULL, sortOrder, coalesce(customNumber, number) IS NULL, coalesce(customNumber, number), coalesce(customName, name)"""
    )
    abstract suspend fun allByCategory(categoryId: Long): List<ChannelEntity>
    @Query("UPDATE channel SET sortOrder = :sortOrder WHERE id = :id") abstract suspend fun setSortOrder(id: Long, sortOrder: Int?)
    @Query("UPDATE channel SET sortOrder = NULL WHERE providerId = :p") abstract suspend fun clearSortOrders(p: Long)
    @Query("UPDATE channel SET customName = :name WHERE id = :id") abstract suspend fun setCustomName(id: Long, name: String?)
    @Query("UPDATE channel SET customNumber = :n WHERE id = :id") abstract suspend fun setCustomNumber(id: Long, n: Int?)
    @Query("UPDATE channel SET parentalLock = :on WHERE id = :id") abstract suspend fun setParentalLock(id: Long, on: Boolean)
    /** Other channels of the playlist already showing number [n]. */
    @Query("SELECT * FROM channel WHERE providerId = :p AND id != :id AND coalesce(customNumber, number) = :n")
    abstract suspend fun numberHolders(p: Long, id: Long, n: Int): List<ChannelEntity>
    @Query("SELECT max(coalesce(customNumber, number)) FROM channel WHERE providerId = :p") abstract suspend fun maxNumber(p: Long): Int?
    /** Where [numberHolders] of [n] go when [ch] takes [n]: [ch]'s old number, or past the playlist's last one if it had none. */
    suspend fun swapTarget(ch: ChannelEntity, n: Int): Int = (ch.customNumber ?: ch.number)?.takeIf { it != n } ?: ((maxNumber(ch.providerId) ?: 0) + 1)
    /** Gives [ch] number [n] (numbers stay unique within the playlist): channels holding it move to [swapTarget]. */
    @Transaction
    open suspend fun changeNumber(ch: ChannelEntity, n: Int) {
        val to = swapTarget(ch, n)
        // A custom number equal to the playlist's own is stored as none, so the channel follows the playlist again.
        numberHolders(ch.providerId, ch.id, n).forEach { setCustomNumber(it.id, to.takeIf { t -> t != it.number }) }
        setCustomNumber(ch.id, n.takeIf { it != ch.number })
    }
    @Query("UPDATE channel SET epgOffsetMinutes = :minutes WHERE id = :id") abstract suspend fun setEpgOffset(id: Long, minutes: Int?)

    /** Merge: channels played through [id] (its sources other than itself). */
    @Query("SELECT * FROM channel WHERE mergedInto = :id") abstract suspend fun mergedInto(id: Long): List<ChannelEntity>
    @Query("UPDATE channel SET mergedInto = :into, mergedAuto = :auto WHERE id IN (:ids)")
    abstract suspend fun setMergedInto(ids: List<Long>, into: Long?, auto: Boolean)
    @Query("UPDATE channel SET mergeHead = :head WHERE id IN (:ids)") abstract suspend fun setMergeHead(ids: List<Long>, head: Boolean)
    /** Heads flagged right again: a channel is a head exactly when another is merged into it. */
    @Query("""UPDATE channel SET mergeHead = NOT mergeHead WHERE providerId = :p
              AND mergeHead != (id IN (SELECT mergedInto FROM channel WHERE providerId = :p AND mergedInto IS NOT NULL))""")
    abstract suspend fun refreshMergeHeads(p: Long)
    @Query("UPDATE channel SET mergedInto = NULL, mergedAuto = 0 WHERE providerId = :p AND mergedAuto = 1") abstract suspend fun clearAutoMerges(p: Long): Int
    /** Sync follower: drops what the user set per channel, before the master's is applied. Only rows that have any. */
    @Query("""UPDATE channel SET hidden = 0, customName = NULL, customNumber = NULL, epgOffsetMinutes = NULL, parentalLock = 0,
              mergedInto = CASE WHEN mergedAuto = 1 THEN mergedInto ELSE NULL END
              WHERE providerId = :p AND (hidden = 1 OR customName IS NOT NULL OR customNumber IS NOT NULL OR epgOffsetMinutes IS NOT NULL
              OR parentalLock = 1 OR (mergedInto IS NOT NULL AND mergedAuto = 0))""")
    abstract suspend fun clearUserEdits(p: Long)
    /** Manage channels: how many channels are merged into each of a category's channels. */
    @Query("""SELECT mergedInto AS id, count(*) AS n FROM channel
              WHERE mergedInto IN (SELECT id FROM channel WHERE categoryId = :categoryId) GROUP BY mergedInto""")
    abstract fun observeMergeCounts(categoryId: Long): Flow<List<MergeCount>>
    /** Channels deleted by a sync: anything merged into them shows again. */
    @Query("UPDATE channel SET mergedInto = NULL, mergedAuto = 0 WHERE mergedInto IN (:ids)") abstract suspend fun unmergeFrom(ids: List<Long>)

    @Query("DELETE FROM channel WHERE providerId = :providerId") abstract suspend fun deleteForProvider(providerId: Long)
    @Query("DELETE FROM channel WHERE id IN (:ids)")
    abstract suspend fun deleteIds(ids: List<Long>)

    /** Deletes channels whose remoteId is gone; returns the deleted row ids. */
    @Transaction
    open suspend fun deleteMissing(providerId: Long, keepRemoteIds: List<String>): List<Long> {
        val keep = keepRemoteIds.toHashSet()
        val gone = byProvider(providerId).filter { it.remoteId !in keep }.map { it.id }
        gone.chunked(SQL_CHUNK).forEach { deleteIds(it) }
        return gone
    }
}

@Dao
abstract class MovieDao {
    @Update(entity = MovieEntity::class) abstract suspend fun updateFromProvider(list: List<MovieFromProvider>)
    @Query("SELECT * FROM movie WHERE providerId = :providerId")
    abstract suspend fun byProvider(providerId: Long): List<MovieEntity>
    /** Only the two columns a remoteId → id lookup needs (a big catalogue's full rows are slow to load on TV boxes). */
    @Query("SELECT id, remoteId FROM movie WHERE providerId = :providerId") abstract suspend fun remoteIds(providerId: Long): List<RemoteRow>
    @Query("SELECT id, remoteId FROM movie WHERE providerId = :providerId AND remoteId IN (:remoteIds)")
    abstract suspend fun idsFor(providerId: Long, remoteIds: List<String>): List<RemoteRow>
    @Query("SELECT c.remoteId AS cat, x.remoteId FROM movie x JOIN category c ON c.id = x.categoryId WHERE x.providerId = :providerId")
    abstract suspend fun byCategory(providerId: Long): List<CategoryItem>
    @Query("SELECT * FROM movie WHERE categoryId = :categoryId ORDER BY name")
    abstract fun observeByCategory(categoryId: Long): Flow<List<MovieEntity>>
    /** [sort]: a [org.imdtv.iptvtv.data.settings.VodSort] name; PLAYLIST = the provider's order (first seen, by id). */
    @Query("""SELECT * FROM movie WHERE categoryId = :categoryId
              ORDER BY CASE :sort WHEN 'AZ' THEN name END, CASE :sort WHEN 'ADDED' THEN addedAt END DESC, id LIMIT :limit""")
    abstract suspend fun inCategory(categoryId: Long, limit: Int, sort: String): List<MovieEntity>
    @Query("SELECT * FROM movie WHERE id IN (:ids)") abstract suspend fun byIds(ids: List<Long>): List<MovieEntity>
    @Query(
        """SELECT m.id, m.name, NULL AS customName, NULL AS number, m.categoryId FROM movie m
           LEFT JOIN category c ON c.id = m.categoryId WHERE m.providerId = :providerId AND coalesce(c.hidden, 0) = 0"""
    )
    abstract suspend fun searchable(providerId: Long): List<SearchRow>
    @Query("SELECT categoryId, count(*) AS n FROM movie WHERE providerId = :providerId GROUP BY categoryId")
    abstract suspend fun counts(providerId: Long): List<CategoryCount>
    @Query("SELECT categoryId, count(*) AS n FROM movie WHERE providerId = :providerId GROUP BY categoryId")
    abstract fun observeCounts(providerId: Long): Flow<List<CategoryCount>>
    @Upsert abstract suspend fun upsert(list: List<MovieEntity>)

    @Query("DELETE FROM movie WHERE providerId = :providerId") abstract suspend fun deleteForProvider(providerId: Long)
    @Query("DELETE FROM movie WHERE id IN (:ids)")
    abstract suspend fun deleteIds(ids: List<Long>)

    @Transaction
    open suspend fun deleteMissing(providerId: Long, keepRemoteIds: List<String>): List<Long> {
        val keep = keepRemoteIds.toHashSet()
        val gone = byProvider(providerId).filter { it.remoteId !in keep }.map { it.id }
        gone.chunked(SQL_CHUNK).forEach { deleteIds(it) }
        return gone
    }
}

@Dao
abstract class SeriesDao {
    @Update(entity = SeriesEntity::class) abstract suspend fun updateFromProvider(list: List<SeriesFromProvider>)
    @Query("SELECT * FROM series WHERE providerId = :providerId")
    abstract suspend fun byProvider(providerId: Long): List<SeriesEntity>
    /** Only the two columns a remoteId → id lookup needs (a big catalogue's full rows are slow to load on TV boxes). */
    @Query("SELECT id, remoteId FROM series WHERE providerId = :providerId") abstract suspend fun remoteIds(providerId: Long): List<RemoteRow>
    @Query("SELECT id, remoteId FROM series WHERE providerId = :providerId AND remoteId IN (:remoteIds)")
    abstract suspend fun idsFor(providerId: Long, remoteIds: List<String>): List<RemoteRow>
    @Query("SELECT c.remoteId AS cat, x.remoteId FROM series x JOIN category c ON c.id = x.categoryId WHERE x.providerId = :providerId")
    abstract suspend fun byCategory(providerId: Long): List<CategoryItem>
    @Query("SELECT * FROM series WHERE categoryId = :categoryId ORDER BY name")
    abstract fun observeByCategory(categoryId: Long): Flow<List<SeriesEntity>>
    @Query("""SELECT * FROM series WHERE categoryId = :categoryId
              ORDER BY CASE :sort WHEN 'AZ' THEN name END, CASE :sort WHEN 'ADDED' THEN addedAt END DESC, id LIMIT :limit""")
    abstract suspend fun inCategory(categoryId: Long, limit: Int, sort: String): List<SeriesEntity>
    @Query("SELECT * FROM series WHERE id IN (:ids)") abstract suspend fun byIds(ids: List<Long>): List<SeriesEntity>
    @Query(
        """SELECT s.id, s.name, NULL AS customName, NULL AS number, s.categoryId FROM series s
           LEFT JOIN category c ON c.id = s.categoryId WHERE s.providerId = :providerId AND coalesce(c.hidden, 0) = 0"""
    )
    abstract suspend fun searchable(providerId: Long): List<SearchRow>
    @Query("SELECT categoryId, count(*) AS n FROM series WHERE providerId = :providerId GROUP BY categoryId")
    abstract suspend fun counts(providerId: Long): List<CategoryCount>
    @Query("SELECT categoryId, count(*) AS n FROM series WHERE providerId = :providerId GROUP BY categoryId")
    abstract fun observeCounts(providerId: Long): Flow<List<CategoryCount>>
    @Upsert abstract suspend fun upsert(list: List<SeriesEntity>)

    @Query("SELECT * FROM episode WHERE seriesId = :seriesId ORDER BY season, number")
    abstract suspend fun episodes(seriesId: Long): List<EpisodeEntity>
    @Upsert abstract suspend fun upsertEpisodes(list: List<EpisodeEntity>)

    @Query("DELETE FROM series WHERE providerId = :providerId") abstract suspend fun deleteForProvider(providerId: Long)
    @Query("DELETE FROM series WHERE id IN (:ids)")
    abstract suspend fun deleteIds(ids: List<Long>)

    @Transaction
    open suspend fun deleteMissing(providerId: Long, keepRemoteIds: List<String>): List<Long> {
        val keep = keepRemoteIds.toHashSet()
        val gone = byProvider(providerId).filter { it.remoteId !in keep }.map { it.id }
        gone.chunked(SQL_CHUNK).forEach { deleteIds(it) }
        return gone
    }
}

@Dao
abstract class ProgrammeDao {
    @Query(
        """SELECT * FROM programme WHERE epgChannelKey IN (:keys) AND stop > :from AND start < :to
           AND gen = (SELECT gen FROM epg_source WHERE id = programme.sourceId)
           ORDER BY epgChannelKey, start"""
    )
    abstract suspend fun window(keys: List<String>, from: Long, to: Long): List<ProgrammeEntity>

    /** Search: programmes of [keys] still to end by [now] whose title is LIKE [pattern] (escape \), soonest first. */
    @Query(
        """SELECT * FROM programme WHERE epgChannelKey IN (:keys) AND stop > :now AND title LIKE :pattern ESCAPE '\'
           AND gen = (SELECT gen FROM epg_source WHERE id = programme.sourceId) ORDER BY start LIMIT :limit"""
    )
    abstract suspend fun search(keys: List<String>, now: Long, pattern: String, limit: Int): List<ProgrammeEntity>

    @Insert abstract suspend fun insertAll(list: List<ProgrammeEntity>)
    /** For use inside [androidx.room.RoomDatabase.runInTransaction] on a background thread. */
    @Insert abstract fun insertAllBlocking(list: List<ProgrammeEntity>)
    @Query("DELETE FROM programme WHERE sourceId = :sourceId") abstract fun deleteForSourceBlocking(sourceId: Long)
    /** Up to [limit] rows of [sourceId] from generations other than [keep]; returns how many went. Small, so short. */
    @Query("DELETE FROM programme WHERE id IN (SELECT id FROM programme WHERE sourceId = :sourceId AND gen != :keep LIMIT :limit)")
    abstract fun deleteOtherGensBlocking(sourceId: Long, keep: Int, limit: Int): Int
    @Query("DELETE FROM programme WHERE sourceId = :sourceId") abstract suspend fun deleteForSource(sourceId: Long)
    /** Deletes programmes that ended before [t]. */
    @Query("DELETE FROM programme WHERE stop < :t") abstract suspend fun deleteBefore(t: Long)
    @Query("SELECT count(*) FROM programme") abstract suspend fun count(): Int
    /** Guide channels ("sourceId|xmltvId") whose last programme hasn't finished yet. The latest start per channel comes
     *  from the (epgChannelKey, start) index alone, then one row per channel: a full scan of every programme took 8–10 s. */
    @Query("""SELECT p.epgChannelKey FROM (SELECT epgChannelKey, MAX(start) AS s FROM programme GROUP BY epgChannelKey) m
        JOIN programme p ON p.epgChannelKey = m.epgChannelKey AND p.start = m.s WHERE p.stop > :now GROUP BY p.epgChannelKey""")
    abstract suspend fun liveKeys(now: Long): List<String>
    @Query("DELETE FROM programme") abstract suspend fun deleteAll()
}

@Dao
interface EpgDao {
    @Insert suspend fun insertSource(s: EpgSourceEntity): Long
    @Update suspend fun updateSource(s: EpgSourceEntity)
    @Query("SELECT * FROM epg_source WHERE providerId = :providerId ORDER BY priority")
    suspend fun sources(providerId: Long): List<EpgSourceEntity>
    @Query("SELECT * FROM epg_source WHERE providerId = :providerId ORDER BY priority")
    fun observeSources(providerId: Long): Flow<List<EpgSourceEntity>>
    @Query("SELECT * FROM epg_source WHERE id = :id") suspend fun source(id: Long): EpgSourceEntity?
    @Query("DELETE FROM epg_source WHERE id = :id") suspend fun deleteSource(id: Long)
    @Query("DELETE FROM epg_link WHERE sourceId = :sourceId") suspend fun deleteLinksForSource(sourceId: Long)
    @Query("UPDATE epg_source SET name = :name WHERE id = :id") suspend fun renameSource(id: Long, name: String?)
    @Query("UPDATE epg_source SET offsetMinutes = :minutes WHERE id = :id") suspend fun setOffset(id: Long, minutes: Int)
    @Query("UPDATE epg_source SET url = :url WHERE id = :id") suspend fun setUrl(id: Long, url: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertChannels(list: List<EpgChannelEntity>)
    @Query("DELETE FROM epg_channel WHERE sourceId = :sourceId") suspend fun deleteChannels(sourceId: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertChannelsBlocking(list: List<EpgChannelEntity>)
    @Query("DELETE FROM epg_channel WHERE sourceId = :sourceId") fun deleteChannelsBlocking(sourceId: Long)
    @Query("UPDATE epg_source SET lastFetched = :t WHERE id = :id") fun setLastFetchedBlocking(id: Long, t: Long)
    @Query("UPDATE epg_source SET gen = :gen, lastFetched = :t WHERE id = :id") fun setGenBlocking(id: Long, gen: Int, t: Long)
    @Query("SELECT * FROM epg_channel WHERE sourceId = :sourceId") suspend fun channels(sourceId: Long): List<EpgChannelEntity>

    @Query("SELECT * FROM epg_link WHERE channelId IN (SELECT id FROM channel WHERE providerId = :providerId)")
    suspend fun links(providerId: Long): List<EpgLinkEntity>
    @Query("SELECT * FROM epg_link WHERE channelId = :channelId") suspend fun link(channelId: Long): EpgLinkEntity?
    @Query("DELETE FROM epg_link WHERE channelId = :id") suspend fun deleteLink(id: Long)
    @Query("SELECT * FROM epg_link WHERE channelId IN (:ids)") suspend fun linksFor(ids: List<Long>): List<EpgLinkEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertLinks(list: List<EpgLinkEntity>)
    /** Copies each channel's linked guide icon to channel.epgLogo; only changed rows are written. */
    @Query("""UPDATE channel SET epgLogo = (SELECT nullif(e.icon, '') FROM epg_link l JOIN epg_channel e ON e.sourceId = l.sourceId AND e.xmltvId = l.xmltvId
        WHERE l.channelId = channel.id AND nullif(e.icon, '') IS NOT NULL LIMIT 1)
        WHERE providerId = :providerId AND epgLogo IS NOT (SELECT nullif(e.icon, '') FROM epg_link l JOIN epg_channel e ON e.sourceId = l.sourceId AND e.xmltvId = l.xmltvId
        WHERE l.channelId = channel.id AND nullif(e.icon, '') IS NOT NULL LIMIT 1)""")
    suspend fun refreshEpgLogos(providerId: Long)
    /** Emits whenever an import (lastFetched) or a rematch (epg_link) finishes, so open guides reload. */
    // Offsets are in it so a changed source offset re-reads the guide without a refresh.
    @Query("SELECT coalesce((SELECT max(lastFetched) FROM epg_source), 0) + (SELECT count(*) FROM epg_link) + (SELECT coalesce(sum(offsetMinutes), 0) FROM epg_source)")
    fun observeGuideVersion(): Flow<Long>
    @Query("DELETE FROM epg_link WHERE manual = 0 AND channelId IN (SELECT id FROM channel WHERE providerId = :providerId)")
    suspend fun deleteAutoLinks(providerId: Long)
    @Query("DELETE FROM epg_link WHERE manual = 0") suspend fun deleteAllAutoLinks()
    @Query("DELETE FROM epg_channel") suspend fun deleteAllChannels()
    @Query("UPDATE epg_source SET lastStatus = :status WHERE id = :id") suspend fun setStatus(id: Long, status: String?)
    @Query("UPDATE epg_source SET lastStatus = NULL, lastFetched = NULL") suspend fun clearAllStatus()
}

@Dao
interface RuleDao {
    @Insert suspend fun insertNameRules(list: List<NameRuleEntity>)
    @Query("SELECT * FROM name_rule WHERE providerId = :providerId ORDER BY ruleOrder")
    suspend fun nameRules(providerId: Long): List<NameRuleEntity>
    @Insert suspend fun insertHideRule(r: HideRuleEntity): Long
    @Query("SELECT * FROM hide_rule WHERE providerId = :providerId AND kind = :kind")
    suspend fun hideRules(providerId: Long, kind: Kind): List<HideRuleEntity>
}

@Dao
abstract class ProgressDao {
    @Upsert abstract suspend fun upsert(p: ProgressEntity)
    @Query("DELETE FROM progress WHERE providerId = :providerId") abstract suspend fun deleteForProvider(providerId: Long)
    @Query("SELECT * FROM progress WHERE providerId = :providerId ORDER BY updatedAt DESC")
    abstract suspend fun all(providerId: Long): List<ProgressEntity>
    @Query("SELECT * FROM progress WHERE providerId = :providerId ORDER BY updatedAt DESC")
    abstract fun observe(providerId: Long): kotlinx.coroutines.flow.Flow<List<ProgressEntity>>
    /** Every playlist in use (Continue watching is global, like Favourites); [active] is always included. A kind switched off has no rows. */
    @Query("""SELECT g.* FROM progress g JOIN provider p ON p.id = g.providerId WHERE g.providerId = :active OR p.enabled = 1 ORDER BY g.updatedAt DESC""")
    abstract fun observeShown(active: Long): kotlinx.coroutines.flow.Flow<List<ProgressEntity>>
    @Query("DELETE FROM progress WHERE providerId = :providerId AND kind = :kind AND itemId = :itemId AND episode = :episode")
    abstract suspend fun delete(providerId: Long, kind: Kind, itemId: Long, episode: String)

    @Query("DELETE FROM progress WHERE providerId = :providerId AND kind = :kind AND itemId IN (:ids)")
    protected abstract suspend fun deleteIds(providerId: Long, kind: Kind, ids: List<Long>)

    @Transaction
    open suspend fun deleteForItems(providerId: Long, kind: Kind, ids: List<Long>) {
        ids.chunked(SQL_CHUNK).forEach { deleteIds(providerId, kind, it) }
    }
}

@Dao
abstract class FavouriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun add(f: FavouriteEntity)
    @Query("DELETE FROM favourite WHERE providerId = :providerId") abstract suspend fun deleteForProvider(providerId: Long)
    @Query("SELECT * FROM favourite WHERE providerId = :providerId AND kind = :kind ORDER BY sortOrder, addedAt DESC")
    abstract suspend fun list(providerId: Long, kind: Kind): List<FavouriteEntity>
    @Query("SELECT * FROM favourite WHERE providerId = :providerId AND kind = :kind ORDER BY sortOrder, addedAt DESC")
    abstract fun observe(providerId: Long, kind: Kind): Flow<List<FavouriteEntity>>
    /** Favourite movies or series of every playlist in use, as live Favourites (issue #12); [active] is always included. */
    @Query("""SELECT f.* FROM favourite f JOIN provider p ON p.id = f.providerId WHERE f.kind = :kind AND (f.providerId = :active OR p.enabled = 1)
           ORDER BY f.sortOrder, f.addedAt DESC""")
    abstract fun observeShown(active: Long, kind: Kind): Flow<List<FavouriteEntity>>
    /** Favourite live channels of every playlist (channel ids are unique across playlists). */
    @Query("SELECT itemId FROM favourite WHERE kind = 'LIVE'") abstract fun observeLiveIds(): Flow<List<Long>>
    @Query("UPDATE favourite SET sortOrder = :sortOrder WHERE providerId = :providerId AND kind = :kind AND itemId = :itemId")
    abstract suspend fun setSortOrder(providerId: Long, kind: Kind, itemId: Long, sortOrder: Int)
    @Query("DELETE FROM favourite WHERE providerId = :providerId AND kind = :kind AND itemId = :itemId")
    abstract suspend fun remove(providerId: Long, kind: Kind, itemId: Long)

    @Query("DELETE FROM favourite WHERE providerId = :providerId AND kind = :kind AND itemId IN (:ids)")
    protected abstract suspend fun deleteIds(providerId: Long, kind: Kind, ids: List<Long>)

    /** Removes favourites of items deleted by sync (no FK: itemId is polymorphic). */
    @Transaction
    open suspend fun deleteForItems(providerId: Long, kind: Kind, ids: List<Long>) {
        ids.chunked(SQL_CHUNK).forEach { deleteIds(providerId, kind, it) }
    }
}

@Dao
abstract class CustomGroupDao {
    @Insert abstract suspend fun insert(g: CustomGroupEntity): Long
    @Query("SELECT coalesce(max(sortOrder) + 1, 0) FROM custom_group WHERE providerId = :providerId")
    protected abstract suspend fun nextOrder(providerId: Long): Int

    /** New user category at the end of the user list. */
    @Transaction
    open suspend fun create(providerId: Long, name: String): Long =
        insert(CustomGroupEntity(providerId = providerId, name = name, sortOrder = nextOrder(providerId)))

    @Query("UPDATE custom_group SET name = :name WHERE id = :id") abstract suspend fun rename(id: Long, name: String)
    @Query("UPDATE custom_group SET placement = :placement WHERE id = :id") abstract suspend fun setPlacement(id: Long, placement: GroupPlacement)
    @Query("UPDATE custom_group SET sortBy = :by WHERE id = :id") abstract suspend fun setSortBy(id: Long, by: SortBy)
    @Query("SELECT sortBy FROM custom_group WHERE id = :id") abstract fun observeSortBy(id: Long): Flow<SortBy?>
    @Query("UPDATE custom_group SET sortOrder = :sortOrder WHERE id = :id") abstract suspend fun setSortOrder(id: Long, sortOrder: Int)
    @Query("DELETE FROM custom_group WHERE id = :id") abstract suspend fun delete(id: Long)
    @Query("DELETE FROM custom_group WHERE providerId = :p") abstract suspend fun deleteForProvider(p: Long)
    @Query("SELECT * FROM custom_group WHERE providerId = :providerId ORDER BY sortOrder, name")
    abstract fun observe(providerId: Long): Flow<List<CustomGroupEntity>>

    @Query(
        """SELECT gc.groupId, count(*) AS n FROM custom_group_channel gc JOIN channel ch ON ch.id = gc.channelId AND ch.hidden = 0 AND ch.mergedInto IS NULL
           JOIN custom_group g ON g.id = gc.groupId WHERE g.providerId = :providerId GROUP BY gc.groupId"""
    )
    abstract fun observeCounts(providerId: Long): Flow<List<GroupCount>>

    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertMember(m: CustomGroupChannelEntity)
    @Query("SELECT coalesce(max(sortOrder) + 1, 0) FROM custom_group_channel WHERE groupId = :groupId")
    protected abstract suspend fun nextMemberOrder(groupId: Long): Int

    /** Appends to the group; a channel already in it keeps its place. */
    @Transaction
    open suspend fun addChannel(groupId: Long, channelId: Long) =
        insertMember(CustomGroupChannelEntity(groupId, channelId, nextMemberOrder(groupId)))

    @Query("UPDATE custom_group_channel SET sortOrder = :sortOrder WHERE groupId = :groupId AND channelId = :channelId")
    abstract suspend fun setMemberOrder(groupId: Long, channelId: Long, sortOrder: Int)

    @Query("DELETE FROM custom_group_channel WHERE groupId = :groupId AND channelId = :channelId")
    abstract suspend fun removeChannel(groupId: Long, channelId: Long)

    @Query(
        """SELECT ch.* FROM channel ch JOIN custom_group_channel gc ON gc.channelId = ch.id
           WHERE gc.groupId = :groupId AND ch.hidden = 0 AND ch.mergedInto IS NULL ORDER BY gc.sortOrder"""
    )
    abstract fun observeChannels(groupId: Long): Flow<List<ChannelEntity>>

    /** Every member in group order, hidden ones included (backup). */
    @Query("SELECT ch.remoteId FROM channel ch JOIN custom_group_channel gc ON gc.channelId = ch.id WHERE gc.groupId = :groupId ORDER BY gc.sortOrder")
    abstract suspend fun memberRemoteIds(groupId: Long): List<String>

    @Query("SELECT groupId FROM custom_group_channel WHERE channelId = :channelId") abstract suspend fun groupsOf(channelId: Long): List<Long>
}

const val RECENT_CAP = 8

@Dao
abstract class RecentDao {
    @Upsert protected abstract suspend fun upsert(r: RecentChannelEntity)

    @Query(
        // Hidden channels don't count towards the cap (Recents doesn't show them); their rows go too.
        """DELETE FROM recent_channel WHERE providerId = :providerId AND channelId NOT IN
           (SELECT r.channelId FROM recent_channel r JOIN channel ch ON ch.id = r.channelId
            WHERE r.providerId = :providerId AND ch.hidden = 0 AND ch.mergedInto IS NULL ORDER BY r.watchedAt DESC LIMIT :cap)"""
    )
    protected abstract suspend fun trim(providerId: Long, cap: Int)

    /** Records a watch; a re-watch moves the channel to the front, and only the newest [RECENT_CAP] are kept. */
    @Transaction
    open suspend fun record(providerId: Long, channelId: Long, at: Long) {
        upsert(RecentChannelEntity(providerId, channelId, at))
        trim(providerId, RECENT_CAP)
    }

    @Query(
        """SELECT ch.* FROM channel ch JOIN recent_channel r ON r.channelId = ch.id AND r.providerId = ch.providerId
           WHERE ch.providerId = :providerId AND ch.hidden = 0 AND ch.mergedInto IS NULL ORDER BY r.watchedAt DESC"""
    )
    abstract fun observe(providerId: Long): Flow<List<ChannelEntity>>
}

data class ChannelFromProvider(val id: Long, val name: String, val logo: String?, val number: Int?, val tvgId: String?, val streamUrl: String, val categoryId: Long?,
                               val drmType: String? = null, val drmKey: String? = null, val archiveDays: Int? = null, val catchupSource: String? = null)
data class MovieFromProvider(val id: Long, val name: String, val poster: String?, val year: Int?, val rating: Double?, val streamUrl: String?, val categoryId: Long?, val addedAt: Long)
data class SeriesFromProvider(val id: Long, val name: String, val poster: String?, val year: Int?, val rating: Double?, val categoryId: Long?, val addedAt: Long)
