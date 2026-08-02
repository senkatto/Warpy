package com.warpy.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WarpyUiSmokeTest {
    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        launchWarpy()
    }

    @Test
    fun topLevelSurfacesReturnToMainScreen() {
        requiredDescription(SETTINGS).click()
        assertNotNull(device.wait(Until.findObject(By.text(SETTINGS)), UI_TIMEOUT_MS))
        device.pressBack()
        requiredDescription(SETTINGS)

        requiredDescription(ADD_PROFILE).click()
        assertNotNull(device.wait(Until.findObject(By.textContains("QR")), UI_TIMEOUT_MS))
        device.pressBack()
        requiredDescription(ADD_PROFILE)

        requiredDescription(OPEN_PROFILES).click()
        requiredDescription(CLOSE_PROFILES)
        device.pressBack()
        requiredDescription(OPEN_PROFILES)
    }

    @Test
    fun backgroundRoundTripKeepsRenderedConnectionState() {
        val stateBefore = renderedConnectionState()

        device.pressHome()
        Thread.sleep(BACKGROUND_SETTLE_MS)
        launchWarpy()

        assertEquals(stateBefore, renderedConnectionState())
    }

    @Test
    fun connectedVpnServiceSurvivesBackground() {
        assumeTrue("A configured, connected profile is required", renderedConnectionState() == VPN_ON)

        device.pressHome()
        Thread.sleep(BACKGROUND_SETTLE_MS)

        val services = device.executeShellCommand(
            "dumpsys activity services $PACKAGE/.vpn.WarpyService",
        )
        assumeTrue("Service dumps are unavailable on this device", services.isNotBlank())
        assertTrue("VPN foreground service disappeared in background", services.contains("WarpyService"))
        assertTrue("VPN service left foreground state", services.contains("isForeground=true"))

        launchWarpy()
        assertEquals(VPN_ON, renderedConnectionState())
    }

    @Test
    fun importProfileFromInstrumentationArgument() {
        val arguments = InstrumentationRegistry.getArguments()
        val encodedProfile = arguments.getString(PROFILE_BASE64_ARGUMENT).orEmpty()
        val profile = if (encodedProfile.isNotBlank()) {
            String(
                Base64.decode(encodedProfile, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                Charsets.UTF_8,
            )
        } else {
            arguments.getString(PROFILE_ARGUMENT).orEmpty()
        }
        val expectedName = arguments.getString(PROFILE_NAME_ARGUMENT).orEmpty()
        assumeTrue("Pass -e profile to run the profile import test", profile.isNotBlank())

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = targetContext.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Warpy test profile", profile))

        requiredDescription(ADD_PROFILE).click()
        val paste = device.wait(Until.findObject(By.text(PASTE_PROFILE)), UI_TIMEOUT_MS)
        assertNotNull("Clipboard import action is missing", paste)
        paste.click()

        requiredDescription(OPEN_PROFILES)
        if (expectedName.isNotBlank()) {
            assertNotNull(
                "Imported profile was not selected",
                device.wait(Until.findObject(By.text(expectedName)), UI_TIMEOUT_MS),
            )
        }
    }

    private fun launchWarpy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(PACKAGE))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
        assertNotNull(
            "Warpy did not become visible",
            device.wait(Until.findObject(By.pkg(PACKAGE)), UI_TIMEOUT_MS),
        )
        requiredDescription(SETTINGS)
    }

    private fun renderedConnectionState(): String {
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            CONNECTION_STATES.firstOrNull { device.hasObject(By.desc(it)) }?.let { return it }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        error("Connection control was not rendered")
    }

    private fun requiredDescription(description: String): UiObject2 =
        requireNotNull(device.wait(Until.findObject(By.desc(description)), UI_TIMEOUT_MS)) {
            "Missing UI element: $description"
        }

    private companion object {
        const val PACKAGE = "com.warpy.app"
        const val SETTINGS = "Настройки"
        const val ADD_PROFILE = "Добавить профиль"
        const val PASTE_PROFILE = "Вставить из буфера"
        const val OPEN_PROFILES = "Открыть список профилей"
        const val CLOSE_PROFILES = "Закрыть список профилей"
        const val VPN_ON = "Выключить VPN"
        const val VPN_OFF = "Включить VPN"
        const val UI_TIMEOUT_MS = 10_000L
        const val BACKGROUND_SETTLE_MS = 2_000L
        const val POLL_INTERVAL_MS = 100L
        const val PROFILE_ARGUMENT = "profile"
        const val PROFILE_BASE64_ARGUMENT = "profileBase64"
        const val PROFILE_NAME_ARGUMENT = "profileName"
        val CONNECTION_STATES = listOf(VPN_ON, VPN_OFF)
    }
}
