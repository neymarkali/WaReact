package com.example.wareact

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** أيقونات لوحة الإعدادات السريعة (البرداية): تشغيل/إيقاف بلمسة واحدة. */
abstract class BaseTile : TileService() {
    abstract val tileLabel: String
    abstract fun isOn(): Boolean
    abstract fun setOn(on: Boolean)
    open fun canStart(): Boolean = true

    override fun onStartListening() {
        refresh()
    }

    override fun onTileAdded() {
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (isOn()) {
            setOn(false)
            refresh()
            return
        }
        if (!LicenseManager.isActive(this) || !serviceOn() || !canStart()) {
            openApp()
            return
        }
        setOn(true)
        refresh()
    }

    private fun serviceOn(): Boolean {
        val s = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return s.split(':').any { it.contains(packageName) && it.contains("ReactService") }
    }

    private fun refresh() {
        val t = qsTile ?: return
        t.label = tileLabel
        t.state = if (isOn()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (isOn()) "شغّال" else "متوقف"
        t.updateTile()
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            startActivityAndCollapse(i)
        }
    }
}

class AutoTile : BaseTile() {
    override val tileLabel = "مراقبة تلقائية"
    override fun isOn() = Prefs.isRunning(this)
    override fun setOn(on: Boolean) {
        Prefs.setRunning(this, on)
        Prefs.setStatus(this, if (on) "بدأت المراقبة (من اللوحة السريعة)" else "تم الإيقاف (من اللوحة السريعة)")
    }
    override fun canStart() = Prefs.keywords(this).isNotEmpty()
}

class ManualTile : BaseTile() {
    override val tileLabel = "تفاعل يدوي"
    override fun isOn() = Prefs.isManual(this)
    override fun setOn(on: Boolean) {
        Prefs.setManual(this, on)
        Prefs.setStatus(this, if (on) "وضع النقر اليدوي شغّال (من اللوحة السريعة)" else "وضع النقر اليدوي متوقف")
    }
}
