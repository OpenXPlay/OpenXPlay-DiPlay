// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-18
package com.shilapi.xcertplay

import android.app.Application
import android.os.Handler
import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutorService

class ViewAreaApplication : Application(), CarPlayViewAreaProvider {
    var areas: List<AirPlayViewArea>? = null
    var canvas: Pair<Int, Int>? = null
    override fun carPlayViewAreas(canvasWidth: Int, canvasHeight: Int): List<AirPlayViewArea>? {
        canvas = canvasWidth to canvasHeight
        return areas
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = ViewAreaApplication::class)
class CarPlayViewAreaProviderTest {
    @Test fun sessionPlistUsesApplicationAreasAndNullOrEmptyRestoresHostBehaviour() {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        fun field(name: String) = CarPlayHostActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("airPlayIdentity").set(host, AirPlayIdentity.generate())
        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(1280, 720)
        val create = CarPlayHostActivity::class.java.getDeclaredMethod("createAirPlayConfig", sizeClass).apply { isAccessible = true }
        val app = RuntimeEnvironment.getApplication() as ViewAreaApplication
        try {
            val baseline = (create.invoke(host, size) as AirPlayConfig).main
            app.areas = listOf(AirPlayViewArea(1280, 720), AirPlayViewArea(640, 700))
            val config = create.invoke(host, size) as AirPlayConfig
            assertEquals(config.main.widthPixels to config.main.heightPixels, app.canvas)
            assertEquals(app.areas, config.main.viewAreas)
            assertNull(field("pendingViewAreas").get(host))
            assertTrue(field("providerViewAreasInUse").getBoolean(host))
            val display = (AirPlayInfoPlist.build(config)["displays"] as List<*>).first() as Map<*, *>
            val areas = (display["viewAreas"] as List<*>).map { it as Map<*, *> }
            assertEquals(0, display["initialViewArea"])
            assertEquals(true, display["viewAreaTransitionControl"])
            assertEquals(listOf(1280, 640), areas.map { it["widthPixels"] })
            assertEquals(listOf(720, 700), areas.map { it["heightPixels"] })
            for (fallback in listOf(null, emptyList<AirPlayViewArea>())) {
                app.areas = fallback
                assertEquals(baseline, (create.invoke(host, size) as AirPlayConfig).main)
                assertFalse(field("providerViewAreasInUse").getBoolean(host))
            }
            assertFalse(SessionControl { false }.showViewArea(0))
        } finally {
            (field("mainHandler").get(host) as Handler).removeCallbacksAndMessages(null)
            for (name in listOf("teardownExecutor", "airPlayCommandExecutor"))
                (field(name).get(host) as ExecutorService).shutdownNow()
        }
    }
}
