package com.example.wareact

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.wareact.databinding.ActivityMainBinding
import com.google.android.material.chip.Chip
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
