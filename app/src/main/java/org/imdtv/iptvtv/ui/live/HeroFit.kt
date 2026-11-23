package org.imdtv.iptvtv.ui.live

import org.imdtv.iptvtv.data.settings.HeaderSize
import org.imdtv.iptvtv.data.settings.TitleSize

/**
 * The guide header (logo, title, meta line, description, progress bar) is one stacked layout at every Header size;
 * a smaller header scales the whole block down instead of moving the title beside the logo. Sizes in dp
 * (font scale is pinned to 1, so sp = dp; a 1080p and a 720p TV are both 960×540dp).
 */
data class HeroBlock(
    /** The channel logo's height at 1× (its width is 60/43 of it). */ val logoH: Float,
    /** The title's font size at 1×. */ val titleSp: Float,
    /** Logo → title. */ val gapTitle: Float,
    /** Title → meta line. */ val gapMeta: Float,
    /** Meta line → description. */ val gapDesc: Float,
) {
    companion object {
        /** Large is the reference; Medium and Small shrink the logo and the gaps so the same stack fits their height. */
        fun of(size: HeaderSize): HeroBlock {
            val k = when (size) { HeaderSize.LARGE -> 1f; HeaderSize.MEDIUM -> .7f; HeaderSize.SMALL -> .45f }
            return HeroBlock(43f * k, 31f * TitleSize.valueOf(size.name).scale, 9f * k, 5f * k, 7f * k)
        }
    }
}

/** What the header shows: the scales after fitting, title lines allowed, whether the progress bar fits. */
data class HeroPlan(val logo: Float, val title: Float, val titleLines: Int, val progress: Boolean)

/** A title line is at most this many times its font size (Inter ≈1.21, Roboto ≈1.17): a little room to spare. */
internal const val TITLE_LINE = 1.3f
/** The "30m · Started at 10:45" line. */
internal const val META_LINE = 18f
/** The progress bar and its gap. */
internal const val PROGRESS = 11.5f
internal const val DESC_LINE = 19f

/** The header's height with these scales, title lines, progress bar and description lines. */
internal fun heroHeight(b: HeroBlock, logo: Float, title: Float, titleLines: Int, progress: Boolean, descLines: Int): Float =
    b.logoH * logo + b.gapTitle + b.titleSp * title * TITLE_LINE * titleLines + b.gapMeta + META_LINE +
        (if (descLines > 0) b.gapDesc + DESC_LINE * descLines else 0f) + (if (progress) PROGRESS else 0f)

/**
 * Fits the header into [avail] dp. The chosen Header channel logo / title sizes come down together (0.05 steps,
 * never below 1× or the chosen size if smaller) until logo, one title line, meta line, progress bar and one
 * description line fit. If even that doesn't fit, the description and then the progress bar make way. A second
 * title line is allowed only when it costs none of those. The description itself takes whatever lines are left.
 */
fun planHero(b: HeroBlock, logo: Float, title: Float, hasProgress: Boolean, hasDesc: Boolean, avail: Float): HeroPlan {
    val desc = if (hasDesc) 1 else 0
    var l = logo
    var t = title
    while (heroHeight(b, l, t, 1, hasProgress, desc) > avail && (l > 1f || t > 1f)) {
        l = maxOf(minOf(1f, logo), l - .05f)
        t = maxOf(minOf(1f, title), t - .05f)
    }
    val progress = hasProgress && heroHeight(b, l, t, 1, true, 0) <= avail
    val lines = if (heroHeight(b, l, t, 2, progress, if (heroHeight(b, l, t, 1, progress, desc) <= avail) desc else 0) <= avail) 2 else 1
    return HeroPlan(l, t, lines, progress)
}
