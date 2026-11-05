// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-11-04
package com.shilapi.xcertplay

import android.app.Application
import android.os.Handler
import android.view.Surface
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.android.util.concurrent.PausedExecutorService

class CanvasApplication : Application(), CarPlayCanvasProvider {
    var canvas: Pair<Int, Int>? = 1920 to 720
    override fun carPlayCanvas(): Pair<Int, Int>? = canvas
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = CanvasApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayCanvasProviderTest {
    private lateinit var activity: CarPlayHostActivity
    private val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")

    @Before fun setUp() {
        AirPlayPersistence.saveAdaptPipResolution(RuntimeEnvironment.getApplication(), false)
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        // A rebuild tears down on this executor; keep it from running real transports.
        (getField("teardownExecutor") as ExecutorService).shutdownNow()
        setField("teardownExecutor", PausedExecutorService())
        CarPlayBackgroundSession::class.java.getDeclaredField("owner").apply { isAccessible = true }
            .set(CarPlayBackgroundSession, activity)
        // The session started with the host window turned; the window now has the other rotation.
        setField("activeDisplaySize", size(1920, 720))
        setField("sessionDisplay", CarPlaySessionDisplay(1920, 720, Surface.ROTATION_90, true, true, 1920, 720))
    }

    @After fun tearDown() {
        (getField("shuttingDown") as AtomicBoolean).set(true)
        (getField("mainHandler") as Handler).removeCallbacksAndMessages(null)
        (getField("teardownExecutor") as ExecutorService).shutdownNow()
        (getField("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        CarPlayBackgroundSession.clear()
        (RuntimeEnvironment.getApplication() as CanvasApplication).canvas = 1920 to 720
    }

    @Test fun turningTheHostWindowKeepsASessionOnTheAppCanvas() {
        applySize(1920, 720)
        assertEquals(0, getField("restartGeneration"))
        assertNotNull(getField("sessionDisplay"))
    }

    @Test fun withoutAnAppCanvasTurningTheHostWindowStillRebuildsTheSession() {
        (RuntimeEnvironment.getApplication() as CanvasApplication).canvas = null
        applySize(1920, 720)
        assertEquals(1, getField("restartGeneration"))
    }

    private fun size(width: Int, height: Int): Any = sizeClass
        .getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        .apply { isAccessible = true }.newInstance(width, height)

    private fun applySize(width: Int, height: Int) {
        activity.javaClass.getDeclaredMethod("applyDisplaySize", sizeClass)
            .apply { isAccessible = true }.invoke(activity, size(width, height))
    }

    private fun getField(name: String): Any? = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)

    private fun setField(name: String, value: Any?) {
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
}
