package org.imdtv.iptvtv.data.logos

import org.imdtv.iptvtv.data.epg.BuiltinRule
import org.imdtv.iptvtv.data.epg.NameNormaliser
import java.text.Normalizer

/** [key] is the name without country prefix, quality/encoding tags and brackets; [quality] is "uhd", "hd" or null. */
data class LogoKey(val key: String, val quality: String?)

/** Trailing run of whole-word quality and encoding tokens. */
private val TAIL = Regex("""(?i)(?:(?:^|[\s\p{Z}\-|]+)(?:UHD|4K|FHD|HD|SD|HEVC|H\.?265|H\.?264|RAW|\d{2,3}[\s\p{Z}]?FPS|\d{3,4}[PI]))+[\s\p{Z}\-|]*$""")
/** "Dunya News UK", "BBC News UK HD": a trailing country word, as guides and tv-logos name the channel without it.
 *  shortcut: English-speaking countries only; extend the list if other countries' playlists name channels this way. */
private val COUNTRY_TAIL = Regex("""(?i)[\s\p{Z}\-|]+(?:UK|GB|US|USA|IE|CA|AU|NZ)[\s\p{Z}\-|]*$""")
private val UHD_LINES = setOf("2160P", "3840P", "4320P")
private val HD_LINES = setOf("720P", "1080P", "1080I")
private val TOKEN_SPLIT = Regex("""[\s\p{Z}\-|]+""")
private val MARKS = Regex("""[\p{M}]+""")
/** "◉", "★" and the like: as spaces, so a trailing one doesn't hide the HD/4K tag before it. */
private val SYMBOLS = Regex("""[\p{So}]+""")
private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

/**
 * The logo key of spec §4.2 (`"UK: Sky Sports Main Event ᵁᴴᴰ"` → `sky-sports-main-event`, uhd);
 * also the sibling key used to borrow guides. Null when nothing is left.
 */
private val NUMBER_WORDS = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
    .withIndex().associate { (i, w) -> w to "${i + 1}" }
/** "+1" timeshift channels: tv-logos names them "-plus" (channel-4-plus-uk.png). */
private val PLUS_ONE = Regex("""\+[\s\p{Z}]*1(?![0-9])""")

/** Number words as digits, token by token ("bbc-two" → "bbc-2"): tv-logos spells numbers, providers mostly don't. */
fun digitsKey(key: String): String = key.split('-').joinToString("-") { NUMBER_WORDS[it] ?: it }

/** The logo key without separators: "BBC Two HD", "UK: BBC 2 HEVC" → "bbc2"; "ITV1" = "ITV 1". */
fun compactKey(name: String, dropCountry: Boolean = true): String? = logoKey(name, dropCountry)?.key?.replace("-", "")?.takeIf { it.isNotEmpty() }

/** [dropCountry] false for guide-side names: "Comedy Central US" must not become a key a UK channel can match. */
fun logoKey(name: String, dropCountry: Boolean = true): LogoKey? {
    var s = Normalizer.normalize(name, Normalizer.Form.NFKC).replace(SYMBOLS, " ")
    for (r in listOf(BuiltinRule.COUNTRY_PREFIX, BuiltinRule.BRACKETS, BuiltinRule.VIP)) s = NameNormaliser.applyBuiltin(r, s)
    var quality: String? = null
    fun dropTail() = TAIL.find(s)?.let { m ->
        val tokens = m.value.split(TOKEN_SPLIT).map { it.uppercase() }
        quality = quality ?: when {
            tokens.any { it == "UHD" || it == "4K" || it in UHD_LINES } -> "uhd"
            tokens.any { it == "HD" || it == "FHD" || it in HD_LINES } -> "hd"
            else -> null
        }
        s = s.substring(0, m.range.first)
    }
    dropTail()
    if (dropCountry) COUNTRY_TAIL.find(s)?.let { s = s.substring(0, it.range.first); dropTail() } // "… UK HD" and "… HD UK"
    // "&" as "and": tv-logos spells it out ("sky-cinema-sci-fi-and-horror"), playlists mostly don't (#24).
    val key = digitsKey(Normalizer.normalize(s.replace(PLUS_ONE, " plus ").replace("&", " and "), Normalizer.Form.NFD).replace(MARKS, "").lowercase()
        .replace(NON_WORD, "-").trim('-'))
    return if (key.isEmpty()) null else LogoKey(key, quality)
}

/**
 * Compact keys one letter apart (a letter added, dropped or changed: "skycinemapremier" ~ "skycinemapremiere"), for
 * names a playlist spells differently from the guide or tv-logos (#19, #24). Only keys of [NEAR_MIN]+ characters, never
 * a digit ("bbc1" stays apart from "bbc2"), and only when exactly one key is that near. A last resort after exact keys.
 */
class NearKeys<V>(private val entries: Map<String, V>) {
    private val byDelete = HashMap<String, MutableList<String>>()
    init { for (k in entries.keys) if (k.length >= NEAR_MIN) letterDeletes(k).forEach { byDelete.getOrPut(it) { ArrayList(1) } += k } }

    fun find(q: String): V? {
        if (q.length < NEAR_MIN - 1) return null
        val hits = HashSet<String>()
        byDelete[q]?.let(hits::addAll) // the key has one letter more
        for (d in letterDeletes(q)) { if (d.length >= NEAR_MIN && d in entries) hits += d; byDelete[d]?.let(hits::addAll) }
        hits.remove(q)
        return hits.filter { oneLetterApart(q, it) }.singleOrNull()?.let(entries::get)
    }

    private companion object {
        const val NEAR_MIN = 8
        fun letterDeletes(k: String) = k.indices.filter { k[it].isLetter() }.map { k.removeRange(it, it + 1) }.distinct()
        /** Same length with one letter changed, or one letter more on either side. */
        fun oneLetterApart(a: String, b: String): Boolean {
            if (a.length == b.length) {
                val i = a.indices.filter { a[it] != b[it] }
                return i.size == 1 && a[i[0]].isLetter() && b[i[0]].isLetter()
            }
            val (s, l) = if (a.length < b.length) a to b else b to a
            if (l.length - s.length != 1) return false
            val i = s.indices.firstOrNull { s[it] != l[it] } ?: s.length
            return l[i].isLetter() && s.substring(i) == l.substring(i + 1)
        }
    }
}
