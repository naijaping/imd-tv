package org.imdtv.iptvtv.ui

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.Text
import org.imdtv.iptvtv.data.db.ChannelEntity
import org.imdtv.iptvtv.data.settings.LogoKind
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Logo only column, rendered for real at 1080p and 720p, for the row heights the guide's header sizes and themes give:
 * logos of every shape (padded on a square canvas like many playlist logos, wide, very wide, square, tall) sit in the
 * same box on the cell's left edge, centred vertically, trimmed and never stretched; no logo or a broken one shows the
 * name in the same place.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land")
class ListLogoLayoutTest {
    val rule = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<android.app.Application>()
            Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        }
    }).around(rule)

    /** A [cw]×[ch] opaque logo drawn in the middle of a transparent [w]×[h] canvas; its file URI. */
    private fun png(name: String, w: Int, h: Int, cw: Int = w, ch: Int = h): String {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val l = (w - cw) / 2f; val t = (h - ch) / 2f
        Canvas(b).drawRect(l, t, l + cw, t + ch, Paint().apply { color = android.graphics.Color.WHITE })
        val f = File(ApplicationProvider.getApplicationContext<android.app.Application>().cacheDir, "$name.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f.toURI().toString()
    }

    private class Logo(val name: String, val url: String?, val aspect: Float?)

    @Config(qualifiers = "w960dp-h540dp-land-xhdpi") @Test fun at1080p() = check(Density(2f, 1f))
    @Config(qualifiers = "w960dp-h540dp-land-tvdpi") @Test fun at720p() = check(Density(4f / 3f, 1f))

    private fun check(density: Density) {
        val logos = listOf(
            Logo("itv-like, padded square canvas", png("padded", 400, 400, 300, 120), 2.5f),
            Logo("u&dave-like wide", png("wide", 300, 100), 3f),
            Logo("very wide wordmark", png("verywide", 500, 80), 6.25f),
            Logo("bbc-like square", png("square", 200, 200), 1f),
            Logo("tall", png("tall", 120, 240), .5f),
            Logo("missing", null, null),
            Logo("broken link", "file:///nowhere/none.png", null),
        )
        val cell = 190.dp // Glass: channel column 195dp less its 5dp gap
        // Row heights from the header sizes and themes (Large header with 5 rows … Small with 10, Retro, Xcube, Plus).
        val rows = listOf(24f, 27f, 30f, 32f, 36f, 44f, 56f)
        var case by mutableStateOf(rows[0] to logos[0])
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                val (rh, lg) = case
                val ch = ChannelEntity(id = 1, providerId = 1, categoryId = null, remoteId = "1", name = "Channel", logo = lg.url, number = 101, tvgId = null, streamUrl = "http://x")
                val (bw, bh) = ListLogoSize.box(rh.dp, cell)
                key(case) {
                    Row(Modifier.padding(start = 30.dp, top = 40.dp).size(cell, rh.dp).testTag("cell"), verticalAlignment = Alignment.CenterVertically) {
                        ListLogo(ch, listOf(LogoKind.PLAYLIST), bw, bh, Modifier.weight(1f).fillMaxHeight()) { Text("Channel", Modifier.testTag("name")) }
                    }
                }
            }
        }
        for (rh in rows) for (lg in logos) {
            case = rh to lg
            val tag = if (lg.aspect == null) "name" else "listLogo"
            rule.waitUntil(5_000) { rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            val where = "${lg.name} row=$rh @${density.density}"
            val c = bounds("cell")
            val b = bounds(tag)
            // Same left edge for every logo, and for the name in its place.
            assertTrue("$where: left ${b.left} != ${c.left}", kotlin.math.abs((b.left - c.left).value) < .6f)
            if (lg.aspect == null) continue
            val (bw, bh) = ListLogoSize.box(rh.dp, cell)
            val w = (b.right - b.left).value; val h = (b.bottom - b.top).value
            assertTrue("$where: ${w}x$h outside the ${bw}x$bh box", w <= bw.value + .6f && h <= bh.value + .6f)
            // Centred vertically in the row.
            val mid = ((b.top + b.bottom) / 2 - (c.top + c.bottom) / 2).value
            assertTrue("$where: off centre by $mid", kotlin.math.abs(mid) < .8f)
            // Trimmed and not stretched: the drawn box has the visible part's shape (pixel rounding allowed).
            assertTrue("$where: aspect ${w / h} != ${lg.aspect}", kotlin.math.abs(w / h - lg.aspect) / lg.aspect < .12f)
            // Square/tall/normal-wide logos get the full height; very wide ones a little less or the box's width.
            if (lg.aspect < ListLogoSize.WIDE) assertTrue("$where: height $h < ${bh.value}", h >= bh.value - .8f)
            else assertTrue("$where: very wide too tall $h", h <= bh.value * ListLogoSize.WIDE_HEIGHT + .8f)
        }
    }

    private fun bounds(tag: String): DpRect = rule.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
}
