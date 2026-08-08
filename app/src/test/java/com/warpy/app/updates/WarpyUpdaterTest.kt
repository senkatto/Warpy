package com.warpy.app.updates

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.json.JSONArray

class WarpyUpdaterTest {
    @Test
    fun boundsReleaseFeedWithAndWithoutContentLength() {
        val exact = ByteArray(MAX_RELEASE_FEED_BYTES.toInt()) { 'x'.code.toByte() }
        assertEquals(
            exact.size,
            readUpdateFeed(ByteArrayInputStream(exact), exact.size.toLong()).length,
        )
        assertFailsWith<IllegalArgumentException> {
            readUpdateFeed(ByteArrayInputStream(byteArrayOf()), MAX_RELEASE_FEED_BYTES + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            readUpdateFeed(
                ByteArrayInputStream(ByteArray(MAX_RELEASE_FEED_BYTES.toInt() + 1)),
                -1,
            )
        }
    }

    @Test
    fun rejectsOversizedUpdateDownloads() {
        requireUpdateSize(MAX_UPDATE_APK_BYTES)
        assertFailsWith<IllegalArgumentException> {
            requireUpdateSize(MAX_UPDATE_APK_BYTES + 1)
        }
    }

    @Test
    fun selectsNewestStableReleaseThatContainsAndroidInstaller() {
        val releases = JSONArray(
            """[
              {"tag_name":"v9.0.0","draft":false,"prerelease":false,"assets":[
                {"name":"Warpy-Windows.exe","browser_download_url":"https://github.com/senkatto/Warpy/releases/download/v9.0.0/Warpy-Windows.exe"}
              ]},
              {"tag_name":"v0.1.15","draft":false,"prerelease":false,"assets":[
                {"name":"Warpy-Android.apk","browser_download_url":"https://github.com/senkatto/Warpy/releases/download/v0.1.15/Warpy-Android.apk"}
              ]},
              {"tag_name":"v0.1.14","draft":false,"prerelease":false,"assets":[]}
            ]""",
        )

        assertEquals("0.1.15", selectAndroidRelease(releases, "0.1.13")?.version)
    }

    @Test
    fun rejectsCurrentPrereleaseAndUntrustedAssets() {
        val releases = JSONArray(
            """[
              {"tag_name":"v0.1.13","draft":false,"prerelease":false,"assets":[]},
              {"tag_name":"v0.1.14","draft":false,"prerelease":true,"assets":[]},
              {"tag_name":"v0.1.15","draft":false,"prerelease":false,"assets":[
                {"name":"Warpy-Android.apk","browser_download_url":"https://example.com/Warpy-Android.apk"}
              ]}
            ]""",
        )

        assertNull(selectAndroidRelease(releases, "0.1.13"))
    }
}
