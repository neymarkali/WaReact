package com.example.wareact

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Prefs {
    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("wareact", Context.MODE_PRIVATE)

    // ---- الإعدادات ----
    fun keywordsRaw(c: Context): String = sp(c).getString("keywords", "") ?: ""
    fun keywords(c: Context): List<String> =
        keywordsRaw(c).split('\n', ',', '،').map { it.trim() }.filter { it.isNotEmpty() }

    fun emoji(c: Context): String = sp(c).getString("emoji", "👍") ?: "👍"
    fun cooldownSec(c: Context): Int = sp(c).getInt("cooldown", 60)

    fun saveSettings(c: Context, keywords: String, emoji: String, cooldownSec: Int) {
        sp(c).edit()
            .putString("keywords", keywords)
            .putString("emoji", emoji)
            .putInt("cooldown", cooldownSec)
            .apply()
    }

    // ---- التشغيل / الإيقاف اليدوي ----
    fun isRunning(c: Context): Boolean = sp(c).getBoolean("running", false)
    fun setRunning(c: Context, v: Boolean) = sp(c).edit().putBoolean("running", v).apply()

    // ---- الحالة ----
    fun reactCount(c: Context): Int = sp(c).getInt("count", 0)
    fun incReact(c: Context) = sp(c).edit().putInt("count", reactCount(c) + 1).apply()

    fun status(c: Context): String = sp(c).getString("status", "—") ?: "—"
    fun setStatus(c: Context, msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        sp(c).edit().putString("status", "$t — $msg").apply()
    }

    // ---- الترخيص (نسخة محلية مؤقتة؛ المرجع الأساسي هو فايربيس) ----
    fun licenseCode(c: Context): String = sp(c).getString("lic_code", "") ?: ""
    fun licenseExpires(c: Context): Long = sp(c).getLong("lic_exp", 0L)
    fun licenseCheckedAt(c: Context): Long = sp(c).getLong("lic_checked", 0L)

    fun saveLicense(c: Context, code: String, expiresAt: Long, checkedAt: Long) {
        sp(c).edit()
            .putString("lic_code", code)
            .putLong("lic_exp", expiresAt)
            .putLong("lic_checked", checkedAt)
            .apply()
    }

    fun clearLicense(c: Context) {
        sp(c).edit().remove("lic_code").remove("lic_exp").remove("lic_checked").apply()
    }
}
