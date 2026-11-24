package org.imdtv.iptvtv.data.backup

import org.imdtv.iptvtv.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.imdtv.iptvtv.data.settings.AppSettings
import org.imdtv.iptvtv.data.settings.AudioDecoder
import org.imdtv.iptvtv.data.settings.GuideKind
import org.imdtv.iptvtv.data.settings.HeaderSize
import org.imdtv.iptvtv.data.settings.LogoKind
import org.imdtv.iptvtv.data.settings.PlayerClock
import org.imdtv.iptvtv.data.settings.ThemeColour
import org.imdtv.iptvtv.data.settings.themeIdOf
import org.imdtv.iptvtv.data.settings.withUserGuides

const val BACKUP_VERSION = 1

class BackupException(message: String, @androidx.annotation.StringRes val resId: Int? = null) : Exception(message) {
    /** The message in the app's language where it has a resource, else the English text. */
    fun localized(context: android.content.Context): String = resId?.let { context.getString(it) } ?: message.orEmpty()
}

/** Backup file (spec §7). Never holds a username or password; enums are stored by name so unknown ones read as defaults. */
@Serializable
data class Backup(val version: Int = BACKUP_VERSION, val playlists: List<BPlaylist>, val settings: BSettings)

@Serializable
data class BPlaylist(
    val name: String,
    val type: String,
    val url: String,
    val userAgent: String?,
    val streamFormat: String,
    val nameStrip: String,
    val epgSources: List<BEpgSource>,
    val categories: List<BCategory>,
    val groups: List<BGroup>,
    val favourites: List<BFavourite>,
    val categoryStrip: String = "",
    /** Where movies and episodes were left (older backups have none). */
    val progress: List<BProgress> = emptyList(),
    /** The user's channel order within live categories (older backups have none). */
    val channelOrder: List<BChannelOrder> = emptyList(),
    /** Kinds the playlist doesn't load (older backups: all loaded). */
    val contentOff: String = "",
    /** "Use this playlist" (older backups: on). */
    val enabled: Boolean = true,
    /** Channel numbers the user changed (older backups have none). */
    val channelNumbers: List<BChannelNumber> = emptyList(),
    /** Channels with Parental Lock on, by remote id (older backups have none). */
    val lockedChannels: List<String> = emptyList(),
    /** Channels hidden one by one, by remote id (older backups have none; a sync follower kept showing them, #22). */
    val hiddenChannels: List<String> = emptyList(),
    /** Renamed channels and channels with their own guide offset (older backups have none). */
    val channelEdits: List<BChannelEdit> = emptyList(),
    /** Channels merged by hand (older backups have none). */
    val merges: List<BMerge> = emptyList(),
    /** Merge channels for this playlist (older backups: on). */
    val mergeOn: Boolean = true,
)

@Serializable
data class BChannelEdit(val channel: String, val name: String? = null, val offsetMinutes: Int? = null)

/** [member] plays through [head]; both remote ids. */
@Serializable
data class BMerge(val member: String, val head: String)

@Serializable
data class BChannelNumber(val channel: String, val number: Int)

/** [url] null = the playlist's own guide, whose URL carries the login: restored from the new login. */
@Serializable
data class BEpgSource(val url: String?, val name: String?, val priority: Int, val offsetMinutes: Int)

@Serializable
data class BCategory(val kind: String, val remoteId: String, val hidden: Boolean, val sortOrder: Int, val pinned: Boolean, val sortBy: String? = null, val parentalLock: Boolean = false)

/** [channels] are channel remoteIds in group order. */
@Serializable
data class BGroup(val name: String, val sortOrder: Int, val channels: List<String>, val placement: String = "NORMAL", val sortBy: String? = null)

@Serializable
data class BFavourite(val kind: String, val remoteId: String)

/** [category] is a LIVE category remoteId; [channels] are the placed channels' remoteIds in order. */
@Serializable
data class BChannelOrder(val category: String, val channels: List<String>)

/** [remoteId] of the movie or series; [episode] the episode's remoteId ("" for a movie). */
@Serializable
data class BProgress(val kind: String, val remoteId: String, val episode: String, val positionMs: Long, val durationMs: Long,
                     val updatedAt: Long, val completed: Boolean)

/** [AppSettings] minus lastLiveChannelId (a row id, meaningless after restore). */
@Serializable
data class BSettings(
    val theme: String = AppSettings().theme.name,
    val themeColour: String = AppSettings().themeColour.name,
    val autoplayOnScroll: Boolean = AppSettings().autoplayOnScroll,
    val audioDecoder: String = AppSettings().audioDecoder.name,
    val lastChannelOnStart: Boolean = AppSettings().lastChannelOnStart,
    val confirmExit: Boolean = AppSettings().confirmExit,
    val startOnBoot: Boolean = AppSettings().startOnBoot,
    val epgUpdateIntervalHours: Int = AppSettings().epgUpdateIntervalHours,
    val epgUpdateOnStart: Boolean = AppSettings().epgUpdateOnStart,
    val epgUpdateOnPlaylistChange: Boolean = AppSettings().epgUpdateOnPlaylistChange,
    val epgDaysToKeep: Int = AppSettings().epgDaysToKeep,
    val epgPastDays: Int = AppSettings().epgPastDays,
    val matchTvLanguage: Boolean = AppSettings().matchTvLanguage,
    val logoOrder: List<String> = AppSettings().logoOrder.map { it.name },
    val guideOrder: List<String> = AppSettings().guideOrder.map { it.name },
    val borrowSiblingGuide: Boolean = AppSettings().borrowSiblingGuide,
    val hideHeaderChannels: Boolean = AppSettings().hideHeaderChannels,
    val showDateTime: Boolean = AppSettings().showDateTime,
    val showPing: Boolean = AppSettings().showPing,
    val subtitlesOn: Boolean = AppSettings().subtitlesOn,
    val subtitleLanguage: String? = AppSettings().subtitleLanguage,
    val recordingOn: Boolean = AppSettings().recordingOn,
    val recordTo: String = AppSettings().recordTo.name,
    val startEarlyMin: Int = AppSettings().startEarlyMin,
    val endLateMin: Int = AppSettings().endLateMin,
    val keepAwake: Boolean = AppSettings().keepAwake,
    val smbServer: String = "",
    val smbShare: String = "",
    val smbFolder: String = "",
    val smbUser: String = "",
    val playlistUpdateHours: Int = AppSettings().playlistUpdateHours,
    val guideRows: Int = AppSettings().guideRows,
    val multiLineGuide: Boolean = AppSettings().multiLineGuide,
    val zapLeftRight: Boolean = AppSettings().zapLeftRight,
    val backToFullScreen: Boolean = AppSettings().backToFullScreen,
    val showChannelNumbers: Boolean = AppSettings().showChannelNumbers,
    val clock24h: Boolean = AppSettings().clock24h,
    /** Before Live TV only / Universal (true = Universal); read only when [playerClockMode] is absent. */
    val playerClock: Boolean = false,
    val playerClockMode: String? = null,
    val showDate: Boolean = AppSettings().showDate,
    val themeLooks: Map<String, String> = emptyMap(),
    val guidesOff: List<String> = emptyList(),
    val liveSubtitlesOn: Boolean = AppSettings().liveSubtitlesOn,
    val videoAspect: String? = null,
    val vodTwoRows: Boolean = AppSettings().vodTwoRows,
    val titleSize: String? = null,
    val vodTitleSize: String? = null,
    val epgTextSize: String? = null,
    val guideLogos: Boolean = false, // old backups only
    val guideDisplay: String? = null,
    val vodSort: String? = null,
    /** Parental controls: the PIN and backup word as hashes only; sync passes them on to followers. */
    val parentalOn: Boolean = false,
    val parentalPin: String? = null,
    val parentalWord: String? = null,
    val ageLimit: String? = null,
    val unlockFor: String? = null,
    val catchUpOn: Boolean = AppSettings().catchUpOn,
    val holdBackOther: Boolean = AppSettings().holdBackOther,
    val guideTimeoutMin: Int = AppSettings().guideTimeoutMin,
    val favouritesSortBy: String? = null,
    val showRecent: Boolean = AppSettings().showRecent,
    val headerSize: String = AppSettings().headerSize.name,
    val previewFade: Boolean = AppSettings().previewFade,
    val showLiveIndicator: Boolean = AppSettings().showLiveIndicator,
    val heroLogoScale: Float = AppSettings().heroLogoScale,
    val heroTitleScale: Float = AppSettings().heroTitleScale,
    val brandLogoScale: Float = AppSettings().brandLogoScale,
    val themeDark: Boolean = AppSettings().themeDark,
    /** The guide order with "Your EPG sources"; absent in older backups, whose [guideOrder] then gets it added. */
    val guideKinds: List<String>? = null,
    // Absent in older backups. Merging changes which channels a list shows, so a follower without them didn't match (#22).
    val removeNameSymbols: Boolean = AppSettings().removeNameSymbols,
    val mergeChannels: Boolean = AppSettings().mergeChannels,
    val mergeAcross: Boolean = AppSettings().mergeAcross,
    val mergeBy: String = AppSettings().mergeBy.name,
) {
    fun toAppSettings(lastLiveChannelId: Long?): AppSettings {
        val d = AppSettings()
        return AppSettings(
            theme = themeIdOf(theme),
            themeColour = ThemeColour.entries.find { it.name == themeColour } ?: d.themeColour,
            autoplayOnScroll = autoplayOnScroll,
            audioDecoder = AudioDecoder.entries.find { it.name == audioDecoder } ?: d.audioDecoder,
            lastChannelOnStart = lastChannelOnStart,
            confirmExit = confirmExit,
            zapLeftRight = zapLeftRight, backToFullScreen = backToFullScreen, showChannelNumbers = showChannelNumbers, clock24h = clock24h,
            playerClock = PlayerClock.entries.find { it.name == playerClockMode } ?: if (playerClock) PlayerClock.ALL else PlayerClock.OFF,
            showDate = showDate, themeLooks = themeLooks, guidesOff = guidesOff.toSet(), liveSubtitlesOn = liveSubtitlesOn, showRecent = showRecent,
            videoAspect = org.imdtv.iptvtv.data.settings.VideoAspect.entries.find { it.name == videoAspect } ?: org.imdtv.iptvtv.data.settings.VideoAspect.AUTO,
            vodTwoRows = vodTwoRows,
            titleSize = org.imdtv.iptvtv.data.settings.TitleSize.entries.find { it.name == titleSize } ?: org.imdtv.iptvtv.data.settings.TitleSize.LARGE,
            vodTitleSize = org.imdtv.iptvtv.data.settings.TitleSize.entries.find { it.name == vodTitleSize } ?: org.imdtv.iptvtv.data.settings.TitleSize.LARGE,
            epgTextSize = org.imdtv.iptvtv.data.settings.EpgTextSize.entries.find { it.name == epgTextSize } ?: org.imdtv.iptvtv.data.settings.EpgTextSize.MEDIUM,
            guideDisplay = org.imdtv.iptvtv.data.settings.GuideChannelDisplay.entries.find { it.name == guideDisplay } ?: if (guideLogos) org.imdtv.iptvtv.data.settings.GuideChannelDisplay.LOGO else org.imdtv.iptvtv.data.settings.GuideChannelDisplay.NAME,
            vodSort = org.imdtv.iptvtv.data.settings.VodSort.entries.find { it.name == vodSort } ?: org.imdtv.iptvtv.data.settings.VodSort.PLAYLIST,
            parentalOn = parentalOn && parentalPin != null, parentalPin = parentalPin, parentalWord = parentalWord,
            ageLimit = org.imdtv.iptvtv.data.parental.AgeLimit.entries.find { it.name == ageLimit } ?: org.imdtv.iptvtv.data.parental.AgeLimit.A12,
            unlockFor = org.imdtv.iptvtv.data.parental.UnlockFor.entries.find { it.name == unlockFor } ?: org.imdtv.iptvtv.data.parental.UnlockFor.APP,
            catchUpOn = catchUpOn, holdBackOther = holdBackOther, guideTimeoutMin = guideTimeoutMin, favouritesSortBy = favouritesSortBy?.let { n -> org.imdtv.iptvtv.data.db.SortBy.entries.find { it.name == n } },
            headerSize = HeaderSize.entries.find { it.name == headerSize } ?: d.headerSize, previewFade = previewFade, themeDark = themeDark,
            showLiveIndicator = showLiveIndicator, heroLogoScale = heroLogoScale, heroTitleScale = heroTitleScale, brandLogoScale = brandLogoScale,
            removeNameSymbols = removeNameSymbols, mergeChannels = mergeChannels, mergeAcross = mergeAcross,
            mergeBy = org.imdtv.iptvtv.data.settings.MergeBy.entries.find { it.name == mergeBy } ?: d.mergeBy,
            startOnBoot = startOnBoot,
            epgUpdateIntervalHours = epgUpdateIntervalHours,
            epgUpdateOnStart = epgUpdateOnStart,
            epgUpdateOnPlaylistChange = epgUpdateOnPlaylistChange,
            epgDaysToKeep = epgDaysToKeep,
            epgPastDays = epgPastDays,
            lastLiveChannelId = lastLiveChannelId,
            matchTvLanguage = matchTvLanguage,
            logoOrder = logoOrder.mapNotNull { n -> LogoKind.entries.find { it.name == n } }.distinct().ifEmpty { d.logoOrder },
            guideOrder = guideKinds?.let(::guideKindsOf)?.ifEmpty { null }
                ?: guideKindsOf(guideOrder).ifEmpty { null }?.let(::withUserGuides) ?: d.guideOrder,
            borrowSiblingGuide = borrowSiblingGuide,
            hideHeaderChannels = hideHeaderChannels,
            showDateTime = showDateTime,
            showPing = showPing,
            guideRows = guideRows,
            multiLineGuide = multiLineGuide,
            subtitlesOn = subtitlesOn,
            subtitleLanguage = subtitleLanguage,
            recordingOn = recordingOn,
            recordTo = org.imdtv.iptvtv.recording.RecStorage.entries.find { it.name == recordTo } ?: d.recordTo,
            startEarlyMin = startEarlyMin,
            endLateMin = endLateMin,
            keepAwake = keepAwake,
            smbServer = smbServer, smbShare = smbShare, smbFolder = smbFolder, smbUser = smbUser,
            playlistUpdateHours = playlistUpdateHours,
        ).clamped()
    }

    companion object {
        fun of(s: AppSettings) = BSettings(
            s.theme.name, s.themeColour.name, s.autoplayOnScroll, s.audioDecoder.name, s.lastChannelOnStart, s.confirmExit,
            s.startOnBoot, s.epgUpdateIntervalHours, s.epgUpdateOnStart, s.epgUpdateOnPlaylistChange, s.epgDaysToKeep,
            s.epgPastDays, s.matchTvLanguage, s.logoOrder.map { it.name }, s.guideOrder.map { it.name },
            s.borrowSiblingGuide, s.hideHeaderChannels, s.showDateTime, s.showPing, s.subtitlesOn, s.subtitleLanguage,
            guideRows = s.guideRows, multiLineGuide = s.multiLineGuide,
            zapLeftRight = s.zapLeftRight, backToFullScreen = s.backToFullScreen, showChannelNumbers = s.showChannelNumbers, clock24h = s.clock24h,
            playerClockMode = s.playerClock.name, showDate = s.showDate, themeLooks = s.themeLooks, guidesOff = s.guidesOff.toList(), liveSubtitlesOn = s.liveSubtitlesOn, showRecent = s.showRecent, videoAspect = s.videoAspect.name, vodTwoRows = s.vodTwoRows, titleSize = s.titleSize.name, vodTitleSize = s.vodTitleSize.name, epgTextSize = s.epgTextSize.name, guideDisplay = s.guideDisplay.name, vodSort = s.vodSort.name, parentalOn = s.parentalOn, parentalPin = s.parentalPin, parentalWord = s.parentalWord, ageLimit = s.ageLimit.name, unlockFor = s.unlockFor.name, catchUpOn = s.catchUpOn, holdBackOther = s.holdBackOther, guideTimeoutMin = s.guideTimeoutMin, favouritesSortBy = s.favouritesSortBy?.name,
            headerSize = s.headerSize.name, previewFade = s.previewFade, showLiveIndicator = s.showLiveIndicator, heroLogoScale = s.heroLogoScale, heroTitleScale = s.heroTitleScale, brandLogoScale = s.brandLogoScale, themeDark = s.themeDark, guideKinds = s.guideOrder.map { it.name },
            removeNameSymbols = s.removeNameSymbols, mergeChannels = s.mergeChannels, mergeAcross = s.mergeAcross, mergeBy = s.mergeBy.name,
            recordingOn = s.recordingOn, recordTo = s.recordTo.name, startEarlyMin = s.startEarlyMin, endLateMin = s.endLateMin,
            keepAwake = s.keepAwake, smbServer = s.smbServer, smbShare = s.smbShare, smbFolder = s.smbFolder, smbUser = s.smbUser, playlistUpdateHours = s.playlistUpdateHours,
        )
    }
}

private fun guideKindsOf(names: List<String>) = names.mapNotNull { n -> GuideKind.entries.find { it.name == n } }.distinct()

private val backupJson = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

fun encodeBackup(b: Backup): String = backupJson.encodeToString(Backup.serializer(), b)

/** The version is read before the body, so a newer file is named as such even when its shape has changed. */
fun decodeBackup(s: String): Backup {
    val tree = try {
        backupJson.parseToJsonElement(s).jsonObject
    } catch (e: SerializationException) {
        throw BackupException("Not a readable backup file", R.string.set_b_backup_unreadable)
    } catch (e: IllegalArgumentException) {
        throw BackupException("Not a readable backup file", R.string.set_b_backup_unreadable)
    }
    val version = (tree["version"] as? JsonPrimitive)?.intOrNull ?: throw BackupException("Not a readable backup file", R.string.set_b_backup_unreadable)
    if (version > BACKUP_VERSION) throw BackupException("This backup was made by a newer version of the app", R.string.set_b_backup_newer)
    return try {
        backupJson.decodeFromJsonElement(Backup.serializer(), tree)
    } catch (e: SerializationException) {
        throw BackupException("Not a readable backup file", R.string.set_b_backup_unreadable)
    } catch (e: IllegalArgumentException) {
        throw BackupException("Not a readable backup file", R.string.set_b_backup_unreadable)
    }
}
