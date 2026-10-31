package com.sesliasistan.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * note    : kullanıcıya söylenecek ek bilgi (örn. "Rehberde bulamadım"). null ise sorun yok.
 * pending : onay bekleyen işlem (örn. SMS). Kullanıcı "evet" derse confirm() ile çalıştırılır.
 */
data class ActionResult(val note: String? = null, val pending: JSONObject? = null)

class ActionExecutor(private val activity: Activity, private val prefs: Prefs) {

    private val ctx: Context get() = activity

    fun execute(a: JSONObject): ActionResult {
        return try {
            when (a.optString("type")) {
                "call" -> call(a)
                "sms" -> sms(a)
                "whatsapp" -> whatsapp(a)
                "open_app" -> openApp(a.optString("app"))
                "youtube" -> youtube(a.optString("query"))
                "web_search" -> webSearch(a.optString("query"))
                "open_url" -> openUrl(a.optString("url"))
                "navigate" -> navigate(a.optString("destination"))
                "map_search" -> mapSearch(a.optString("query"))
                "alarm" -> alarm(a)
                "timer" -> timer(a)
                "flashlight" -> flashlight(a.optBoolean("on", true))
                "volume" -> volume(a.optString("direction"))
                "camera" -> start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                "settings" -> settings(a.optString("page"))
                else -> ActionResult()
            }
        } catch (e: ActivityNotFoundException) {
            ActionResult("Bunu yapabilecek bir uygulama bulamadım.")
        } catch (e: SecurityException) {
            ActionResult("Bunun için izin vermen gerekiyor.")
        } catch (e: Exception) {
            ActionResult("Bir sorun çıktı: ${e.message}")
        }
    }

    // ---------------- Arama / Mesaj ----------------

    private fun resolveNumber(a: JSONObject): Pair<String, String>? {
        val num = a.optString("number").filter { it.isDigit() || it == '+' }
        if (num.length >= 3) return Pair(num, num)
        val name = a.optString("contact")
        if (name.isBlank()) return null
        return ContactFinder.find(ctx, name)
    }

    private fun contactMissing(a: JSONObject): ActionResult {
        if (!has(Manifest.permission.READ_CONTACTS)) {
            return ActionResult("Rehberine erişim izni vermediğin için kişiyi bulamıyorum.")
        }
        return ActionResult("Rehberde ${a.optString("contact")} adında birini bulamadım.")
    }

    private fun call(a: JSONObject): ActionResult {
        val (number, _) = resolveNumber(a) ?: return contactMissing(a)
        val uri = Uri.parse("tel:" + Uri.encode(number))
        return if (has(Manifest.permission.CALL_PHONE)) {
            start(Intent(Intent.ACTION_CALL, uri))
        } else {
            start(Intent(Intent.ACTION_DIAL, uri))
        }
    }

    private fun sms(a: JSONObject): ActionResult {
        val (number, display) = resolveNumber(a) ?: return contactMissing(a)
        val msg = a.optString("message")
        if (msg.isBlank()) {
            return start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")))
        }
        val resolved = JSONObject().put("type", "sms_send").put("number", number)
            .put("display", display).put("message", msg)
        if (prefs.confirmSms) {
            return ActionResult(
                "$display kişisine şu mesajı göndereyim mi: $msg",
                resolved
            )
        }
        return confirm(resolved)
    }

    /** Onaylanan bekleyen işlemi çalıştırır. */
    fun confirm(p: JSONObject): ActionResult {
        if (p.optString("type") != "sms_send") return ActionResult()
        val number = p.getString("number")
        val msg = p.getString("message")
        if (!has(Manifest.permission.SEND_SMS)) {
            // İzin yoksa mesaj uygulamasını hazır metinle aç
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number"))
            i.putExtra("sms_body", msg)
            start(i)
            return ActionResult()
        }
        val sm: SmsManager = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        val parts = sm.divideMessage(msg)
        sm.sendMultipartTextMessage(number, null, parts, null, null)
        return ActionResult("Mesaj gönderildi.")
    }

    private fun whatsapp(a: JSONObject): ActionResult {
        val (number, _) = resolveNumber(a) ?: return contactMissing(a)
        val intl = toInternational(number)
        val text = URLEncoder.encode(a.optString("message"), "UTF-8").replace("+", "%20")
        return start(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$intl?text=$text")))
    }

    /** 0532 123 45 67 -> 905321234567 (WhatsApp için) */
    private fun toInternational(n: String): String {
        var d = n.filter { it.isDigit() }
        if (n.trim().startsWith("+")) return d
        if (d.startsWith("00")) return d.substring(2)
        if (d.startsWith("0") && d.length == 11) d = "90" + d.substring(1)
        else if (d.length == 10 && d.startsWith("5")) d = "90$d"
        return d
    }

    // ---------------- Uygulamalar / Web ----------------

    private fun openApp(name: String): ActionResult {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val q = Text.norm(name)
        if (q.isBlank()) return ActionResult("Hangi uygulamayı açayım?")

        var bestPkg: String? = null
        var bestScore = 0.0
        for (ri in apps) {
            val label = Text.norm(ri.loadLabel(pm).toString())
            val score = Text.score(q, label)
            if (score > bestScore) {
                bestScore = score
                bestPkg = ri.activityInfo.packageName
            }
        }
        if (bestPkg == null || bestScore < 0.6) {
            return ActionResult("$name adında bir uygulama bulamadım.")
        }
        val intent = pm.getLaunchIntentForPackage(bestPkg)
            ?: return ActionResult("$name açılamadı.")
        return start(intent)
    }

    private fun youtube(q: String): ActionResult {
        val url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8")
        return start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun webSearch(q: String): ActionResult {
        val url = "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")
        return start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun openUrl(u: String): ActionResult {
        val url = if (u.startsWith("http")) u else "https://$u"
        return start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun navigate(dest: String): ActionResult {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest)))
        return try {
            start(i)
        } catch (e: ActivityNotFoundException) {
            start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest))))
        }
    }

    private fun mapSearch(q: String): ActionResult =
        start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))))

    // ---------------- Alarm / Zamanlayıcı ----------------

    private fun alarm(a: JSONObject): ActionResult {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour", 7))
            .putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute", 0))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val label = a.optString("label")
        if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return start(i)
    }

    private fun timer(a: JSONObject): ActionResult {
        val secs = a.optInt("seconds", 60).coerceIn(1, 86400)
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val label = a.optString("label")
        if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return start(i)
    }

    // ---------------- Donanım ----------------

    private fun flashlight(on: Boolean): ActionResult {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ActionResult("Bu telefonda fener bulunamadı.")
        cm.setTorchMode(id, on)
        return ActionResult()
    }

    private fun volume(dir: String): ActionResult {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val s = AudioManager.STREAM_MUSIC
        when (dir) {
            "up" -> repeat(3) { am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
            "down" -> repeat(3) { am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            "mute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "max" -> am.setStreamVolume(s, am.getStreamMaxVolume(s), AudioManager.FLAG_SHOW_UI)
        }
        return ActionResult()
    }

    private fun settings(page: String): ActionResult {
        val action = when (page) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return start(Intent(action))
    }

    // ---------------- Yardımcılar ----------------

    private fun start(i: Intent): ActionResult {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(i)
        return ActionResult()
    }

    private fun has(perm: String) =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
}

/** Rehberde isimle kişi arar; Türkçe ekleri ve küçük hataları tolere eder. */
object ContactFinder {
    fun find(ctx: Context, name: String): Pair<String, String>? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val q = Text.norm(name)
        if (q.isBlank()) return null

        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY
        )
        var best: Pair<String, String>? = null
        var bestScore = 0.0
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val display = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                val primaryBonus = if (c.getInt(2) > 0) 0.01 else 0.0
                val score = Text.score(q, Text.norm(display)) + primaryBonus
                if (score > bestScore) {
                    bestScore = score
                    best = Pair(number, display)
                }
            }
        }
        return if (bestScore >= 0.62) best else null
    }
}

object Text {
    private val tr = Locale("tr", "TR")

    fun norm(s: String): String = s.lowercase(tr)
        .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
        .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** 0..1 arası benzerlik puanı. */
    fun score(q: String, target: String): Double {
        if (target.isEmpty()) return 0.0
        if (q == target) return 1.0
        val words = target.split(' ')
        if (words.any { it == q }) return 0.95
        if (target.startsWith(q)) return 0.9
        if (words.any { it.startsWith(q) && q.length >= 3 }) return 0.85
        if (q.contains(target) && target.length >= 3) return 0.8
        if (target.contains(q) && q.length >= 3) return 0.75
        // Türkçe ek almış olabilir: "annemi" -> "annem"
        var best = sim(q, target)
        for (w in words) best = maxOf(best, sim(q, w))
        return best * 0.9
    }

    private fun sim(a: String, b: String): Double {
        val m = maxOf(a.length, b.length)
        if (m == 0) return 0.0
        return 1.0 - lev(a, b).toDouble() / m
    }

    private fun lev(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(
                    dp[j] + 1, dp[j - 1] + 1,
                    prev + if (a[i - 1] == b[j - 1]) 0 else 1
                )
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
