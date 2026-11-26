package org.imdtv.iptvtv.data.epg

import android.content.Context
import org.imdtv.iptvtv.R

import androidx.room.withTransaction
import org.imdtv.iptvtv.data.db.AppDatabase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Source status after a good import (spec §2.2), e.g. `OK · 12:04 · 229k programmes`. */
fun epgOkStatus(at: Long, programmes: Int, zone: ZoneId): String {
    val time = Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    val n = if (programmes < 1000) "$programmes" else "${programmes / 1000}k"
    return "OK · $time · $n programmes"
}

/** As [epgOkStatus], in the app's language. Keep the leading "OK" in every translation: [org.imdtv.iptvtv.ui.settings.guideStatus] tells errors apart by it. */
fun epgOkStatus(context: Context, at: Long, programmes: Int, zone: ZoneId): String {
    val time = Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    val n = if (programmes < 1000) "$programmes" else "${programmes / 1000}k"
    return context.getString(R.string.set_a_epg_ok_status, time, n)
}

fun epgErrorStatus(e: Throwable): String = (e.message ?: e::class.simpleName ?: "Error").take(120)

/** `+1:30`, `-0:15`, `0:00`. */
fun formatOffset(minutes: Int): String {
    val sign = if (minutes > 0) "+" else if (minutes < 0) "-" else ""
    val m = kotlin.math.abs(minutes)
    return "$sign${m / 60}:${"%02d".format(m % 60)}"
}

/** A channel's guide time offset as the channel menu shows it: [none] for 0, else `+1h`, `-30m`, `+1h 15m`. */
fun offsetText(minutes: Int, none: String): String {
    if (minutes == 0) return none
    val sign = if (minutes > 0) "+" else "-"
    val h = kotlin.math.abs(minutes) / 60
    val m = kotlin.math.abs(minutes) % 60
    return sign + listOfNotNull(h.takeIf { it > 0 }?.let { "${it}h" }, m.takeIf { it > 0 }?.let { "${it}m" }).joinToString(" ")
}

/** A channel's offset: [own] its override (null = none), [guide] its guide source's (0 when it has no guide). */
data class ChannelOffset(val own: Int?, val guide: Int) {
    /** What the guide applies to the channel (as [effectiveOffsetMinutes]). */
    val inUse: Int get() = own ?: guide
}

/** The channel menu's offset list: −12h … +12h in 30-minute steps, plus [current] in its sorted place when it's not one. */
fun offsetChoices(current: Int): List<Int> = ((-720..720 step 30) + current).distinct().sorted()

/** −12:00 to +12:00 in 15-minute steps (spec §5). */
val OFFSET_STEPS = (-720..720 step 15).toList()

/** Clear guide data: programmes, XMLTV channels and automatic links go; manual links stay. */
suspend fun clearGuide(db: AppDatabase) = db.withTransaction {
    db.programmeDao().deleteAll()
    db.epgDao().deleteAllChannels()
    db.epgDao().deleteAllAutoLinks()
    db.epgDao().clearAllStatus()
}
