package com.warpy.app

import com.warpy.app.localization.WarpyLocalization
import com.warpy.app.localization.resolveAppLanguage
import com.warpy.app.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class WarpyLocalizationTest {
    @Test
    fun explicitLanguageRemainsSelected() {
        assertEquals(AppLanguage.English, resolveAppLanguage(AppLanguage.English, "ru"))
        assertEquals(AppLanguage.Russian, resolveAppLanguage(AppLanguage.Russian, "en"))
    }

    @Test
    fun russianKeepsCanonicalTextAndEnglishTranslatesIt() {
        assertEquals(
            "VPN работает",
            WarpyLocalization.text("VPN работает", AppLanguage.Russian, "en"),
        )
        assertEquals(
            "VPN is running",
            WarpyLocalization.text("VPN работает", AppLanguage.English, "ru"),
        )
    }

    @Test
    fun dynamicMessagesAndUnitsAreTranslated() {
        assertEquals(
            "Profiles imported: 3",
            WarpyLocalization.text("Импортировано профилей: 3", AppLanguage.English),
        )
        assertEquals(
            "42 ms",
            WarpyLocalization.text("42 мс", AppLanguage.English),
        )
        assertEquals(
            "The profile failed: the server is not responding",
            WarpyLocalization.text(
                "Профиль не подключился: сервер не отвечает",
                AppLanguage.English,
            ),
        )
    }
}
