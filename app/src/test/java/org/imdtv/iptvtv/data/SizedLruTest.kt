package org.imdtv.iptvtv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SizedLruTest {
    @Test fun boundedByTotalSizeOldestFirst() {
        val c = SizedLru<String, List<Int>>(maxSize = 10, maxItem = 6) { it.size }
        c["a"] = List(4) { it }; c["b"] = List(4) { it }
        c["a"] // a is now the most recent
        c["c"] = List(4) { it } // 12 > 10: b (least recent) goes
        assertNull(c["b"]); assertEquals(8, c.totalSize); assertEquals(setOf("a", "c"), c.snapshot().keys)
        c["huge"] = List(7) { it } // bigger than maxItem: not kept, nothing evicted for it
        assertNull(c["huge"]); assertEquals(2, c.size)
        c["a"] = List(1) { it } // replacing counts the new size
        assertEquals(5, c.totalSize)
    }

    @Test fun channelListsStayUnderBudget() {
        // The category preview's cache: browsing 200 categories of 400 channels keeps at most 6,000 rows (~3 MB).
        val c = SizedLru<Int, List<Int>>(6_000, maxItem = 3_000) { it.size }
        repeat(200) { c[it] = List(400) { 0 } }
        assertEquals(15, c.size); assertEquals(6_000, c.totalSize)
    }
}
