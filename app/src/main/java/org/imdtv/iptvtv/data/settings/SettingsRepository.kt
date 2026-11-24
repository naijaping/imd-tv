package org.imdtv.iptvtv.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** AppSettings in the app's DataStore, alongside ProviderRepository's "active_provider_id". */
class SettingsRepository(private val prefs: DataStore<Preferences>) {
    private val theme = stringPreferencesKey("theme")
    private val themeColour = stringPreferencesKey("theme_colour")
    private val autoplay = booleanPreferencesKey("autoplay_on_scroll")
    private val decoder = stringPreferencesKey("audio_decoder")
    private val lastChannelOnStart = booleanPreferencesKey("last_channel_on_start")
    private val confirmExit = booleanPreferencesKey("confirm_exit")
    private val zapLeftRight = booleanPreferencesKey("zap_left_right")
    private val backToFullScreen = booleanPreferencesKey("back_to_full_screen")
    private val showChannelNumbers = booleanPreferencesKey("show_channel_numbers")
    private val themeDark = booleanPreferencesKey("theme_dark")
    private val clock24h = booleanPreferencesKey("clock_24h")
    private val oldPlayerClock = booleanPreferencesKey("player_clock") // before Live TV only / Universal: true = Universal
    private val playerClock = stringPreferencesKey("player_clock_mode")
    private val showDate = booleanPreferencesKey("show_date")
    private val themeLooks = stringPreferencesKey("theme_looks")
    private val guidesOff = androidx.datastore.preferences.core.stringSetPreferencesKey("guides_off")
    private val liveSubtitlesOn = booleanPreferencesKey("live_subtitles_on")
    private val videoAspect = stringPreferencesKey("video_aspect")
    private val mergeChannels = booleanPreferencesKey("merge_channels")
    private val mergeAcross = booleanPreferencesKey("merge_across")
    private val mergeBy = stringPreferencesKey("merge_by")
    private val vodTwoRows = booleanPreferencesKey("vod_two_rows")
    private val titleSize = stringPreferencesKey("title_size")
    private val vodTitleSize = stringPreferencesKey("vod_title_size")
    private val epgTextSize = stringPreferencesKey("epg_text_size")
    private val guideLogos = booleanPreferencesKey("guide_logos") // read-only: the pre-"Channel display" switch
    private val guideDisplay = stringPreferencesKey("guide_display")
    private val vodSort = stringPreferencesKey("vod_sort")
    private val parentalOn = booleanPreferencesKey("parental_on")
    private val parentalPin = stringPreferencesKey("parental_pin")
    private val parentalWord = stringPreferencesKey("parental_word")
    private val ageLimit = stringPreferencesKey("age_limit")
    private val unlockFor = stringPreferencesKey("unlock_for")
    private val catchUpOn = booleanPreferencesKey("catch_up_on")
    private val holdBackOther = booleanPreferencesKey("hold_back_other")
    private val guideTimeoutMin = intPreferencesKey("guide_timeout_min")
    private val favouritesSortBy = stringPreferencesKey("favourites_sort_by")
    private val headerSize = stringPreferencesKey("header_size")
    private val previewFade = booleanPreferencesKey("preview_fade")
    private val showLiveIndicator = booleanPreferencesKey("show_live_indicator")
    private val autoPlayFirstStream = booleanPreferencesKey("auto_play_first_stream")
    private val brandLogoPath = stringPreferencesKey("brand_logo_path")
    private val brandLogoUrl = stringPreferencesKey("brand_logo_url")
    private val heroLogoScale = floatPreferencesKey("hero_logo_scale")
    private val heroTitleScale = floatPreferencesKey("hero_title_scale")
    private val brandLogoScale = floatPreferencesKey("brand_logo_scale")
    private val startOnBoot = booleanPreferencesKey("start_on_boot")
    private val interval = intPreferencesKey("epg_update_interval_hours")
    private val playlistHours = intPreferencesKey("playlist_update_hours")
    private val epgOnStart = booleanPreferencesKey("epg_update_on_start")
    private val epgOnPlaylist = booleanPreferencesKey("epg_update_on_playlist_change")
    private val daysToKeep = intPreferencesKey("epg_days_to_keep")
    private val pastDays = intPreferencesKey("epg_past_days")
    private val lastChannel = longPreferencesKey("last_live_channel_id")
    private val matchLanguage = booleanPreferencesKey("match_tv_language")
    private val logoOrder = stringPreferencesKey("logo_order")
    private val guideOrder = stringPreferencesKey("guide_order")
    /** Since "Your EPG sources" became a kind: an order here may leave it out on purpose; one under [guideOrder] predates it. */
    private val guideKinds = stringPreferencesKey("guide_kinds")
    // Single-choice settings before 2026-11-12, read once to seed the orders.
    private val oldLogoSource = stringPreferencesKey("logo_source")
    private val oldGuideSource = stringPreferencesKey("guide_source")
    private val borrowSibling = booleanPreferencesKey("borrow_sibling_guide")
    private val hideHeaders = booleanPreferencesKey("hide_header_channels")
    private val nameSymbols = booleanPreferencesKey("remove_name_symbols")
    private val showDateTime = booleanPreferencesKey("show_date_time")
    private val showPing = booleanPreferencesKey("show_ping")
    private val showRecent = booleanPreferencesKey("show_recent")
    private val guideRows = intPreferencesKey("guide_rows")
    private val multiLineGuide = booleanPreferencesKey("multi_line_guide")
    private val subtitlesOn = booleanPreferencesKey("subtitles_on")
    private val subtitleLanguage = stringPreferencesKey("subtitle_language")
    private val backupFolder = stringPreferencesKey("backup_folder")
    private val recordingOn = booleanPreferencesKey("recording_on")
    private val recordTo = stringPreferencesKey("record_to")
    private val recordUsbPath = stringPreferencesKey("record_usb_path")
    private val startEarly = intPreferencesKey("start_early_min")
    private val endLate = intPreferencesKey("end_late_min")
    private val keepAwake = booleanPreferencesKey("keep_awake")
    private val smbServer = stringPreferencesKey("smb_server")
    private val smbShare = stringPreferencesKey("smb_share")
    private val smbFolder = stringPreferencesKey("smb_folder")
    private val smbUser = stringPreferencesKey("smb_user")
    private val smbPassword = stringPreferencesKey("smb_password")

    val settings: Flow<AppSettings> = prefs.data.map { it.toSettings() }.distinctUntilChanged()

    suspend fun update(change: (AppSettings) -> AppSettings) {
        prefs.edit { p -> p.write(change(p.toSettings()).clamped()) }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            theme = themeIdOf(this[theme]),
            themeColour = ThemeColour.entries.find { it.name == this[themeColour] } ?: d.themeColour,
            autoplayOnScroll = this[autoplay] ?: d.autoplayOnScroll,
            audioDecoder = this[decoder]?.let { n -> AudioDecoder.entries.find { it.name == n } } ?: d.audioDecoder,
            lastChannelOnStart = this[lastChannelOnStart] ?: d.lastChannelOnStart,
            confirmExit = this[confirmExit] ?: d.confirmExit,
            zapLeftRight = this[zapLeftRight] ?: d.zapLeftRight,
            backToFullScreen = this[backToFullScreen] ?: d.backToFullScreen,
            showChannelNumbers = this[showChannelNumbers] ?: d.showChannelNumbers,
            themeDark = this[themeDark] ?: d.themeDark,
            clock24h = this[clock24h] ?: d.clock24h,
            playerClock = PlayerClock.entries.find { it.name == this[playerClock] }
                ?: this[oldPlayerClock]?.let { if (it) PlayerClock.ALL else PlayerClock.OFF } ?: d.playerClock,
            showDate = this[showDate] ?: d.showDate,
            guidesOff = this[guidesOff].orEmpty(),
            themeLooks = this[themeLooks]?.split('|')?.mapNotNull { e -> e.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap().orEmpty(),
            liveSubtitlesOn = this[liveSubtitlesOn] ?: d.liveSubtitlesOn,
            videoAspect = VideoAspect.entries.find { it.name == this[videoAspect] } ?: d.videoAspect,
            mergeChannels = this[mergeChannels] ?: d.mergeChannels,
            mergeAcross = this[mergeAcross] ?: d.mergeAcross,
            mergeBy = MergeBy.entries.find { it.name == this[mergeBy] } ?: d.mergeBy,
            vodTwoRows = this[vodTwoRows] ?: d.vodTwoRows,
            titleSize = TitleSize.entries.find { it.name == this[titleSize] } ?: d.titleSize,
            vodTitleSize = TitleSize.entries.find { it.name == this[vodTitleSize] } ?: d.vodTitleSize,
            epgTextSize = EpgTextSize.entries.find { it.name == this[epgTextSize] } ?: d.epgTextSize,
            guideDisplay = GuideChannelDisplay.entries.find { it.name == this[guideDisplay] } ?: if (this[guideLogos] == true) GuideChannelDisplay.LOGO else d.guideDisplay,
            vodSort = VodSort.entries.find { it.name == this[vodSort] } ?: d.vodSort,
            parentalOn = this[parentalOn] ?: d.parentalOn,
            parentalPin = this[parentalPin], parentalWord = this[parentalWord],
            ageLimit = org.imdtv.iptvtv.data.parental.AgeLimit.entries.find { it.name == this[ageLimit] } ?: d.ageLimit,
            unlockFor = org.imdtv.iptvtv.data.parental.UnlockFor.entries.find { it.name == this[unlockFor] } ?: d.unlockFor,
            catchUpOn = this[catchUpOn] ?: d.catchUpOn,
            holdBackOther = this[holdBackOther] ?: d.holdBackOther,
            guideTimeoutMin = this[guideTimeoutMin] ?: d.guideTimeoutMin,
            favouritesSortBy = this[favouritesSortBy]?.let { n -> org.imdtv.iptvtv.data.db.SortBy.entries.find { it.name == n } },
            headerSize = HeaderSize.entries.find { it.name == this[headerSize] } ?: d.headerSize,
            previewFade = this[previewFade] ?: d.previewFade,
            showLiveIndicator = this[showLiveIndicator] ?: d.showLiveIndicator,
            autoPlayFirstStream = this[autoPlayFirstStream] ?: d.autoPlayFirstStream,
            brandLogoPath = this[brandLogoPath],
            brandLogoUrl = this[brandLogoUrl],
            heroLogoScale = this[heroLogoScale] ?: d.heroLogoScale,
            heroTitleScale = this[heroTitleScale] ?: d.heroTitleScale,
            brandLogoScale = this[brandLogoScale] ?: d.brandLogoScale,
            startOnBoot = this[startOnBoot] ?: d.startOnBoot,
            epgUpdateIntervalHours = this[interval] ?: d.epgUpdateIntervalHours,
            playlistUpdateHours = this[playlistHours] ?: d.playlistUpdateHours,
            epgUpdateOnStart = this[epgOnStart] ?: d.epgUpdateOnStart,
            epgUpdateOnPlaylistChange = this[epgOnPlaylist] ?: d.epgUpdateOnPlaylistChange,
            epgDaysToKeep = this[daysToKeep] ?: d.epgDaysToKeep,
            epgPastDays = this[pastDays] ?: d.epgPastDays,
            lastLiveChannelId = this[lastChannel],
            matchTvLanguage = this[matchLanguage] ?: d.matchTvLanguage,
            logoOrder = order(this[logoOrder] ?: this[oldLogoSource]?.let(OLD_LOGO::get), LogoKind.entries) ?: d.logoOrder,
            guideOrder = order(this[guideKinds], GuideKind.entries)
                ?: order(this[guideOrder] ?: this[oldGuideSource]?.let(OLD_GUIDE::get), GuideKind.entries)?.let(::withUserGuides)
                ?: d.guideOrder,
            borrowSiblingGuide = this[borrowSibling] ?: d.borrowSiblingGuide,
            hideHeaderChannels = this[hideHeaders] ?: d.hideHeaderChannels,
            removeNameSymbols = this[nameSymbols] ?: d.removeNameSymbols,
            showDateTime = this[showDateTime] ?: d.showDateTime,
            showPing = this[showPing] ?: d.showPing,
            showRecent = this[showRecent] ?: d.showRecent,
            guideRows = this[guideRows] ?: d.guideRows,
            multiLineGuide = this[multiLineGuide] ?: d.multiLineGuide,
            subtitlesOn = this[subtitlesOn] ?: d.subtitlesOn,
            subtitleLanguage = this[subtitleLanguage],
            backupFolder = this[backupFolder],
            recordingOn = this[recordingOn] ?: d.recordingOn,
            recordTo = org.imdtv.iptvtv.recording.RecStorage.entries.find { it.name == this[recordTo] } ?: d.recordTo,
            recordUsbPath = this[recordUsbPath],
            startEarlyMin = this[startEarly] ?: d.startEarlyMin,
            endLateMin = this[endLate] ?: d.endLateMin,
            keepAwake = this[keepAwake] ?: d.keepAwake,
            smbServer = this[smbServer] ?: "",
            smbShare = this[smbShare] ?: "",
            smbFolder = this[smbFolder] ?: "",
            smbUser = this[smbUser] ?: "",
            smbPassword = org.imdtv.iptvtv.data.security.Secrets.decrypt(this[smbPassword]) ?: "",
        ).clamped()
    }

    private fun MutablePreferences.write(s: AppSettings) {
        this[theme] = s.theme.name
        this[themeColour] = s.themeColour.name
        this[autoplay] = s.autoplayOnScroll
        this[decoder] = s.audioDecoder.name
        this[lastChannelOnStart] = s.lastChannelOnStart
        this[confirmExit] = s.confirmExit
        this[zapLeftRight] = s.zapLeftRight
        this[backToFullScreen] = s.backToFullScreen
        this[showChannelNumbers] = s.showChannelNumbers
        this[themeDark] = s.themeDark
        this[clock24h] = s.clock24h
        this[playerClock] = s.playerClock.name
        this[showDate] = s.showDate
        this[guidesOff] = s.guidesOff
        this[themeLooks] = s.themeLooks.entries.joinToString("|") { "${it.key}=${it.value}" }
        this[liveSubtitlesOn] = s.liveSubtitlesOn
        this[videoAspect] = s.videoAspect.name
        this[mergeChannels] = s.mergeChannels
        this[mergeAcross] = s.mergeAcross
        this[mergeBy] = s.mergeBy.name
        this[vodTwoRows] = s.vodTwoRows
        this[titleSize] = s.titleSize.name
        this[vodTitleSize] = s.vodTitleSize.name
        this[epgTextSize] = s.epgTextSize.name
        this[guideDisplay] = s.guideDisplay.name
        this[vodSort] = s.vodSort.name
        this[parentalOn] = s.parentalOn
        if (s.parentalPin != null) this[parentalPin] = s.parentalPin else remove(parentalPin)
        if (s.parentalWord != null) this[parentalWord] = s.parentalWord else remove(parentalWord)
        this[ageLimit] = s.ageLimit.name
        this[unlockFor] = s.unlockFor.name
        this[catchUpOn] = s.catchUpOn
        this[holdBackOther] = s.holdBackOther
        this[guideTimeoutMin] = s.guideTimeoutMin
        s.favouritesSortBy?.let { this[favouritesSortBy] = it.name } ?: remove(favouritesSortBy)
        this[headerSize] = s.headerSize.name
        this[previewFade] = s.previewFade
        this[showLiveIndicator] = s.showLiveIndicator
        this[autoPlayFirstStream] = s.autoPlayFirstStream
        s.brandLogoPath?.let { this[brandLogoPath] = it } ?: remove(brandLogoPath)
        s.brandLogoUrl?.let { this[brandLogoUrl] = it } ?: remove(brandLogoUrl)
        this[heroLogoScale] = s.heroLogoScale
        this[heroTitleScale] = s.heroTitleScale
        this[brandLogoScale] = s.brandLogoScale
        this[startOnBoot] = s.startOnBoot
        this[interval] = s.epgUpdateIntervalHours
        this[playlistHours] = s.playlistUpdateHours
        this[epgOnStart] = s.epgUpdateOnStart
        this[epgOnPlaylist] = s.epgUpdateOnPlaylistChange
        this[daysToKeep] = s.epgDaysToKeep
        this[pastDays] = s.epgPastDays
        this[matchLanguage] = s.matchTvLanguage
        this[logoOrder] = s.logoOrder.joinToString(",") { it.name }
        this[guideKinds] = s.guideOrder.joinToString(",") { it.name }
        this[borrowSibling] = s.borrowSiblingGuide
        this[hideHeaders] = s.hideHeaderChannels
        this[nameSymbols] = s.removeNameSymbols
        this[showDateTime] = s.showDateTime
        this[showPing] = s.showPing
        this[showRecent] = s.showRecent
        this[subtitlesOn] = s.subtitlesOn
        this[guideRows] = s.guideRows
        this[multiLineGuide] = s.multiLineGuide
        s.subtitleLanguage?.let { this[subtitleLanguage] = it } ?: remove(subtitleLanguage)
        s.backupFolder?.let { this[backupFolder] = it } ?: remove(backupFolder)
        s.lastLiveChannelId?.let { this[lastChannel] = it } ?: remove(lastChannel)
        this[recordingOn] = s.recordingOn
        this[recordTo] = s.recordTo.name
        s.recordUsbPath?.let { this[recordUsbPath] = it } ?: remove(recordUsbPath)
        this[startEarly] = s.startEarlyMin
        this[endLate] = s.endLateMin
        this[keepAwake] = s.keepAwake
        this[smbServer] = s.smbServer
        this[smbShare] = s.smbShare
        this[smbFolder] = s.smbFolder
        this[smbUser] = s.smbUser
        // Re-encrypt only when changed: Secrets.encrypt of the same text gives a new ciphertext each time.
        if (org.imdtv.iptvtv.data.security.Secrets.decrypt(this[smbPassword]).orEmpty() != s.smbPassword) {
            this[smbPassword] = org.imdtv.iptvtv.data.security.Secrets.encrypt(s.smbPassword).orEmpty()
        }
    }

    private companion object {
        val OLD_LOGO = mapOf("PLAYLIST" to "PLAYLIST", "PLAYLIST_FILLED" to "PLAYLIST,OPEN_SOURCE", "TV_LOGOS" to "OPEN_SOURCE",
            "MY_PACK" to "PACK,PLAYLIST", "MY_PACK_FILLED" to "PACK,OPEN_SOURCE,PLAYLIST")
        val OLD_GUIDE = mapOf("PLAYLIST" to "PLAYLIST", "PLAYLIST_FILLED" to "PLAYLIST,OPEN_SOURCE", "EPGSHARE" to "OPEN_SOURCE")

        /** Known names in order, no repeats; null when nothing usable is left (the first priority can't be empty). */
        fun <T : Enum<T>> order(s: String?, all: List<T>): List<T>? =
            s?.split(',')?.mapNotNull { n -> all.find { it.name == n.trim() } }?.distinct()?.takeIf { it.isNotEmpty() }
    }
}
