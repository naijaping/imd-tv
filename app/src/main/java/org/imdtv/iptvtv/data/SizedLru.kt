package org.imdtv.iptvtv.data

/**
 * A least-recently-used cache bounded by total size, not entry count: [sizeOf] says how big a value is (rows, episodes),
 * the oldest entries go once the total passes [maxSize], and a value bigger than [maxItem] isn't kept at all.
 * Thread-safe (synchronized); small and in memory only.
 */
class SizedLru<K, V>(private val maxSize: Int, private val maxItem: Int = maxSize, private val sizeOf: (V) -> Int) {
    private val map = LinkedHashMap<K, V>(16, .75f, true)
    private var total = 0

    @Synchronized operator fun get(key: K): V? = map[key]

    @Synchronized operator fun set(key: K, value: V) {
        map.remove(key)?.let { total -= sizeOf(it) }
        val size = sizeOf(value)
        if (size > maxItem) return
        map[key] = value
        total += size
        val it = map.entries.iterator()
        while (total > maxSize && it.hasNext()) { val e = it.next(); if (e.key == key) continue; total -= sizeOf(e.value); it.remove() }
    }

    @Synchronized fun remove(key: K) { map.remove(key)?.let { total -= sizeOf(it) } }
    @get:Synchronized val size: Int get() = map.size
    @get:Synchronized val totalSize: Int get() = total
    @Synchronized fun snapshot(): Map<K, V> = LinkedHashMap(map)
}
