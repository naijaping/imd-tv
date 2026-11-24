package org.imdtv.iptvtv.data.addons

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.imdtv.iptvtv.data.security.Secrets
import org.imdtv.iptvtv.data.source.StremioAddon

/**
 * An add-on installed on the Addons page. [url] is the manifest URL (secret: it may hold a token); [manifest] the last
 * manifest fetched, so the list works offline and opens fast; [off] the keys of catalogs switched off.
 */
@Serializable
data class InstalledAddon(val url: String, val manifest: String, val enabled: Boolean = true, val off: Set<String> = emptySet(), val checkedAt: Long = 0)

/** An installed add-on with its parsed manifest; [addon] has the switched-off catalogs left out. */
data class AddonEntry(val installed: InstalledAddon, val full: StremioAddon) {
    val addon get() = full.without(installed.off)
}

/**
 * The Addons page's list, in priority order (catalog rows and stream lists follow it). Kept in the settings store as
 * one encrypted value (the URLs may hold tokens), outside backups and sync: it stays on this box.
 */
class AddonRepository(private val prefs: DataStore<Preferences>, private val client: AddonClient, private val json: Json,
                      private val now: () -> Long = System::currentTimeMillis) {
    private val key = stringPreferencesKey("stremio_addons")
    private val listSer = ListSerializer(InstalledAddon.serializer())

    private fun decode(stored: String?): List<InstalledAddon> =
        stored?.let { Secrets.decrypt(it) }?.let { runCatching { json.decodeFromString(listSer, it) }.getOrNull() }.orEmpty()

    val entries: Flow<List<AddonEntry>> = prefs.data.map { it[key] }.distinctUntilChanged().map { s ->
        decode(s).mapNotNull { i -> runCatching { AddonEntry(i, StremioAddon.of(i.url, json.parseToJsonElement(i.manifest))) }.getOrNull() }
    }

    /** The add-ons switched on, in order, without their switched-off catalogs. */
    val enabled: Flow<List<StremioAddon>> = entries.map { l -> l.filter { it.installed.enabled }.map { it.addon } }
    suspend fun enabledNow(): List<StremioAddon> = enabled.first()

    private suspend fun edit(change: (List<InstalledAddon>) -> List<InstalledAddon>) {
        prefs.edit { p -> p[key] = Secrets.encrypt(json.encodeToString(listSer, change(decode(p[key]))))!! }
    }

    /**
     * Checks what was pasted: normalised (stremio:// → https), fetched and parsed. Throws [AddonException]: BAD_URL,
     * UNREACHABLE, TIMEOUT, HTTP, NOT_ADDON, or NEEDS_CONFIG when the add-on must be configured in a browser first.
     */
    suspend fun check(input: String): Pair<StremioAddon, String> {
        val url = AddonUrls.normalize(input) ?: throw AddonException(AddonException.Reason.BAD_URL)
        val (addon, raw) = client.manifest(url)
        if (addon.configurationRequired) throw AddonException(AddonException.Reason.NEEDS_CONFIG)
        return addon to raw
    }

    /** Adds (at the end) or, for an address already installed, updates its manifest. */
    suspend fun install(addon: StremioAddon, raw: String) = edit { l ->
        val i = l.indexOfFirst { it.url == addon.manifestUrl }
        if (i >= 0) l.toMutableList().also { it[i] = it[i].copy(manifest = raw, checkedAt = now()) }
        else l + InstalledAddon(addon.manifestUrl, raw, checkedAt = now())
    }

    suspend fun remove(url: String) = edit { l -> l.filter { it.url != url } }
    suspend fun setEnabled(url: String, on: Boolean) = edit { l -> l.map { if (it.url == url) it.copy(enabled = on) else it } }
    suspend fun setCatalog(url: String, catalogKey: String, on: Boolean) = edit { l ->
        l.map { if (it.url == url) it.copy(off = if (on) it.off - catalogKey else it.off + catalogKey) else it }
    }

    /** Moves the add-on [delta] places (−1 up, +1 down), within the list. */
    suspend fun move(url: String, delta: Int) = edit { l ->
        val i = l.indexOfFirst { it.url == url }
        val j = (i + delta).coerceIn(0, l.lastIndex)
        if (i < 0 || i == j) l else l.toMutableList().apply { add(j, removeAt(i)) }
    }

    /** Fetches the manifest again (new catalogs, version); keeps the switches. Throws [AddonException]. */
    suspend fun refresh(url: String) {
        val (addon, raw) = client.manifest(url)
        install(addon, raw)
    }
}
