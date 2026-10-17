package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClusterSpeedometerSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun defaultsPersistAndAttachedViewUpdatesWithoutReconnection() {
        assertFalse(AirPlayPersistence.loadClusterSpeedometerEnabled(context))
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        var changed: ((Int?) -> Unit)? = null
        var stopped = 0
        val view = ClusterSpeedometerView(controller.get()) { listener ->
            changed = listener
            { stopped++ }
        }
        controller.get().setContentView(view)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, view.visibility)
        AirPlayPersistence.saveClusterSpeedometerEnabled(context, true)
        AirPlayPersistence.saveClusterSpeedometerX(context, 30)
        AirPlayPersistence.saveClusterSpeedometerY(context, 55)
        AirPlayPersistence.saveClusterSpeedometerSize(context, 65)
        AirPlayPersistence.saveClusterSpeedometerOpacity(context, 40)
        shadowOf(Looper.getMainLooper()).idle()
        changed!!(0)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, view.visibility)
        assertEquals(0, ReflectionHelpers.getField<Int>(view, "speedKmh"))
        assertEquals(30, ReflectionHelpers.getField<Int>(view, "xPercent"))
        assertEquals(55, ReflectionHelpers.getField<Int>(view, "yPercent"))
        assertEquals(65, ReflectionHelpers.getField<Int>(view, "sizePercent"))
        assertEquals(40, ReflectionHelpers.getField<Int>(view, "opacityPercent"))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        assertEquals(View.GONE, view.visibility)
        changed!!(43)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(43, ReflectionHelpers.getField<Int>(view, "speedKmh"))
        AirPlayPersistence.saveClusterSpeedometerEnabled(context, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, view.visibility)
        assertEquals(1, stopped)
        val oldCallback = changed!!
        val replacement = ClusterSpeedometerView(controller.get()) { listener ->
            changed = listener
            { stopped++ }
        }
        controller.get().setContentView(replacement)
        AirPlayPersistence.saveClusterSpeedometerEnabled(context, true)
        shadowOf(Looper.getMainLooper()).idle()
        oldCallback(99)
        changed!!(18)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, replacement.visibility)
        assertEquals(65, ReflectionHelpers.getField<Int>(replacement, "sizePercent"))
        assertEquals(View.GONE, view.visibility)
        controller.pause().stop().destroy()
    }

    @Test fun speedCardRendersCenteredAtDifferentSizes() {
        for (size in listOf(50, 75)) {
            val view = ClusterSpeedometerView(context) { { } }
            view.setLayout(82, 30, size)
            view.setSpeedKmh(72)
            view.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 1920, 720)
            val bitmap = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val directory = File("build/reports/cluster-speedometer-ui").apply { mkdirs() }
            File(directory, "card-$size.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }
    }

}
