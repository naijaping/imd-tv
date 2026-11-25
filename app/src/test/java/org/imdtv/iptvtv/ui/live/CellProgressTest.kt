package org.imdtv.iptvtv.ui.live

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The programme progress bar drawn as in the guide (the cell's background, the bar, then the cell's 1 dp border on
 * top) at 1080p: on Glass's rounded cell with its faint edge and with the focus outline, and on the boxed cell, blue
 * appears only inside the cell's shape and its border — never in the rounded corners or on the border.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CellProgressTest {
    /** [radiusPx]: the shape's corner radius at 2×. */
    private class Case(val name: String, val shape: Shape, val border: Color, val fraction: Float, val radiusPx: Float)

    private fun draw(c: Case): androidx.compose.ui.graphics.PixelMap {
        val density = Density(2f)
        val size = Size(240f, 72f) // a 120 × 36 dp cell at 2×
        val img = ImageBitmap(240, 72)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(img), size) {
            drawRect(Color.Black)
            val outline = c.shape.createOutline(size, layoutDirection, this)
            drawOutline(outline, Color(0xFF2A3A8A))
            drawCellProgress(c.shape, c.fraction)
            // Modifier.border: a 1 dp stroke inside the outline.
            val bw = 1.dp.toPx()
            val inner = c.shape.createOutline(Size(size.width - bw, size.height - bw), layoutDirection, this)
            drawContext.transform.translate(bw / 2, bw / 2)
            drawOutline(inner, c.border, style = Stroke(bw))
            drawContext.transform.translate(-bw / 2, -bw / 2)
        }
        return img.toPixelMap()
    }

    @Test fun staysInsideTheCellShapeAndBorder() {
        for (c in listOf(
            Case("glass cell", RoundedCornerShape(4.dp), Color.White.copy(alpha = .16f), 1f, 8f),
            Case("focused cell", RoundedCornerShape(4.dp), Color(0xCCFFFFFF), 1f, 8f),
            Case("boxed cell", RoundedCornerShape(2.dp), Color(0xCCFFFFFF), 1f, 4f),
            Case("just started", RoundedCornerShape(4.dp), Color(0xCCFFFFFF), .01f, 8f),
        )) {
            val px = draw(c)
            val w = px.width; val h = px.height
            fun blue(x: Int, y: Int) = px[x, y].let { it.blue > .75f && it.red < .35f && it.green > .45f }
            val barY = h - ((1.5f + 1.75f) * 2).toInt() // the bar's middle row
            assertTrue("${c.name}: bar at its start", blue(8, barY))
            if (c.fraction == 1f) assertTrue("${c.name}: bar in the middle", blue(w / 2, barY))
            else assertFalse("${c.name}: 1% stays short", blue(w / 2, barY))
            // Never on the border (the outer 2 px) or past it, never in the rounded corners.
            for (x in 0 until w) for (y in h - 2 until h) assertFalse("${c.name}: blue on the bottom border at $x,$y", blue(x, y))
            for (y in 0 until h) for (x in listOf(0, 1, w - 2, w - 1)) assertFalse("${c.name}: blue on a side border at $x,$y", blue(x, y))
            // Outside the rounded corners' arcs (pixel centre beyond the radius from the corner's centre): never blue.
            val r = c.radiusPx
            for (x in 0 until r.toInt() + 1) for (y in h - r.toInt() - 1 until h) {
                // Distance from the corner arc's centre; the right corner mirrors the left one.
                val d = kotlin.math.hypot(x + .5f - r, y + .5f - (h - r))
                if (x + .5f < r && y + .5f > h - r && d > r) {
                    assertFalse("${c.name}: blue outside the bottom-left corner at $x,$y", blue(x, y))
                    assertFalse("${c.name}: blue outside the bottom-right corner at ${w - 1 - x},$y", blue(w - 1 - x, y))
                }
            }
        }
    }
}
