package com.shilapi.xcertplay.update

import androidx.core.content.FileProvider
import com.shilapi.xcertplay.FileProviderPathTestShadow
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33], shadows = [FileProviderPathTestShadow::class])
class UpdateInstallCompatibilityTest {

    // Robolectric gives each test a new cache dir; FileProvider keeps the first one in a static cache.
    @Before fun resetFileProviderCache() {
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    @Test fun providerRejectsSiblingDirectoriesWithTheSameUpdatePrefix() {
        val context = RuntimeEnvironment.getApplication()
        val outside = File(context.cacheDir, "update-other/DiPlay.apk")
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, "${context.packageName}.update-apks", outside)
        }
    }

}
