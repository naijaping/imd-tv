package org.imdtv.iptvtv.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation
import org.imdtv.iptvtv.data.db.ChannelEntity
import org.imdtv.iptvtv.data.logos.shownLogos
import org.imdtv.iptvtv.data.settings.LogoKind
import kotlin.math.roundToInt

/**
 * How a logo sits in a list's logo column (the guide's Logo only mode): every logo in the same box, its left edge on the
 * column's, fitted without stretching. Pure numbers, so they are tested on the JVM.
 */
object ListLogoSize {
    /** The box: at most this share of the row's height… */
    const val HEIGHT = .54f
    /** …and of the channel cell's width (the rest is the gap before the number). */
    const val WIDTH = .48f
    /** A logo this many times wider than tall (or more) is "very wide": e.g. a long wordmark… */
    const val WIDE = 3.2f
    /** …and gets a little less height, so it doesn't look heavier than square ones beside it. */
    const val WIDE_HEIGHT = .84f

    /** The box for a row [rowHeight] tall in a channel cell [cellWidth] wide. */
    fun box(rowHeight: Dp, cellWidth: Dp): Pair<Dp, Dp> = cellWidth * WIDTH to rowHeight * HEIGHT

    /** Width and height of a logo [aspect] (width / height) wide, fitted into [maxW] × [maxH]. */
    fun fit(aspect: Float, maxW: Float, maxH: Float): Pair<Float, Float> {
        val a = aspect.takeIf { it.isFinite() && it > 0f } ?: 1f
        val h = if (a >= WIDE) maxH * WIDE_HEIGHT else maxH
        val w = minOf(maxW, h * a)
        return w to w / a
    }

    /**
     * The smallest rectangle (left, top, right, bottom; right/bottom exclusive) holding every pixel of [argb] ([w] × [h])
     * more opaque than [minAlpha]; null when there's nothing to trim (no transparent margin) or nothing visible at all.
     */
    fun opaqueBounds(argb: IntArray, w: Int, h: Int, minAlpha: Int = 24): IntArray? {
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) if ((argb[row + x] ushr 24) > minAlpha) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                b = y
            }
        }
        if (r < 0) return null // fully transparent: leave it be
        val out = intArrayOf(l, t, r + 1, b + 1)
        return if (l == 0 && t == 0 && r + 1 == w && b + 1 == h) null else out
    }
}

/**
 * Crops a logo's transparent margins (many logos are padded square canvases, so the visible part came out tiny or off
 * to the side), then scales it down to fit [maxW] × [maxH] px. Coil keeps the result in its memory cache under the URL,
 * size and this [cacheKey], so each logo is trimmed once at its display size; the download stays in Coil's disk cache.
 */
class TrimTransparent(private val maxW: Int, private val maxH: Int) : Transformation {
    override val cacheKey = "trim-$maxW-$maxH"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (Build.VERSION.SDK_INT >= 26 && input.config == Bitmap.Config.HARDWARE) return input
        val w = input.width; val h = input.height
        if (w <= 0 || h <= 0) return input
        val px = IntArray(w * h)
        input.getPixels(px, 0, w, 0, 0, w, h)
        val box = ListLogoSize.opaqueBounds(px, w, h)
        val cw = box?.let { it[2] - it[0] } ?: w
        val ch = box?.let { it[3] - it[1] } ?: h
        val scale = minOf(1f, maxW.toFloat() / cw, maxH.toFloat() / ch)
        val ow = (cw * scale).roundToInt().coerceAtLeast(1)
        val oh = (ch * scale).roundToInt().coerceAtLeast(1)
        if (box == null && ow == w && oh == h) return input
        val cropped = if (box == null) input else Bitmap.createBitmap(input, box[0], box[1], cw, ch)
        return if (ow == cw && oh == ch) cropped
        else Bitmap.createScaledBitmap(cropped, ow, oh, true).also { if (cropped !== input) cropped.recycle() }
    }
}

/**
 * A channel's logo in a list's logo column: trimmed of transparent margins, fitted into the [ListLogoSize] box
 * ([boxW] × [boxH]) without stretching, on its left edge and centred vertically. Nothing while it loads (a cached logo
 * is there at once); [fallback] (the channel's name, in the same place) when it has none or every link fails.
 */
@Composable
fun ListLogo(ch: ChannelEntity, source: List<LogoKind>, boxW: Dp, boxH: Dp, modifier: Modifier, fallback: @Composable () -> Unit) {
    val urls = shownLogos(ch, source)
    var tried by remember(urls) { mutableIntStateOf(0) } // failed loads so far: e.g. tv-logos unreachable → playlist logo
    val url = urls.getOrNull(tried)
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        if (url == null) { fallback(); return@Box }
        val density = LocalDensity.current
        val wPx = with(density) { boxW.roundToPx() }.coerceAtLeast(1)
        val hPx = with(density) { boxH.roundToPx() }.coerceAtLeast(1)
        val context = LocalContext.current
        // Decoded at twice the box, so a logo with wide margins still has its box's worth of pixels once trimmed.
        val request = remember(url, wPx, hPx) {
            ImageRequest.Builder(context).data(url).size(wPx * 2, hPx * 2).transformations(TrimTransparent(wPx, hPx)).build()
        }
        val painter = rememberAsyncImagePainter(request, onError = { if (tried < urls.size) tried++ })
        when (val st = painter.state) {
            is AsyncImagePainter.State.Success -> {
                val d = st.result.drawable
                val aspect = if (d.intrinsicHeight > 0) d.intrinsicWidth.toFloat() / d.intrinsicHeight else 1f
                val (w, h) = ListLogoSize.fit(aspect, boxW.value, boxH.value)
                Image(painter, null, Modifier.size(w.dp, h.dp).testTag("listLogo"), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
            }
            is AsyncImagePainter.State.Error -> fallback() // the last link failed (an earlier one moves on to the next)
            else -> {}
        }
    }
}
