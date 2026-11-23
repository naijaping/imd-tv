package org.imdtv.iptvtv.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    // Own scope, not runTest's: DataStore keeps a coroutine alive that runTest would wait on.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs by lazy { PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("t.preferences_pb") } }
    private val repo by lazy { SettingsRepository(prefs) }

    @After fun tearDown() = scope.cancel()

    @Test fun defaultsMatchSpec() = runTest { assertEquals(AppSettings(), repo.settings.first()) }

    @Test fun updateRoundTripsEveryField() = runTest {
        val s = AppSettings(ThemeId.CELESTIAL_GLASS, true, AudioDecoder.HARDWARE, false, false, true, 24, true, false, 14, 0, 42L, true)
            .copy(subtitlesOn = true, subtitleLanguage = "fr", zapLeftRight = true, backToFullScreen = true, showChannelNumbers = false, themeDark = true, clock24h = false, playerClock = PlayerClock.LIVE, liveSubtitlesOn = true, headerSize = HeaderSize.SMALL, previewFade = false, showLiveIndicator = false, brandLogoPath = "/data/brand_logo/logo_1.png", brandLogoUrl = "https://e.com/l.png", heroLogoScale = 1.5f, heroTitleScale = .75f, vodTwoRows = true,
                showDate = false, themeLooks = mapOf("CELESTIAL_GLASS" to "TEAL;true;7;MEDIUM;false"), guidesOff = setOf("https://g/ie1.xml.gz"))
        repo.update { s }
        assertEquals(s, repo.settings.first())
    }

    @Test fun oldPlayerClockOnReadsUniversal() = runTest {
        prefs.edit { it[androidx.datastore.preferences.core.booleanPreferencesKey("player_clock")] = true }
        assertEquals(PlayerClock.ALL, repo.settings.first().playerClock)
    }

    @Test fun eachThemeKeepsItsOwnLook() {
        val liquid = AppSettings().copy(headerSize = HeaderSize.MEDIUM, themeColour = ThemeColour.TEAL).withTheme(ThemeId.LIQUID_GLASS)
        assertEquals(HeaderSize.LARGE, liquid.headerSize) // Celestial's Medium doesn't carry over
        assertEquals(ThemeColour.DEFAULT, liquid.themeColour)
        val back = liquid.copy(guideRows = 8).withTheme(ThemeId.CELESTIAL_GLASS)
        assertEquals(HeaderSize.MEDIUM, back.headerSize)
        assertEquals(ThemeColour.TEAL, back.themeColour)
        assertEquals(5, back.guideRows)
        assertEquals(8, back.withTheme(ThemeId.LIQUID_GLASS).guideRows)
        assertEquals(AppSettings().copy(confirmExit = false), back.copy(confirmExit = false).resetAppearance())
    }

    @Test fun channelsInGuideFollowsHeaderSize() {
        val med = AppSettings().withHeader(HeaderSize.MEDIUM)
        assertEquals(7, med.guideRows)                        // 5 at Large ≈ 6.6 at Medium on a 1080p TV
        assertEquals(5, med.withHeader(HeaderSize.LARGE).guideRows)
        assertEquals((8..14).toList(), guideRowChoices(HeaderSize.SMALL, liquid = false))
        assertEquals(14, AppSettings(guideRows = 9).withHeader(HeaderSize.SMALL).guideRows)
    }

    @Test fun subtitleLanguageClearsToNull() = runTest {
        repo.update { it.copy(subtitleLanguage = "fr") }
        repo.update { it.copy(subtitleLanguage = null) }
        assertEquals(null, repo.settings.first().subtitleLanguage)
    }

    @Test fun unknownHeaderSizeFallsBackToLarge() = runTest {
        prefs.edit { it[stringPreferencesKey("header_size")] = "HUGE" }
        assertEquals(HeaderSize.LARGE, repo.settings.first().headerSize)
    }

    @Test fun themeRoundTrips() = runTest {
        repo.update { it.copy(theme = ThemeId.CELESTIAL_GLASS) }
        assertEquals(ThemeId.CELESTIAL_GLASS, repo.settings.first().theme)
        prefs.edit { it[stringPreferencesKey("theme")] = "MIDNIGHT" }
        assertEquals(ThemeId.CELESTIAL_GLASS, repo.settings.first().theme)
    }

    @Test fun themeColourRoundTripsAndUnknownReadsDefault() = runTest {
        repo.update { it.copy(themeColour = ThemeColour.TEAL) }
        assertEquals(ThemeColour.TEAL, repo.settings.first().themeColour)
        prefs.edit { it[stringPreferencesKey("theme_colour")] = "nonsense" }
        assertEquals(ThemeColour.DEFAULT, repo.settings.first().themeColour)
    }

    @Test fun outOfRangeValuesAreClamped() = runTest {
        repo.update { it.copy(epgDaysToKeep = 30, epgPastDays = -1, epgUpdateIntervalHours = 5) }
        val s = repo.settings.first()
        assertEquals(14, s.epgDaysToKeep); assertEquals(0, s.epgPastDays); assertEquals(12, s.epgUpdateIntervalHours)
    }

    @Test fun clearingLastChannel() = runTest {
        repo.update { it.copy(lastLiveChannelId = 7) }; repo.update { it.copy(lastLiveChannelId = null) }
        assertNull(repo.settings.first().lastLiveChannelId)
    }

    @Test fun logoAndGuideOrdersRoundTrip() = runTest {
        repo.update { it.copy(logoOrder = listOf(LogoKind.OPEN_SOURCE, LogoKind.PLAYLIST), guideOrder = listOf(GuideKind.OPEN_SOURCE, GuideKind.PLAYLIST), borrowSiblingGuide = false) }
        val s = repo.settings.first()
        assertEquals(listOf(LogoKind.OPEN_SOURCE, LogoKind.PLAYLIST), s.logoOrder)
        assertEquals(listOf(GuideKind.OPEN_SOURCE, GuideKind.PLAYLIST), s.guideOrder); assertFalse(s.borrowSiblingGuide)
    }

    @Test fun defaultsArePlaylistUserThenOpenSource() = runTest {
        assertEquals(listOf(LogoKind.PLAYLIST, LogoKind.OPEN_SOURCE), repo.settings.first().logoOrder)
        assertEquals(listOf(GuideKind.PLAYLIST, GuideKind.USER, GuideKind.OPEN_SOURCE), repo.settings.first().guideOrder)
    }

    /** Settings saved by the single-choice options before 2026-11-12 keep their meaning. */
    @Test fun oldChoicesMigrate() = runTest {
        prefs.edit { it[stringPreferencesKey("logo_source")] = "MY_PACK_FILLED"; it[stringPreferencesKey("guide_source")] = "EPGSHARE" }
        assertEquals(listOf(LogoKind.PACK, LogoKind.OPEN_SOURCE, LogoKind.PLAYLIST), repo.settings.first().logoOrder)
        assertEquals(listOf(GuideKind.OPEN_SOURCE, GuideKind.USER), repo.settings.first().guideOrder)
        prefs.edit { it[stringPreferencesKey("logo_source")] = "TV_LOGOS"; it[stringPreferencesKey("guide_source")] = "PLAYLIST" }
        assertEquals(listOf(LogoKind.OPEN_SOURCE), repo.settings.first().logoOrder)
        assertEquals(listOf(GuideKind.PLAYLIST, GuideKind.USER), repo.settings.first().guideOrder)
    }

    @Test fun badOrdersFallBack() = runTest {
        prefs.edit { it[stringPreferencesKey("logo_order")] = "BOGUS,,PLAYLIST,PLAYLIST"; it[stringPreferencesKey("guide_order")] = "" }
        assertEquals(listOf(LogoKind.PLAYLIST), repo.settings.first().logoOrder)
        assertEquals(DEFAULT_GUIDE_ORDER, repo.settings.first().guideOrder)
    }

    private suspend fun oldGuideOrder(v: String) = prefs.edit { it[stringPreferencesKey("guide_order")] = v }.let { repo.settings.first().guideOrder }

    @Test fun oldGuideOrdersGainUserSources() = runTest {
        val P = GuideKind.PLAYLIST; val U = GuideKind.USER; val O = GuideKind.OPEN_SOURCE
        assertEquals(listOf(P, U, O), oldGuideOrder("PLAYLIST,OPEN_SOURCE"))
        assertEquals(listOf(O, P, U), oldGuideOrder("OPEN_SOURCE,PLAYLIST"))
        assertEquals(listOf(O, U), oldGuideOrder("OPEN_SOURCE"))
    }

    @Test fun leavingUserSourcesOutIsKept() = runTest {
        val order = listOf(GuideKind.PLAYLIST, GuideKind.OPEN_SOURCE)
        repo.update { it.copy(guideOrder = order) }
        assertEquals(order, repo.settings.first().guideOrder)
    }

    @Test fun guideSettingsRoundTripAndClamp() = runTest {
        assertEquals(5, repo.settings.first().guideRows); assertFalse(repo.settings.first().multiLineGuide)
        repo.update { it.copy(guideRows = 9, multiLineGuide = true) }
        assertEquals(9, repo.settings.first().guideRows); assertEquals(true, repo.settings.first().multiLineGuide)
        assertEquals(10, AppSettings(guideRows = 10).clamped().guideRows) // valid with a smaller header
        assertEquals(15, AppSettings(guideRows = 40).clamped().guideRows)
    }

    @Test fun hideHeaderChannelsRoundTrip() = runTest {
        assertFalse(repo.settings.first().hideHeaderChannels)
        repo.update { it.copy(hideHeaderChannels = true) }
        assertEquals(true, repo.settings.first().hideHeaderChannels)
    }

    @Test fun recordingSettingsRoundTripAndClamp() = runTest {
        val s = AppSettings(recordingOn = true, recordTo = org.imdtv.iptvtv.recording.RecStorage.SMB, recordUsbPath = "/storage/AB/x",
            startEarlyMin = 5, endLateMin = 30, keepAwake = false, smbServer = "nas", smbShare = "tv", smbFolder = "Rec", smbUser = "me", smbPassword = "pw")
        repo.update { s }
        assertEquals(s, repo.settings.first())
        repo.update { it.copy(startEarlyMin = 7, endLateMin = 99) }
        repo.settings.first().let { assertEquals(2, it.startEarlyMin); assertEquals(5, it.endLateMin) }
    }
}
