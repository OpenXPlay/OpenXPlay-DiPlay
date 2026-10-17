package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration
import com.shilapi.xcertplay.host.R
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What DiPlay says about the car hotspot before and after its name is saved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1000dp-h700dp-land")
class CarHotspotNameTextTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    // A plain space at the end of a string resource is dropped; Traditional Chinese read "… ·Car 5G".
    @Test fun theNameIsSetApartFromTheButtonTitleInEveryLanguage() {
        for (tag in listOf("en", "ar", "es", "ja", "ru", "uk", "zh-CN", "zh-TW")) {
            val localized = context.createConfigurationContext(
                Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
            assertTrue(tag, localized.getString(R.string.edit_saved_hotspot_prefix).endsWith(" · "))
        }
    }

}
