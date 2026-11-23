package org.imdtv.iptvtv.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.imdtv.iptvtv.data.model.Kind
import org.imdtv.iptvtv.data.model.StreamFormat

enum class ProviderType {
    XTREAM, M3U, STALKER, PLEX, JELLYFIN, STREMIO,
    /** A Dispatcharr server: signed in with its own login, then loaded through its Xtream Codes API (DispatcharrAuth). */
    DISPATCHARR;
    /** Loaded through player_api.php with username + password (Dispatcharr's are the user's XC password). */
    val xtream get() = this == XTREAM || this == DISPATCHARR
    /** Added under Settings › Media servers: Movies + Series only, no form, not in file backups. */
    val mediaServer get() = this == PLEX || this == JELLYFIN || this == STREMIO
}

/** Where a user category sits in the category list (Your categories › Options › Priority). */
enum class GroupPlacement { TOP, NORMAL, BOTTOM }

/** Sort by (issue #9): the playlist's order (the default), A–Z, or the order the user made (Move, Sort A–Z once). */
enum class SortBy { PLAYLIST, AZ, MANUAL }

enum class NameRuleType { BUILTIN, FIND_REPLACE, REGEX }

/** [EpgSourceEntity.origin]: provider/user URLs, or epgshare01 files managed by the app. */
const val ORIGIN_PLAYLIST = "PLAYLIST"
const val ORIGIN_EPGSHARE = "EPGSHARE"

@Entity(tableName = "provider")
data class ProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: ProviderType,
    val url: String,
    val username: String?,
    val password: String?,
    val userAgent: String?,
    val streamFormat: StreamFormat,
    /** Empty = use the source's own EPG URLs. */
    val epgOverrideUrls: List<String>,
    val lastSync: Long?,
    @ColumnInfo(defaultValue = "") val nameStrip: String = "",
    /** Category name clean-up list, same format as [nameStrip]. */
    @ColumnInfo(defaultValue = "") val categoryStrip: String = "",
    /** Xtream user_info.max_connections, refreshed when recording needs it; null = unknown (M3U). */
    val maxConnections: Int? = null,
    /** Kinds (Kind names, comma-separated) this playlist doesn't load: never fetched, their data removed. */
    @ColumnInfo(defaultValue = "") val contentOff: String = "",
    /** "Use this playlist": off = left out of the category lists' other playlists (never the one in use). */
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    /** Settings › Channels › Merge options › Playlists: this playlist merges when "Merge channels" is on. */
    @ColumnInfo(defaultValue = "1") val mergeOn: Boolean = true,
    /** Plex, Jellyfin: library keys (comma-separated) the user left unticked, never fetched. */
    @ColumnInfo(defaultValue = "") val skipLibraries: String = "",
) {
    fun loads(kind: org.imdtv.iptvtv.data.model.Kind) = kind.name !in contentOff.split(',')
}

@Entity(
    tableName = "category",
    foreignKeys = [ForeignKey(entity = ProviderEntity::class, parentColumns = ["id"], childColumns = ["providerId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["providerId", "kind", "remoteId"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val kind: Kind,
    val remoteId: String,
    val name: String,
    val sortOrder: Int = 0,
    val hidden: Boolean = false,
    val locked: Boolean = false,
    val pinned: Boolean = false,
    val country: String? = null,
    /** How its channels are listed; only MANUAL uses their saved places (channel.sortOrder). */
    @ColumnInfo(defaultValue = "PLAYLIST") val sortBy: SortBy = SortBy.PLAYLIST,
    /** Parental controls: everything in it needs the PIN (hold OK on it › Enable Parental Lock). */
    @ColumnInfo(defaultValue = "0") val parentalLock: Boolean = false,
)

@Entity(
    tableName = "channel",
    foreignKeys = [
        ForeignKey(entity = ProviderEntity::class, parentColumns = ["id"], childColumns = ["providerId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["providerId", "remoteId"], unique = true), Index("categoryId")],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val categoryId: Long?,
    val remoteId: String,
    val name: String,
    val customName: String? = null,
    val logo: String?,
    val customLogo: String? = null,
    val number: Int?,
    val customNumber: Int? = null,
    val hidden: Boolean = false,
    val tvgId: String?,
    val epgChannelId: String? = null,
    val epgOffsetMinutes: Int? = null,
    val streamUrl: String,
    /** File path of the logo matched from the user's logo pack. */
    val packLogo: String? = null,
    /** tv-logos path, e.g. `countries/united-kingdom/hd/sky-sports-main-event-uhd-uk.png` (not a URL). */
    val builtinLogo: String? = null,
    /** The <icon> of the guide channel it is linked to (EpgDao.refreshEpgLogos). */
    val epgLogo: String? = null,
    /** Playlist DRM (Kodi license_type / license_key), read at play time by drmSpec(). */
    val drmType: String? = null,
    val drmKey: String? = null,
    /** The user's place in its category (Settings › Manage channels); null = by number, after any placed ones. */
    val sortOrder: Int? = null,
    /** Catch-up: days of past programmes the provider keeps (Xtream tv_archive_duration); null = none. */
    val archiveDays: Int? = null,
    /** M3U catch-up, packed "type|template" (template may be empty); null = the provider's default (shift). */
    val catchupSource: String? = null,
    /** Merge: the channel this one is a source of (hidden from every list, played through it); null = not merged. */
    val mergedInto: Long? = null,
    /** True when [mergedInto] came from "Merge duplicate channels", so turning that off or a re-run only undoes these. */
    @ColumnInfo(defaultValue = "0") val mergedAuto: Boolean = false,
    /** Other channels are merged into this one: its name is shown without quality tags (HD, 4K…). */
    @ColumnInfo(defaultValue = "0") val mergeHead: Boolean = false,
    /** Hold OK on a channel › Enable Parental Lock: playing it needs the PIN. */
    @ColumnInfo(defaultValue = "0") val parentalLock: Boolean = false,
) {
    fun drm(): org.imdtv.iptvtv.data.source.Drm? = drmType?.let { org.imdtv.iptvtv.data.source.Drm(it, drmKey) }
}

@Entity(
    tableName = "movie",
    foreignKeys = [
        ForeignKey(entity = ProviderEntity::class, parentColumns = ["id"], childColumns = ["providerId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["providerId", "remoteId"], unique = true), Index("categoryId"), Index(value = ["providerId", "categoryId"])],
)
data class MovieEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val categoryId: Long?,
    val remoteId: String,
    val name: String,
    val poster: String?,
    val year: Int?,
    val rating: Double?,
    val streamUrl: String?,
    val addedAt: Long = 0,
    // Detail fields, filled when the detail page is opened.
    val backdrop: String? = null,
    val plot: String? = null,
    val genre: String? = null,
    val director: String? = null,
    val cast: String? = null,
    val durationSecs: Int? = null,
)

@Entity(
    tableName = "series",
    foreignKeys = [
        ForeignKey(entity = ProviderEntity::class, parentColumns = ["id"], childColumns = ["providerId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["providerId", "remoteId"], unique = true), Index("categoryId"), Index(value = ["providerId", "categoryId"])],
)
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val categoryId: Long?,
    val remoteId: String,
    val name: String,
    val poster: String?,
    val year: Int?,
    val rating: Double?,
    val addedAt: Long = 0,
    val backdrop: String? = null,
    val plot: String? = null,
    val genre: String? = null,
)

/** Seasons are derived from [season]; there is no season table. */
@Entity(
    tableName = "episode",
    foreignKeys = [ForeignKey(entity = SeriesEntity::class, parentColumns = ["id"], childColumns = ["seriesId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["seriesId", "remoteId"], unique = true)],
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seriesId: Long,
    val remoteId: String,
    val season: Int,
    val number: Int,
    val title: String,
    val plot: String?,
    val thumb: String?,
    val durationSecs: Int?,
    val streamUrl: String,
)

@Entity(
    tableName = "epg_source",
    foreignKeys = [ForeignKey(entity = ProviderEntity::class, parentColumns = ["id"], childColumns = ["providerId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("providerId")],
)
data class EpgSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val url: String,
    val priority: Int,
    val offsetMinutes: Int = 0,
    val lastFetched: Long? = null,
    val name: String? = null,
    val lastStatus: String? = null,
    @ColumnInfo(defaultValue = ORIGIN_PLAYLIST) val origin: String = ORIGIN_PLAYLIST,
    /** The programme generation the guide shows; an import writes the next one, then switches (no long write lock). */
    @ColumnInfo(defaultValue = "0") val gen: Int = 0,
)

/** One row per (source, xmltv id, display name): an XMLTV channel can have several display names. */
@Entity(
    tableName = "epg_channel",
    primaryKeys = ["sourceId", "xmltvId", "displayName"],
    foreignKeys = [ForeignKey(entity = EpgSourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("xmltvId")],
)
data class EpgChannelEntity(
    val sourceId: Long,
    val xmltvId: String,
    val displayName: String,
    val normalisedName: String,
    val icon: String?,
)

@Entity(
    tableName = "programme",
    indices = [Index(value = ["epgChannelKey", "start"]), Index("sourceId")],
)
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    /** `"$sourceId|$xmltvId"` */
    val epgChannelKey: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val desc: String?,
    val episodeNum: String?,
    val icon: String?,
    /** Import generation; only rows of the source's current [EpgSourceEntity.gen] are shown. */
    @ColumnInfo(defaultValue = "0") val gen: Int = 0,
    /** Age from the XMLTV `<rating>` (parental controls); null = unrated. */
    val ageRating: Int? = null,
) {
    companion object {
        fun key(sourceId: Long, xmltvId: String) = "$sourceId|$xmltvId"
    }
}

@Entity(
    tableName = "epg_link",
    foreignKeys = [ForeignKey(entity = ChannelEntity::class, parentColumns = ["id"], childColumns = ["channelId"], onDelete = ForeignKey.CASCADE)],
)
data class EpgLinkEntity(
    @PrimaryKey val channelId: Long,
    val sourceId: Long,
    val xmltvId: String,
    val manual: Boolean,
)

@Entity(
    tableName = "name_rule",
    foreignKeys = [ForeignKey(entity = ProviderEntity::class, parentColumns = ["id"], childColumns = ["providerId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("providerId")],
)
data class NameRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    @ColumnInfo(name = "ruleOrder") val order: Int,
    val enabled: Boolean,
    val type: NameRuleType,
    /** For BUILTIN: the BuiltinRule name. */
    val pattern: String,
    val replacement: String? = null,
)

@Entity(
    tableName = "hide_rule",
    foreignKeys = [ForeignKey(entity = ProviderEntity::class, parentColumns = ["id"], childColumns = ["providerId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("providerId")],
)
data class HideRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val kind: Kind,
    val regex: String,
)

/** [itemId] is the local row id of the channel / movie / series. */
@Entity(tableName = "favourite", primaryKeys = ["providerId", "kind", "itemId"])
data class FavouriteEntity(
    val providerId: Long,
    val kind: Kind,
    val itemId: Long,
    val addedAt: Long,
    /** Your own order (Your categories › Favourites); equal values fall back to newest first. */
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
)

/** Where a movie or episode was left. [itemId] is the local row id of the movie or the series; [episode] is the
 *  episode's remoteId ("" for a movie): episodes are fetched on demand and have no local row. */
@Entity(tableName = "progress", primaryKeys = ["providerId", "kind", "itemId", "episode"])
data class ProgressEntity(
    val providerId: Long,
    val kind: Kind,
    val itemId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val completed: Boolean,
    @ColumnInfo(defaultValue = "") val episode: String = "",
)

@Entity(
    tableName = "recent_channel",
    primaryKeys = ["providerId", "channelId"],
    foreignKeys = [ForeignKey(entity = ChannelEntity::class, parentColumns = ["id"], childColumns = ["channelId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("channelId")],
)
data class RecentChannelEntity(
    val providerId: Long,
    val channelId: Long,
    val watchedAt: Long,
)

@Entity(
    tableName = "reminder",
    foreignKeys = [ForeignKey(entity = ChannelEntity::class, parentColumns = ["id"], childColumns = ["channelId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("channelId")],
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: Long,
    val programmeStart: Long,
    val title: String,
)

@Entity(
    tableName = "custom_group",
    foreignKeys = [ForeignKey(entity = ProviderEntity::class, parentColumns = ["id"], childColumns = ["providerId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("providerId")],
)
data class CustomGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val name: String,
    val sortOrder: Int,
    @ColumnInfo(defaultValue = "NORMAL") val placement: GroupPlacement = GroupPlacement.NORMAL,
    /** As [CategoryEntity.sortBy]; MANUAL uses the members' saved places. */
    @ColumnInfo(defaultValue = "PLAYLIST") val sortBy: SortBy = SortBy.PLAYLIST,
)

@Entity(
    tableName = "custom_group_channel",
    primaryKeys = ["groupId", "channelId"],
    foreignKeys = [
        ForeignKey(entity = CustomGroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ChannelEntity::class, parentColumns = ["id"], childColumns = ["channelId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("channelId")],
)
data class CustomGroupChannelEntity(
    val groupId: Long,
    val channelId: Long,
    val sortOrder: Int,
)

/** The Xtream login, for filling the placeholders in saved links; null for M3U or a playlist without one. */
fun ProviderEntity.login(): org.imdtv.iptvtv.data.security.Login? =
    if (type.xtream && username != null && password != null) org.imdtv.iptvtv.data.security.Login(username, password)
    // Stremio's key is the account's: never handed to an add-on's stream host.
    else if (type.mediaServer && type != ProviderType.STREMIO && password != null) org.imdtv.iptvtv.data.security.Login(username.orEmpty(), password) // fills X-Plex-Token / api_key
    else null
