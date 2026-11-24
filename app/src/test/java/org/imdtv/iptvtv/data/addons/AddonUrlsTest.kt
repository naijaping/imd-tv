package org.imdtv.iptvtv.data.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddonUrlsTest {
    @Test fun normalizesPastedLinks() {
        assertEquals("https://v3-cinemeta.strem.io/manifest.json", AddonUrls.normalize("  https://v3-cinemeta.strem.io/manifest.json "))
        assertEquals("https://torrentio.strem.fun/sort=qualitysize/manifest.json", AddonUrls.normalize("stremio://torrentio.strem.fun/sort=qualitysize/manifest.json"))
        assertEquals("https://catalog.nuvio.tv/manifest.json", AddonUrls.normalize("https://catalog.nuvio.tv"))
        assertEquals("https://catalog.nuvio.tv/manifest.json", AddonUrls.normalize("https://catalog.nuvio.tv/"))
        assertEquals("https://x.example/abc/manifest.json?token=1", AddonUrls.normalize("https://x.example/abc/manifest.json?token=1"))
        for (bad in listOf("", "catalog.nuvio.tv/manifest.json", "ftp://x/manifest.json", "https://exa mple.com/manifest.json", "magnet:?xt=urn")) assertNull(bad, AddonUrls.normalize(bad))
    }

    @Test fun resourcesKeepTheQueryAndEncodeIds() {
        val m = "https://x.example/cfg123/manifest.json?token=abc"
        assertEquals("https://x.example/cfg123/stream/series/tt0903747%3A1%3A2.json?token=abc", AddonUrls.resource(m, "stream", "series", "tt0903747:1:2"))
        assertEquals("https://x.example/cfg123/catalog/movie/top/search=the%20matrix&skip=20.json?token=abc",
            AddonUrls.resource(m, "catalog", "movie", "top", AddonUrls.extra("search" to "the matrix", "genre" to null, "skip" to "20")))
        assertEquals("https://v3-cinemeta.strem.io/meta/movie/tt0133093.json", AddonUrls.resource("https://v3-cinemeta.strem.io/manifest.json", "meta", "movie", "tt0133093"))
    }

    @Test fun masksPersonalParts() {
        assertEquals("https://v3-cinemeta.strem.io/manifest.json", AddonUrls.mask("https://v3-cinemeta.strem.io/manifest.json"))
        assertEquals("https://torrentio.strem.fun/…/manifest.json", AddonUrls.mask("https://torrentio.strem.fun/realdebrid=SECRETKEY123/manifest.json"))
        assertEquals("http://nas:7000/…/manifest.json", AddonUrls.mask("http://nas:7000/manifest.json?token=SECRET"))
        assertEquals(false, AddonUrls.mask("https://a.example/SECRETKEY123/manifest.json?k=SECRET").contains("SECRET"))
    }
}
