package com.example.wareact

import java.util.Calendar

object TextUtil {
    private val marks = Regex("[\\u064B-\\u065F\\u0670\\u0640]")
    private val spaces = Regex("[\\s\\u200E\\u200F\\u202F\\u00A0]+")
    private val timeRe = Regex(
        "^(\\d{1,2})[:.](\\d{2})(?:\\s?(a\\.?m\\.?|p\\.?m\\.?|ص|م))?$",
        RegexOption.IGNORE_CASE
    )

    fun latinDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(
                when (ch) {
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    /** توحيد النص العربي (تشكيل، ألف، ياء، تاء مربوطة) لمطابقة أدق للمناطق. */
    fun normalize(s: String): String {
        var t = latinDigits(s.lowercase())
        t = marks.replace(t, "")
        t = t.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ى', 'ي').replace('ة', 'ه')
        return spaces.replace(t, " ").trim()
    }

    fun firstMatch(text: String, keywords: List<String>): String? {
        val t = normalize(text)
        return keywords.firstOrNull { k ->
            val nk = normalize(k)
            nk.isNotEmpty() && t.contains(nk)
        }
    }

    /** يحوّل نص وقت الرسالة (3:55 PM / 15:55 / ٣:٥٥ م) إلى دقائق من بداية اليوم. */
    fun parseMinutes(text: String): Int? {
        val t = spaces.replace(latinDigits(text), " ").trim()
        val m = timeRe.find(t) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h > 23 || min > 59) return null
        val ap = m.groupValues[3].lowercase()
        if (ap.isNotEmpty()) {
            val pm = ap.startsWith("p") || ap == "م"
            h = h % 12 + if (pm) 12 else 0
        }
        return h * 60 + min
    }

    /** الرسالة "حديثة" إذا كان وقتها ضمن آخر دقيقتين (حماية من التفاعل مع الرسائل القديمة). */
    fun isFresh(msgMinutes: Int, slack: Int = 2): Boolean {
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val diff = (now - msgMinutes + 1440) % 1440
        return diff <= slack || diff >= 1439
    }
}
