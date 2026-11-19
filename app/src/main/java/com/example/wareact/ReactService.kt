package com.example.wareact

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.PendingIntent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * يراقب واتساب:
 *  1) إشعار جديد فيه كلمة مفتاحية -> يفتح المحادثة تلقائياً.
 *  2) داخل المحادثة: رسالة واردة فيها كلمة مفتاحية -> ضغط مطوّل ثم اختيار الإيموجي فوراً.
 * ثم فترة خمول ومنع تكرار لنفس الرسالة. سطر الحالة بالتطبيق يشرح سبب أي تجاهل.
 */
class ReactService : AccessibilityService() {

    private class Cand(
        val sig: String,
        val keyword: String,
        val bubble: AccessibilityNodeInfo?,
        val bounds: Rect
    )

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val seen = HashMap<String, Long>()
    private var busy = false
    private var scanQueued = false
    private var pollQueued = false
    private var refreshing = false
    private var lastRefreshTry = 0L
    private var lastReactAt = 0L
    private var lastDiag = ""

    private val scanTask = Runnable {
        scanQueued = false
        scan()
    }
    private val pollTask = Runnable {
        pollQueued = false
        scan()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.setStatus(this, "خدمة الوصول متصلة")
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        e ?: return
        val pkg = e.packageName?.toString() ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
        if (!Prefs.isRunning(this)) return

        maybeRefreshLicense()
        if (!LicenseManager.isActive(this)) {
            diag("الترخيص غير فعّال أو يحتاج إنترنت للتحقق")
            return
        }

        if (e.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            onNotification(e)
        } else if (!scanQueued) {
            scanQueued = true
            main.postDelayed(scanTask, 30)
        }
    }

    // ------------------------------------------------------------------ إشعارات

    private fun onNotification(e: AccessibilityEvent) {
        val n = e.parcelableData as? Notification ?: return
        val ex = n.extras
        val text = buildString {
            ex?.getCharSequence(Notification.EXTRA_TEXT)?.let { append(it).append(' ') }
            ex?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let { append(it).append(' ') }
            e.text.forEach { append(it).append(' ') }
        }
        if (inCooldown()) return
        val kw = TextUtil.firstMatch(text, Prefs.keywords(this)) ?: return

        val key = "N:" + TextUtil.normalize(text).take(80)
        val now = System.currentTimeMillis()
        if (seen[key]?.let { now - it < 30_000 } == true) return
        seen[key] = now

        Prefs.setStatus(this, "إشعار فيه «$kw» — جارٍ فتح المحادثة")
        try {
            n.contentIntent?.send()
        } catch (_: PendingIntent.CanceledException) {
            Prefs.setStatus(this, "تعذّر فتح المحادثة من الإشعار")
        }
    }

    // ------------------------------------------------------------------ مسح المحادثة

    private fun scan() {
        if (busy) return
        if (!Prefs.isRunning(this) || !LicenseManager.isActive(this)) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString()
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        // استمر بالمسح كل 0.4 ثانية طالما واتساب أمامك (حتى لو فاتتنا أحداث)
        schedulePoll()
        if (lastDiag.isEmpty()) diag("أراقب شاشة واتساب… بانتظار رسالة فيها كلمة مفتاحية")
        if (inCooldown()) return

        val kws = Prefs.keywords(this)
        if (kws.isEmpty()) {
            diag("لا توجد كلمات مفتاحية محفوظة")
            return
        }

        val width = resources.displayMetrics.widthPixels
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        pruneSeen()

        var best: Cand? = null
        walk(root) { node ->
            val t = node.text?.toString()
            if (t.isNullOrEmpty() || t.length < 2) return@walk
            val kw = TextUtil.firstMatch(t, kws) ?: return@walk

            // وقت الرسالة (اختياري): أقرب أب (حتى 7 مستويات) يحتوي نص وقت
            var tm: Pair<Int, String>? = null
            var cur: AccessibilityNodeInfo = node
            var depth = 0
            while (depth < 7 && tm == null) {
                val p = cur.parent ?: break
                tm = findTime(p)
                cur = p
                depth++
            }
            // إن وُجد الوقت وكان أقدم من دقيقتين = رسالة قديمة نتجاهلها.
            // إن لم يوجد وقت نتفاعل فوراً.
            if (tm != null && !TextUtil.isFresh(tm.first)) {
                diag("«$kw» رسالة قديمة (${tm.second}) — أتفاعل فقط مع آخر دقيقتين")
                return@walk
            }

            // تجاهل رسائلي أنا (الواردة عند بداية الشاشة)
            val tb = Rect()
            node.getBoundsInScreen(tb)
            if (tb.width() <= 0 || tb.height() <= 0) return@walk
            val leftGap = tb.left
            val rightGap = width - tb.right
            val incoming = if (rtl) rightGap <= leftGap else leftGap <= rightGap
            if (!incoming) {
                diag("«$kw» رسالة صادرة منك فتجاهلتها")
                return@walk
            }

            val sig = TextUtil.normalize(t).take(80) + "|" + (tm?.second ?: "")
            if (seen.containsKey(sig)) return@walk

            // الفقاعة = أقرب أب يقبل الضغط المطوّل (إن وُجد)
            var bubble: AccessibilityNodeInfo? = null
            var a: AccessibilityNodeInfo? = node
            var d = 0
            while (a != null && d < 9) {
                if (a.isLongClickable) { bubble = a; break }
                a = a.parent
                d++
            }

            val current = best
            if (current == null || tb.bottom > current.bounds.bottom) {
                best = Cand(sig, kw, bubble, tb)
            }
        }

        val target = best ?: return
        react(target)
    }

    private fun findTime(c: AccessibilityNodeInfo): Pair<Int, String>? {
        var result: Pair<Int, String>? = null
        walk(c) { n ->
            val s = n.text?.toString()
            if (!s.isNullOrEmpty() && s.length <= 12) {
                val m = TextUtil.parseMinutes(s)
                if (m != null) result = m to s.trim()
            }
        }
        return result
    }

    // ------------------------------------------------------------------ التفاعل

    private fun react(c: Cand) {
        busy = true
        seen[c.sig] = System.currentTimeMillis()
        Prefs.setStatus(this, "رسالة فيها «${c.keyword}» — جارٍ التفاعل")
        lastDiag = "react"

        val viaAction = c.bubble?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true
        val delay: Long
        if (viaAction) {
            delay = 250
        } else {
            gesture(
                c.bounds.centerX().toFloat(), c.bounds.centerY().toFloat(),
                ViewConfiguration.getLongPressTimeout() + 250L
            )
            delay = ViewConfiguration.getLongPressTimeout() + 500L
        }
        main.postDelayed({ pickEmoji(0) }, delay)
    }

    private fun pickEmoji(attempt: Int) {
        val emoji = Prefs.emoji(this)
        val node = findEmojiNode(emoji)
        if (node != null && clickNode(node)) {
            lastReactAt = System.currentTimeMillis()
            Prefs.incReact(this)
            Prefs.setStatus(this, "تم التفاعل $emoji")
            busy = false
            schedulePoll()
            return
        }
        if (attempt < 15) {
            main.postDelayed({ pickEmoji(attempt + 1) }, 100)
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
            Prefs.setStatus(this, "لم أجد $emoji في شريط التفاعلات (استعمل إيموجي من الشريط السريع)")
            busy = false
            schedulePoll()
        }
    }

    private val englishNames = mapOf(
        "👍" to listOf("thumbs up", "like"),
        "❤️" to listOf("red heart", "heart", "love"),
        "😂" to listOf("tears of joy", "laughing"),
        "😮" to listOf("open mouth", "surprised"),
        "😢" to listOf("crying", "sad"),
        "🙏" to listOf("folded hands", "pray", "thanks")
    )

    private fun findEmojiNode(emoji: String): AccessibilityNodeInfo? {
        val target = emoji.replace("\uFE0F", "").trim()
        val names = englishNames[target].orEmpty()
        val roots = ArrayList<AccessibilityNodeInfo>()
        windows?.forEach { w -> w.root?.let { roots.add(it) } }
        rootInActiveWindow?.let { roots.add(it) }

        var found: AccessibilityNodeInfo? = null
        for (r in roots) {
            walk(r) { n ->
                if (found != null) return@walk
                val txt = n.text?.toString()?.replace("\uFE0F", "")?.trim()
                val desc = n.contentDescription?.toString()
                val d = desc?.replace("\uFE0F", "")?.trim()
                val hit = txt == target || d == target ||
                    (desc != null && desc.length <= 40 && names.any { desc.lowercase().contains(it) })
                if (hit) found = n
            }
            if (found != null) break
        }
        return found
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var i = 0
        while (cur != null && i < 5) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            i++
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        return gesture(r.centerX().toFloat(), r.centerY().toFloat(), 60)
    }

    private fun gesture(x: Float, y: Float, duration: Long): Boolean {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, duration))
            .build()
        return dispatchGesture(g, null, null)
    }

    // ------------------------------------------------------------------ أدوات

    private fun diag(msg: String) {
        if (msg != lastDiag) {
            lastDiag = msg
            Prefs.setStatus(this, msg)
        }
    }

    private fun schedulePoll() {
        if (!pollQueued) {
            pollQueued = true
            main.postDelayed(pollTask, 400)
        }
    }

    private fun inCooldown(): Boolean =
        System.currentTimeMillis() - lastReactAt < Prefs.cooldownSec(this) * 1000L

    private fun pruneSeen() {
        val now = System.currentTimeMillis()
        seen.entries.removeAll { now - it.value > 15 * 60_000L }
    }

    private fun walk(n: AccessibilityNodeInfo?, depth: Int = 0, f: (AccessibilityNodeInfo) -> Unit) {
        if (n == null || depth > 40) return
        f(n)
        for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1, f)
    }

    private fun maybeRefreshLicense() {
        if (refreshing || Prefs.licenseCode(this).isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - Prefs.licenseCheckedAt(this) < 6 * 3_600_000L) return
        if (now - lastRefreshTry < 10 * 60_000L) return
        lastRefreshTry = now
        refreshing = true
        scope.launch {
            try {
                LicenseManager.refresh(this@ReactService)
            } finally {
                refreshing = false
            }
        }
    }
}
