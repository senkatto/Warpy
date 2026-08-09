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
    fun `rejects invalid site values`() {
        assertNull(normalizeTunnelSite("not-a-domain"))
    }
}
