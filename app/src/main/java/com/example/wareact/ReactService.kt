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
        val list: AccessibilityNodeInfo?,
        val bounds: Rect,
        val qk: String,
        val why: String
    )

    /** نتيجة صعود واحد من عقدة داخل رسالة: الفقاعة (تقبل الضغط المطوّل) وقائمة الرسائل وصف الرسالة. */
    private class Row(
        val list: AccessibilityNodeInfo?,
        val item: AccessibilityNodeInfo?,
        val bubble: AccessibilityNodeInfo?
    )

    /** فحص سريع لصف رسالة: هل هو رسالة فعلاً (له وقت)؟ وتوقيعها إن أمكن. */
    private class Info(val isMsg: Boolean, val sig: String?)

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

    /** عقد تجاهلناها (قديمة/صادرة/بلا كلمة/معالجة): مفتاحها -> وقت انتهاء التجاهل. يوفّر عملاً متكرراً. */
    private val skip = HashMap<String, Long>()
    private var lastPrune = 0L

    // مسار الكشف السريع
    private var lastFastAt = 0L
    private var fastOk = false

    // الكلمات المفتاحية بعد التطبيع (تُحسب مرة لكل إعداد)
    private var rawKws: List<String> = emptyList()
    private var preKws: List<Pair<String, String>> = emptyList()
    private var preDirty = true

    private var lastInputWalk = 0L
    private var inConvUntil = 0L

    // مسار الكشف السريع: جذر مخزّن (يوفّر جلب الشجرة عند كل حدث) ومعرّفات واتساب
    private var fastRoot: AccessibilityNodeInfo? = null
    private var fastRootAt = 0L
    private var fastQueued = false
    private var lastIdHit = 0L

    // تصنيف وارد/صادر: علامة التسليم (مستقلة عن اتجاه اللغة) + اتجاه واتساب المتعلَّم
    private var tickOk = false
    private var outLeft: Boolean? = null
    private var lastCls = ""
    private var lastOwnClickAt = 0L

    // قياس الزمن داخل التفاعل
    private var curH0 = 0L
    private var curTLong = 0L
    private var curTLongEnd = 0L
    private var curViaAction = true
    private var curWhy = ""
    private var pickRoot: AccessibilityNodeInfo? = null

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
        val h0 = SystemClock.uptimeMillis()
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
            AccessibilityEvent.TYPE_VIEW_CLICKED -> if (manual) onManualTap(e, h0)
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> if (auto) onNotification(e)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // نافذة جديدة: الجذر المخزّن وحالة «داخل محادثة» لم يعودا صالحين
                fastRoot = null
                inConvUntil = 0L
                if (auto) {
                    if (e.className?.toString()?.contains("onversation", ignoreCase = true) == true) {
                        forceNewSession = true
                    }
                    requestScan(20)
                }
            }
            else -> if (auto) {
                fastScan(e.eventTime) // كشف فوري بلا انتظار
                // المسح الكامل شبكة أمان فقط: نخففه عندما يعمل المسار السريع (يريح الهواتف الأضعف)
                requestScan(if (idPathOk()) NET_GAP else if (fastOk) FULL_AFTER_EVENT else 20)
            }
        }
    }

    /** هل وجدنا نصوص الرسائل عبر معرّف واتساب حديثاً؟ (المسار السريع يعمل) */
    private fun idPathOk(): Boolean = SystemClock.uptimeMillis() - lastIdHit < ID_OK_MS

    /** أي إيقاف/تشغيل أو حفظ إعدادات من التطبيق يصفّر حالة الخدمة (يحل التعليق). */
    private fun syncEpoch() {
        val ep = Prefs.epoch(this)
        if (ep != epoch) {
            epoch = ep
            seen.clear()
            skip.clear()
            fastRoot = null
            inConvUntil = 0L
            lastCls = ""
            preDirty = true
            lastFastAt = 0L
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

    private fun onManualTap(e: AccessibilityEvent, h0: Long) {
        val nowUp = SystemClock.uptimeMillis()
        if (busy) return
        // حدث النقر الذي ولّدناه نحن عند اختيار الإيموجي: نتجاهله كي لا يكتب فوق حالة «تم التفاعل»
        if (nowUp - lastOwnClickAt < OWN_CLICK_GUARD) return
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

        // صعود واحد من العنصر المنقور: الفقاعة ثم قائمة الرسائل وصف الرسالة (بلا جلب جذر النافذة)
        val row = climb(src)
        val bubble = row.bubble
        val list = row.list
        val item = row.item
        if (bubble == null) {
            diag("نقرة على ${shortName(src)} — ما لقيت رسالة تحتها")
            return
        }
        if (list == null || item == null) {
            diag("نقرة على ${shortName(src)} خارج قائمة الرسائل")
            return
        }

        // نتأكد أنها رسالة داخل محادثة بمعرّف وقت الرسالة (طلبان فقط) بدل جلب شجرة النافذة كلها
        val pkg = e.packageName?.toString() ?: "com.whatsapp"
        val info = quickInfo(item, pkg)
        if (!info.isMsg) {
            // احتياط (معرّفات واتساب تغيّرت أو رسالة غير نصية): الفحص الكامل القديم
            val root = rootInActiveWindow ?: return
            if (!inConversation(root, true)) return // خارج شاشة المحادثة (قائمة المحادثات مثلاً)
        }

        // نتجاهل الرسالة التي تفاعلنا عليها سابقاً (فحوصات رخيصة: بضعة طلبات فقط)
        val sig = info.sig ?: messageSig(item)
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
        react(bubble, r, sig, "نقرة يدوية — جارٍ التفاعل", true, list, e.eventTime, h0, "نقرة المستخدم")
    }

    /** فحص سريع لصف الرسالة بمعرّفات واتساب (طلبان): هل هو رسالة (له وقت)؟ وتوقيعها. */
    private fun quickInfo(item: AccessibilityNodeInfo, pkg: String): Info {
        var text: String? = null
        var tm: String? = null
        try {
            text = item.findAccessibilityNodeInfosByViewId("$pkg:id/message_text")
                ?.firstOrNull()?.text?.toString()
            tm = item.findAccessibilityNodeInfosByViewId("$pkg:id/date")
                ?.firstOrNull()?.text?.toString()?.trim()
        } catch (_: Throwable) {
        }
        val tmv = tm
        val txv = text
        val isMsg = !tmv.isNullOrBlank() && TextUtil.parseMinutes(tmv) != null
        val sig = if (isMsg && !txv.isNullOrBlank()) TextUtil.normalize(txv).take(80) + "|" + tmv else null
        return Info(isMsg, sig)
    }

    private fun isListNode(n: AccessibilityNodeInfo): Boolean {
        if (n.isScrollable) return true
        val cn = n.className?.toString().orEmpty()
        return cn.contains("RecyclerView") || cn.contains("ListView")
    }

    /**
     * صعود واحد من عقدة داخل رسالة: الفقاعة (أول أب يقبل الضغط المطوّل)،
     * ثم قائمة الرسائل وعنصر القائمة (صف الرسالة) الذي يحويها.
     */
    private fun climb(start: AccessibilityNodeInfo): Row {
        var bubble: AccessibilityNodeInfo? = null
        var prev: AccessibilityNodeInfo = start
        var cur: AccessibilityNodeInfo? = start
        var steps = 0
        while (cur != null && steps < 18) {
            if (steps > 0 && isListNode(cur)) return Row(cur, prev, bubble)
            if (bubble == null && steps < 9 && cur.isLongClickable) bubble = cur
            prev = cur
            cur = cur.parent
            steps++
        }
        return Row(null, null, bubble)
    }

    /** وقت الرسالة من معرّف واتساب داخل صفها (طلب واحد). */
    private fun rowTime(item: AccessibilityNodeInfo, pkg: String): Pair<Int, String>? {
        try {
            val l = item.findAccessibilityNodeInfosByViewId("$pkg:id/date") ?: return null
            for (n in l) {
                val s = n.text?.toString()?.trim()
                if (!s.isNullOrEmpty() && s.length <= 12) {
                    val m = TextUtil.parseMinutes(s)
                    if (m != null) return m to s
                }
            }
        } catch (_: Throwable) {
        }
        return null
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
        // أسرع: طلب واحد ينفَّذ داخل واتساب نفسه
        val hits = try {
            item.findAccessibilityNodeInfosByText(target)
        } catch (_: Throwable) {
            null
        }
        if (hits != null) {
            for (n in hits) {
                val t = n.text?.toString()?.replace("️", "").orEmpty()
                val d = n.contentDescription?.toString()?.replace("️", "").orEmpty()
                val hit = (t.length in 1..6 && t.contains(target)) ||
                    (d.length in 1..40 && d.contains(target))
                if (hit && (n.isClickable || n.parent?.isClickable == true)) return true
            }
            return false
        }
        // احتياط: المشي على الصف
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

    /**
     * هل نحن داخل محادثة؟ نتعلم معرّف خانة الكتابة ثم نفحصه بطلب واحد.
     * المشي الكامل الاحتياطي محدود التكرار (إلا عند النقر اليدوي force).
     */
    private fun inConversation(root: AccessibilityNodeInfo, force: Boolean): Boolean {
        val id = inputId
        if (id != null) {
            val l = root.findAccessibilityNodeInfosByViewId(id)
            if (!l.isNullOrEmpty()) return true
        }
        val nowUp = SystemClock.uptimeMillis()
        if (!force && nowUp - lastInputWalk < INPUT_WALK_GAP) return false
        lastInputWalk = nowUp
        var found = false
        walk(root) {
            if (!found && it.isEditable) {
                found = true
                inputId = it.viewIdResourceName ?: inputId
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
        runScan(false, SystemClock.uptimeMillis())
    }

    /**
     * كشف فوري عند كل حدث: يطلب نصوص الرسائل بطلب واحد بدل المشي على الشجرة كلها.
     * إن جاء حدث أثناء فترة التهدئة نؤجّله لنهايتها (لا نُسقطه) فلا تضيع آخر رسالة.
     */
    private fun fastScan(evTime: Long) {
        val nowUp = SystemClock.uptimeMillis()
        if (busy) return
        val since = nowUp - lastFastAt
        if (since < FAST_GAP) {
            if (!fastQueued) {
                fastQueued = true
                main.postDelayed({
                    fastQueued = false
                    fastScan(evTime)
                }, FAST_GAP - since)
            }
            return
        }
        lastFastAt = nowUp
        try {
            runScan(true, evTime)
        } catch (t: Throwable) {
            fail("خطأ بالكشف السريع", t)
        }
    }

    /** الجذر المخزّن للمسار السريع (يتجدد كل بضع ثوانٍ أو عند تغيّر النافذة). */
    private fun fastRootNode(nowUp: Long): AccessibilityNodeInfo? {
        val r = fastRoot
        if (r != null && nowUp - fastRootAt < ROOT_TTL) return r
        return freshRoot(nowUp)
    }

    private fun freshRoot(nowUp: Long): AccessibilityNodeInfo? {
        val r = rootInActiveWindow
        fastRoot = r
        fastRootAt = nowUp
        return r
    }

    private fun runScan(fast: Boolean, t0: Long) {
        val nowUp = SystemClock.uptimeMillis()
        syncEpoch()

        if (busy) {
            if (fast) return
            if (nowUp - busySince > BUSY_LIMIT) {
                busy = false
                diag("إعادة ضبط تلقائية بعد تعليق")
            } else {
                requestScan(200)
                return
            }
        }
        if (!Prefs.isRunning(this) || !LicenseManager.isActive(this)) return
        var root: AccessibilityNodeInfo = (if (fast) fastRootNode(nowUp) else freshRoot(nowUp)) ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        if (!fast) {
            lastScanAt = nowUp
            // يبقي المسح الدوري شغّالاً طالما واتساب أمامك (أبطأ عندما يعمل المسار السريع)
            requestScan(if (idPathOk()) NET_GAP else POLL)
        }

        if (preDirty) {
            rawKws = Prefs.keywords(this)
            preKws = TextUtil.normalizeAll(rawKws)
            preDirty = false
        }
        if (preKws.isEmpty()) {
            diag("لا توجد كلمات مفتاحية محفوظة")
            return
        }
        val test = Prefs.isTest(this)
        if (inCooldown() && !test) {
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
        if (fast) {
            var nodes = fastNodes(root, pkg, rawKws)
            if (nodes.isNullOrEmpty() && nowUp - fastRootAt > ROOT_RETRY) {
                // الجذر المخزّن قد يكون قديماً: نجلب جذراً جديداً مرة واحدة ثم نعيد المحاولة
                val fresh = freshRoot(nowUp)
                if (fresh != null) {
                    root = fresh
                    nodes = fastNodes(root, pkg, rawKws)
                }
            }
            fastOk = nodes != null
            if (nodes.isNullOrEmpty()) return // لا مسار سريع متاح أو لا رسائل ظاهرة: نكتفي بالمسح الدوري
            hasInput = nowUp < inConvUntil || inConversation(root, false)
            if (!hasInput) return
            inConvUntil = nowUp + IN_CONV_TTL
            for (n in nodes) consider(n, width, rtl, now, cands, pkg, test)
        } else {
            // مرحلتان: نجمع النصوص ونعرف هل نحن بمحادثة (خانة كتابة)، ثم نفحص فقط إن كنا داخلها
            val textNodes = ArrayList<AccessibilityNodeInfo>()
            walk(root) { node ->
                // خانة الكتابة: نعرف منها إننا داخل محادثة، ولا نطابق نصها
                if (node.isEditable) {
                    hasInput = true
                    inputId = node.viewIdResourceName ?: inputId
                    return@walk
                }
                val t = node.text
                if (t != null && t.length >= 2) textNodes.add(node)
            }
            if (hasInput) {
                inConvUntil = nowUp + IN_CONV_TTL
                for (n in textNodes) consider(n, width, rtl, now, cands, pkg, test)
            }
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
            val until = seen[c.sig] ?: 0L
            if (until > now) {
                skip[c.qk] = minOf(until, now + SKIP_MS)
                continue
            }
            if (mine.contains(c.sig)) {
                skip[c.qk] = now + SKIP_MS
                continue
            }
            if (inBaseline && !(armed && armedMatches(c.text))) {
                seen[c.sig] = now + 15 * 60_000L // كانت موجودة قبلنا: لا نتفاعل
                skip[c.qk] = now + SKIP_MS
                continue
            }
            if (best == null || c.bounds.bottom > best.bounds.bottom) best = c
        }
        val target = best ?: return
        armedUntil = 0L
        react(
            target.bubble, target.bounds, target.sig,
            "رسالة فيها «${target.keyword}» — جارٍ التفاعل", false,
            target.list ?: target.bubble?.let { listAncestor(it)?.first }, t0, nowUp, target.why
        )
    }

    /** عقد نصوص الرسائل بطلب واحد (معرّف واتساب)، وإلا بحث بالكلمات إن كانت قليلة، وإلا null. */
    private fun fastNodes(
        root: AccessibilityNodeInfo,
        pkg: String,
        kws: List<String>
    ): List<AccessibilityNodeInfo>? {
        val byId = root.findAccessibilityNodeInfosByViewId("$pkg:id/message_text")
        if (!byId.isNullOrEmpty()) {
            lastIdHit = SystemClock.uptimeMillis()
            return byId
        }
        if (kws.size <= 6) {
            val out = ArrayList<AccessibilityNodeInfo>()
            for (k in kws) {
                val l = root.findAccessibilityNodeInfosByText(k)
                if (!l.isNullOrEmpty()) out.addAll(l)
            }
            return out
        }
        return null
    }

    /** موضع الفقاعة وحده: هل النص على جهة الرسائل الواردة؟ (باتجاه واتساب المتعلَّم إن وُجد وإلا اتجاه الجهاز) */
    private fun geoIncoming(tb: Rect, width: Int, rtl: Boolean): Boolean {
        val leftGap = tb.left
        val rightGap = width - tb.right
        val ol = outLeft
        return if (ol != null) {
            // الصادر على اليسار => الوارد على اليمين، والعكس
            if (ol) rightGap <= leftGap else leftGap <= rightGap
        } else {
            if (rtl) rightGap <= leftGap else leftGap <= rightGap
        }
    }

    /** نتعلّم أي جهة تقع عليها الرسائل الصادرة في واتساب (من رسائل مؤكَّدة بعلامة التسليم). */
    private fun learnSide(tb: Rect, width: Int, outgoing: Boolean) {
        val d = tb.left - (width - tb.right) // سالب: النص أقرب لليسار
        if (Math.abs(d) < width / 5) return    // رسالة عريضة: الموضع غير حاسم
        val onLeft = d < 0
        val ol = if (outgoing) onLeft else !onLeft
        if (outLeft != ol) {
            outLeft = ol
            Diag.log(this, "📐 تعلّمت اتجاه واتساب: الصادرة على ${if (ol) "اليسار" else "اليمين"}")
        }
    }

    /**
     * هل الرسالة واردة؟
     * (1) علامة التسليم (✓✓) لا تظهر إلا على رسائلك الصادرة، وهي مستقلة عن اتجاه اللغة؛
     *     فإذا تأكدنا أنها تفرّق فعلاً بين الصادر والوارد على هذا الجهاز نعتمدها.
     * (2) وإلا نعتمد موضع الفقاعة (باتجاه واتساب المتعلَّم إن وُجد وإلا اتجاه الجهاز).
     */
    private fun isIncoming(
        tb: Rect,
        width: Int,
        rtl: Boolean,
        row: Row,
        pkg: String,
        why: StringBuilder
    ): Boolean {
        val geo = geoIncoming(tb, width, rtl)
        val list = row.list
        val item = row.item
        if (list == null || item == null) {
            why.append("بالموضع فقط (لم أجد صف الرسالة)")
            return geo
        }
        try {
            val ticks = list.findAccessibilityNodeInfosByViewId("$pkg:id/status").orEmpty()
            var rowTick = false
            if (ticks.isNotEmpty()) {
                val ib = Rect()
                item.getBoundsInScreen(ib)
                val r = Rect()
                for (n in ticks) {
                    n.getBoundsInScreen(r)
                    if (r.centerY() >= ib.top && r.centerY() <= ib.bottom) {
                        rowTick = true
                        break
                    }
                }
                if (!tickOk) {
                    // العلامة تفرّق فعلاً إذا وُجدت رسائل نصية أكثر من العلامات (أي بعضها بلا علامة)
                    val texts = list.findAccessibilityNodeInfosByViewId("$pkg:id/message_text").orEmpty().size
                    if (texts > ticks.size) {
                        tickOk = true
                        Diag.log(this, "✔ علامات التسليم تفرّق بين الصادر والوارد على هذا الجهاز")
                    }
                }
            }
            if (tickOk) {
                learnSide(tb, width, rowTick)
                why.append(if (rowTick) "علامة تسليم ظاهرة ← صادرة" else "بلا علامة تسليم ← واردة")
                if ((!rowTick) != geo) why.append(" (خالفت الموضع فاعتمدت العلامة)")
                return !rowTick
            }
        } catch (_: Throwable) {
        }
        why.append("بالموضع فقط (لا دليل من علامات التسليم)")
        return geo
    }

    /** يفحص عقدة نصية: إن كانت رسالة واردة جديدة فيها كلمة مفتاحية يضيفها للمرشحين. */
    private fun consider(
        node: AccessibilityNodeInfo,
        width: Int,
        rtl: Boolean,
        now: Long,
        out: ArrayList<Cand>,
        pkg: String,
        test: Boolean
    ) {
        if (!node.isVisibleToUser) return
        val t = node.text?.toString()
        if (t.isNullOrEmpty() || t.length < 2) return
        val qk = node.hashCode().toString() + "|" + t.take(60)
        if ((skip[qk] ?: 0L) > now) return

        val nt = TextUtil.normalize(t)
        val kw = TextUtil.firstMatchPre(nt, preKws)
        if (kw == null) {
            skip[qk] = now + SKIP_MS
            return
        }

        val tb = Rect()
        node.getBoundsInScreen(tb)
        if (tb.width() <= 0 || tb.height() <= 0) return

        // صعود واحد: الفقاعة + القائمة + صف الرسالة (يُستعمل للتصنيف والوقت والضغط المطوّل)
        val row = climb(node)

        // رسالة صادرة منك؟
        val why = StringBuilder()
        val incoming = isIncoming(tb, width, rtl, row, pkg, why)
        if (!incoming) {
            if (!test) {
                skip[qk] = now + SKIP_MS
                diag("«$kw» رسالة صادرة منك فتجاهلتها — التفاعل على الواردة فقط (للتجربة من هاتف واحد فعّل وضع الاختبار)")
                val cls = "تجاهلت «$kw»: صادرة — $why"
                if (cls != lastCls) {
                    lastCls = cls
                    Diag.note(this, "cls", cls)
                }
                return
            }
            why.append(" | وضع الاختبار: تفاعلت رغم أنها صادرة")
        }

        // وقت الرسالة (اختياري): معرّف واتساب داخل الصف، وإلا مشي على الصف، وإلا صعود قديم
        var tm: Pair<Int, String>? = null
        val item = row.item
        if (item != null) {
            tm = rowTime(item, pkg) ?: findTime(item)
        } else {
            var cur: AccessibilityNodeInfo = node
            var depth = 0
            while (depth < 7 && tm == null) {
                val p = cur.parent ?: break
                tm = findTime(p)
                cur = p
                depth++
            }
        }
        if (tm != null && !TextUtil.isFresh(tm.first, 1)) {
            skip[qk] = now + SKIP_MS
            diag("«$kw» رسالة قديمة (${tm.second}) — أتفاعل فقط مع الجديدة")
            return
        }

        val sig = nt.take(80) + "|" + (tm?.second ?: "")
        out.add(Cand(sig, kw, t, row.bubble, row.list, tb, qk, why.toString()))
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
        t0: Long,
        h0: Long,
        why: String
    ) {
        busy = true
        busySince = SystemClock.uptimeMillis()
        curSig = sig
        curManual = manual
        curList = list
        curT0 = t0
        curH0 = h0
        curWhy = why
        pickRoot = null
        if (sig != null) seen[sig] = System.currentTimeMillis() + 15 * 60_000L
        lastDiag = "react"

        val tk = ++reactToken
        curTLong = SystemClock.uptimeMillis()
        val viaAction = bubble?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true
        curTLongEnd = SystemClock.uptimeMillis()
        curViaAction = viaAction
        Prefs.setStatus(this, label)
        if (viaAction) {
            pickEmoji(0, tk) // أول محاولة فوراً بدون انتظار دورة جديدة
        } else {
            // احتياط (أبطأ): ضغطة مطوّلة بإيماءة. نبدأ فحص الشريط لحظة انتهاء مهلة الضغط المطوّل
            val lp = ViewConfiguration.getLongPressTimeout().toLong()
            gesture(bounds.centerX().toFloat(), bounds.centerY().toFloat(), lp + 200L)
            main.postDelayed({ pickEmoji(0, tk) }, lp + 60L)
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
            // بحث سريع بطلب واحد؛ والمسح الكامل احتياط بطيء كل 10 محاولات بعد 60 محاولة
            val node = fastFind(emoji, attempt)
                ?: if (attempt >= 60 && attempt % 10 == 0) findEmojiSlow(emoji) else null
            if (node != null) {
                val tFound = SystemClock.uptimeMillis()
                if (clickNode(node)) {
                    val tDone = SystemClock.uptimeMillis()
                    lastOwnClickAt = tDone
                    val total = tDone - curT0
                    val lat = (curH0 - curT0).coerceAtLeast(0L) // وصول الحدث من النظام إلينا
                    val prep = curTLong - curH0                 // عملنا قبل الضغط المطوّل
                    val lc = curTLongEnd - curTLong             // مدة استدعاء الضغط المطوّل نفسه
                    val bar = tFound - curTLongEnd              // انتظار ظهور شريط الإيموجي
                    val clk = tDone - tFound
                    if (!curManual) lastReactAt = System.currentTimeMillis()
                    curSig?.let { remember(it) }
                    if (curManual) manualPauseUntil = SystemClock.uptimeMillis() + MANUAL_PAUSE
                    Prefs.incReact(this)
                    val parts = "حدث $lat + تحضير $prep + ضغط $lc + شريط $bar + نقر $clk"
                    Prefs.setStatus(
                        this,
                        (if (curManual) "تم التفاعل يدوياً $emoji" else "تم التفاعل $emoji") +
                            " — ${total}ms ($parts)"
                    )
                    Diag.note(
                        this, "react",
                        (if (curManual) "يدوي" else "تلقائي") + " ${total}ms = $parts | محاولات ${attempt + 1}" +
                            " | " + (if (curViaAction) "ضغط مطوّل بإجراء" else "ضغط مطوّل بإيماءة (بطيء)") +
                            " | $curWhy"
                    )
                    busy = false
                    requestScan(POLL)
                    return
                }
            }
            if (SystemClock.uptimeMillis() - busySince < PICK_LIMIT) {
                main.postDelayed({ pickEmoji(attempt + 1, tk) }, PICK_DELAY)
            } else {
                // فشل: نسمح بإعادة المحاولة على نفس الرسالة بعد 8 ثوانٍ فقط
                curSig?.let { seen[it] = System.currentTimeMillis() + 8_000L }
                if (curManual) manualPauseUntil = SystemClock.uptimeMillis() + MANUAL_PAUSE
                performGlobalAction(GLOBAL_ACTION_BACK)
                Prefs.setStatus(this, "لم أجد $emoji في شريط التفاعلات (استعمل إيموجي من الشريط السريع)")
                Diag.note(
                    this, "react",
                    (if (curManual) "يدوي" else "تلقائي") + " فشل: لم أجد $emoji بعد ${attempt + 1} محاولة | " +
                        (if (curViaAction) "ضغط مطوّل بإجراء" else "ضغط مطوّل بإيماءة (بطيء)") + " | $curWhy"
                )
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

    /**
     * بحث سريع: نحتفظ بجذر النافذة النشطة ونسأله عن الإيموجي نفسه (طلب واحد لكل محاولة)،
     * وكل 3 محاولات نفحص باقي النوافذ وباقي الاستعلامات (أسماء الإيموجي).
     */
    private fun fastFind(emoji: String, attempt: Int): AccessibilityNodeInfo? {
        val target = emoji.replace("️", "").trim()
        val names = englishNames[target].orEmpty()
        if (pickRoot == null || attempt == 1 || attempt % 40 == 0) {
            // المحاولة الأولى بالجذر المخزّن (رخيصة)، ثم جذر النافذة النشطة الحالي (قد تكون نافذة الشريط)
            val cached = fastRoot
            pickRoot = if (attempt == 0 && cached != null && SystemClock.uptimeMillis() - fastRootAt < ROOT_TTL) {
                cached
            } else {
                rootInActiveWindow
            }
        }
        pickRoot?.let { r -> firstHit(r, listOf(target), target, names)?.let { return it } }
        if (attempt % 3 == 2) {
            val queries = (listOf(emoji, target) + names).distinct()
            for (r in roots()) firstHit(r, queries, target, names)?.let { return it }
        }
        return null
    }

    private fun firstHit(
        r: AccessibilityNodeInfo,
        queries: List<String>,
        target: String,
        names: List<String>
    ): AccessibilityNodeInfo? {
        for (q in queries) {
            val list = r.findAccessibilityNodeInfosByText(q) ?: continue
            for (n in list) {
                // الفحص الرخيص أولاً ثم صعود الشجرة
                if (matchesEmoji(n, target, names) && !inMsgList(n)) return n
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
        if (now - lastPrune < 1000L) return
        lastPrune = now
        seen.entries.removeAll { it.value <= now }
        skip.entries.removeAll { it.value <= now }
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

    /** ملخص حالة الخدمة الداخلية للتقرير (يُستدعى من الواجهة على الخيط الرئيسي). */
    fun debugState(): String {
        val nowUp = SystemClock.uptimeMillis()
        val devRtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val ol = when (outLeft) {
            true -> "يسار"
            false -> "يمين"
            null -> "غير معروف بعد"
        }
        val idAge = if (lastIdHit == 0L) "لم تُرَ" else "قبل ${(nowUp - lastIdHit) / 1000} ث"
        return "اتجاه الجهاز=${if (devRtl) "RTL" else "LTR"} | الصادر عند واتساب=$ol | " +
            "علامة التسليم موثوقة=$tickOk | معرّفات الرسائل=$idAge | " +
            "مسار سريع=$fastOk | مشغول=$busy | وضع الاختبار=${Prefs.isTest(this)}"
    }

    private companion object {
        const val POLL = 300L            // مسح دوري احتياطي (الكشف السريع يعمل عند كل حدث)
        const val NET_GAP = 700L         // شبكة الأمان عندما يعمل المسار السريع: مسح كامل كل ~0.7ث
        const val ROOT_TTL = 4000L       // عمر الجذر المخزّن للمسار السريع
        const val ROOT_RETRY = 250L      // أقل فاصل لإعادة جلب الجذر عندما لا نجد رسائل
        const val IN_CONV_TTL = 1500L    // مدة تذكّر أننا داخل محادثة (يوفّر طلباً عند كل حدث)
        const val ID_OK_MS = 4000L       // المسار السريع «يعمل» إذا وجدنا نصوص الرسائل خلال هذه المدة
        const val OWN_CLICK_GUARD = 700L // نتجاهل حدث النقر الذي ولّدناه نحن
        const val MIN_GAP = 120L         // أقل فاصل بين مسحين كاملين (يخفف الحمل)
        const val FAST_GAP = 30L         // أقل فاصل بين كشفين سريعين
        const val FULL_AFTER_EVENT = 150L // المسح الكامل بعد الأحداث عندما يعمل الكشف السريع
        const val SKIP_MS = 20_000L      // مدة تذكّر العقد المتجاهَلة
        const val INPUT_WALK_GAP = 400L  // أقل فاصل بين مشيين كاملين للبحث عن خانة الكتابة
        const val PICK_DELAY = 1L        // فاصل فحص شريط الإيموجي (1ms)
        const val PICK_LIMIT = 3500L     // أقصى مدة للبحث عن الإيموجي بعد الضغط المطوّل
        const val BUSY_LIMIT = 5000L     // أقصى مدة لعملية تفاعل قبل إعادة الضبط
        const val MANUAL_PAUSE = 10_000L // توقف النقر اليدوي بعد كل تفاعل
        const val SESSION_GAP = 3000L    // غياب خانة الكتابة أكثر من هذا = دخول محادثة جديدة
        const val BASELINE_MS = 1500L    // مدة تجاهل الرسائل الموجودة عند فتح المحادثة
    }
}
