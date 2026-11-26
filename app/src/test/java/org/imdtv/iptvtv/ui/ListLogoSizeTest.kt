package org.imdtv.iptvtv.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListLogoSizeTest {
    @Test fun boxIsAShareOfRowAndCell() {
        val (w, h) = ListLogoSize.box(32.dp, 190.dp)
        assertEquals(190f * .48f, w.value, .01f); assertEquals(32f * .54f, h.value, .01f)
    }

    @Test fun fitsWithoutStretching() {
        // Square and tall logos take the full height; their width follows.
        assertEquals(17f to 17f, ListLogoSize.fit(1f, 90f, 17f))
        val (tw, th) = ListLogoSize.fit(.5f, 90f, 17f); assertEquals(8.5f, tw, .01f); assertEquals(17f, th, .01f)
        // A 2.5:1 logo (ITV1 HD-like) also takes the full height.
        val (w, h) = ListLogoSize.fit(2.5f, 90f, 17f); assertEquals(42.5f, w, .01f); assertEquals(17f, h, .01f)
        // A very wide one gets less height; the widest are capped by the box width, aspect kept.
        val (vw, vh) = ListLogoSize.fit(4f, 90f, 17f); assertEquals(17f * ListLogoSize.WIDE_HEIGHT, vh, .01f); assertEquals(vh * 4f, vw, .01f)
        val (xw, xh) = ListLogoSize.fit(10f, 90f, 17f); assertEquals(90f, xw, .01f); assertEquals(9f, xh, .01f)
        // Nonsense aspects are drawn square rather than crash.
        assertEquals(17f to 17f, ListLogoSize.fit(Float.NaN, 90f, 17f))
    }

    @Test fun findsTheOpaquePart() {
        val w = 10; val h = 6
        val px = IntArray(w * h) // all transparent
        assertNull(ListLogoSize.opaqueBounds(px, w, h))
        for (y in 2..3) for (x in 3..7) px[y * w + x] = 0xFFFFFFFF.toInt()
        px[0] = 0x10FFFFFF // near-invisible speck: ignored
        assertArrayEquals(intArrayOf(3, 2, 8, 4), ListLogoSize.opaqueBounds(px, w, h))
        // Opaque to the edges: nothing to trim.
        assertNull(ListLogoSize.opaqueBounds(IntArray(w * h) { 0xFF000000.toInt() }, w, h))
        assertTrue(ListLogoSize.WIDE > 2.5f)
    }
}
