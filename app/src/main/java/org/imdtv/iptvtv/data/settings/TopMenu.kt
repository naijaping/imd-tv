package org.imdtv.iptvtv.data.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One top menu item as the user set it (Settings › Top menu): [id] is the item's stable identity (the tab's name, e.g.
 * "LIVE"), never its position or label; [name] a custom name (null = the translated default); [iconOnly] its style.
 */
@Serializable
data class TopMenuEntry(val id: String, val name: String? = null, val iconOnly: Boolean = false)

object TopMenu {
    /** A custom name's longest (the menu ends a long one with "…" anyway). */
    const val MAX_NAME = 16
    /** Can be renamed and restyled but never removed: always in the menu. */
    const val SETTINGS_ID = "SETTINGS"

    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(TopMenuEntry.serializer())

    /**
     * The saved list made whole for [ids] (every item the app has, in its default order): saved items in their saved
     * order, unknown ones dropped, duplicates removed, items new to the app added at the end with default name and style.
     */
    fun normalized(saved: List<TopMenuEntry>, ids: List<String>): List<TopMenuEntry> {
        val kept = saved.filter { it.id in ids }.distinctBy { it.id }
        return kept + ids.filter { id -> kept.none { it.id == id } }.map { TopMenuEntry(it) }
    }

    /** A typed name: trimmed, at most [MAX_NAME] characters; blank = back to the default (null). */
    fun cleanName(text: String?): String? = text?.trim()?.replace(Regex("\\s+"), " ")?.take(MAX_NAME)?.trim()?.ifEmpty { null }

    /** Moves [id] [delta] places within [list]. */
    fun move(list: List<TopMenuEntry>, id: String, delta: Int): List<TopMenuEntry> {
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return list
        val j = (i + delta).coerceIn(0, list.lastIndex)
        return if (i == j) list else list.toMutableList().apply { add(j, removeAt(i)) }
    }

    fun encode(list: List<TopMenuEntry>): String = json.encodeToString(ser, list)
    fun decode(text: String?): List<TopMenuEntry> = text?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() }.orEmpty()
}
