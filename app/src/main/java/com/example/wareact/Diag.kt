package com.example.wareact

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** مرجع للخدمة الحيّة: إن كان null فالنظام غير رابط خدمة الوصول حالياً. */
object Live {
    @Volatile
    var service: ReactService? = null
}

/** سجل أحداث وتشخيص: يحفظ سبب إغلاق التطبيق وتاريخ أحداث الخدمة ليُرسل مع تقرير الدعم. */
object Diag {
    private const val FILE = "wareact_diag"
    private const val MAX_LINES = 40

    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun stamp(t: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(t))

    @Synchronized
    fun log(c: Context, msg: String, sync: Boolean = false) {
        try {
            val old = sp(c).getString("log", "") ?: ""
            val lines = old.split('\n').filter { it.isNotBlank() }.toMutableList()
            lines.add("${stamp()}  $msg")
            while (lines.size > MAX_LINES) lines.removeAt(0)
            val e = sp(c).edit().putString("log", lines.joinToString("\n"))
            if (sync) e.commit() else e.apply()
        } catch (_: Throwable) {
        }
    }

    fun saveCrash(c: Context, t: Throwable) {
        try {
            val frames = t.stackTrace.take(5).joinToString("\n    ") { it.toString() }
            val txt = "${stamp()}  ${t.javaClass.name}: ${t.message?.take(160)}\n    $frames"
            sp(c).edit().putString("crash", txt).commit()
            log(c, "💥 انهيار: ${t.javaClass.simpleName}", true)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ سبب إغلاق العملية (أندرويد 11+)

    private fun reasonName(r: Int): String = when (r) {
        1 -> "أغلق نفسه"
        2 -> "قتله النظام (إشارة)"
        3 -> "نقص ذاكرة"
        4 -> "انهيار بالكود"
        5 -> "انهيار نظامي"
        6 -> "تعليق ANR"
        7 -> "فشل تهيئة"
        8 -> "تغيّر صلاحية"
        9 -> "استهلاك موارد مفرط"
        10 -> "إيقاف إجباري / سحب من المهام"
        11 -> "أوقفه المستخدم"
        12 -> "مات ما يعتمد عليه"
        13 -> "سبب آخر (غالباً مدير بطارية الشركة)"
        14 -> "تجميد من النظام"
        15 -> "تغيّرت حالة الحزمة"
        16 -> "تحديث التطبيق"
        else -> "غير معروف"
    }

    private fun importanceName(i: Int): String = when {
        i <= 100 -> "أمامي"
        i <= 125 -> "خدمة أمامية"
        i <= 230 -> "ظاهر"
        i <= 350 -> "خدمة خلفية"
        else -> "مخبّأ بالذاكرة"
    }

    fun exitLines(c: Context, max: Int = 5): List<String> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        return try {
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getHistoricalProcessExitReasons(c.packageName, 0, max).map { i ->
                val d = i.description
                stamp(i.timestamp) + "  [" + i.reason + "] " + reasonName(i.reason) +
                    " | الحالة: " + importanceName(i.importance) +
                    (if (!d.isNullOrBlank()) " | $d" else "")
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** يكتب بالسجل أي إغلاق جديد للعملية حدث منذ آخر مرة. */
    fun logNewExits(c: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val last = sp(c).getLong("lastExitTs", 0L)
            var maxTs = last
            val list = am.getHistoricalProcessExitReasons(c.packageName, 0, 5)
            for (i in list.reversed()) {
                if (i.timestamp > last) {
                    val d = i.description
                    log(
                        c,
                        "🔴 أُغلقت العملية السابقة: [" + i.reason + "] " + reasonName(i.reason) +
                            " | " + importanceName(i.importance) +
                            (if (!d.isNullOrBlank()) " | $d" else ""),
                        true
                    )
                    if (i.timestamp > maxTs) maxTs = i.timestamp
                }
            }
            if (maxTs != last) sp(c).edit().putLong("lastExitTs", maxTs).apply()
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ فحوصات الحماية

    fun accessibilityEnabled(c: Context): Boolean {
        val s = Settings.Secure.getString(
            c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return s.split(':').any { it.contains(c.packageName) && it.contains("ReactService") }
    }

    fun batteryOk(c: Context): Boolean = try {
        (c.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(c.packageName)
    } catch (_: Throwable) {
        false
    }

    fun notifOk(c: Context): Boolean = try {
        NotificationManagerCompat.from(c).areNotificationsEnabled()
    } catch (_: Throwable) {
        true
    }

    fun overlayOk(c: Context): Boolean = try {
        Settings.canDrawOverlays(c)
    } catch (_: Throwable) {
        false
    }

    fun isXiaomi(): Boolean {
        val m = Build.MANUFACTURER.lowercase(Locale.US)
        val b = Build.BRAND.lowercase(Locale.US)
        return m.contains("xiaomi") || b.contains("xiaomi") || b.contains("redmi") || b.contains("poco")
    }

    /** "التشغيل التلقائي" بشاومي: true مسموح، false محظور، null غير معروف. */
    fun miuiAutostart(c: Context): Boolean? {
        if (!isXiaomi()) return null
        return try {
            val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val m = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            val r = m.invoke(ops, 10008, Process.myUid(), c.packageName) as Int
            r == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            null
        }
    }

    private fun version(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
    } catch (_: Throwable) {
        "?"
    }

    fun report(c: Context): String = buildString {
        appendLine("=== تقرير WaReact ===")
        appendLine("الوقت: ${stamp()}")
        appendLine("الجهاز: ${Build.MANUFACTURER} ${Build.MODEL} | أندرويد ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("النظام: ${Build.DISPLAY}")
        appendLine("نسخة التطبيق: ${version(c)}")
        appendLine("خدمة الوصول: مفعّلة=${accessibilityEnabled(c)} | متصلة فعلياً=${Live.service != null}")
        appendLine("تلقائي=${Prefs.isRunning(c)} | يدوي=${Prefs.isManual(c)}")
        appendLine("استثناء البطارية=${batteryOk(c)} | الإشعارات=${notifOk(c)} | العرض فوق التطبيقات=${overlayOk(c)}")
        appendLine("تشغيل تلقائي شاومي=${miuiAutostart(c) ?: "غير معروف"}")
        val at = try {
            Settings.Global.getInt(c.contentResolver, Settings.Global.AUTO_TIME, -1)
        } catch (_: Throwable) {
            -1
        }
        appendLine("الوقت التلقائي=$at (1=مفعّل)")
        appendLine(
            "الترخيص: فعّال=${LicenseManager.isActive(c)} | آخر تحقق=${stamp(Prefs.licenseCheckedAt(c))} | ينتهي=${stamp(Prefs.licenseExpires(c))}"
        )
        appendLine("آخر حالة: ${Prefs.status(c)}")
        appendLine()
        appendLine("-- آخر انهيار --")
        appendLine(sp(c).getString("crash", null) ?: "لا يوجد")
        appendLine()
        appendLine("-- سجل إغلاق العملية من النظام --")
        val ex = exitLines(c, 6)
        if (ex.isEmpty()) appendLine("لا يوجد / غير مدعوم") else ex.forEach { appendLine(it) }
        appendLine()
        appendLine("-- سجل الأحداث --")
        appendLine(sp(c).getString("log", "") ?: "")
    }
}
