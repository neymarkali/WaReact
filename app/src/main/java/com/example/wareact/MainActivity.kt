package com.example.wareact

import android.Manifest
import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.wareact.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshUi()
            ui.postDelayed(this, 1000)
        }
    }
    private val emojis = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
    private lateinit var swManual: MaterialSwitch
    private lateinit var manualListener: CompoundButton.OnCheckedChangeListener
    private lateinit var tvPTitle: TextView
    private lateinit var tvPAcc: TextView
    private lateinit var tvPBat: TextView
    private lateinit var tvPNotif: TextView
    private lateinit var tvPAuto: TextView
    private lateinit var tvPExit: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.etCode.setText(Prefs.licenseCode(this))
        b.etKeywords.setText(Prefs.keywordsRaw(this))

        // اختيار الإيموجي بأزرار
        val saved = Prefs.emoji(this).replace("\uFE0F", "")
        emojis.forEach { e ->
            val chip = Chip(this).apply {
                text = e
                textSize = 22f
                tag = e
                id = View.generateViewId()
                isCheckable = true
                isCheckedIconVisible = false
                isChecked = e.replace("\uFE0F", "") == saved
            }
            b.chipEmoji.addView(chip)
        }
        if (b.chipEmoji.checkedChipId == View.NO_ID) {
            (b.chipEmoji.getChildAt(0) as Chip).isChecked = true
        }

        // فترة الخمول بشريط تمرير
        val cd = ((Prefs.cooldownSec(this) + 2) / 5 * 5).coerceIn(5, 300)
        b.sliderCooldown.value = cd.toFloat()
        b.tvCooldownVal.text = "$cd ثانية"
        b.sliderCooldown.addOnChangeListener { _, v, _ ->
            b.tvCooldownVal.text = "${v.toInt()} ثانية"
        }

        // مفتاح وضع النقر اليدوي + زر إضافة المربعات للبرداية (قبل زر المراقبة)
        val dp = resources.displayMetrics.density
        swManual = MaterialSwitch(this).apply {
            text = "وضع النقر اليدوي: اضغط على رسالة داخل المحادثة وتتفاعل فوراً"
            textSize = 14f
            setTextColor(getColor(R.color.text_primary))
            isChecked = Prefs.isManual(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (14 * dp).toInt() }
        }
        manualListener = CompoundButton.OnCheckedChangeListener { _, on ->
            if (on && !LicenseManager.isActive(this)) {
                setManualChecked(false)
                toast("فعّل كود الترخيص أولاً")
            } else if (on && !serviceEnabled()) {
                setManualChecked(false)
                toast("فعّل خدمة إمكانية الوصول أولاً")
            } else {
                save()
                Prefs.setManual(this, on)
                Prefs.setStatus(this, if (on) "وضع النقر اليدوي شغّال" else "وضع النقر اليدوي متوقف")
            }
        }
        swManual.setOnCheckedChangeListener(manualListener)

        val tileBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "➕ أضف أزرار للبرداية (الإعدادات السريعة)"
            cornerRadius = (26 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (52 * dp).toInt()
            ).apply { topMargin = (10 * dp).toInt() }
        }
        tileBtn.setOnClickListener { addTiles() }

        val holder = b.btnToggle.parent as LinearLayout
        holder.addView(swManual, holder.indexOfChild(b.btnToggle))
        holder.addView(tileBtn, holder.indexOfChild(b.btnToggle))

        val batBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "🔋 منع النظام من إيقاف التطبيق"
            cornerRadius = (26 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (52 * dp).toInt()
            ).apply { topMargin = (8 * dp).toInt() }
        }
        batBtn.setOnClickListener { openBattery() }
        holder.addView(batBtn, holder.indexOfChild(b.btnToggle))

        setupProtect()

        b.btnActivate.setOnClickListener { activate() }
        b.btnSave.setOnClickListener { save(); toast("تم الحفظ ✔") }
        b.btnAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        b.btnOverlay.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        b.btnToggle.setOnClickListener { toggle() }
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
        Keep.sync(this)
        val ex = Diag.exitLines(this, 1).firstOrNull()
        tvPExit.text = if (ex != null) "آخر إغلاق للتطبيق من النظام:\n$ex" else ""
        if (LicenseManager.isActive(this) && Build.VERSION.SDK_INT >= 33 && !Diag.notifOk(this) &&
            !getSharedPreferences("wareact_ui", MODE_PRIVATE).getBoolean("askedNotif", false)
        ) requestNotif()
        if (Prefs.licenseCode(this).isNotEmpty()) {
            lifecycleScope.launch {
                LicenseManager.refresh(this@MainActivity)
                refreshUi()
            }
        }
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    private fun selectedEmoji(): String {
        val id = b.chipEmoji.checkedChipId
        return if (id == View.NO_ID) "👍"
        else (b.chipEmoji.findViewById<Chip>(id).tag as? String) ?: "👍"
    }

    private fun activate() {
        val code = b.etCode.text?.toString().orEmpty()
        b.btnActivate.isEnabled = false
        b.btnActivate.text = "جارٍ التحقق…"
        b.tvActivateMsg.text = ""
        lifecycleScope.launch {
            val r = LicenseManager.activate(this@MainActivity, code)
            b.btnActivate.isEnabled = true
            b.btnActivate.text = "تفعيل"
            if (r == LicenseResult.OK) hideKeyboard()
            else b.tvActivateMsg.text = when (r) {
                LicenseResult.INVALID -> "الكود غير صحيح"
                LicenseResult.REVOKED -> "هذا الكود ملغى"
                LicenseResult.USED_ELSEWHERE -> "هذا الكود مستعمل على جهاز آخر"
                LicenseResult.EXPIRED -> "انتهت مدة هذا الكود"
                else -> "تعذّر التحقق: تأكد من الإنترنت وإعدادات فايربيس"
            }
            refreshUi()
        }
    }

    private fun save() {
        Prefs.saveSettings(
            this,
            b.etKeywords.text?.toString().orEmpty(),
            selectedEmoji(),
            b.sliderCooldown.value.toInt()
        )
    }

    private fun toggle() {
        if (Prefs.isRunning(this)) {
            Prefs.setRunning(this, false)
            Prefs.setStatus(this, "تم الإيقاف يدوياً")
            refreshUi()
            return
        }
        save()
        when {
            !LicenseManager.isActive(this) -> toast("فعّل كود الترخيص أولاً")
            !serviceEnabled() -> toast("فعّل خدمة إمكانية الوصول أولاً")
            Prefs.keywords(this).isEmpty() -> toast("أضف كلمة مفتاحية واحدة على الأقل")
            else -> {
                Prefs.setRunning(this, true)
                Prefs.setStatus(this, "بدأت المراقبة")
                refreshUi()
            }
        }
    }

    private fun serviceEnabled(): Boolean {
        val s = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return s.split(':').any { it.contains(packageName) && it.contains("ReactService") }
    }

    private fun refreshUi() {
        val active = LicenseManager.isActive(this)
        b.layoutActivate.visibility = if (active) View.GONE else View.VISIBLE
        b.layoutMain.visibility = if (active) View.VISIBLE else View.GONE
        if (!active) {
            if (Prefs.isRunning(this)) Prefs.setRunning(this, false)
            return
        }

        if (swManual.isChecked != Prefs.isManual(this)) setManualChecked(Prefs.isManual(this))

        // العدّ التنازلي
        val left = (Prefs.licenseExpires(this) - System.currentTimeMillis()).coerceAtLeast(0L)
        val s = left / 1000
        val warn = left < 3L * 86_400_000L
        val c = getColor(if (warn) R.color.danger else R.color.accent)
        b.tvDays.text = (s / 86400).toString()
        b.tvHours.text = String.format(Locale.US, "%02d", (s % 86400) / 3600)
        b.tvMins.text = String.format(Locale.US, "%02d", (s % 3600) / 60)
        b.tvSecs.text = String.format(Locale.US, "%02d", s % 60)
        listOf(b.tvDays, b.tvHours, b.tvMins, b.tvSecs).forEach { it.setTextColor(c) }

        // خدمة الوصول
        val svc = serviceEnabled()
        b.tvService.text =
            if (svc) "خدمة إمكانية الوصول: مفعّلة ✔" else "خدمة إمكانية الوصول: غير مفعّلة ✖"
        b.tvService.setTextColor(getColor(if (svc) R.color.accent else R.color.danger))

        // الحالة والزر
        val running = Prefs.isRunning(this)
        b.tvRunState.text = if (running) "● تعمل الآن" else "○ متوقفة"
        b.tvRunState.setTextColor(getColor(if (running) R.color.accent else R.color.text_secondary))
        b.tvLast.text = "${Prefs.status(this)}\nعدد التفاعلات: ${Prefs.reactCount(this)}"
        b.btnToggle.text = if (running) "أوقف المراقبة" else "ابدأ المراقبة"
        b.btnToggle.backgroundTintList =
            ColorStateList.valueOf(getColor(if (running) R.color.danger else R.color.accent))
        b.btnToggle.setTextColor(getColor(if (running) R.color.text_primary else R.color.on_accent))
        refreshProtect()
    }

    // ------------------------------------------------------------------ حماية من الإغلاق

    private fun setupProtect() {
        val holder = b.btnToggle.parent as LinearLayout
        val content = (holder.parent as View).parent as LinearLayout
        val card = layoutInflater.inflate(R.layout.view_protect, content, false)
        content.addView(card)

        tvPTitle = card.findViewById(R.id.tvPTitle)
        tvPAcc = card.findViewById(R.id.tvPAcc)
        tvPBat = card.findViewById(R.id.tvPBat)
        tvPNotif = card.findViewById(R.id.tvPNotif)
        tvPAuto = card.findViewById(R.id.tvPAuto)
        tvPExit = card.findViewById(R.id.tvPExit)

        val xiaomi = Diag.isXiaomi()
        card.findViewById<View>(R.id.rowPSaver).visibility = if (xiaomi) View.VISIBLE else View.GONE
        card.findViewById<View>(R.id.rowPPop).visibility = if (xiaomi) View.VISIBLE else View.GONE

        card.findViewById<View>(R.id.btnPAcc).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        card.findViewById<View>(R.id.btnPBat).setOnClickListener { requestBatteryExemption() }
        card.findViewById<View>(R.id.btnPNotif).setOnClickListener { requestNotif() }
        card.findViewById<View>(R.id.btnPAuto).setOnClickListener { openAutostart() }
        card.findViewById<View>(R.id.btnPSaver).setOnClickListener { openMiuiSaver() }
        card.findViewById<View>(R.id.btnPPop).setOnClickListener { openMiuiPopup() }
        card.findViewById<View>(R.id.btnPReport).setOnClickListener { sendReport() }
    }

    private fun mark(tv: TextView, ok: Boolean, label: String) {
        tv.text = (if (ok) "✅ " else "❌ ") + label
        tv.setTextColor(getColor(if (ok) R.color.accent else R.color.danger))
    }

    private fun refreshProtect() {
        val acc = serviceEnabled()
        val bat = Diag.batteryOk(this)
        val notif = Diag.notifOk(this)
        val auto: Boolean? = Diag.miuiAutostart(this)
        mark(tvPAcc, acc, "خدمة الوصول مفعّلة")
        mark(tvPBat, bat, "استثناء البطارية (بدون قيود)")
        mark(tvPNotif, notif, "الإشعارات (تُبقي الخدمة حيّة)")
        if (Diag.isXiaomi()) {
            if (auto == null) {
                tvPAuto.text = "❔ التشغيل التلقائي (شاومي) — تأكد أنه مفعّل"
                tvPAuto.setTextColor(getColor(R.color.text_secondary))
            } else {
                mark(tvPAuto, auto, "التشغيل التلقائي (شاومي)")
            }
        } else {
            tvPAuto.text = "⚙️ السماح بالعمل بالخلفية (إعدادات التطبيق)"
            tvPAuto.setTextColor(getColor(R.color.text_secondary))
        }
        val ok = acc && bat && notif && auto != false
        tvPTitle.text = if (ok) "🛡 الحماية كاملة ✔" else "🛡 الحماية ناقصة — أكمل البنود بالأحمر"
        tvPTitle.setTextColor(getColor(if (ok) R.color.accent else R.color.danger))
    }

    private fun requestNotif() {
        if (Diag.notifOk(this)) return
        val p = getSharedPreferences("wareact_ui", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && !p.getBoolean("askedNotif", false)) {
            p.edit().putBoolean("askedNotif", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
        } else {
            try {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            } catch (_: Throwable) {
                openAppDetails()
            }
        }
    }

    private fun requestBatteryExemption() {
        if (Diag.batteryOk(this)) {
            toast("مفعّل ✔")
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Throwable) {
            openBattery()
        }
    }

    private fun tryStart(vararg intents: Intent): Boolean {
        for (i in intents) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Throwable) {
            }
        }
        return false
    }

    private fun openAppDetails() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        } catch (_: Throwable) {
        }
    }

    private fun openAutostart() {
        val ok = tryStart(
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))
        )
        if (!ok) openAppDetails()
        toast("فعّل WaReact بالقائمة (التشغيل التلقائي / السماح بالعمل بالخلفية)")
    }

    private fun openMiuiSaver() {
        val ok = tryStart(
            Intent().setClassName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                .putExtra("package_name", packageName)
                .putExtra("package_label", getString(R.string.app_name)),
            Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").addCategory(Intent.CATEGORY_DEFAULT)
        )
        if (!ok) openAppDetails()
        toast("اختر «بدون قيود» لتطبيق WaReact")
    }

    private fun openMiuiPopup() {
        val ok = tryStart(
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                .putExtra("extra_pkgname", packageName),
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
                .putExtra("extra_pkgname", packageName)
        )
        if (!ok) openAppDetails()
        toast("فعّل: «عرض نوافذ منبثقة أثناء التشغيل بالخلفية» و«التشغيل التلقائي»")
    }

    private fun sendReport() {
        val r = Diag.report(this)
        try {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("WaReact", r))
        } catch (_: Throwable) {
        }
        try {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, r),
                    "إرسال التقرير"
                )
            )
        } catch (_: Throwable) {
        }
        toast("تم نسخ التقرير ✔ أرسله للدعم")
    }

    private fun openBattery() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
        toast("ابحث عن WaReact واختر «بدون قيود»، وفعّل التشغيل التلقائي إن وُجد")
    }

    private fun setManualChecked(v: Boolean) {
        swManual.setOnCheckedChangeListener(null)
        swManual.isChecked = v
        swManual.setOnCheckedChangeListener(manualListener)
    }

    private fun addTiles() {
        if (Build.VERSION.SDK_INT >= 33) {
            val sbm = getSystemService(StatusBarManager::class.java)
            val icon = Icon.createWithResource(this, R.drawable.ic_tile)
            sbm.requestAddTileService(
                ComponentName(this, ManualTile::class.java), "تفاعل يدوي", icon, mainExecutor
            ) { _ ->
                sbm.requestAddTileService(
                    ComponentName(this, AutoTile::class.java), "مراقبة تلقائية", icon, mainExecutor
                ) { _ -> }
            }
        } else {
            toast("اسحب شريط الإشعارات لتحت ← ✏️ تعديل ← اسحب «تفاعل يدوي» و«مراقبة تلقائية» للأعلى")
        }
    }

    private fun hideKeyboard() {
        currentFocus?.let {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
