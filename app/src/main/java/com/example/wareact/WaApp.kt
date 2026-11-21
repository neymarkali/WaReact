package com.example.wareact

import android.app.Application
import android.os.Process

class WaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                Diag.saveCrash(this, e)
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
        Diag.log(this, "▶ بدأت العملية (pid ${Process.myPid()})")
        Diag.logNewExits(this)
    }
}
