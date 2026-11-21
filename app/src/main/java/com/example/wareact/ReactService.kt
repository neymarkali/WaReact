package com.example.wareact

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
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
 *  1) تلقائي: رسالة واردة جديدة فيها كلمة مفتاحية -> ضغط مطوّل ثم اختيار الإيموجي.
 *     الرسائل الموجودة لحظة فتح المحادثة تُتجاهل (إلا إذا فُتحت من إشعار فيه الكلمة).
 *  2) يدوي: نقرة واحدة على رسالة -> يتفاعل عليها فوراً، ثم يتوقف 10 ثوانٍ.
 *     يتجاهل الرسالة التي تفاعلت عليها سابقاً.
 * حمايات: مؤقّت مراقبة (watchdog)، التقاط كل الأخطاء، تصفير الحالة عند كل إيقاف/تشغيل/حفظ،
 * تقليل ضغط المسح، ولا يتفاعل خارج شاشة المحادثة.
 */
class ReactService : AccessibilityService() {

    private class Cand(
        val sig: String,
        val keyword: String,
        val text: String,
        val bubble: AccessibilityNodeInfo?,
        val bounds: Rect
    )

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** توقيع الرسالة -> وقت انتهاء تجاهلها (System.currentTimeMillis). */
    private val seen = HashMap<String, Long>()

    /** رسائل تفاعلنا عليها (حتى 400). */
    private val mine = LinkedHashSet<String>()

    private var busy = false
    private var busySince = 0L
    private var curSig: String? = null
    private var curManual = false
    private var curList: AccessibilityNodeInfo? = null
    private var curT0 = 0L
    private var reactToken = 0

    private var scanQueued = false
    private var queuedDue = 0L
    private var lastScanAt = 0L

    // جلسة المحادثة: نتجاهل الرسائل الموجودة عند فتحها
    private var forceNewSession = false
    private var lastHasInputAt = 0L
    private var sessionStart = 0L
    private var armedUntil = 0L
    private var armedText = ""

    private var epoch = -1L
    private var refreshing = false
    private var lastRefreshTry = 0L
    private var lastReactAt = 0L
    private var manualPauseUntil = 0L
    private var lastManualAt = 0L
    private var lastDiag = ""
    private var inputId: String? = null

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
        Live.service = this
        Diag.log(this, "✅ خدمة الوصول اتصلت")
        Prefs.setStatus(this, "خدمة الوصول متصلة")
        Keep.sync(this)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (Live.service === this) Live.service = null
        Diag.log(this, "⛔ النظام فصل خدمة الوصول (onUnbind)", true)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (Live.service === this) Live.service = null
        Diag.log(this, "⛔ خدمة الوصول دُمّرت (onDestroy)", true)
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
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> if (auto) {
                if (e.className?.toString()?.contains("onversation", ignoreCase = true) == true) {
                    forceNewSession = true
                }
                requestScan(20)
            }
            else -> if (auto) requestScan(20)
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
            manualPauseUntil = 0L
            lastHasInputAt = 0L
            armedUntil = 0L
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

        // نسمح بالتفاعل مع هذه الرسالة بالذات عند فتح المحادثة من الإشعار
        armedText = TextUtil.normalize(text)
        armedUntil = now + 20_000L

        Prefs.setStatus(this, "إشعار فيه «$kw» — جارٍ فتح المحادثة")
        try {
            n.contentIntent?.send()
        } catch (_: PendingIntent.CanceledException) {
            Prefs.setStatus(this, "تعذّر فتح المحادثة من الإشعار")
        }
    }

    private fun armedMatches(t: String): Boolean {
        if (armedText.isEmpty()) return false
        val m = TextUtil.normalize(t)
        return m.isNotEmpty() && (armedText.contains(m) || m.contains(armedText))
    }

    // ------------------------------------------------------------------ وضع النقر اليدوي

    private fun onManualTap(e: AccessibilityEvent) {
        val nowUp = SystemClock.uptimeMillis()
        if (busy) return
        if (nowUp < manualPauseUntil) {
            diag("⏸ النقر اليدوي متوقف: باقي ${(manualPauseUntil - nowUp) / 1000 + 1} ث")
            return
        }
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
        val la = listAncestor(bubble)
        if (la == null) {
            diag("نقرة على ${shortName(src)} خارج قائمة الرسائل")
            return
        }
        val list = la.first
        val item = la.second

        // نتجاهل الرسالة التي تفاعلنا عليها سابقاً
        val sig = messageSig(item)
        if (sig != null && mine.contains(sig)) {
            diag("عليها تفاعل منك مسبقاً — تجاهلتها")
            return
        }
        if (hasSameReaction(item, Prefs.emoji(this))) {
            diag("عليها نفس التفاعل مسبقاً — تجاهلتها")
            return
        }

        lastManualAt = nowUp
        val r = Rect()
        bubble.getBoundsInScreen(r)
        react(bubble, r, sig, "نقرة يدوية — جارٍ التفاعل", true, list, e.eventTime)
    }

    private fun shortName(n: AccessibilityNodeInfo): String =
        n.className?.toString()?.substringAfterLast('.') ?: "عنصر"

    /** قائمة الرسائل + عنصر القائمة (صف الرسالة) الذي يحوي هذه الفقاعة. */
    private fun listAncestor(
        n: AccessibilityNodeInfo
    ): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo>? {
        var prev: AccessibilityNodeInfo = n
        var p = n.parent
        var d = 0
        while (p != null && d < 8) {
            val cn = p.className?.toString().orEmpty()
            if (p.isScrollable || cn.contains("RecyclerView") || cn.contains("ListView")) {
                return Pair(p, prev)
            }
            prev = p
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

    /** توقيع الرسالة = أطول نص فيها + وقتها. */
    private fun messageSig(item: AccessibilityNodeInfo): String? {
        var longest = ""
        walk(item) { n ->
            val s = n.text?.toString()
            if (!s.isNullOrBlank() && TextUtil.parseMinutes(s.trim()) == null && s.length > longest.length) {
                longest = s
            }
        }
        if (longest.isEmpty()) return null
        val tm = findTime(item)?.second ?: ""
        return TextUtil.normalize(longest).take(80) + "|" + tm
    }

    /** هل تحت الرسالة فقاعة تفاعل بنفس الإيموجي؟ (الضغط عليها مرة ثانية يلغي التفاعل) */
    private fun hasSameReaction(item: AccessibilityNodeInfo, emoji: String): Boolean {
        val target = emoji.replace("️", "")
        if (target.isEmpty()) return false
        var found = false
        walk(item) { n ->
            if (found) return@walk
            val t = n.text?.toString()?.replace("️", "").orEmpty()
            val d = n.contentDescription?.toString()?.replace("️", "").orEmpty()
            val hit = (t.length in 1..6 && t.contains(target)) ||
                (d.length in 1..40 && d.contains(target))
            if (hit && (n.isClickable || n.parent?.isClickable == true)) found = true
        }
        return found
    }

    /** نتعلم معرّف خانة الكتابة مرة واحدة ثم نفحصه بطلب واحد سريع. */
    private fun inConversation(root: AccessibilityNodeInfo): Boolean {
        val id = inputId
        if (id != null) {
            val l = root.findAccessibilityNodeInfosByViewId(id)
            if (!l.isNullOrEmpty()) return true
        }
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
                requestScan(200)
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
        val cands = ArrayList<Cand>()
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
            if (tm != null && !TextUtil.isFresh(tm.first, 1)) {
                diag("«$kw» رسالة قديمة (${tm.second}) — أتفاعل فقط مع الجديدة")
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

            // الفقاعة = أقرب أب يقبل الضغط المطوّل (إن وُجد)
            var bubble: AccessibilityNodeInfo? = null
            var a: AccessibilityNodeInfo? = node
            var d = 0
            while (a != null && d < 9) {
                if (a.isLongClickable) { bubble = a; break }
                a = a.parent
                d++
            }
            cands.add(Cand(sig, kw, t, bubble, tb))
        }

        if (!hasInput) return // لسنا داخل محادثة (مثلاً قائمة المحادثات): لا نتفاعل

        // جلسة جديدة = دخول محادثة (أو عودة لها بعد غياب): نتجاهل ما كان موجوداً فيها
        val newSession = forceNewSession || nowUp - lastHasInputAt > SESSION_GAP
        lastHasInputAt = nowUp
        if (newSession) {
            forceNewSession = false
            sessionStart = nowUp
        }
        val inBaseline = nowUp - sessionStart < BASELINE_MS
        val armed = now < armedUntil

        var best: Cand? = null
        for (c in cands) {
            if ((seen[c.sig] ?: 0L) > now) continue
            if (mine.contains(c.sig)) continue
            if (inBaseline && !(armed && armedMatches(c.text))) {
                seen[c.sig] = now + 15 * 60_000L // كانت موجودة قبلنا: لا نتفاعل
                continue
            }
            if (best == null || c.bounds.bottom > best.bounds.bottom) best = c
        }
        val target = best ?: return
        armedUntil = 0L
        react(
            target.bubble, target.bounds, target.sig,
            "رسالة فيها «${target.keyword}» — جارٍ التفاعل", false,
            target.bubble?.let { listAncestor(it)?.first }, nowUp
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
        list: AccessibilityNodeInfo?,
        t0: Long
    ) {
        busy = true
        busySince = SystemClock.uptimeMillis()
        curSig = sig
        curManual = manual
        curList = list
        curT0 = t0
        if (sig != null) seen[sig] = System.currentTimeMillis() + 15 * 60_000L
        Prefs.setStatus(this, label)
        lastDiag = "react"

        val tk = ++reactToken
        val viaAction = bubble?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true
        if (viaAction) {
            main.post { pickEmoji(0, tk) }
        } else {
            gesture(
                bounds.centerX().toFloat(), bounds.centerY().toFloat(),
                ViewConfiguration.getLongPressTimeout() + 250L
            )
            main.postDelayed({ pickEmoji(0, tk) }, ViewConfiguration.getLongPressTimeout() + 400L)
        }
    }

    private fun remember(sig: String) {
        mine.add(sig)
        if (mine.size > 400) {
            val it = mine.iterator()
            if (it.hasNext()) { it.next(); it.remove() }
        }
    }

    private fun pickEmoji(attempt: Int, tk: Int) {
        // انتهت هذه العملية (تصفير/مهلة): لا نكمل حلقة قديمة تتداخل مع عملية جديدة
        if (tk != reactToken || !busy) return
        try {
            val emoji = Prefs.emoji(this)
            // بحث سريع بطلب واحد؛ وبعد عدة محاولات نلجأ للمسح الكامل كاحتياط
            val node = findEmojiNode(emoji) ?: if (attempt >= 60) findEmojiSlow(emoji) else null
            if (node != null && clickNode(node)) {
                val ms = SystemClock.uptimeMillis() - curT0
                if (!curManual) lastReactAt = System.currentTimeMillis()
                curSig?.let { remember(it) }
                if (curManual) manualPauseUntil = SystemClock.uptimeMillis() + MANUAL_PAUSE
                Prefs.incReact(this)
                Prefs.setStatus(
                    this,
                    (if (curManual) "تم التفاعل يدوياً $emoji" else "تم التفاعل $emoji") + " (${ms}ms)"
                )
                busy = false
                requestScan(POLL)
                return
            }
            if (SystemClock.uptimeMillis() - busySince < PICK_LIMIT) {
                main.postDelayed({ pickEmoji(attempt + 1, tk) }, 4)
            } else {
                // فشل: نسمح بإعادة المحاولة على نفس الرسالة بعد 8 ثوانٍ فقط
                curSig?.let { seen[it] = System.currentTimeMillis() + 8_000L }
                if (curManual) manualPauseUntil = SystemClock.uptimeMillis() + MANUAL_PAUSE
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

    /** النافذة النشطة أولاً (الأسرع) ثم باقي النوافذ. */
    private fun roots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        windows?.forEach { w -> w.root?.let { roots.add(it) } }
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
                    if (!inMsgList(n) && matchesEmoji(n, target, names)) return n
                }
            }
        }
        return null
    }

    /** احتياط بطيء: المسح الكامل. */
    private fun findEmojiSlow(emoji: String): AccessibilityNodeInfo? {
        val target = emoji.replace("️", "").trim()
        val names = englishNames[target].orEmpty()
        var found: AccessibilityNodeInfo? = null
        for (r in roots()) {
            walk(r) { n ->
                if (found != null) return@walk
                if (matchesEmoji(n, target, names) && !inMsgList(n)) found = n
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
        val age = now - Prefs.licenseCheckedAt(this)
        if (age >= 0 && age < 6 * 3_600_000L) return // حديث (وإن رجعت الساعة للخلف نعيد التحقق)
        if (now - lastRefreshTry < 10 * 60_000L) return
        lastRefreshTry = now
        refreshing = true
        scope.launch {
            try {
                LicenseManager.refresh(this@ReactService)
            } catch (_: Throwable) {
            } finally {
                refreshing = false
            }
        }
    }

    private companion object {
        const val POLL = 300L            // مسح دوري احتياطي (الأحداث تشغّل المسح فوراً)
        const val MIN_GAP = 80L          // أقل فاصل بين مسحين (يخفف الحمل فلا يقتل النظام التطبيق)
        const val PICK_LIMIT = 3500L     // أقصى مدة للبحث عن الإيموجي بعد الضغط المطوّل
        const val BUSY_LIMIT = 5000L     // أقصى مدة لعملية تفاعل قبل إعادة الضبط
        const val MANUAL_PAUSE = 10_000L // توقف النقر اليدوي بعد كل تفاعل
        const val SESSION_GAP = 3000L    // غياب خانة الكتابة أكثر من هذا = دخول محادثة جديدة
        const val BASELINE_MS = 1500L    // مدة تجاهل الرسائل الموجودة عند فتح المحادثة
    }
}
