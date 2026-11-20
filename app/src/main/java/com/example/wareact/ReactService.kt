package com.example.wareact

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.PendingIntent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 *  1) تلقائي: إشعار/رسالة واردة فيها كلمة مفتاحية -> ضغط مطوّل ثم اختيار الإيموجي.
 *  2) يدوي: نقرة واحدة على أي رسالة داخل المحادثة -> يتفاعل عليها فوراً بالإيموجي المختار.
 * حمايات ضد التعليق: مؤقّت مراقبة (watchdog)، التقاط كل الأخطاء، إعادة ضبط الحالة
 * عند كل إيقاف/تشغيل أو حفظ إعدادات، وتقليل ضغط المسح (throttle).
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

    /** توقيع الرسالة -> وقت انتهاء تجاهلها (System.currentTimeMillis). */
    private val seen = HashMap<String, Long>()

    private var busy = false
    private var busySince = 0L
    private var curSig: String? = null
    private var curManual = false
    private var curList: AccessibilityNodeInfo? = null

    private var scanQueued = false
    private var queuedDue = 0L
    private var lastScanAt = 0L

    private var epoch = -1L
    private var refreshing = false
    private var lastRefreshTry = 0L
    private var lastReactAt = 0L
    private var lastManualAt = 0L
    private var lastDiag = ""

    private val scanTask = Runnable {
        scanQueued = false
        try {
            scan()
        } catch (t: Throwable) {
            fail("خطأ بالمسح", t)
        }
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

    // ------------------------------------------------------------------ الأحداث

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        e ?: return
        try {
            handle(e)
        } catch (t: Throwable) {
            fail("خطأ", t)
        }
    }

    private fun handle(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        val auto = Prefs.isRunning(this)
        val manual = Prefs.isManual(this)
        syncEpoch()
        if (!auto && !manual) return

        maybeRefreshLicense()
        if (!LicenseManager.isActive(this)) {
            diag("الترخيص غير فعّال أو يحتاج إنترنت للتحقق")
            return
        }

        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> if (manual) onManualTap(e)
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> if (auto) onNotification(e)
            else -> if (auto) requestScan(40)
        }
    }

    /** أي إيقاف/تشغيل أو حفظ إعدادات من التطبيق يصفّر حالة الخدمة (يحل التعليق). */
    private fun syncEpoch() {
        val ep = Prefs.epoch(this)
        if (ep != epoch) {
            epoch = ep
            seen.clear()
            busy = false
            lastReactAt = 0L
            lastManualAt = 0L
            lastDiag = ""
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
        if ((seen[key] ?: 0L) > now) return
        seen[key] = now + 30_000L

        Prefs.setStatus(this, "إشعار فيه «$kw» — جارٍ فتح المحادثة")
        try {
            n.contentIntent?.send()
        } catch (_: PendingIntent.CanceledException) {
            Prefs.setStatus(this, "تعذّر فتح المحادثة من الإشعار")
        }
    }

    // ------------------------------------------------------------------ وضع النقر اليدوي

    private fun onManualTap(e: AccessibilityEvent) {
        val nowUp = SystemClock.uptimeMillis()
        if (busy) return
        if (nowUp - lastManualAt < 1500) return

        val src = e.source
        if (src == null) {
            diag("نقرة: لا يوجد عنصر مصدر")
            return
        }
        val root = rootInActiveWindow ?: return
        if (!inConversation(root)) return // خارج شاشة المحادثة (قائمة المحادثات مثلاً)

        // الفقاعة = أقرب أب يقبل الضغط المطوّل
        var bubble: AccessibilityNodeInfo? = null
        var cur: AccessibilityNodeInfo? = src
        var d = 0
        while (cur != null && d < 10) {
            if (cur.isLongClickable) { bubble = cur; break }
            cur = cur.parent
            d++
        }
        if (bubble == null) {
            diag("نقرة على ${shortName(src)} — ما لقيت رسالة تحتها")
            return
        }
        val list = listAncestor(bubble)
        if (list == null) {
            diag("نقرة على ${shortName(src)} خارج قائمة الرسائل")
            return
        }

        lastManualAt = nowUp
        val r = Rect()
        bubble.getBoundsInScreen(r)
        react(bubble, r, null, "نقرة يدوية — جارٍ التفاعل", true, list)
    }

    private fun shortName(n: AccessibilityNodeInfo): String =
        n.className?.toString()?.substringAfterLast('.') ?: "عنصر"

    /** قائمة الرسائل التي تحوي هذه الفقاعة (أو null إن لم توجد). */
    private fun listAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var p = n.parent
        var d = 0
        while (p != null && d < 8) {
            val cn = p.className?.toString().orEmpty()
            if (p.isScrollable || cn.contains("RecyclerView") || cn.contains("ListView")) return p
            p = p.parent
            d++
        }
        return null
    }

    private fun isInside(n: AccessibilityNodeInfo, anc: AccessibilityNodeInfo): Boolean {
        var p: AccessibilityNodeInfo? = n
        var d = 0
        while (p != null && d < 14) {
            if (p == anc) return true
            p = p.parent
            d++
        }
        return false
    }

    /** عنصر داخل قائمة الرسائل (مثل فقاعة تفاعل موجودة تحت رسالة) وليس شريط الإيموجي. */
    private fun inMsgList(n: AccessibilityNodeInfo): Boolean {
        val l = curList ?: return false
        return isInside(n, l)
    }

    /** معرّف خانة الكتابة (نتعلمه مرة واحدة ثم نفحصه بطلب واحد سريع بدل مسح الشاشة كلها). */
    private var inputId: String? = null

    private fun inConversation(root: AccessibilityNodeInfo): Boolean {
        val id = inputId
        if (id != null) {
            val l = root.findAccessibilityNodeInfosByViewId(id)
            if (!l.isNullOrEmpty()) return true
        }
        // مسار بطيء: فقط عند الجهل بالمعرّف، أو خارج المحادثة
        var found = false
        walk(root) {
            if (!found && it.isEditable) {
                found = true
                inputId = it.viewIdResourceName
            }
        }
        return found
    }

    // ------------------------------------------------------------------ مسح المحادثة (تلقائي)

    private fun requestScan(delayMs: Long) {
        val nowUp = SystemClock.uptimeMillis()
        val due = nowUp + maxOf(delayMs, MIN_GAP - (nowUp - lastScanAt), 0L)
        if (scanQueued) {
            if (due >= queuedDue) return
            main.removeCallbacks(scanTask)
        }
        scanQueued = true
        queuedDue = due
        main.postAtTime(scanTask, due)
    }

    private fun scan() {
        val nowUp = SystemClock.uptimeMillis()
        syncEpoch()

        if (busy) {
            if (nowUp - busySince > BUSY_LIMIT) {
                busy = false
                diag("إعادة ضبط تلقائية بعد تعليق")
            } else {
                requestScan(300)
                return
            }
        }
        if (!Prefs.isRunning(this) || !LicenseManager.isActive(this)) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString()
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        lastScanAt = nowUp
        requestScan(POLL) // يبقي المسح الدوري شغّالاً طالما واتساب أمامك

        val kws = Prefs.keywords(this)
        if (kws.isEmpty()) {
            diag("لا توجد كلمات مفتاحية محفوظة")
            return
        }
        if (inCooldown()) {
            val left = (Prefs.cooldownSec(this) * 1000L - (System.currentTimeMillis() - lastReactAt)) / 1000 + 1
            diag("⏳ فترة الخمول: باقي $left ث")
            return
        }
        if (lastDiag.isEmpty() || lastDiag.startsWith("⏳")) {
            diag("أراقب شاشة واتساب… بانتظار رسالة فيها كلمة مفتاحية")
        }

        val width = resources.displayMetrics.widthPixels
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val now = System.currentTimeMillis()
        pruneSeen(now)

        var hasInput = false
        var best: Cand? = null
        walk(root) { node ->
            // خانة الكتابة: نعرف منها إننا داخل محادثة، ولا نطابق نصها
            if (node.isEditable) { hasInput = true; return@walk }

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
            if ((seen[sig] ?: 0L) > now) return@walk

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

        if (!hasInput) return // لسنا داخل محادثة (مثلاً قائمة المحادثات): لا نتفاعل
        val target = best ?: return
        react(
            target.bubble, target.bounds, target.sig,
            "رسالة فيها «${target.keyword}» — جارٍ التفاعل", false,
            target.bubble?.let { listAncestor(it) }
        )
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

    private fun react(
        bubble: AccessibilityNodeInfo?,
        bounds: Rect,
        sig: String?,
        label: String,
        manual: Boolean,
        list: AccessibilityNodeInfo?
    ) {
        busy = true
        curList = list
        busySince = SystemClock.uptimeMillis()
        curSig = sig
        curManual = manual
        if (sig != null) seen[sig] = System.currentTimeMillis() + 15 * 60_000L
        Prefs.setStatus(this, label)
        lastDiag = "react"

        val viaAction = bubble?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true
        val delay: Long
        if (viaAction) {
            delay = 40
        } else {
            gesture(
                bounds.centerX().toFloat(), bounds.centerY().toFloat(),
                ViewConfiguration.getLongPressTimeout() + 250L
            )
            delay = ViewConfiguration.getLongPressTimeout() + 500L
        }
        main.postDelayed({ pickEmoji(0) }, delay)
    }

    private fun pickEmoji(attempt: Int) {
        try {
            val emoji = Prefs.emoji(this)
            // بحث سريع بطلب واحد؛ وبعد عدة محاولات نلجأ للمسح الكامل كاحتياط
            val node = findEmojiNode(emoji) ?: if (attempt >= 6) findEmojiSlow(emoji) else null
            if (node != null && clickNode(node)) {
                if (!curManual) lastReactAt = System.currentTimeMillis()
                Prefs.incReact(this)
                Prefs.setStatus(this, if (curManual) "تم التفاعل يدوياً $emoji" else "تم التفاعل $emoji")
                busy = false
                requestScan(POLL)
                return
            }
            if (attempt < 40) {
                main.postDelayed({ pickEmoji(attempt + 1) }, 50)
            } else {
                // فشل: نسمح بإعادة المحاولة على نفس الرسالة بعد 8 ثوانٍ فقط
                curSig?.let { seen[it] = System.currentTimeMillis() + 8_000L }
                performGlobalAction(GLOBAL_ACTION_BACK)
                Prefs.setStatus(this, "لم أجد $emoji في شريط التفاعلات (استعمل إيموجي من الشريط السريع)")
                busy = false
                requestScan(POLL)
            }
        } catch (t: Throwable) {
            fail("خطأ بالتفاعل", t)
        }
    }

    private val englishNames = mapOf(
        "👍" to listOf("thumbs up", "like"),
        "❤" to listOf("red heart", "heart", "love"),
        "😂" to listOf("tears of joy", "laughing"),
        "😮" to listOf("open mouth", "surprised"),
        "😢" to listOf("crying", "sad"),
        "🙏" to listOf("folded hands", "pray", "thanks")
    )

    private fun roots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        windows?.forEach { w -> w.root?.let { roots.add(it) } }
        rootInActiveWindow?.let { roots.add(it) }
        return roots
    }

    private fun matchesEmoji(n: AccessibilityNodeInfo, target: String, names: List<String>): Boolean {
        val txt = n.text?.toString()?.replace("️", "")?.trim()
        val desc = n.contentDescription?.toString()
        val d = desc?.replace("️", "")?.trim()
        return txt == target || d == target ||
            (desc != null && desc.length <= 40 && names.any { desc.lowercase().contains(it) })
    }

    /** بحث سريع: طلب واحد لكل نافذة بدل المشي على كل العناصر. */
    private fun findEmojiNode(emoji: String): AccessibilityNodeInfo? {
        val target = emoji.replace("️", "").trim()
        val names = englishNames[target].orEmpty()
        val queries = (listOf(emoji, target) + names).distinct()
        for (r in roots()) {
            for (q in queries) {
                val list = r.findAccessibilityNodeInfosByText(q) ?: continue
                for (n in list) {
                    if (n.isVisibleToUser && !inMsgList(n) && matchesEmoji(n, target, names)) return n
                }
            }
        }
        return null
    }

    /** احتياط بطيء: المسح الكامل القديم. */
    private fun findEmojiSlow(emoji: String): AccessibilityNodeInfo? {
        val target = emoji.replace("️", "").trim()
        val names = englishNames[target].orEmpty()
        val roots = ArrayList<AccessibilityNodeInfo>()
        windows?.forEach { w -> w.root?.let { roots.add(it) } }
        rootInActiveWindow?.let { roots.add(it) }

        var found: AccessibilityNodeInfo? = null
        for (r in roots) {
            walk(r) { n ->
                if (found != null) return@walk
                val txt = n.text?.toString()?.replace("️", "")?.trim()
                val desc = n.contentDescription?.toString()
                val d = desc?.replace("️", "")?.trim()
                val hit = txt == target || d == target ||
                    (desc != null && desc.length <= 40 && names.any { desc.lowercase().contains(it) })
                if (hit && !inMsgList(n)) found = n
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

    private fun fail(msg: String, t: Throwable) {
        busy = false
        Prefs.setStatus(this, "$msg: ${t.javaClass.simpleName} — أكمل العمل")
    }

    private fun diag(msg: String) {
        if (msg != lastDiag) {
            lastDiag = msg
            Prefs.setStatus(this, msg)
        }
    }

    private fun inCooldown(): Boolean =
        System.currentTimeMillis() - lastReactAt < Prefs.cooldownSec(this) * 1000L

    private fun pruneSeen(now: Long) {
        seen.entries.removeAll { it.value <= now }
    }

    /** نمشي فقط على العناصر الظاهرة على الشاشة (أخف بكثير ولا يعلّق الخدمة). */
    private fun walk(n: AccessibilityNodeInfo?, depth: Int = 0, f: (AccessibilityNodeInfo) -> Unit) {
        if (n == null || depth > 40) return
        if (!n.isVisibleToUser) return
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

    private companion object {
        const val POLL = 400L        // مسح كل 0.4 ثانية
        const val MIN_GAP = 250L     // أقل فاصل بين مسحين
        const val BUSY_LIMIT = 5000L // أقصى مدة لعملية تفاعل قبل إعادة الضبط
    }
}
