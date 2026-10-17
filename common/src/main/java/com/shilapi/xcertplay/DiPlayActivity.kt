// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-14

package com.shilapi.xcertplay

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.TextView

/** Entry point kept for existing callers: opens the app's own launcher, or CarPlay when this activity is the launcher. */
class DiPlayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        route(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
    }

    private fun route(incoming: Intent) {
        val launcher = packageManager.getLaunchIntentForPackage(packageName)
        if (launcher?.component != null &&
            launcher.component != ComponentName(this, DiPlayActivity::class.java)) {
            incoming.extras?.let { launcher.putExtras(it) }
            startActivity(launcher)
            finish()
            return
        }
        try {
            DiPlayBootstrap.ensure(this, AirPlayPersistence.loadMfiTarget(this))
        } catch (failure: Exception) {
            setContentView(TextView(this).apply {
                text = failure.toString()
            })
            return
        }
        startActivity(Intent(this, CarPlayHostActivity::class.java).apply {
            incoming.extras?.let { putExtras(it) }
        })
        finish()
    }
}
