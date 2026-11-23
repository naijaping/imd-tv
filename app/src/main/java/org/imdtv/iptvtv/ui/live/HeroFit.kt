package org.imdtv.iptvtv.ui.live

/**
 * The guide header's fixed parts, in dp (font scale is pinned to 1, so sp = dp): what the Header channel logo and
 * Header title size settings must leave room for.
 */
data class HeroBlock(
    /** The channel logo's height at 1×. */ val logoH: Float,
    /** The title's font size at 1×. */ val titleSp: Float,
    /** Title under the logo (else beside it). */ val stacked: Boolean,
    /** Padding around the logo/title block: 9 + 5 stacked, 5 (2) under the row. */ val topGap: Float,
    /** The progress bar shows (9 + 2.5). */ val progress: Boolean,
    /** One description line and its padding, when there is one: always kept. */ val descLine: Float,
)

data class HeroScales(val logo: Float, val title: Float)

/** A title line is about this many times its font size. */
internal const val TITLE_LINE = 1.3f
/** The "30m · Started at 10:45" line. */
internal const val META_LINE = 18f

/** The header's height with [logo] and [title] scales. */
internal fun heroHeight(b: HeroBlock, logo: Float, title: Float): Float {
    val l = b.logoH * logo
    val t = b.titleSp * title * TITLE_LINE
    return (if (b.stacked) l + t else maxOf(l, t)) + b.topGap + META_LINE + (if (b.progress) 11.5f else 0f) + b.descLine
}

/**
 * The chosen scales, or smaller ones (never below 1× for a scale above it) when the header would be taller than
 * [avail]: both come down together in 0.05 steps, so a 2× title in the Small header is as big as still fits.
 */
fun fitHeroScales(logo: Float, title: Float, b: HeroBlock, avail: Float): HeroScales {
    var l = logo
    var t = title
    while (heroHeight(b, l, t) > avail && (l > 1f || t > 1f)) {
        l = maxOf(minOf(1f, logo), l - .05f)
        t = maxOf(minOf(1f, title), t - .05f)
    }
    return HeroScales(l, t)
}
