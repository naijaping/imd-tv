package org.imdtv.iptvtv.data.repo

import org.imdtv.iptvtv.data.db.AppDatabase
import org.imdtv.iptvtv.data.db.ChannelEntity
import org.imdtv.iptvtv.data.db.ProgrammeEntity
import org.imdtv.iptvtv.data.db.SQL_CHUNK
import org.imdtv.iptvtv.data.epg.effectiveOffsetMinutes

/** A programme on a channel's own clock (EPG offset already applied). */
data class GuideProgramme(val title: String, val desc: String?, val episodeNum: String?, val start: Long, val stop: Long, val ageRating: Int? = null,
    /** The programme's image (XMLTV `<icon src>`; Xtream's xmltv.php carries it too); shown in the empty preview. */ val icon: String? = null)

class GuideRepository(private val db: AppDatabase) {
    /** Programmes overlapping [from, to) per channel id, in start order. Unlinked channels are absent. */
    suspend fun programmes(providerId: Long, channels: List<ChannelEntity>, from: Long, to: Long): Map<Long, List<GuideProgramme>> {
        val links = channels.map { it.id }.chunked(SQL_CHUNK).flatMap { db.epgDao().linksFor(it) }.associateBy { it.channelId }
        val sources = db.epgDao().sources(providerId).associateBy { it.id }
        val offsets = channels.mapNotNull { c ->
            val l = links[c.id] ?: return@mapNotNull null
            val s = sources[l.sourceId] ?: return@mapNotNull null
            c.id to (ProgrammeEntity.key(l.sourceId, l.xmltvId) to effectiveOffsetMinutes(c, s) * MIN)
        }.toMap()
        if (offsets.isEmpty()) return emptyMap()
        val slack = offsets.values.maxOf { kotlin.math.abs(it.second) }
        val byKey = offsets.values.map { it.first }.distinct().chunked(SQL_CHUNK)
            .flatMap { db.programmeDao().window(it, from - slack, to + slack) }
            .groupBy { it.epgChannelKey }
        return offsets.mapValues { (_, ko) -> onChannelClock(byKey[ko.first].orEmpty(), ko.second, from, to) }
    }

    companion object {
        /**
         * The one place a guide time offset is applied: each programme's start AND stop moved by [offsetMs] (positive and
         * negative alike), then kept if it overlaps [from, to) on the channel's clock, in start order. Rows must have been
         * fetched with the window widened by the offset (see [programmes]).
         */
        fun onChannelClock(rows: List<ProgrammeEntity>, offsetMs: Long, from: Long, to: Long): List<GuideProgramme> =
            rows.map { it.shifted(offsetMs) }.filter { it.stop > from && it.start < to }.sortedBy { it.start }
    }

    private val MIN = 60_000L
}

/** This programme on a channel's clock: start and stop both moved by [offsetMs]. */
fun ProgrammeEntity.shifted(offsetMs: Long) =
    GuideProgramme(title, desc, episodeNum, start + offsetMs, stop + offsetMs, ageRating, icon?.takeIf { it.isNotBlank() })
