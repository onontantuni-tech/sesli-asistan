package com.sesliasistan.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Gemini'nin cevabı: söylenecek söz + yapılacak işler. */
data class AssistantReply(
    val speech: String,
    val actions: List<JSONObject>,
    val expectReply: Boolean
)

class GeminiClient(private val prefs: Prefs) {

    // Sohbet geçmişi (son birkaç mesaj), Gemini önceki konuşmayı hatırlasın diye
    private val history = ArrayList<JSONObject>()

    fun clearHistory() = history.clear()

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("d MMMM yyyy EEEE, HH:mm", Locale("tr", "TR")).format(Date())
        val name = prefs.assistantName
        return """
Sen "$name" adında, Android telefonda çalışan Türkçe konuşan bir sesli asistansın (Siri gibi).
Kullanıcı seninle sesle konuşuyor; konuşma metni ses tanımadan geldiği için küçük yazım hataları olabilir, anlamı tahmin et.
Şu anki tarih ve saat: $now.

HER ZAMAN yalnızca şu biçimde geçerli bir JSON nesnesi döndür, başka hiçbir şey yazma:
{"speech": "kullanıcıya sesli söylenecek kısa cevap", "actions": [ ... ], "expect_reply": false}

- "speech": Kısa, doğal, samimi Türkçe. Sesli okunacağı için 1-3 cümle; madde işareti, emoji, markdown kullanma.
- "expect_reply": Kullanıcıya bir soru sorduysan ve cevabını bekliyorsan true, yoksa false.
- "actions": Telefonda yapılacak işler listesi. Sadece sohbet ise boş liste [] ver.

Kullanabileceğin eylemler:
{"type":"call","contact":"Kişi adı"}  veya  {"type":"call","number":"05xx..."}
{"type":"sms","contact":"Kişi adı","message":"mesaj metni"}  (veya "number")
{"type":"whatsapp","contact":"Kişi adı","message":"mesaj metni"}  (veya "number")
{"type":"open_app","app":"Uygulama adı"}            örn. WhatsApp, Instagram, Kamera, Ayarlar, Spotify
{"type":"youtube","query":"aranacak şey"}
{"type":"web_search","query":"aranacak şey"}
{"type":"open_url","url":"https://..."}
{"type":"navigate","destination":"gidilecek yer"}   yol tarifi
{"type":"map_search","query":"yakındaki eczane"}
{"type":"alarm","hour":7,"minute":30,"label":"etiket"}   24 saat biçimi
{"type":"timer","seconds":600,"label":"etiket"}
{"type":"flashlight","on":true}
{"type":"volume","direction":"up"}   up / down / mute / max
{"type":"camera"}
{"type":"settings","page":"wifi"}    wifi / bluetooth / display / sound / battery / location / general

Kurallar:
- Kişi adlarını rehberde kayıtlı olabileceği yalın hâliyle yaz: "annemi ara" -> "Annem", "Ali'ye mesaj at" -> "Ali", "Ayşe'yi ara" -> "Ayşe".
- "Hatırlat" isteklerinde: belirli saat verildiyse alarm, "10 dakika sonra" gibi süre verildiyse timer kullan; etikete hatırlatılacak şeyi yaz.
- "Sabah 7" -> 7:00, "akşam 7" -> 19:00. Saat belirtilmemiş ama "yarın sabah" denmişse makul bir saat seç ve speech içinde söyle.
- Güncel bilgi (hava durumu, döviz, maç sonucu, haber) gerektiren sorularda bildiğini kısaca söyle ve gerekirse web_search eylemi ekle.
- Eylem yapıyorsan speech kısa olsun, örn. "Annemi arıyorum." veya "Alarmı 7:30'a kurdum."
- Bilgi sorularına (tarih, matematik, tarif, tavsiye vb.) doğrudan kendin cevap ver.
""".trimIndent()
    }

    /** Ağ isteği yapar; arka planda (UI thread dışında) çağrılmalı. */
    fun ask(userText: String): AssistantReply {
        val key = prefs.apiKey
        if (key.isBlank()) throw IllegalStateException("API anahtarı yok")

        history.add(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", userText))))
        while (history.size > 16) history.removeAt(0)
        // Geçmiş "user" ile başlamalı
        while (history.isNotEmpty() && history[0].optString("role") != "user") history.removeAt(0)

        val body = JSONObject()
            .put("systemInstruction", JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put("contents", JSONArray(history))
            .put("generationConfig", JSONObject()
                .put("temperature", 0.6)
                .put("responseMimeType", "application/json"))

        // Seçili model yoğunsa sırayla yedek modelleri dene
        val models = LinkedHashSet<String>()
        models.add(prefs.model)
        models.addAll(FALLBACK_MODELS)
        val payload = body.toString().toByteArray(Charsets.UTF_8)
        var lastCode = 0
        var lastMsg = ""
        try {
            for (m in models) {
                for (attempt in 0 until 2) {
                    val (code, text) = post(m, key, payload)
                    if (code in 200..299) {
                        val reply = parse(text)
                        history.add(JSONObject().put("role", "model")
                            .put("parts", JSONArray().put(JSONObject().put("text", reply.speech))))
                        return reply
                    }
                    lastCode = code
                    lastMsg = try {
                        JSONObject(text).getJSONObject("error").optString("message")
                    } catch (e: Exception) { text.take(200) }
                    // Anahtar hatalıysa diğer modelleri denemenin anlamı yok
                    if (code == 400 || code == 401 || code == 403) throw RuntimeException(friendly(code, lastMsg))
                    // Yoğunluk / geçici hata ise kısa bekleyip bir kez daha dene
                    if (code == 429 || code >= 500) { Thread.sleep(1200L); continue }
                    break // 404 vb. -> sonraki model
                }
            }
            throw RuntimeException(friendly(lastCode, lastMsg))
        } catch (e: Exception) {
            if (history.isNotEmpty() && history[history.size - 1].optString("role") == "user") {
                history.removeAt(history.size - 1)
            }
            throw e
        }
    }

    private fun post(model: String, key: String, payload: ByteArray): Pair<Int, String> {
        val m = URLEncoder.encode(model, "UTF-8")
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", key)
        }
        try {
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Pair(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun friendly(code: Int, msg: String): String = when {
        code == 429 && msg.contains("quota", true) ->
            "Bugünkü ücretsiz kullanım hakkın dolmuş olabilir. Biraz sonra tekrar dene."
        code == 429 || code >= 500 ->
            "Google'ın sunucuları şu an çok yoğun. Birkaç saniye sonra tekrar dener misin?"
        code == 400 && msg.contains("API key", true) ->
            "Gemini API anahtarı geçersiz görünüyor. Ayarlar'dan kontrol eder misin?"
        code == 401 || code == 403 ->
            "Gemini API anahtarı kabul edilmedi. Ayarlar'dan kontrol eder misin?"
        else -> "Gemini hatası ($code): $msg"
    }

    companion object {
        private val FALLBACK_MODELS = listOf(
            "gemini-flash-latest",
            "gemini-flash-lite-latest",
            "gemini-3.5-flash-lite",
            "gemini-2.5-flash"
        )
    }

    private fun parse(raw: String): AssistantReply {
        val root = JSONObject(raw)
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            return AssistantReply("Buna cevap veremiyorum.", emptyList(), false)
        }
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (p.optBoolean("thought", false)) continue
                sb.append(p.optString("text", ""))
            }
        }
        var txt = sb.toString().trim()
        // Olur da ```json ... ``` içinde gelirse temizle
        if (txt.startsWith("```")) {
            txt = txt.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        }
        return try {
            val start = txt.indexOf('{')
            val end = txt.lastIndexOf('}')
            val obj = JSONObject(if (start >= 0 && end > start) txt.substring(start, end + 1) else txt)
            val acts = ArrayList<JSONObject>()
            val arr = obj.optJSONArray("actions")
            if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { acts.add(it) }
            AssistantReply(obj.optString("speech", ""), acts, obj.optBoolean("expect_reply", false))
        } catch (e: Exception) {
            // JSON değilse düz metin olarak kabul et
            AssistantReply(txt, emptyList(), false)
        }
    }
}
