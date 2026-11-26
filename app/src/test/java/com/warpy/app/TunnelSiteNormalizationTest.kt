package com.warpy.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TunnelSiteNormalizationTest {
    @Test
    fun `preserves www host for exact service routing`() {
        assertEquals(
            "www.google.com",
            normalizeTunnelSite("https://www.google.com/search?q=warpy"),
        )
    }

    @Test
    fun `accepts top level domain suffixes`() {
        assertEquals("ru", normalizeTunnelSite("ru"))
        assertEquals("ru", normalizeTunnelSite(" .RU. "))
        assertEquals("su", normalizeTunnelSite("su"))
        assertEquals("com", normalizeTunnelSite("com"))
        assertEquals("xn--p1ai", normalizeTunnelSite("xn--p1ai"))
    }

    @Test
    fun `rejects invalid site values`() {
        assertNull(normalizeTunnelSite("not-a-domain"))
        assertNull(normalizeTunnelSite("r"))
        assertNull(normalizeTunnelSite("ru com"))
        assertNull(normalizeTunnelSite(""))
    }
}
