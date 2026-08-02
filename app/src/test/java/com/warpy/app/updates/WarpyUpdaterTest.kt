package com.warpy.app.updates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.json.JSONArray

class WarpyUpdaterTest {
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
