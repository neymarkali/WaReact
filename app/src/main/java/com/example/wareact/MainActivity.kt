package com.example.wareact

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.wareact.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshUi()
            ui.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.etKeywords.setText(Prefs.keywordsRaw(this))
        b.etEmoji.setText(Prefs.emoji(this))
        b.etCooldown.setText(Prefs.cooldownSec(this).toString())

        b.btnActivate.setOnClickListener { activate() }
        b.btnSave.setOnClickListener { save(); toast("تم الحفظ") }
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

    private fun activate() {
        val code = b.etCode.text?.toString().orEmpty()
        b.btnActivate.isEnabled = false
        lifecycleScope.launch {
            val r = LicenseManager.activate(this@MainActivity, code)
            b.btnActivate.isEnabled = true
            toast(
                when (r) {
                    LicenseResult.OK -> "تم تفعيل الترخيص ✔"
                    LicenseResult.INVALID -> "الكود غير صحيح"
                    LicenseResult.REVOKED -> "هذا الكود ملغى"
                    LicenseResult.USED_ELSEWHERE -> "هذا الكود مستعمل على جهاز آخر"
                    LicenseResult.EXPIRED -> "انتهت مدة هذا الكود"
                    LicenseResult.NETWORK -> "تعذّر التحقق: تأكد من الإنترنت وإعدادات فايربيس"
                }
            )
            refreshUi()
        }
    }

    private fun save() {
        val emoji = b.etEmoji.text?.toString()?.trim().orEmpty().ifEmpty { "👍" }
        val cd = (b.etCooldown.text?.toString()?.toIntOrNull() ?: 60).coerceIn(5, 3600)
        Prefs.saveSettings(this, b.etKeywords.text?.toString().orEmpty(), emoji, cd)
        b.etEmoji.setText(emoji)
        b.etCooldown.setText(cd.toString())
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
        b.tvLicense.text = when {
            LicenseManager.isActive(this) -> "الترخيص: مفعّل — المتبقي ${LicenseManager.daysLeft(this)} يوم"
            Prefs.licenseCode(this).isNotEmpty() -> "الترخيص: منتهي أو يحتاج اتصالاً بالإنترنت للتحقق"
            else -> "الترخيص: غير مفعّل"
        }
        b.tvService.text =
            if (serviceEnabled()) "خدمة إمكانية الوصول: مفعّلة ✔" else "خدمة إمكانية الوصول: غير مفعّلة"
        val running = Prefs.isRunning(this)
        b.tvRunState.text = if (running) "الحالة: تعمل ●" else "الحالة: متوقفة"
        b.tvLast.text = "${Prefs.status(this)}\nعدد التفاعلات: ${Prefs.reactCount(this)}"
        b.btnToggle.text = if (running) "أوقف المراقبة" else "ابدأ المراقبة"
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
