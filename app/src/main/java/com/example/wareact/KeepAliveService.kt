package com.example.wareact

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** يشغّل/يوقف خدمة الحماية حسب حالة المراقبة. آمن: لا يرمي استثناءات. */
object Keep {
    fun sync(c: Context) {
        try {
            val want = Prefs.isRunning(c) || Prefs.isManual(c)
            val i = Intent(c.applicationContext, KeepAliveService::class.java)
            if (want) ContextCompat.startForegroundService(c.applicationContext, i)
            else c.applicationContext.stopService(i)
        } catch (t: Throwable) {
            Diag.log(c, "تعذّر تشغيل خدمة الحماية: ${t.javaClass.simpleName}")
        }
    }
}

/**
 * خدمة أمامية بإشعار دائم: ترفع أولوية العملية فيصعب على النظام قتلها،
 * وتراقب خدمة الوصول وتنبّهك فوراً (إشعار + زر) إذا توقفت.
 */
class KeepAliveService : Service() {

    private val h = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var badSince = 0L
    private var alerted = false
    private var lastShown = ""

    private val tick = object : Runnable {
        override fun run() {
            try {
                inspect()
            } catch (t: Throwable) {
                Diag.log(this@KeepAliveService, "خطأ بالحارس: ${t.javaClass.simpleName}")
            }
            h.postDelayed(this, 15_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        Diag.log(this, "🛡 خدمة الحماية بدأت")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ok = goForeground()
        if (!ok || !(Prefs.isRunning(this) || Prefs.isManual(this))) {
            stopSelf()
            return START_NOT_STICKY
        }
        startedAt = SystemClock.uptimeMillis()
        h.removeCallbacks(tick)
        h.postDelayed(tick, 4_000L)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Diag.log(this, "سُحب التطبيق من المهام الأخيرة", true)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        Diag.log(this, "🛡 خدمة الحماية توقفت", true)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ المراقبة

    private fun inspect() {
        if (!(Prefs.isRunning(this) || Prefs.isManual(this))) {
            stopSelf()
            return
        }
        val nowUp = SystemClock.uptimeMillis()
        val connected = Live.service != null
        val nm = NotificationManagerCompat.from(this)

        if (connected) {
            if (badSince != 0L) Diag.log(this, "✅ خدمة الوصول رجعت للعمل")
            badSince = 0L
            if (alerted) nm.cancel(ALERT_ID)
            alerted = false
            show(statusText(), false)
            return
        }

        // مهلة بعد إقلاع العملية: النظام يعيد ربط الخدمة تلقائياً
        if (nowUp - startedAt < 20_000L) return

        val enabled = Diag.accessibilityEnabled(this)
        if (badSince == 0L) {
            badSince = nowUp
            Diag.log(
                this,
                if (enabled) "⚠️ الخدمة مفعّلة لكن غير متصلة" else "⚠️ خدمة الوصول متوقفة (أطفأها النظام)",
                true
            )
        }
        show(
            if (enabled) "الخدمة غير متصلة — اضغط لإعادة تفعيلها"
            else "خدمة الوصول مطفأة — اضغط لإعادة تفعيلها",
            true
        )
        if (!alerted) {
            alerted = true
            postAlert()
        }
        if (nowUp - badSince > 10 * 60_000L) stopSelf()
    }

    private fun statusText(): String {
        val a = Prefs.isRunning(this)
        val m = Prefs.isManual(this)
        return when {
            a && m -> "مراقبة تلقائية + نقر يدوي"
            a -> "المراقبة التلقائية شغّالة"
            m -> "وضع النقر اليدوي شغّال"
            else -> "جاهز"
        }
    }

    // ------------------------------------------------------------------ الإشعارات

    private fun goForeground(): Boolean {
        return try {
            val n = buildOngoing(statusText(), false)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            lastShown = statusText() + "|false"
            true
        } catch (t: Throwable) {
            Diag.log(this, "تعذّر تشغيل الإشعار الدائم: ${t.javaClass.simpleName}", true)
            false
        }
    }

    private fun show(text: String, warn: Boolean) {
        val key = "$text|$warn"
        if (key == lastShown) return
        lastShown = key
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildOngoing(text, warn))
        } catch (_: Throwable) {
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 1,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun openAccessibility(): PendingIntent = PendingIntent.getActivity(
        this, 2,
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildOngoing(text: String, warn: Boolean): Notification =
        NotificationCompat.Builder(this, CH_KEEP)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(if (warn) "⚠️ WaReact متوقف" else "WaReact شغّال")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(if (warn) openAccessibility() else openApp())
            .build()

    private fun postAlert() {
        try {
            val n = NotificationCompat.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("⚠️ WaReact توقّف عن العمل")
                .setContentText("النظام أوقف خدمة الوصول. اضغط هنا لإعادة تفعيلها.")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "النظام أوقف خدمة الوصول فتوقف التفاعل.\n" +
                            "اضغط هنا ← فعّل WaReact من جديد.\n" +
                            "لمنع تكرار ذلك: افتح التطبيق وأكمل بنود «حماية من الإغلاق»."
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(openAccessibility())
                .build()
            NotificationManagerCompat.from(this).notify(ALERT_ID, n)
        } catch (_: Throwable) {
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_KEEP, "حالة التشغيل", NotificationManager.IMPORTANCE_LOW).apply {
                description = "إشعار دائم يبقي التطبيق شغّالاً بالخلفية"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "تنبيهات التوقف", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "ينبّهك إذا أوقف النظام خدمة الوصول"
            }
        )
    }

    private companion object {
        const val CH_KEEP = "wareact_keep"
        const val CH_ALERT = "wareact_alert"
        const val NOTIF_ID = 4101
        const val ALERT_ID = 4102
    }
}
