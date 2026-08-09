import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun releaseValue(name: String): String? =
    providers.gradleProperty(name).orNull ?: providers.environmentVariable(name).orNull

val releaseStoreFile = releaseValue("RELEASE_STORE_FILE")
val releaseStorePassword = releaseValue("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseValue("RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseValue("RELEASE_KEY_PASSWORD")
val productionSigningConfigured = releaseStoreFile != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null &&
    file(releaseStoreFile).exists()

val hiddifyCoreVersion = "4.1.0"
val hiddifyCoreArchiveSha256 = "6C4841F7AAB23EB1FB17831349ECDFC3CA9C31553B8CBE5EFFD820CB12607F56"
val hiddifyCoreAarSha256 = "8BC1CE38BCA2DD3E13022A4457336602490F2E7D063626A0192D89209A49D07E"
val hiddifyCoreAar = layout.projectDirectory.file("libs/hiddify-core.aar").asFile

fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02X".format(it) }
}

val fetchHiddifyCore by tasks.registering {
    val archive = layout.buildDirectory.file(
        "downloads/hiddify-lib-android-v$hiddifyCoreVersion.tar.gz",
    ).get().asFile
    outputs.file(hiddifyCoreAar)
    outputs.upToDateWhen {
        hiddifyCoreAar.isFile && hiddifyCoreAar.sha256() == hiddifyCoreAarSha256
    }

    doLast {
        archive.parentFile.mkdirs()
        hiddifyCoreAar.parentFile.mkdirs()
        if (!archive.isFile || archive.sha256() != hiddifyCoreArchiveSha256) {
            val temporary = File(archive.parentFile, "${archive.name}.tmp")
            temporary.delete()
            val url =
                "https://github.com/hiddify/hiddify-core/releases/download/" +
                    "v$hiddifyCoreVersion/hiddify-lib-android.tar.gz"
            val connection = URI(url).toURL().openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 180_000
            }
            connection.getInputStream().use { input ->
                temporary.outputStream().buffered().use(input::copyTo)
            }
            check(temporary.sha256() == hiddifyCoreArchiveSha256) {
                "Hiddify Android archive checksum mismatch."
            }
            Files.move(
                temporary.toPath(),
                archive.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }

        hiddifyCoreAar.delete()
        copy {
            from(tarTree(resources.gzip(archive))) {
                include("hiddify-core.aar")
            }
            into(hiddifyCoreAar.parentFile)
        }
        check(hiddifyCoreAar.isFile && hiddifyCoreAar.sha256() == hiddifyCoreAarSha256) {
            "Hiddify Android AAR checksum mismatch."
        }
    }
}

if (gradle.startParameter.taskNames.any { it.contains("Production") || it.contains("DeviceTest") } &&
    !productionSigningConfigured) {
    throw GradleException(
        "Production signing is not configured. Set RELEASE_STORE_FILE, " +
            "RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS and RELEASE_KEY_PASSWORD.",
    )
}

val checkMojibakeText by tasks.registering {
    group = "verification"
    description = "Fails when source files contain common mojibake sequences."

    val scannedRoots = listOf(
        layout.projectDirectory.dir("src/main"),
        layout.projectDirectory.file("build.gradle.kts"),
    )
    inputs.files(scannedRoots)

    doLast {
        val markers = listOf(
            "\u00d0",
            "\u00d1",
            "\u0420\u045f",
            "\u0420\u040e",
            "\u0420\u045c",
            "\u0420\u201d",
            "\u0420\u2018",
            "\u0420\u0490",
            "\u0420\u0458",
            "\u0421\u0453",
            "\u0421\u0402",
            "\u0421\u201a",
            "\u0421\u040a",
            "\u0421\u040f",
            "\u0421\u2039",
            "\u0421\u040e",
            "\u0432\u0402",
            "\ufffd",
            "?".repeat(4),
        )
        val allowedExtensions = setOf("kt", "kts", "xml", "json", "properties")
        val findings = scannedRoots
            .flatMap { root ->
                val file = root.asFile
                if (file.isFile) listOf(file) else file.walkTopDown().filter { it.isFile }.toList()
            }
            .filter { it.extension in allowedExtensions }
            .flatMap { file ->
                file.readLines(Charsets.UTF_8).mapIndexedNotNull { index, line ->
                    if (markers.any(line::contains)) {
                        "${file.relativeTo(projectDir)}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
            }

        if (findings.isNotEmpty()) {
            throw GradleException(
                "Possible mojibake text found:\n" + findings.joinToString("\n"),
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn(checkMojibakeText)
    dependsOn(fetchHiddifyCore)
}

android {
    namespace = "com.warpy.app"
    compileSdk = 36
    testBuildType = providers.gradleProperty("warpyTestBuildType").orElse("debug").get()

    defaultConfig {
        applicationId = "com.warpy.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 20
        versionName = "1.0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            if (productionSigningConfigured) {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
        create("production") {
            if (productionSigningConfigured) {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (productionSigningConfigured) {
                signingConfigs.getByName("release")
            } else {
                null
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("production") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("production")
        }
        create("deviceTest") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("production")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        disable += "NullSafeMutableLiveData"
        disable += "FrequentlyChangingValue"
        disable += "RememberInComposition"
        disable += "AutoboxingStateCreation"
    }
}


kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.snakeyaml:snakeyaml-engine:3.0.1")
    implementation(files(hiddifyCoreAar))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.20")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
    androidTestImplementation("androidx.tracing:tracing:1.2.0")
}
