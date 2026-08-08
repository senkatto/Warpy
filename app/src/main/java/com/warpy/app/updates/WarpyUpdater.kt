package com.warpy.app.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.warpy.app.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

private const val RELEASES_API =
    "https://api.github.com/repos/senkatto/Warpy/releases?per_page=30"
private const val TRUSTED_RELEASE_PATH = "/senkatto/Warpy/releases/download/"
internal const val MAX_UPDATE_APK_BYTES = 200L * 1024 * 1024
internal const val MAX_RELEASE_FEED_BYTES = 1L * 1024 * 1024
private val VERSION_TAG = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)$")

enum class UpdateStage {
    Idle,
    Checking,
    Available,
    Downloading,
    Ready,
    AwaitingPermission,
    Installing,
    Error,
}

data class UpdateUiState(
    val stage: UpdateStage = UpdateStage.Idle,
    val version: String = "",
    val progress: Int? = null,
    val apkPath: String = "",
    val message: String = "",
)

data class AndroidRelease(
    val version: String,
    val apkUrl: String,
)

class WarpyUpdater(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun check(): AndroidRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RELEASES_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Warpy Android updater")
            .build()
        val releases = client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GitHub returned HTTP ${response.code}" }
            val body = checkNotNull(response.body)
            JSONArray(readUpdateFeed(body.byteStream(), body.contentLength()))
        }
        selectAndroidRelease(releases, BuildConfig.VERSION_NAME)
    }

    suspend fun download(
        release: AndroidRelease,
        onProgress: suspend (Int?) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        require(isTrustedReleaseUrl(release.apkUrl)) {
            "Untrusted update URL"
        }

        val target = File(context.cacheDir, "warpy-update.apk")
        val temporary = File(context.cacheDir, "warpy-update.apk.part")
        temporary.delete()
        try {
            val request = Request.Builder()
                .url(release.apkUrl)
                .header("User-Agent", "Warpy Android updater")
                .build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Download returned HTTP ${response.code}" }
                val body = checkNotNull(response.body)
                body.contentLength().takeIf { it >= 0 }?.let(::requireUpdateSize)
                val total = body.contentLength().takeIf { it > 0 }
                body.byteStream().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            requireUpdateSize(downloaded)
                            onProgress(total?.let { ((downloaded * 100 / it).coerceIn(0, 99)).toInt() })
                        }
                    }
                }
            }
            validateApk(temporary, release.version)
            target.delete()
            check(temporary.renameTo(target)) { "Could not finalize update file" }
            onProgress(100)
            target
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    fun canRequestPackageInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    )

    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun validateApk(apk: File, expectedVersion: String) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archive = checkNotNull(context.packageManager.getPackageArchiveInfo(apk.path, flags)) {
            "Downloaded file is not an APK"
        }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        check(archive.packageName == context.packageName) { "Update package name mismatch" }
        check(archive.versionName == expectedVersion) { "Update version mismatch" }
        check(archive.longVersionCodeCompat() > installed.longVersionCodeCompat()) {
            "Update version code is not newer"
        }
        check(archive.signingFingerprints() == installed.signingFingerprints()) {
            "Update signature mismatch"
        }
    }
}

internal fun requireUpdateSize(bytes: Long) {
    require(bytes <= MAX_UPDATE_APK_BYTES) { "Update file is too large" }
}

internal fun readUpdateFeed(input: InputStream, declaredLength: Long): String {
    if (declaredLength >= 0) {
        require(declaredLength <= MAX_RELEASE_FEED_BYTES) { "Release feed is too large" }
    }
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= MAX_RELEASE_FEED_BYTES) { "Release feed is too large" }
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}

internal fun selectAndroidRelease(releases: JSONArray, currentVersion: String): AndroidRelease? {
    val candidates = buildList {
        for (index in 0 until releases.length()) {
            val release = releases.getJSONObject(index)
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            val match = VERSION_TAG.matchEntire(release.optString("tag_name")) ?: continue
            val version = match.groupValues.drop(1).map(String::toInt)
            if (compareVersions(version, parseVersion(currentVersion)) <= 0) continue
            val assets = release.optJSONArray("assets") ?: continue
            var apkName = ""
            var apkUrl = ""
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.getJSONObject(assetIndex)
                val name = asset.optString("name")
                if (name == "Warpy-Android.apk" || name.endsWith("-android-arm64-v8a.apk")) {
                    apkName = name
                    apkUrl = asset.optString("browser_download_url")
                    break
                }
            }
            if (apkName.isBlank() || !isTrustedReleaseUrl(apkUrl)) continue
            add(version to apkUrl)
        }
    }
    val selected = candidates.maxWithOrNull { left, right -> compareVersions(left.first, right.first) }
        ?: return null
    return AndroidRelease(
        version = selected.first.joinToString("."),
        apkUrl = selected.second,
    )
}

private fun parseVersion(value: String): List<Int> =
    VERSION_TAG.matchEntire("v$value")?.groupValues?.drop(1)?.map(String::toInt) ?: listOf(0, 0, 0)

private fun compareVersions(left: List<Int>, right: List<Int>): Int {
    for (index in 0..2) {
        val result = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
        if (result != 0) return result
    }
    return 0
}

private fun isTrustedReleaseUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && uri.host == "github.com" && uri.path.orEmpty().startsWith(TRUSTED_RELEASE_PATH)
}.getOrDefault(false)

@Suppress("DEPRECATION")
private fun PackageInfo.longVersionCodeCompat(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

@Suppress("DEPRECATION")
private fun PackageInfo.signingFingerprints(): Set<String> {
    val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val info = checkNotNull(signingInfo)
        if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
    } else {
        signatures.orEmpty()
    }
    return signatures.mapTo(linkedSetOf()) { signature ->
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
