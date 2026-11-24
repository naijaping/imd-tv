package org.imdtv.iptvtv.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.imdtv.iptvtv.data.db.AppDatabase
import org.imdtv.iptvtv.data.db.ChannelEntity
import org.imdtv.iptvtv.data.db.EpgLinkEntity
import org.imdtv.iptvtv.data.db.FavouriteEntity
import org.imdtv.iptvtv.data.db.ProgrammeEntity
import org.imdtv.iptvtv.data.db.ProgressEntity
import org.imdtv.iptvtv.data.db.ProviderEntity
import org.imdtv.iptvtv.data.db.ProviderType
import org.imdtv.iptvtv.data.db.inMemoryDb
import org.imdtv.iptvtv.data.model.Kind
import org.imdtv.iptvtv.data.model.StreamFormat

@RunWith(RobolectricTestRunner::class)
class ProviderRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: AppDatabase

    @Before fun setUp() { db = inMemoryDb() }
    @After fun tearDown() { db.close() }

    private fun TestScope.repo(afterSync: suspend (Long) -> Unit = {}) = ProviderRepository(
        db = db, sync = CatalogueSync(db), sourceFor = { p -> FakeSource(epg = listOf("http://h/xmltv.php?password=${p.password}")) },
        prefs = PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("prefs.preferences_pb") },
        afterSync = afterSync,
    )

    @Test fun syncSucceedsWhenAfterSyncThrows() = runTest {
        val r = repo(afterSync = { throw java.io.IOException("github down") })
        val p = r.add(provider("a", "http://a/e.xml"))
        val result = r.syncNow(p)
        assertEquals(0, result.channels)
        assertTrue(db.providerDao().get(p)!!.lastSync != null)
    }

    @Test fun serverResumePointsFillContinueWatchingButOursWinWhenNewer() = runTest {
        val vod = FakeSource(vodCats = listOf(org.imdtv.iptvtv.data.model.Category("1", "Films", Kind.MOVIE)),
            movieList = listOf("a", "b").map { org.imdtv.iptvtv.data.model.Movie(it, "1", it, null, null, null, "http://s/$it") })
        var points = listOf(org.imdtv.iptvtv.data.source.ResumePoint(Kind.MOVIE, "a", "", 60_000, 600_000, 1_000),
            org.imdtv.iptvtv.data.source.ResumePoint(Kind.MOVIE, "gone", "", 1, 2, 3)) // not in the catalogue: ignored
        val r = ProviderRepository(db = db, sync = CatalogueSync(db),
            sourceFor = { object : org.imdtv.iptvtv.data.source.PlaylistSource by vod { override suspend fun resumePoints() = points } },
            prefs = PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("prefs.preferences_pb") })
        val p = r.add(provider("plex", "http://p/e.xml"))
        r.syncNow(p) // imports at the end of a sync
        val a = db.movieDao().byProvider(p).single { it.remoteId == "a" }.id
        assertEquals(listOf(a to 60_000L), db.progressDao().all(p).map { it.itemId to it.positionMs })

        db.progressDao().upsert(db.progressDao().all(p).single().copy(positionMs = 90_000, updatedAt = 5_000)) // watched further here, later
        points = listOf(org.imdtv.iptvtv.data.source.ResumePoint(Kind.MOVIE, "a", "", 70_000, 600_000, 2_000))
        r.importResume(p, minGapMs = 0)
        assertEquals(90_000L, db.progressDao().all(p).single().positionMs)
    }

    private fun provider(name: String, epg: String) = ProviderEntity(
        name = name, type = ProviderType.M3U, url = "http://$name/list.m3u", username = null, password = null,
        userAgent = null, streamFormat = StreamFormat.TS, epgOverrideUrls = listOf(epg), lastSync = null,
    )

    /** One channel, a favourite, a progress row and a programme on the provider's first EPG source. */
    private suspend fun fill(p: Long) {
        db.channelDao().upsert(listOf(ChannelEntity(providerId = p, categoryId = null, remoteId = "1", name = "C$p",
            logo = null, number = null, tvgId = null, streamUrl = "http://h/$p")))
        val ch = db.channelDao().byProvider(p).single().id
        db.favouriteDao().add(FavouriteEntity(p, Kind.LIVE, ch, 1))
        db.progressDao().upsert(ProgressEntity(p, Kind.MOVIE, 9, 1, 2, 3, false))
        val s = db.epgDao().sources(p).first().id
        db.programmeDao().insertAll(listOf(ProgrammeEntity(sourceId = s, epgChannelKey = ProgrammeEntity.key(s, "x"),
            start = 0, stop = 1, title = "t", desc = null, episodeNum = null, icon = null)))
    }

    private suspend fun programmesOf(p: Long) =
        db.epgDao().sources(p).sumOf { s -> db.programmeDao().window(listOf(ProgrammeEntity.key(s.id, "x")), 0, 2).size }

    @Test
    fun deleteRemovesUnlinkedRowsAndSwitchesActive() = runTest {
        val repo = repo()
        val a = repo.add(provider("a", "http://e/a.xml"))
        val b = repo.add(provider("b", "http://e/b.xml"))
        fill(a); fill(b)
        val aSource = db.epgDao().sources(a).single().id
        assertEquals(a, repo.activeProviderId.first())

        repo.remove(a)

        assertTrue(db.favouriteDao().list(a, Kind.LIVE).isEmpty())
        assertTrue(db.progressDao().all(a).isEmpty())
        assertTrue(db.channelDao().byProvider(a).isEmpty())
        assertEquals(0, db.programmeDao().window(listOf(ProgrammeEntity.key(aSource, "x")), 0, 2).size)
        assertEquals(1, db.favouriteDao().list(b, Kind.LIVE).size)
        assertEquals(1, db.progressDao().all(b).size)
        assertEquals(1, db.channelDao().byProvider(b).size)
        assertEquals(1, programmesOf(b))
        assertEquals(b, repo.activeProviderId.first())
    }

    /** 0.7.0: the Movies rail made Plex active, which emptied Live TV; a media server is active only when it's alone. */
    @Test
    fun mediaServerIsNeverActiveBesideAnIptvPlaylist() = runTest {
        val repo = repo()
        val plex = repo.add(provider("Aplex", "http://e/p.xml").copy(type = ProviderType.PLEX))
        assertEquals(plex, repo.activeProviderId.first())
        val tv = repo.add(provider("b", "http://e/b.xml"))
        repo.setActive(plex)
        assertEquals(tv, repo.activeProviderId.first())
    }

    @Test
    fun deletingLastProviderClearsActive() = runTest {
        val repo = repo()
        val a = repo.add(provider("a", "http://e/a.xml"))
        repo.remove(a)
        assertNull(repo.activeProviderId.first())
        assertNull(db.providerDao().get(a))
    }

    @Test
    fun updateKeepsIdAndCatalogue() = runTest {
        val repo = repo()
        val a = repo.add(provider("a", "http://e/a.xml"))
        fill(a)
        val p = db.providerDao().get(a)!!
        repo.update(p.copy(url = "http://new/list.m3u", username = "u", password = "pw", userAgent = "VLC"))
        val after = db.providerDao().get(a)!!
        assertEquals("http://new/list.m3u", after.url)
        assertEquals("VLC", after.userAgent)
        assertEquals("u", after.username)
        assertEquals(1, db.channelDao().byProvider(a).size)
        assertEquals(1, db.favouriteDao().list(a, Kind.LIVE).size)
    }

    @Test
    fun editKeepsProviderEpgSourceOnNewLogin() = runTest {
        val repo = repo()
        val a = repo.add(provider("a", "http://e/a.xml").copy(epgOverrideUrls = emptyList(), password = "old"))
        repo.addEpgSource(a, "http://own/guide.xml", null)
        val before = db.epgDao().sources(a)
        db.epgDao().setOffset(before[0].id, 60)

        repo.update(db.providerDao().get(a)!!.copy(password = "new"))

        val after = db.epgDao().sources(a)
        assertEquals(listOf("http://h/xmltv.php?password=new", "http://own/guide.xml"), after.map { it.url })
        assertEquals(before.map { it.id }, after.map { it.id })
        assertEquals(60, after[0].offsetMinutes)
    }

    @Test
    fun removeEpgSourceDeletesItsProgrammes() = runTest {
        val repo = repo()
        val a = repo.add(provider("a", "http://e/a.xml"))
        fill(a)
        repo.addEpgSource(a, "http://e/second.xml", "Second")
        val (first, second) = db.epgDao().sources(a)
        assertEquals(1, second.priority)
        assertEquals("Second", second.name)

        db.epgDao().upsertLinks(listOf(EpgLinkEntity(db.channelDao().byProvider(a).single().id, first.id, "x", manual = true)))

        repo.removeEpgSource(first.id)

        assertEquals(listOf(second.id), db.epgDao().sources(a).map { it.id })
        assertEquals(0, db.programmeDao().window(listOf(ProgrammeEntity.key(first.id, "x")), 0, 2).size)
        assertTrue(db.epgDao().links(a).isEmpty())
    }
}
