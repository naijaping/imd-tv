package org.imdtv.iptvtv.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrandLogoUrlTest {
    @Test fun acceptsHttpAndHttps() {
        assertEquals("https://example.com/logo.png", BrandLogo.validUrl("  https://example.com/logo.png ")?.toString())
        assertEquals("http://nas:8080/img/logo.webp", BrandLogo.validUrl("http://nas:8080/img/logo.webp")?.toString())
        assertEquals("https://example.com/a.png?x=1", BrandLogo.validUrl("HTTPS://example.com/a.png?x=1")?.toString())
    }

    @Test fun rejectsTheRest() {
        for (bad in listOf("", "https://", "example.com/logo.png", "ftp://example.com/logo.png", "file:///sdcard/logo.png",
            "content://media/1", "https://exa mple.com/logo.png", "javascript:alert(1)")) {
            assertNull(bad, BrandLogo.validUrl(bad))
        }
    }
}
