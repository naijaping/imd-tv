package org.imdtv.iptvtv.data.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.imdtv.iptvtv.data.db.AppDatabase
import org.imdtv.iptvtv.data.db.FavouriteEntity
import org.imdtv.iptvtv.data.db.ProgressEntity
import org.imdtv.iptvtv.data.db.ProviderEntity
import org.imdtv.iptvtv.data.db.ProviderType
import org.imdtv.iptvtv.data.db.inMemoryDb
import org.imdtv.iptvtv.data.model.Kind
import org.imdtv.iptvtv.data.model.StreamFormat
import org.imdtv.iptvtv.data.repo.CatalogueSync
import org.imdtv.iptvtv.data.repo.FakeSource
import org.imdtv.iptvtv.data.repo.ProviderRepository
import org.imdtv.iptvtv.data.repo.channel
import org.imdtv.iptvtv.data.repo.live
import org.imdtv.iptvtv.data.settings.HeaderSize
import org.imdtv.iptvtv.data.settings.PlayerClock
import org.imdtv.iptvtv.data.settings.SettingsRepository
import org.imdtv.iptvtv.data.settings.ThemeId

@RunWith(RobolectricTestRunner::class)
class BackupTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dbs = mutableListOf<AppDatabase>()

    @After fun tearDown() { dbs.forEach { it.close() } }

    private val catalogue = FakeSource(
        movieList = listOf(org.imdtv.iptvtv.data.model.Movie("m1", null, "Film", null, null, null, "http://h/movie/m1.mp4")),
        seriesList = listOf(org.imdtv.iptvtv.data.model.Series("s1", null, "Show", null, null, null)),
        liveCats = listOf(live("5", "UK| News"), live("6", "UK| Sport")),
        channels = listOf(channel("1", "BBC One", cat = "5"), channel("2", "Sky News", cat = "5"), channel("3", "Sky Sports", cat = "6")),
        epg = listOf("http://h/xmltv.php?username=alice-user&password=s3cret-pw"),
    )

    private class Env(val db: AppDatabase, val providers: ProviderRepository, val settings: SettingsRepository, val backup: BackupRepository)

    private fun TestScope.env(source: FakeSource = catalogue): Env {
        val db = inMemoryDb().also { dbs += it }
        val prefs = PreferenceDataStoreFactory.create(scope = backgroundScope) { java.io.File(tmp.newFolder(), "s.preferences_pb") }
        val providers = ProviderRepository(db, CatalogueSync(db), { source }, prefs)
        val settings = SettingsRepository(prefs)
        return Env(db, providers, settings, BackupRepository(db, settings, providers))
    }

    private fun xtream() = ProviderEntity(
        name = "Main", type = ProviderType.XTREAM, url = "http://h", username = "alice-user", password = "s3cret-pw",
        userAgent = "UA", streamFormat = StreamFormat.TS, epgOverrideUrls = emptyList(), lastSync = null, nameStrip = "UK|",
    )

    /** Provider synced, News hidden and sorted after Sport, a group, a favourite, a user EPG source, custom settings. */
    private suspend fun Env.customise(): Long {
        val p = providers.add(xtream())
        providers.syncNow(p)
        val cats = db.categoryDao().all(p, Kind.LIVE).associateBy { it.remoteId }
        db.categoryDao().update(cats.getValue("5").copy(hidden = true, sortOrder = 2))
        db.categoryDao().update(cats.getValue("6").copy(sortOrder = 1, pinned = true))
        val ch = db.channelDao().byProvider(p).associateBy { it.remoteId }
        val g = db.customGroupDao().create(p, "Mine")
        db.customGroupDao().addChannel(g, ch.getValue("3").id)
        db.customGroupDao().addChannel(g, ch.getValue("1").id)
        db.customGroupDao().setPlacement(g, org.imdtv.iptvtv.data.db.GroupPlacement.TOP)
        db.favouriteDao().add(FavouriteEntity(p, Kind.LIVE, ch.getValue("2").id, 1))
        val movie = db.movieDao().byProvider(p).single().id
        val show = db.seriesDao().byProvider(p).single().id
        db.progressDao().upsert(ProgressEntity(p, Kind.MOVIE, movie, 90_000, 7_200_000, 10, false))
        db.progressDao().upsert(ProgressEntity(p, Kind.SERIES, show, 0, 3_000_000, 20, true, "e1"))
        db.progressDao().upsert(ProgressEntity(p, Kind.SERIES, show, 45_000, 3_000_000, 30, false, "e2"))
        providers.addEpgSource(p, "http://guide/uk.xml", "UK guide")
        db.providerDao().setCategoryStrip(p, "UK|")
        db.epgDao().sources(p).first().let { db.epgDao().setOffset(it.id, 60) }
        settings.update { it.copy(theme = ThemeId.LIQUID_GLASS, confirmExit = false, epgDaysToKeep = 3, zapLeftRight = true, backToFullScreen = true, showChannelNumbers = false, themeDark = true, clock24h = false, playerClock = PlayerClock.LIVE, liveSubtitlesOn = true, headerSize = HeaderSize.SMALL, previewFade = false, vodTwoRows = true,
                showDate = false, themeLooks = mapOf("CELESTIAL_GLASS" to "TEAL;true;7;MEDIUM;false"), guidesOff = setOf("https://g/ie1.xml.gz")) }
        return p
    }

    private suspend fun Env.snapshot(p: Long): List<Any> {
        val ch = db.channelDao().byProvider(p).associate { it.id to it.remoteId }
        val groupId = db.customGroupDao().observe(p).first().single().id
        return listOf(
            db.categoryDao().all(p, Kind.LIVE).map { Triple(it.remoteId, it.hidden, it.sortOrder to it.pinned) },
            db.customGroupDao().observe(p).first().map { it.name to it.placement },
            db.customGroupDao().observeChannels(groupId).first().map { it.remoteId },
            db.favouriteDao().list(p, Kind.LIVE).map { ch[it.itemId] },
            db.epgDao().sources(p).map { Triple(it.url, it.name, it.offsetMinutes) },
            db.progressDao().all(p).map { r ->
                val remote = if (r.kind == Kind.MOVIE) db.movieDao().byProvider(p).single { it.id == r.itemId }.remoteId
                else db.seriesDao().byProvider(p).single { it.id == r.itemId }.remoteId
                listOf(r.kind, remote, r.episode, r.positionMs, r.durationMs, r.updatedAt, r.completed)
            },
            db.providerDao().get(p)!!.nameStrip,
            db.providerDao().get(p)!!.categoryStrip,
            settings.settings.first().copy(lastLiveChannelId = null),
        )
    }

    @Test fun roundTripKeepsEverything() = runTest {
        val a = env()
        val p = a.customise()
        a.settings.update { it.copy(lastLiveChannelId = 99, subtitlesOn = true, subtitleLanguage = "fr") }
        val json = encodeBackup(a.backup.create())

        val b = env()
        val decoded = decodeBackup(json)
        val result = b.backup.restorePlaylist(decoded.playlists.single(), "alice-user", "s3cret-pw") {}
        b.backup.restoreSettings(decoded.settings)

        val q = b.db.providerDao().all().single()
        assertEquals("alice-user", q.username)
        assertEquals(a.snapshot(p), b.snapshot(q.id))
        assertEquals(null, b.settings.settings.first().lastLiveChannelId)
        assertEquals(true, b.settings.settings.first().subtitlesOn)
        assertEquals("fr", b.settings.settings.first().subtitleLanguage)
        assertEquals(0, result.skipped)
        assertTrue(result.applied > 0)
    }

    @Test fun progressMapsToNewLocalIds() = runTest {
        val a = env()
        a.customise()
        val decoded = decodeBackup(encodeBackup(a.backup.create()))
        assertEquals(3, decoded.playlists.single().progress.size)

        val b = env()
        // A throwaway provider first, so the restored rows get different local ids from the original ones.
        b.providers.add(xtream().copy(name = "Other")).also { b.providers.syncNow(it) }
        b.backup.restorePlaylist(decoded.playlists.single(), "alice-user", "s3cret-pw") {}
        val q = b.db.providerDao().all().last().id
        val movie = b.db.movieDao().byProvider(q).single().id
        val show = b.db.seriesDao().byProvider(q).single().id
        val rows = b.db.progressDao().all(q).map { Triple(it.kind, it.itemId, it.episode) }.toSet()
        assertEquals(setOf(Triple(Kind.MOVIE, movie, ""), Triple(Kind.SERIES, show, "e1"), Triple(Kind.SERIES, show, "e2")), rows)
        assertTrue(b.db.progressDao().all(q).single { it.episode == "e2" }.let { it.positionMs == 45_000L && !it.completed })
    }

    @Test fun oldBackupWithoutProgressStillDecodes() {
        val json = """{"version": 1, "playlists": [{"name": "P", "type": "XTREAM", "url": "http://h", "userAgent": null, "streamFormat": "TS",
            "nameStrip": "", "epgSources": [], "categories": [], "groups": [], "favourites": []}], "settings": {}}"""
        assertEquals(emptyList<BProgress>(), decodeBackup(json).playlists.single().progress)
    }

    @Test fun channelOrderRoundTrips() = runTest {
        val a = env()
        val p = a.providers.add(xtream()).also { a.providers.syncNow(it) }
        val ch = a.db.channelDao().byProvider(p).associateBy { it.remoteId }
        a.db.channelDao().setSortOrder(ch.getValue("2").id, 0)
        a.db.channelDao().setSortOrder(ch.getValue("1").id, 1)
        val decoded = decodeBackup(encodeBackup(a.backup.create()))
        assertEquals(listOf(BChannelOrder("5", listOf("2", "1"))), decoded.playlists.single().channelOrder)

        val b = env()
        b.providers.add(xtream().copy(name = "Other")).also { b.providers.syncNow(it) }
        b.backup.restorePlaylist(decoded.playlists.single(), "alice-user", "s3cret-pw") {}
        val q = b.db.providerDao().all().last().id
        val cat = b.db.categoryDao().all(q, Kind.LIVE).first { it.remoteId == "5" }
        assertEquals(listOf("2", "1"), b.db.channelDao().allByCategory(cat.id).map { it.remoteId })
        assertEquals(listOf(0, 1), b.db.channelDao().allByCategory(cat.id).map { it.sortOrder })
        val sport = b.db.categoryDao().all(q, Kind.LIVE).first { it.remoteId == "6" }
        assertEquals(listOf<Int?>(null), b.db.channelDao().allByCategory(sport.id).map { it.sortOrder })
    }

    @Test fun oldBackupWithoutChannelOrderStillDecodes() {
        val json = """{"version": 1, "playlists": [{"name": "P", "type": "XTREAM", "url": "http://h", "userAgent": null, "streamFormat": "TS",
            "nameStrip": "", "epgSources": [], "categories": [], "groups": [], "favourites": []}], "settings": {}}"""
        assertEquals(emptyList<BChannelOrder>(), decodeBackup(json).playlists.single().channelOrder)
    }

    @Test fun restoreOntoSameProviderUpdatesInPlace() = runTest {
        val a = env()
        val p = a.customise()
        val backup = a.backup.create()
        a.db.categoryDao().setHidden(a.db.categoryDao().all(p, Kind.LIVE).first { it.remoteId == "5" }.id, false)
        a.backup.restorePlaylist(backup.playlists.single(), "alice-user", "s3cret-pw") {}
        assertEquals(1, a.db.providerDao().all().size)
        assertTrue(a.db.categoryDao().all(p, Kind.LIVE).first { it.remoteId == "5" }.hidden)
        assertEquals(1, a.db.customGroupDao().observe(p).first().size)
    }

    @Test fun noCredentialsInJson() = runTest {
        val a = env()
        a.customise()
        val json = encodeBackup(a.backup.create())
        assertFalse(json, "alice-user" in json)
        assertFalse(json, "s3cret-pw" in json)
        assertFalse(json, "\"username\"" in json)
        assertFalse(json, "\"password\"" in json)
    }

    @Test fun m3uLoginStaysOutOfTheBackupAndComesBackOnRestore() = runTest {
        val m3u = "http://h/get.php?username=alice-user&password=s3cret-pw&type=m3u_plus"
        val a = env()
        a.providers.add(xtream().copy(type = ProviderType.M3U, url = m3u, username = null, password = null))
        val json = encodeBackup(a.backup.create())
        assertFalse(json, "alice-user" in json)
        assertFalse(json, "s3cret-pw" in json)
        val pl = a.backup.create().playlists.single()
        assertTrue(org.imdtv.iptvtv.ui.settings.needsLogin(pl))
        val b = env()
        b.backup.restorePlaylist(pl, "alice-user", "s3cret-pw") {}
        assertEquals(m3u, b.db.providerDao().all().single().url)
    }

    @Test fun newerVersionRejected() {
        val e = assertThrows(BackupException::class.java) {
            decodeBackup("""{"version": ${BACKUP_VERSION + 1}, "playlists": [], "settings": {}}""")
        }
        assertEquals("This backup was made by a newer version of the app", e.message)
    }

    @Test fun garbageRejected() {
        val e = assertThrows(BackupException::class.java) { decodeBackup("not json {") }
        assertEquals("Not a readable backup file", e.message)
        assertThrows(BackupException::class.java) { decodeBackup("""{"hello": 1}""") }
    }

    @Test fun restoreSkipsMissingRemoteIds() = runTest {
        val b = env()
        val playlist = BPlaylist(
            name = "Other", type = "XTREAM", url = "http://h", userAgent = null, streamFormat = "TS", nameStrip = "",
            epgSources = emptyList(),
            categories = listOf(BCategory("LIVE", "gone1", hidden = true, sortOrder = 1, pinned = false),
                BCategory("LIVE", "gone2", hidden = false, sortOrder = 2, pinned = true)),
            groups = listOf(BGroup("G", 0, listOf("gone3"))),
            favourites = listOf(BFavourite("LIVE", "gone4")),
        )
        val result = b.backup.restorePlaylist(playlist, "u", "p") {}
        assertEquals(RestoreResult(applied = 0, skipped = 4), result)
        val p = b.db.providerDao().all().single().id
        assertTrue(b.db.categoryDao().all(p, Kind.LIVE).none { it.hidden })
    }

    @Test fun guideSettingsSurviveBackup() {
        val s = org.imdtv.iptvtv.data.settings.AppSettings(guideRows = 7, multiLineGuide = true)
        val back = decodeBackup(encodeBackup(Backup(playlists = emptyList(), settings = BSettings.of(s)))).settings.toAppSettings(null)
        assertEquals(7, back.guideRows); assertTrue(back.multiLineGuide)
    }

    @Test fun guideOrderWithoutUserSourcesSurvivesBackup() {
        val order = listOf(org.imdtv.iptvtv.data.settings.GuideKind.OPEN_SOURCE, org.imdtv.iptvtv.data.settings.GuideKind.PLAYLIST)
        val s = org.imdtv.iptvtv.data.settings.AppSettings(guideOrder = order)
        assertEquals(order, decodeBackup(encodeBackup(Backup(playlists = emptyList(), settings = BSettings.of(s)))).settings.toAppSettings(null).guideOrder)
    }

    @Test fun oldBackupGuideOrderGainsUserSources() {
        val g = org.imdtv.iptvtv.data.settings.GuideKind.entries.associateBy { it.name }
        val old = BSettings(guideOrder = listOf("PLAYLIST", "OPEN_SOURCE"), guideKinds = null)
        assertEquals(listOf(g["PLAYLIST"], g["USER"], g["OPEN_SOURCE"]), old.toAppSettings(null).guideOrder)
    }

    @Test fun backupHasRecordingSettingsButNoSmbPassword() {
        val s = org.imdtv.iptvtv.data.settings.AppSettings(recordingOn = true, smbServer = "nas-box", smbUser = "me", smbPassword = "s3cretpw", endLateMin = 10)
        val json = encodeBackup(Backup(playlists = emptyList(), settings = BSettings.of(s)))
        assertTrue("nas-box" in json); assertFalse("s3cretpw" in json)
        val back = decodeBackup(json).settings.toAppSettings(null)
        assertEquals(s.copy(smbPassword = ""), back)
    }

    @Test fun mirrorReplacesUserDataKeepsCatalogueAndLocalSettings() = runTest {
        val a = env()
        val p = a.customise()
        val snap = a.backup.syncSnapshot()
        assertEquals(listOf(org.imdtv.iptvtv.data.sync.SLogin("alice-user", "s3cret-pw")), snap.logins)
        val wire = org.imdtv.iptvtv.data.sync.decodeSnapshot(org.imdtv.iptvtv.data.sync.encodeSnapshot(snap))

        val b = env()
        val q = b.providers.add(xtream().copy(password = "old-pw")).also { b.providers.syncNow(it) }
        val gone = b.providers.add(xtream().copy(name = "Gone", url = "http://g")).also { b.providers.syncNow(it) }
        val ch = b.db.channelDao().byProvider(q).associateBy { it.remoteId }
        b.db.favouriteDao().add(FavouriteEntity(q, Kind.LIVE, ch.getValue("3").id, 5))
        b.db.customGroupDao().create(q, "Old")
        b.db.channelDao().setSortOrder(ch.getValue("3").id, 0)
        b.settings.update { it.copy(backupFolder = "/x", lastLiveChannelId = 7) }
        val synced = b.db.providerDao().get(q)!!.lastSync

        b.backup.mirror(wire) {}

        assertEquals(listOf(q), b.db.providerDao().all().map { it.id })
        assertEquals("s3cret-pw", b.db.providerDao().get(q)!!.password)
        assertEquals(synced, b.db.providerDao().get(q)!!.lastSync) // no re-download
        assertTrue(b.db.providerDao().get(gone) == null)
        assertEquals(listOf<Int?>(null), b.db.channelDao().byProvider(q).filter { it.remoteId == "3" }.map { it.sortOrder })
        assertEquals("/x", b.settings.settings.first().backupFolder)
        assertEquals(7L, b.settings.settings.first().lastLiveChannelId)
        b.settings.update { it.copy(backupFolder = null) }
        assertEquals(a.snapshot(p), b.snapshot(q))
    }

    /** #22: a follower must hide, rename and merge channels as the master does, and drop its own such edits. */
    @Test fun mirrorCarriesPerChannelEditsAndMergeSettings() = runTest {
        val a = env()
        val p = a.providers.add(xtream()).also { a.providers.syncNow(it) }
        val ach = a.db.channelDao().byProvider(p).associateBy { it.remoteId }
        a.db.channelDao().setHidden(ach.getValue("2").id, true)
        a.db.channelDao().setCustomName(ach.getValue("1").id, "BBC 1")
        a.db.channelDao().setEpgOffset(ach.getValue("1").id, 30)
        org.imdtv.iptvtv.data.repo.ChannelMerge.merge(a.db, ach.getValue("3"), ach.getValue("1"))
        a.settings.update { it.copy(mergeChannels = !it.mergeChannels, removeNameSymbols = !it.removeNameSymbols) }
        val snap = org.imdtv.iptvtv.data.sync.decodeSnapshot(org.imdtv.iptvtv.data.sync.encodeSnapshot(a.backup.syncSnapshot()))

        val b = env()
        val q = b.providers.add(xtream()).also { b.providers.syncNow(it) }
        b.db.channelDao().byProvider(q).single { it.remoteId == "3" }.let { b.db.channelDao().setHidden(it.id, true); b.db.channelDao().setCustomName(it.id, "Mine") }
        b.backup.mirror(snap) {}

        val bch = b.db.channelDao().byProvider(q).associateBy { it.remoteId }
        assertEquals(listOf("2"), bch.values.filter { it.hidden }.map { it.remoteId })
        assertEquals("BBC 1", bch.getValue("1").customName)
        assertEquals(30, bch.getValue("1").epgOffsetMinutes)
        assertEquals(null, bch.getValue("3").customName)
        assertEquals(bch.getValue("1").id, bch.getValue("3").mergedInto)
        assertTrue(bch.getValue("1").mergeHead)
        val sa = a.settings.settings.first(); val sb = b.settings.settings.first()
        assertEquals(sa.mergeChannels to sa.removeNameSymbols, sb.mergeChannels to sb.removeNameSymbols)
    }

    @Test fun mirrorAddsNewPlaylistWithItsLogin() = runTest {
        val a = env()
        a.customise()
        val b = env()
        b.backup.mirror(a.backup.syncSnapshot()) {}
        val q = b.db.providerDao().all().single()
        assertEquals("alice-user" to "s3cret-pw", q.username to q.password)
        assertTrue(q.lastSync != null)
    }

    @Test fun mirrorSkipsAPlaylistThatCantDownload() = runTest {
        val a = env()
        a.customise()
        val snap = a.backup.syncSnapshot()
        val broken = snap.backup.playlists.single().copy(name = "Local", url = "http://localhost")
        val two = snap.copy(backup = snap.backup.copy(playlists = listOf(broken) + snap.backup.playlists), logins = listOf(null) + snap.logins)
        val b = env()
        val prefs = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = backgroundScope) { java.io.File(tmp.newFolder(), "f.preferences_pb") }
        val providers = ProviderRepository(b.db, CatalogueSync(b.db), { p -> if (p.url == "http://localhost") throw java.io.IOException("refused") else catalogue }, prefs)
        val r = BackupRepository(b.db, b.settings, providers).mirror(two) {}
        assertEquals(listOf("Main"), b.db.providerDao().all().map { it.name })
        assertEquals(1, r.added.size)
        assertTrue(r.failed.single(), r.failed.single().startsWith("Local"))
    }

    @Test fun mirrorMovesOffARemovedActivePlaylistToTheMastersOne() = runTest {
        val a = env()
        val main = a.customise()
        val other = a.providers.add(xtream().copy(name = "Other", url = "http://o")).also { a.providers.syncNow(it) }
        a.providers.setActive(main)
        val snap = a.backup.syncSnapshot()
        assertEquals(snap.backup.playlists.indexOfFirst { it.name == "Main" }, snap.active)

        val b = env()
        val gone = b.providers.add(xtream().copy(name = "Gone", url = "http://g")).also { b.providers.syncNow(it) }
        b.providers.setActive(gone)
        b.backup.mirror(snap) {}
        assertEquals("Main", b.db.providerDao().get(b.providers.activeProviderId.first()!!)!!.name)

        // A follower's own choice stands while that playlist is still there.
        val o = b.db.providerDao().all().first { it.name == "Other" }.id
        b.providers.setActive(o)
        b.backup.mirror(snap) {}
        assertEquals(o, b.providers.activeProviderId.first())
        assertTrue(other > 0)
    }

    @Test fun mirrorSaysWhetherTheGuideMatchingInputsChanged() = runTest {
        val a = env()
        a.customise()
        val snap = a.backup.syncSnapshot()
        val b = env()
        assertTrue(b.backup.mirror(snap) {}.guideChanged)
        assertFalse(b.backup.mirror(snap) {}.guideChanged)
        // A change that doesn't touch matching (theme) needs no rematch; the guide order does.
        val themed = snap.copy(backup = snap.backup.copy(settings = snap.backup.settings.copy(themeDark = false)))
        assertFalse(b.backup.mirror(themed) {}.guideChanged)
        val reordered = snap.copy(backup = snap.backup.copy(settings = snap.backup.settings.copy(guideKinds = snap.backup.settings.guideKinds!!.reversed())))
        assertTrue(b.backup.mirror(reordered) {}.guideChanged)
    }
}
