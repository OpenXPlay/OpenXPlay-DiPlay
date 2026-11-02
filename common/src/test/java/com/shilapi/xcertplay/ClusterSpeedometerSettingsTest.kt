package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
        assertFalse(AirPlayPersistence.loadClusterSpeedometerAlbumColor(context))
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

    @Test fun albumBackgroundFadesRetainsOpacityAndRestoresDefaultWithoutRestartingSpeed() {
        ClusterMusicState.publish(com.shilapi.xcertplay.media.CarPlayNowPlaying(), null)
        shadowOf(Looper.getMainLooper()).idle()
        AirPlayPersistence.saveClusterSpeedometerEnabled(context, true)
        AirPlayPersistence.saveClusterSpeedometerOpacity(context, 40)
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        var subscriptions = 0
        val view = ClusterSpeedometerView(controller.get()) { subscriptions++; { } }
        controller.get().setContentView(view)
        shadowOf(Looper.getMainLooper()).idle()
        view.layout(0, 0, 1280, 480)
        view.setSpeedKmh(188)
        val output = Bitmap.createBitmap(1280, 480, Bitmap.Config.ARGB_8888)
        fun color(): Int {
            view.draw(Canvas(output))
            return ReflectionHelpers.getField<android.graphics.Paint>(view, "backgroundPaint").color
        }
        val original = color()
        val red = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val blue = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        fun publish(art: Bitmap?) {
            ClusterMusicState.publish(com.shilapi.xcertplay.media.CarPlayNowPlaying(title = "Track"), art)
            shadowOf(Looper.getMainLooper()).idle()
        }
        publish(red)
        assertEquals(original, color()) // opt-in only
        AirPlayPersistence.saveClusterSpeedometerAlbumColor(context, true)
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        assertEquals(ClusterMusicView.coverFrameColor(red) and 0xffffff, color() and 0xffffff)
        assertEquals(102, Color.alpha(color()))
        publish(blue)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
        val middle = color()
        assertTrue(Color.red(middle) > 0 && Color.blue(middle) > 0)
        publish(null) // metadata-only update must not reset the transition
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
        assertEquals(ClusterMusicView.coverFrameColor(blue) and 0xffffff, color() and 0xffffff)
        AirPlayPersistence.saveClusterSpeedometerAlbumColor(context, false)
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        assertEquals(original, color())
        assertEquals(1, subscriptions)
        assertEquals(Color.WHITE, ReflectionHelpers.getField<android.graphics.Paint>(view, "speedPaint").color)
        AirPlayPersistence.saveClusterSpeedometerAlbumColor(context, true)
        shadowOf(Looper.getMainLooper()).idle()
        ClusterMusicState.publish(com.shilapi.xcertplay.media.CarPlayNowPlaying(), null)
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        assertEquals(original, color())
        controller.pause().stop().destroy()
        assertNull(ReflectionHelpers.getField<Any?>(view, "stopObservingArtwork"))
        output.recycle()
    }

    @Test fun squareSpeedCardFitsThreeDigitsAtEverySize() {
        val directory = File("build/reports/cluster-speedometer-ui").apply { mkdirs() }
        for ((width, height) in listOf(1280 to 480, 1920 to 720, 60 to 200)) {
            for (size in listOf(30, 50, 75, 95)) {
                val view = ClusterSpeedometerView(context) { { } }
                view.setLayout(82, 30, size)
                view.layout(0, 0, width, height)
                val rect = com.shilapi.xcertplay.airplay.ClusterSpeedometerOverlay.rect(width, height, 82, 30, size)
                assertEquals(rect.width, rect.height)
                assertTrue(rect.left >= 0 && rect.top >= 0)
                assertTrue(rect.left + rect.width <= width && rect.top + rect.height <= height)
                for (speed in listOf(8, 88, 188, 300)) {
                    view.setSpeedKmh(speed)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    val paint = ReflectionHelpers.getField<android.graphics.Paint>(view, "speedPaint")
                    val unit = ReflectionHelpers.getField<android.graphics.Paint>(view, "unitPaint")
                    assertTrue("$speed must fit with 5% clearance on each side at size $size",
                        paint.measureText(speed.toString()) <= rect.width * .90f)
                    assertTrue(unit.measureText("km/h") < rect.width * .8f)
                    val numberBounds = android.graphics.Rect()
                    val unitBounds = android.graphics.Rect()
                    paint.getTextBounds(speed.toString(), 0, speed.toString().length, numberBounds)
                    unit.getTextBounds("km/h", 0, 4, unitBounds)
                    assertTrue(numberBounds.height() + unitBounds.height() + rect.height * .06f < rect.height * .8f)
                    if (width == 1280 && size in listOf(30, 50, 95) && speed in listOf(188, 300)) {
                        File(directory, "square-$size-$speed.png").outputStream().use {
                            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    }
                    bitmap.recycle()
                }
            }
        }
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
