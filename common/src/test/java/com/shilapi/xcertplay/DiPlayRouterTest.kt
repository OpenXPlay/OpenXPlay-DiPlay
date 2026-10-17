// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-14

package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.widget.TextView
import com.shilapi.xcertplay.orchestration.MfiTarget
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DiPlayRouterTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun resetBootstrap() {
        DiPlayBootstrap::class.java.getDeclaredField("ready").apply { isAccessible = true }
            .setBoolean(null, false)
        AirPlayPersistence.saveMfiTarget(app, MfiTarget.USB_CH341)
    }

    private fun registerLauncher(component: ComponentName) {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_INFO)
            .setPackage(app.packageName)
        shadowOf(app.packageManager).addResolveInfoForIntent(query, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = component.packageName
                name = component.className
            }
        })
    }

    @Test fun forwardsExtrasToAnotherLauncherOnCreateAndNewIntent() {
        val launcher = ComponentName(app.packageName, "org.openxplay.Launcher")
        registerLauncher(launcher)
        // A different launcher must bypass even a broken local bootstrap.
        AirPlayPersistence.saveMfiTarget(app, MfiTarget.LOCAL)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent().putExtra("page", "connection")).create()
        val activity = controller.get()
        val first = requireNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(launcher, first.component)
        assertEquals("connection", first.getStringExtra("page"))
        assertTrue(activity.isFinishing)
        controller.newIntent(Intent().putExtra("page", "settings"))
        val second = requireNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(launcher, second.component)
        assertEquals("settings", second.getStringExtra("page"))
        controller.destroy()
    }

    @Test fun fallsBackToHostForMissingOrSelfLauncher() {
        for (selfLauncher in listOf(false, true)) {
            if (selfLauncher) registerLauncher(ComponentName(app, DiPlayActivity::class.java))
            val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
                Intent().putExtra("wireless", true)).create()
            val activity = controller.get()
            val forwarded = requireNotNull(shadowOf(activity).nextStartedActivity)
            assertEquals(ComponentName(app, CarPlayHostActivity::class.java), forwarded.component)
            assertTrue(forwarded.getBooleanExtra("wireless", false))
            assertTrue(activity.isFinishing)
            controller.destroy()
        }
    }

    @Test fun bootstrapFailureStaysOpenWithoutForwardingOnCreateOrNewIntent() {
        AirPlayPersistence.saveMfiTarget(app, MfiTarget.LOCAL)
        val directory = File(app.noBackupFilesDir, "offline-mfi")
        directory.deleteRecursively()
        assertTrue(directory.mkdirs())
        File(directory, "identity.pk8").writeText("invalid identity")
        try {
            val controller = Robolectric.buildActivity(DiPlayActivity::class.java).create()
            val activity = controller.get()
            assertNull(shadowOf(activity).nextStartedActivity)
            assertFalse(activity.isFinishing)
            assertFalse(activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                .getChildAt(0).let { it as TextView }.text.isNullOrBlank())
            controller.newIntent(Intent().putExtra("page", "connection"))
            assertNull(shadowOf(activity).nextStartedActivity)
            assertFalse(activity.isFinishing)
            controller.destroy()
        } finally {
            directory.deleteRecursively()
        }
    }
}
