package org.imdtv.iptvtv.data.repo

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.imdtv.iptvtv.data.db.AppDatabase
import org.imdtv.iptvtv.data.db.ChannelEntity
import org.imdtv.iptvtv.data.db.EpgLinkEntity
import org.imdtv.iptvtv.data.db.EpgSourceEntity
import org.imdtv.iptvtv.data.db.ProgrammeEntity
import org.imdtv.iptvtv.data.db.inMemoryDb
import org.imdtv.iptvtv.data.db.insertTestProvider

@RunWith(RobolectricTestRunner::class)
class GuideRepositoryTest {
    private lateinit var db: AppDatabase
    private var p = 0L
    private val min = 60_000L

    @Before fun setUp() = runTest { db = inMemoryDb(); p = db.insertTestProvider() }
    @After fun tearDown() { db.close() }

    private suspend fun channel(remoteId: String, offset: Int? = null): ChannelEntity {
        db.channelDao().upsert(listOf(ChannelEntity(providerId = p, categoryId = null, remoteId = remoteId, name = remoteId,
            logo = null, number = null, tvgId = null, streamUrl = "http://h/$remoteId", epgOffsetMinutes = offset)))
        return db.channelDao().byProvider(p).single { it.remoteId == remoteId }
    }

    private suspend fun prog(source: Long, id: String, startMin: Long, stopMin: Long, title: String) =
        db.programmeDao().insertAll(listOf(ProgrammeEntity(sourceId = source, epgChannelKey = ProgrammeEntity.key(source, id),
            start = startMin * min, stop = stopMin * min, title = title, desc = null, episodeNum = null, icon = null)))

    @Test
    fun linkedChannelGetsProgrammesInWindowUnlinkedGetsNone() = runTest {
        val s = db.epgDao().insertSource(EpgSourceEntity(providerId = p, url = "u", priority = 0))
        val a = channel("a")
        val b = channel("b")
        db.epgDao().upsertLinks(listOf(EpgLinkEntity(a.id, s, "x", manual = false)))
        prog(s, "x", 0, 50, "Before")
        prog(s, "x", 50, 120, "Now")
        prog(s, "x", 120, 180, "Next")
        prog(s, "x", 300, 360, "Later")

        val rows = GuideRepository(db).programmes(p, listOf(a, b), from = 60 * min, to = 240 * min)

        assertEquals(listOf("Now", "Next"), rows[a.id]!!.map { it.title })
        assertEquals(emptyList<GuideProgramme>(), rows[b.id] ?: emptyList<GuideProgramme>())
    }

    @Test
    fun offsetShiftsProgrammeTimes() = runTest {
        val s = db.epgDao().insertSource(EpgSourceEntity(providerId = p, url = "u", priority = 0, offsetMinutes = 30))
        val a = channel("a")          // uses source offset +30
        val b = channel("b", offset = -60) // own offset wins
        db.epgDao().upsertLinks(listOf(EpgLinkEntity(a.id, s, "x", manual = false), EpgLinkEntity(b.id, s, "x", manual = false)))
        prog(s, "x", 600, 660, "Show")

        val rows = GuideRepository(db).programmes(p, listOf(a, b), from = 0, to = 2000 * min)

        assertEquals(630 * min, rows[a.id]!!.single().start)
        assertEquals(540 * min, rows[b.id]!!.single().start)
        assertEquals(600 * min, rows[b.id]!!.single().stop)
    }

    // --- Guide time offsets: one shift for start and stop, then the window (GuideRepository.onChannelClock). ---

    private val h = 60 * min
    /** A day of programmes on the guide's clock: 30-minute shows from 00:00, one 23:30–00:30 across midnight, then the next day. */
    private fun day(): List<ProgrammeEntity> = (0 until 47).map { i -> row(i * 30L, i * 30L + 30, "P$i") } +
        row(23 * 60L + 30, 24 * 60L + 30, "Midnight") + (1 until 10).map { i -> row(24 * 60L + i * 30, 24 * 60L + i * 30 + 30, "N$i") }
    private fun row(startMin: Long, stopMin: Long, title: String) = ProgrammeEntity(sourceId = 1, epgChannelKey = "1|x",
        start = startMin * min, stop = stopMin * min, title = title, desc = null, episodeNum = null, icon = null)

    @Test
    fun everyOffsetMovesStartAndStopTogether() {
        for (offMin in listOf(0, 30, 60, 120, -60, -120)) {
            val off = offMin * min
            // The visible window 13:30–16:00 on the channel's clock; rows fetched with it widened by the offset.
            val from = 13 * h + 30 * min; val to = 16 * h
            val fetched = day().filter { it.stop > from - kotlin.math.abs(off) && it.start < to + kotlin.math.abs(off) }
            val shown = GuideRepository.onChannelClock(fetched, off, from, to)
            val where = "offset $offMin"
            // Same shows, same lengths, every one moved by the offset.
            shown.forEach { g -> val src = day().single { it.title == g.title }; assertEquals(where, src.start + off, g.start); assertEquals(where, src.stop + off, g.stop) }
            // The programme on at the window's start is there (it started before the window), and the row is whole.
            assertTrue("$where: first ${shown.first().start}", shown.first().start <= from && shown.first().stop > from)
            assertTrue(where, shown.last().stop >= to)
            shown.zipWithNext().forEach { (a, b) -> assertTrue("$where: overlap", a.stop <= b.start); assertEquals("$where: gap", a.stop, b.start) }
            // None left out: every show overlapping the window on the channel's clock.
            assertEquals(where, day().count { it.stop + off > from && it.start + off < to }, shown.size)
        }
    }

    @Test
    fun acrossMidnightAndTheEndsOfTheDay() {
        // At +2 h the 23:30–00:30 show is 01:30–02:30 the next day; at −2 h the day's first show (00:00) is 22:00 the day before.
        val plus = GuideRepository.onChannelClock(day(), 120 * min, 25 * h, 27 * h)
        assertTrue(plus.any { it.title == "Midnight" && it.start == 25 * h + 30 * min && it.stop == 26 * h + 30 * min })
        val minus = GuideRepository.onChannelClock(day(), -120 * min, -2 * h, 0)
        assertEquals(listOf("P0", "P1", "P2", "P3"), minus.map { it.title })
        assertEquals(-2 * h, minus.first().start)
        // The day's last half-hour show (23:00–23:30), at −1 h, is 22:00–22:30.
        assertEquals(22 * h + 30 * min, GuideRepository.onChannelClock(day(), -60 * min, 22 * h, 24 * h).first { it.title == "P46" }.stop)
    }

    @Test
    fun positiveOffsetFromTheDatabaseHasNoGap() = runTest {
        val s = db.epgDao().insertSource(EpgSourceEntity(providerId = p, url = "u", priority = 0))
        val ait = channel("ait", offset = 120)
        val other = channel("arise")
        db.epgDao().upsertLinks(listOf(EpgLinkEntity(ait.id, s, "x", manual = false), EpgLinkEntity(other.id, s, "y", manual = false)))
        for (i in 0 until 48) { prog(s, "x", i * 30L, i * 30L + 30, "A$i"); prog(s, "y", i * 30L, i * 30L + 30, "B$i") }
        val from = 13 * h + 30 * min; val to = 16 * h
        val rows = GuideRepository(db).programmes(p, listOf(ait, other), from, to)
        // AIT: 11:30 on the guide is 13:30 on the channel, the row whole; the channel without an offset is untouched.
        assertEquals("A23", rows[ait.id]!!.first().title)
        assertEquals(from, rows[ait.id]!!.first().start)
        assertEquals(to, rows[ait.id]!!.last().stop)
        assertEquals("B27", rows[other.id]!!.first().title)
        assertEquals(5, rows[other.id]!!.size)
    }
}
