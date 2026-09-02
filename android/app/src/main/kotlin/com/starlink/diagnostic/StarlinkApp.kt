package com.starlink.diagnostic

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Application entry: boots the embedded CPython runtime (Chaquopy) as early
 * as possible so the first screen is not delayed by interpreter startup.
 */
class StarlinkApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }
}
