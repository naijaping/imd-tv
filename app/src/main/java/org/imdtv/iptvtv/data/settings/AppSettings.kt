package org.imdtv.iptvtv.data.settings

import androidx.annotation.StringRes
import org.imdtv.iptvtv.R

/** Layout themes (spec §6A); each sub-stage appends its entry, so the drop-down lists only built themes. */
enum class ThemeId(val label: String, @StringRes val labelRes: Int) {
    CELESTIAL_GLASS("Celestial Glass", R.string.set_a_theme_celestial_glass), CELESTIAL_PLUS("Celestial +", R.string.set_a_theme_celestial_plus),
    CELESTIAL_RETRO("Celestial Retro", R.string.set_a_theme_celestial_retro), LIQUID_GLASS("Liquid Glass", R.string.set_a_theme_liquid_glass),
    XCUBE_180("Xcube 360", R.string.set_a_theme_xcube), GAMESTATION("Gamestation", R.string.set_a_theme_gamestation)
}

/** A stored theme name this build doesn't know (old SKY_GLASS/MIDNIGHT…, or a newer build's theme) reads as the default. */
fun themeIdOf(stored: String?): ThemeId = ThemeId.entries.find { it.name == stored } ?: ThemeId.CELESTIAL_GLASS

/**
 * Background colour for Celestial Glass and Liquid Glass (Appearance › Theme colour). ARGB stops: [glass] is Celestial
 * Glass's dark→light gradient, [liquid] Liquid Glass's backdrop gradient. DEFAULT keeps each theme's own colours.
 */
enum class ThemeColour(val label: String, @StringRes val labelRes: Int, val glass: List<Long>, val liquid: List<Long>) {
    DEFAULT("Default", R.string.set_a_colour_default, listOf(0xFF0A1753, 0xFF1A39A6, 0xFF3459CA), listOf(0xFF0FB5BA, 0xFF7A3CF0)),
    PURPLE("Purple", R.string.set_a_colour_purple, listOf(0xFF22104F, 0xFF4B21A0, 0xFF6E45C8), listOf(0xFF9B3CF0, 0xFFF03C9B)),
    TEAL("Teal", R.string.set_a_colour_teal, listOf(0xFF06323A, 0xFF0D6F7A, 0xFF1C9AA0), listOf(0xFF0FB5BA, 0xFF0F5FBA)),
    GREEN("Green", R.string.set_a_colour_green, listOf(0xFF0A3318, 0xFF1A6E35, 0xFF2F9752), listOf(0xFF2FBF71, 0xFF0F9AB5)),
    RED("Red", R.string.set_a_colour_red, listOf(0xFF420A12, 0xFF8C1A2A, 0xFFB43544), listOf(0xFFF0563C, 0xFFB03CF0)),
    GRAPHITE("Graphite", R.string.set_a_colour_graphite, listOf(0xFF12151C, 0xFF29303B, 0xFF444E5E), listOf(0xFF5A6478, 0xFF262B38)),
}

enum class AudioDecoder { HARDWARE, SOFTWARE }

/** Where a channel logo can come from; the setting is an order of these (first available wins). */
enum class LogoKind(val label: String, @StringRes val labelRes: Int) {
    PLAYLIST("Playlist", R.string.set_a_logo_playlist), OPEN_SOURCE("Open-source logos", R.string.set_a_logo_open_source), PACK("My logo pack", R.string.set_a_logo_pack),
    /** The icon in the channel's guide (XMLTV), e.g. from Your EPG sources (issue #4). */
    EPG("EPG logos", R.string.set_a_logo_epg)
}

/** Where the guide can come from; the setting is an order of these (spec §5.1, reversible since 2026-11-12). */
enum class GuideKind(val label: String, @StringRes val labelRes: Int) {
    PLAYLIST("Playlist", R.string.set_a_guide_playlist), USER("Your EPG sources", R.string.set_a_guide_user), OPEN_SOURCE("Open-source guide", R.string.set_a_guide_open_source)
}

val DEFAULT_LOGO_ORDER = listOf(LogoKind.PLAYLIST, LogoKind.OPEN_SOURCE)
val DEFAULT_GUIDE_ORDER = listOf(GuideKind.PLAYLIST, GuideKind.USER, GuideKind.OPEN_SOURCE)

/**
 * An order saved before "Your EPG sources" was its own kind (2026-11-17): those sources ranked with the playlist's,
 * so the new kind goes right after Playlist (nothing changes), or last when Playlist wasn't in the order.
 */
fun withUserGuides(old: List<GuideKind>): List<GuideKind> = when {
    GuideKind.USER in old -> old
    GuideKind.PLAYLIST in old -> old.toMutableList().apply { add(indexOf(GuideKind.PLAYLIST) + 1, GuideKind.USER) }
    else -> old + GuideKind.USER
}

val EPG_INTERVALS = listOf(6, 12, 24, 48)
val START_EARLY = listOf(0, 1, 2, 5, 10)
val END_LATE = listOf(0, 2, 5, 10, 15, 30)

/** App-wide settings (spec §2.1). Per-playlist data lives in Room. */
/** Guide header size: a smaller header leaves the guide taller. */
/** Clock in the full-screen player. */
enum class PlayerClock(val label: String, @StringRes val labelRes: Int) {
    OFF("Off", R.string.set_a_clock_off), LIVE("On (Live TV only)", R.string.set_a_clock_live), ALL("On (Universal)", R.string.set_a_clock_all)
}

/** Full-screen picture shape: the stream's own, forced 16:9 or 4:3, or zoomed to fill the screen. */
enum class VideoAspect(val label: String, @StringRes val labelRes: Int) {
    AUTO("Auto", R.string.set_a_aspect_auto), WIDE("16:9", R.string.set_a_aspect_wide), STANDARD("4:3", R.string.set_a_aspect_standard), FILL("Fill", R.string.set_a_aspect_fill)
}

/** How a merged channel picks which of its sources to play. */
enum class MergeBy(@StringRes val labelRes: Int) { RESOLUTION(R.string.set_a_merge_by_resolution), LATENCY(R.string.set_a_merge_by_latency) }

enum class HeaderSize(val label: String, @StringRes val labelRes: Int) {
    LARGE("Large", R.string.set_a_size_large), MEDIUM("Medium", R.string.set_a_size_medium), SMALL("Small", R.string.set_a_size_small)
}

/** Scales programme, channel and movie/series titles (Appearance › Title size). */
enum class TitleSize(val label: String, @StringRes val labelRes: Int, val scale: Float) {
    LARGE("Large", R.string.set_a_size_large, 1f), MEDIUM("Medium", R.string.set_a_size_medium, .85f), SMALL("Small", R.string.set_a_size_small, .72f)
}

/** Scales all TV guide text, on top of Title size (Appearance › Live TV › Text size); short rows still cap it to fit. */
enum class EpgTextSize(@StringRes val labelRes: Int, val scale: Float) {
    XLARGE(R.string.set_a_size_xlarge, 1.2f), LARGE(R.string.set_a_size_large, 1.1f), MEDIUM(R.string.set_a_size_medium, 1f), SMALL(R.string.set_a_size_small, .88f)
}

/** Settings › Channels › Channel display: what the TV guide's channel column shows. */
enum class GuideChannelDisplay(@StringRes val labelRes: Int) { NAME(R.string.set_a_guide_display_name), LOGO(R.string.set_a_guide_display_logo), BOTH(R.string.set_a_guide_display_both) }

/** Settings › Movies & Series › Sort by: one order for every movie and series list (issue #11). */
enum class VodSort(@StringRes val labelRes: Int) { PLAYLIST(R.string.set_b_vod_sort_playlist), AZ(R.string.set_b_vod_sort_az), ADDED(R.string.set_b_vod_sort_added) }

data class AppSettings(
    val theme: ThemeId = ThemeId.CELESTIAL_GLASS,
    val autoplayOnScroll: Boolean = false,
    val audioDecoder: AudioDecoder = AudioDecoder.SOFTWARE,
    val lastChannelOnStart: Boolean = true,
    val confirmExit: Boolean = true,
    val startOnBoot: Boolean = false,
    val epgUpdateIntervalHours: Int = 12,
    val epgUpdateOnStart: Boolean = false,
    val epgUpdateOnPlaylistChange: Boolean = true,
    val epgDaysToKeep: Int = 7,
    val epgPastDays: Int = 1,
    val lastLiveChannelId: Long? = null,
    val matchTvLanguage: Boolean = false,
    val logoOrder: List<LogoKind> = DEFAULT_LOGO_ORDER,
    val guideOrder: List<GuideKind> = DEFAULT_GUIDE_ORDER,
    val borrowSiblingGuide: Boolean = true,
    val hideHeaderChannels: Boolean = false,
    /** Channel names lose superscript / circled letters and symbols (ᴿᴬᵂ, ⓋⒾⓅ, ◉). */
    val removeNameSymbols: Boolean = false,
    val themeColour: ThemeColour = ThemeColour.DEFAULT,
    /** Celestial Glass / Liquid Glass: near-black versions of the theme colour. */
    val themeDark: Boolean = false,
    /** The clock at the top of the screen. */
    val showDateTime: Boolean = true,
    /** The date beside that clock. */
    val showDate: Boolean = true,
    val showPing: Boolean = false,
    /** The "Recently watched" category in the TV guide. */
    val showRecent: Boolean = true,
    /** Channel rows the Celestial/Liquid Glass guide shows: one of [guideRowChoices] for the header size. */
    val guideRows: Int = 5,
    /** Channel names and programme titles may wrap onto 2 lines in the guide grid. */
    val multiLineGuide: Boolean = false,
    /** Movies and series start with subtitles in [subtitleLanguage] when the file has that language. */
    val subtitlesOn: Boolean = false,
    /** ISO 639-1 code; null = the TV's language. */
    val subtitleLanguage: String? = null,
    /** Folder backups are saved to (absolute path; needs all files access). Null = Downloads. Not backed up: per box. */
    val backupFolder: String? = null,
    val recordingOn: Boolean = false,
    val recordTo: org.imdtv.iptvtv.recording.RecStorage = org.imdtv.iptvtv.recording.RecStorage.INTERNAL,
    /** The chosen USB drive's recordings folder; null = the first drive found. Not backed up: per box. */
    val recordUsbPath: String? = null,
    val startEarlyMin: Int = 2,
    val endLateMin: Int = 5,
    val keepAwake: Boolean = true,
    val smbServer: String = "",
    val smbShare: String = "",
    val smbFolder: String = "",
    val smbUser: String = "",
    /** Stored encrypted (Secrets); never backed up. */
    val smbPassword: String = "",
    /** Live full screen: Left/Right zaps (Left = previous) instead of Up/Down. */
    val zapLeftRight: Boolean = false,
    /** Back in the live TV guide opens the playing channel full screen instead of going to the menu. */
    val backToFullScreen: Boolean = false,
    /** Holding Back in the live TV guide does what a tap doesn't (full screen or the menu). */
    val holdBackOther: Boolean = true,
    /** Minutes without a key press in the live TV guide before the playing channel goes full screen; 0 = never. */
    val guideTimeoutMin: Int = 0,
    /** Sort by for Favourites (categories keep theirs in the database); null = not picked yet (see ChannelSort). */
    val favouritesSortBy: org.imdtv.iptvtv.data.db.SortBy? = null,
    val showChannelNumbers: Boolean = true,
    /** 13:05 instead of 1.05pm. */
    val clock24h: Boolean = true,
    val playerClock: PlayerClock = PlayerClock.OFF,
    val liveSubtitlesOn: Boolean = false,
    val videoAspect: VideoAspect = VideoAspect.AUTO,
    /** Settings › Channels › Merge channels, and its Merge options (each playlist also has its own switch). */
    val mergeChannels: Boolean = false,
    val mergeAcross: Boolean = false,
    val mergeBy: MergeBy = MergeBy.RESOLUTION,
    val vodTwoRows: Boolean = false,
    val titleSize: TitleSize = TitleSize.LARGE,
    val vodTitleSize: TitleSize = TitleSize.LARGE,
    val epgTextSize: EpgTextSize = EpgTextSize.MEDIUM,
    val guideDisplay: GuideChannelDisplay = GuideChannelDisplay.NAME,
    val vodSort: VodSort = VodSort.PLAYLIST,
    /** Parental controls (App behaviour); [parentalPin] and [parentalWord] are hashes (Pin.hash), never the PIN itself. */
    val parentalOn: Boolean = false,
    val parentalPin: String? = null,
    val parentalWord: String? = null,
    val ageLimit: org.imdtv.iptvtv.data.parental.AgeLimit = org.imdtv.iptvtv.data.parental.AgeLimit.A12,
    val unlockFor: org.imdtv.iptvtv.data.parental.UnlockFor = org.imdtv.iptvtv.data.parental.UnlockFor.APP,
    /** Playback › Catch-up: off hides every catch-up control, even on channels that have it. */
    val catchUpOn: Boolean = true,
    val headerSize: HeaderSize = HeaderSize.LARGE,
    val previewFade: Boolean = true,
    /** The red-dot LIVE badge at the top right of the guide's preview. */
    val showLiveIndicator: Boolean = true,
    /** Celestial/Liquid Glass guide, top left: a copy of the chosen image in app storage (BrandLogo); null = the app's logo. Not backed up: per box. */
    val brandLogoPath: String? = null,
    /** The address [brandLogoPath] was downloaded from (Logo image URL); null when it came from a file or is the default. */
    val brandLogoUrl: String? = null,
    /** Appearance › Live TV › Header channel logo / Header title size: multiplies the guide header's logo / title; one of [HERO_SCALES]. */
    val heroLogoScale: Float = 1f,
    val heroTitleScale: Float = 1f,
    /** Other themes' [look]s by theme name, so a glass theme's colour/header/rows don't carry to another theme. */
    val themeLooks: Map<String, String> = emptyMap(),
    /** Open-source guide files (URLs) turned off in EPG sources: kept in the list, never imported or matched. */
    val guidesOff: Set<String> = emptySet(),
    /** Settings › Playlists › Update playlists every: each playlist (media servers too) is synced again once this old. */
    val playlistUpdateHours: Int = 24,
) {
    /** The per-theme appearance: colour, mode, guide rows, header size, preview fade. */
    fun look() = listOf(themeColour.name, themeDark, guideRows, headerSize.name, previewFade).joinToString(";")

    /** Switch theme: stash this theme's look, bring back [t]'s (defaults the first time). */
    fun withTheme(t: ThemeId): AppSettings {
        if (t == theme) return this
        val looks = themeLooks + (theme.name to look())
        val v = looks[t.name]?.split(';').orEmpty()
        val d = AppSettings()
        return copy(theme = t, themeLooks = looks - t.name,
            themeColour = ThemeColour.entries.find { it.name == v.getOrNull(0) } ?: d.themeColour,
            themeDark = v.getOrNull(1)?.toBooleanStrictOrNull() ?: d.themeDark,
            guideRows = v.getOrNull(2)?.toIntOrNull() ?: d.guideRows,
            headerSize = HeaderSize.entries.find { it.name == v.getOrNull(3) } ?: d.headerSize,
            previewFade = v.getOrNull(4)?.toBooleanStrictOrNull() ?: d.previewFade,
        ).clamped()
    }

    /** Header size change: Channels in guide follows, so the guide keeps about the same row height. */
    fun withHeader(h: HeaderSize): AppSettings {
        val liquid = theme == ThemeId.LIQUID_GLASS
        val n = Math.round(guideRows / headerRowFactor(headerSize, liquid) * headerRowFactor(h, liquid))
        val choices = guideRowChoices(h, liquid)
        return copy(headerSize = h, guideRows = n.coerceIn(choices.first(), choices.last()))
    }

    /** Appearance › Reset: every Appearance setting (all themes' looks too) back to its default. */
    fun resetAppearance() = AppSettings().let { d ->
        copy(theme = d.theme, themeColour = d.themeColour, themeDark = d.themeDark, guideRows = d.guideRows, headerSize = d.headerSize,
            previewFade = d.previewFade, themeLooks = emptyMap(), multiLineGuide = d.multiLineGuide, showDateTime = d.showDateTime,
            showDate = d.showDate, clock24h = d.clock24h, playerClock = d.playerClock, epgTextSize = d.epgTextSize,
            showLiveIndicator = d.showLiveIndicator, heroLogoScale = d.heroLogoScale, heroTitleScale = d.heroTitleScale)
    }

    fun clamped() = copy(
        epgUpdateIntervalHours = epgUpdateIntervalHours.takeIf { it in EPG_INTERVALS } ?: 12,
        playlistUpdateHours = playlistUpdateHours.takeIf { it in EPG_INTERVALS } ?: 24,
        epgDaysToKeep = epgDaysToKeep.coerceIn(1, 14),
        epgPastDays = epgPastDays.coerceIn(0, 7),
        startEarlyMin = startEarlyMin.takeIf { it in START_EARLY } ?: 2,
        endLateMin = endLateMin.takeIf { it in END_LATE } ?: 5,
        guideRows = guideRows.coerceIn(GUIDE_ROWS.first(), 15),
        heroLogoScale = nearestHeroScale(heroLogoScale),
        heroTitleScale = nearestHeroScale(heroTitleScale),
    )
}

/** Header channel logo / Header title size choices (×). */
val HERO_SCALES = listOf(.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

fun nearestHeroScale(f: Float): Float = HERO_SCALES.minBy { kotlin.math.abs(it - f) }

/** Channels in guide choices with the Large header. */
val GUIDE_ROWS = listOf(5, 6, 7, 8, 9)

/**
 * How many more rows a header size fits than Large, on a 540dp-tall TV (1080p): the grid starts [shift] higher
 * (LiveGuideScreen: grid top 306dp Glass / 334dp Liquid, 12dp foot).
 */
fun headerRowFactor(h: HeaderSize, liquid: Boolean): Float {
    val area = 540f - (if (liquid) 334f else 306f) - 12f
    return (area + headerShiftDp(h)) / area
}

fun headerShiftDp(h: HeaderSize) = when (h) { HeaderSize.LARGE -> 0f; HeaderSize.MEDIUM -> 70f; HeaderSize.SMALL -> 130f }

/** Large's 5–9 rows, scaled to the header size (e.g. Small in Celestial Glass: 8–14). */
fun guideRowChoices(h: HeaderSize, liquid: Boolean): List<Int> =
    headerRowFactor(h, liquid).let { f -> (Math.round(GUIDE_ROWS.first() * f)..Math.round(GUIDE_ROWS.last() * f)).toList() }

/** The Subtitle language choices besides "TV language" (ISO 639-1); shown sorted by display name. */
val SUBTITLE_LANGUAGES = listOf("ar", "bg", "cs", "da", "de", "el", "en", "es", "et", "fa", "fi", "fr", "he", "hi", "hr",
    "hu", "id", "it", "ja", "ko", "nl", "no", "pl", "pt", "ro", "ru", "sk", "sv", "th", "tr")
