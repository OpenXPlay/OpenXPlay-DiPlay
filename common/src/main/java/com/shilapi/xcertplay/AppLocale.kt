// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-14

package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

internal object AppLocale {
    const val SYSTEM = "system"

    fun wrap(context: Context): Context = context

    fun preference(context: Context): String = SYSTEM

    fun enforce(context: Context) = Unit

    fun showPicker(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS,
                Uri.fromParts("package", activity.packageName, null)))
        }
    }
}
