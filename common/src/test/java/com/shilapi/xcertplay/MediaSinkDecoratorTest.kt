// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-14
package com.shilapi.xcertplay

import android.app.Application
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MediaSinkDecorator
import com.shilapi.xcertplay.airplay.MediaSinkDecoratorProvider
import com.shilapi.xcertplay.airplay.SessionControl
import com.shilapi.xcertplay.airplay.ScreenSessionPolicy
import com.shilapi.xcertplay.airplay.ScreenSessionPolicyProvider
import com.shilapi.xcertplay.media.AndroidMediaSink
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MediaSinkDecoratorTest {
    private lateinit var activity: CarPlayHostActivity

    class DecoratingApplication : Application(), MediaSinkDecoratorProvider, ScreenSessionPolicyProvider {
        override var screenSessionPolicy = ScreenSessionPolicy(false, false, false)
        val decoratedSink = object : MediaSink {}
        var originalSink: MediaSink? = null
        var control: SessionControl? = null
        var calls = 0
        override val mediaSinkDecorator = MediaSinkDecorator { sink, control ->
            originalSink = sink
            this.control = control
            calls++
            decoratedSink
        }
    }

    @Test
    @Config(application = DecoratingApplication::class)
    fun applicationDecoratorSuppliesTheEngineSink() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val application = activity.application as DecoratingApplication
        val sink = mock(AndroidMediaSink::class.java)

        assertSame(application.decoratedSink, engineSink(sink))
        assertSame(sink, application.originalSink)
        assertEquals(1, application.calls)
        assertFalse(application.control!!.sendTouch(emptyList()))
    }

    @Test
    fun applicationWithoutProviderKeepsTheOriginalSink() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val sink = mock(AndroidMediaSink::class.java)

        assertSame(sink, engineSink(sink))
        assertEquals(ScreenSessionPolicy(), policy(engine(sink)))
    }

    @Test
    @Config(application = DecoratingApplication::class)
    fun mediaPolicyIsCapturedByEachNewEngine() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val application = activity.application as DecoratingApplication
        val sink = mock(AndroidMediaSink::class.java)
        val first = engine(sink)
        application.screenSessionPolicy = ScreenSessionPolicy()
        assertEquals(ScreenSessionPolicy(false, false, false), policy(first))
        assertEquals(ScreenSessionPolicy(), policy(engine(sink)))
    }

    private fun policy(engine: CarPlayMediaEngine) = CarPlayMediaEngine::class.java
        .getDeclaredField("screenSessionPolicy").apply { isAccessible = true }.get(engine) as ScreenSessionPolicy

    private fun engine(sink: AndroidMediaSink): CarPlayMediaEngine = CarPlayHostActivity::class.java
        .getDeclaredMethod("createMediaEngine", AndroidMediaSink::class.java)
        .apply { isAccessible = true }.invoke(activity, sink) as CarPlayMediaEngine

    private fun engineSink(sink: AndroidMediaSink): MediaSink {
        val engine = engine(sink)
        return CarPlayMediaEngine::class.java.getDeclaredField("sink")
            .apply { isAccessible = true }.get(engine) as MediaSink
    }

    @After
    fun tearDown() {
        if (!::activity.isInitialized) return
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            (CarPlayHostActivity::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.get(activity) as ExecutorService).shutdownNow()
        }
    }
}
