package com.example.wareact

import android.content.Context
import android.provider.Settings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.Transaction
import kotlinx.coroutines.tasks.await

enum class LicenseResult { OK, INVALID, REVOKED, USED_ELSEWHERE, EXPIRED, NETWORK }

/**
 * ترخيص بكود صالح 30 يوماً من لحظة أول تفعيل (بتوقيت خادم فايربيس).
 * الكود يُربط بجهاز واحد (ANDROID_ID) عند أول تفعيل.
 */
object LicenseManager {
    private const val COLLECTION = "licenses"
    private const val DAY_MS = 86_400_000L
    private const val OFFLINE_GRACE_MS = 72L * 3_600_000L // أقصى مدة عمل بدون تحقق من الخادم
    private const val CLOCK_TOLERANCE_MS = 6L * 3_600_000L // سماح بتصحيح ساعة الجهاز من الشبكة للخلف

    fun deviceId(c: Context): String =
        Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    /** فحص محلي سريع (تستعمله الخدمة عند كل حدث). */
    fun isActive(c: Context): Boolean {
        if (Prefs.licenseCode(c).isEmpty()) return false
        val exp = Prefs.licenseExpires(c)
        val checked = Prefs.licenseCheckedAt(c)
        val now = System.currentTimeMillis()
        // الشرط الثاني يكشف إرجاع ساعة الجهاز للخلف، والثالث يفرض إعادة التحقق أونلاين
        return exp > 0 && now >= checked - CLOCK_TOLERANCE_MS && now < exp && now - checked < OFFLINE_GRACE_MS
    }

    fun daysLeft(c: Context): Long {
        val left = Prefs.licenseExpires(c) - System.currentTimeMillis()
        return if (left <= 0) 0 else (left + DAY_MS - 1) / DAY_MS
    }

    fun normalizeCode(raw: String): String = raw.trim().uppercase()

    private suspend fun ensureAuth() {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) auth.signInAnonymously().await()
    }

    suspend fun activate(c: Context, rawCode: String): LicenseResult {
        val code = normalizeCode(rawCode)
        if (code.isEmpty() || code.contains('/')) return LicenseResult.INVALID
        return try {
            ensureAuth()
            val db = FirebaseFirestore.getInstance()
            val ref = db.collection(COLLECTION).document(code)
            val me = deviceId(c)

            val outcome = db.runTransaction(Transaction.Function<String> { tx ->
                val snap = tx.get(ref)
                val dev = snap.getString("deviceId")
                when {
                    !snap.exists() -> "INVALID"
                    snap.getBoolean("active") != true -> "REVOKED"
                    dev.isNullOrEmpty() -> {
                        tx.update(
                            ref, mapOf(
                                "deviceId" to me,
                                "activatedAt" to FieldValue.serverTimestamp(),
                                "lastSeen" to FieldValue.serverTimestamp()
                            )
                        )
                        "OK"
                    }
                    dev == me -> "OK"
                    else -> "USED"
                }
            }).await()

            when (outcome) {
                "INVALID" -> LicenseResult.INVALID
                "REVOKED" -> LicenseResult.REVOKED
                "USED" -> LicenseResult.USED_ELSEWHERE
                else -> check(c, code)
            }
        } catch (e: Exception) {
            LicenseResult.NETWORK
        }
    }

    /** إعادة تحقق دورية من الخادم (إلغاء الكود، انتهاء المدة، تغيّر الجهاز). */
    suspend fun refresh(c: Context): LicenseResult {
        val code = Prefs.licenseCode(c)
        if (code.isEmpty()) return LicenseResult.INVALID
        return try {
            ensureAuth()
            check(c, code)
        } catch (e: Exception) {
            LicenseResult.NETWORK
        }
    }

    private suspend fun check(c: Context, code: String): LicenseResult {
        val ref = FirebaseFirestore.getInstance().collection(COLLECTION).document(code)
        val me = deviceId(c)

        val first = ref.get(Source.SERVER).await()
        if (!first.exists()) { Prefs.clearLicense(c); return LicenseResult.INVALID }
        if (first.getBoolean("active") != true) { Prefs.clearLicense(c); return LicenseResult.REVOKED }
        if (first.getString("deviceId") != me) { Prefs.clearLicense(c); return LicenseResult.USED_ELSEWHERE }

        // نبضة تكتب وقت الخادم، نقرأه ونستعمله كـ "الآن" الموثوق
        ref.update("lastSeen", FieldValue.serverTimestamp()).await()
        val snap = ref.get(Source.SERVER).await()

        val activated = snap.getTimestamp("activatedAt")?.toDate()?.time ?: return LicenseResult.NETWORK
        val serverNow = snap.getTimestamp("lastSeen")?.toDate()?.time ?: return LicenseResult.NETWORK
        val days = snap.getLong("durationDays") ?: 30L
        val expires = activated + days * DAY_MS

        Prefs.saveLicense(c, code, expires, System.currentTimeMillis())
        return if (serverNow >= expires) LicenseResult.EXPIRED else LicenseResult.OK
    }
}
