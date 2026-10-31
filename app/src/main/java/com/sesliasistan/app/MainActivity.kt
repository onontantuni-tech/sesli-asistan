package com.sesliasistan.app

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var gemini: GeminiClient
    private lateinit var executor: ActionExecutor

    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var pulse: View

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listening = false
    private var busy = false
    private var listenAfterSpeech = false
    private var pulseAnim: AnimatorSet? = null

    private var pendingConfirm: JSONObject? = null
    private val ui = Handler(Looper.getMainLooper())
    private var skipAutoListen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        gemini = GeminiClient(prefs)
        executor = ActionExecutor(this, prefs)

        chat = findViewById(R.id.chat)
        scroll = findViewById(R.id.scroll)
        status = findViewById(R.id.status)
        input = findViewById(R.id.input)
        pulse = findViewById(R.id.pulse)

        findViewById<ImageButton>(R.id.btnMic).setOnClickListener {
            if (listening) stopListening() else startListening()
        }
        findViewById<TextView>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.btnClear).setOnClickListener {
            chat.removeAllViews()
            gemini.clearHistory()
            pendingConfirm = null
            status.text = "Dokun ve konuş"
        }
        findViewById<TextView>(R.id.btnSend).setOnClickListener { sendTyped() }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
        }

        initTts()
        askPermissions()

        if (prefs.apiKey.isBlank()) {
            addBubble("Merhaba! Ben ${prefs.assistantName}. Başlamak için sağ üstteki ⚙ Ayarlar'dan ücretsiz Gemini API anahtarını girmen gerekiyor.", false)
        } else {
            addBubble("Merhaba! Ben ${prefs.assistantName}. Ne yapmamı istersin?", false)
        }
        processIntent(intent)
    }

    /** "Harun" uyandırma kelimesiyle açıldıysak: komutu çalıştır ya da dinlemeye başla. */
    private fun processIntent(i: Intent?) {
        if (i == null || !i.getBooleanExtra(EXTRA_WAKE, false)) return
        i.removeExtra(EXTRA_WAKE)
        skipAutoListen = true
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        val cmd = i.getStringExtra(EXTRA_COMMAND)
        i.removeExtra(EXTRA_COMMAND)
        ui.postDelayed({
            if (!cmd.isNullOrBlank()) handleUserText(cmd) else reply("Efendim?", true)
        }, 300)
    }

    override fun onResume() {
        super.onResume()
        // Uygulama ekrandayken arka plan dinleyicisi mikrofonu bıraksın
        WakeService.paused = true
        WakeService.instance?.pause()
        if (prefs.wakeWord && has(Manifest.permission.RECORD_AUDIO) && WakeService.instance == null) {
            try { WakeService.start(this) } catch (e: Exception) { }
        }
        if (skipAutoListen) {
            skipAutoListen = false
            return
        }
        if (prefs.autoListen && prefs.apiKey.isNotBlank() && !busy && !listening &&
            has(Manifest.permission.RECORD_AUDIO)) {
            ui.postDelayed({ startListening() }, 400)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_WAKE, false)) {
            processIntent(intent)
        } else if (!listening && !busy) {
            // Asistan tuşu ile tekrar çağrıldığında dinlemeye başla
            skipAutoListen = true
            ui.postDelayed({ startListening() }, 300)
        }
    }

    override fun onPause() {
        super.onPause()
        if (listening) stopListening()
        tts?.stop()
        listenAfterSpeech = false
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(false)
        if (prefs.wakeWord) WakeService.instance?.resume()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------------- İzinler ----------------

    private fun askPermissions() {
        val list = arrayListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        val wanted = list.filter { !has(it) }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (prefs.autoListen && prefs.apiKey.isNotBlank() && has(Manifest.permission.RECORD_AUDIO)) {
            startListening()
        }
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // ---------------- Ses tanıma ----------------

    private fun startListening() {
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            askPermissions()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "Telefonunda ses tanıma bulunamadı (Google uygulaması gerekli)."
            return
        }
        tts?.stop()
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(listener)
            }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        listening = true
        status.text = "Dinliyorum…"
        startPulse()
        recognizer?.startListening(intent)
    }

    private fun stopListening() {
        listening = false
        recognizer?.cancel()
        stopPulse()
        status.text = "Dokun ve konuş"
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            val s = 1f + (rmsdB.coerceIn(0f, 10f) / 25f)
            findViewById<View>(R.id.btnMic).scaleX = s
            findViewById<View>(R.id.btnMic).scaleY = s
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            status.text = "Düşünüyorum…"
        }
        override fun onError(error: Int) {
            listening = false
            stopPulse()
            status.text = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    "Seni duyamadım, tekrar dokun."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "İnternet bağlantısı sorunu."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon izni gerekli."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Ses tanıma meşgul, tekrar dene."
                else -> "Dokun ve konuş"
            }
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                recognizer?.destroy()
                recognizer = null
            }
        }
        override fun onResults(results: Bundle?) {
            listening = false
            stopPulse()
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isEmpty()) {
                status.text = "Seni duyamadım, tekrar dokun."
            } else {
                handleUserText(text)
            }
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) status.text = t
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        handleUserText(t)
    }

    // ---------------- Ana akış ----------------

    private fun handleUserText(text: String) {
        addBubble(text, true)

        // Onay bekleyen bir SMS var mı?
        pendingConfirm?.let { p ->
            pendingConfirm = null
            val t = Text.norm(text)
            val yes = listOf("evet", "gonder", "tamam", "olur", "aynen", "tabi").any { t.contains(it) }
            if (yes) {
                val r = executor.confirm(p)
                reply(r.note ?: "Tamamdır.", false)
            } else {
                reply("Peki, göndermiyorum.", false)
            }
            return
        }

        // Kısa yerel komutlar (internet gerekmez)
        val n = Text.norm(text)
        if (n == "iptal" || n == "vazgec" || n == "sus" || n == "dur") {
            tts?.stop()
            status.text = "Dokun ve konuş"
            return
        }

        if (prefs.apiKey.isBlank()) {
            reply("Önce Ayarlar'dan Gemini API anahtarını girmen gerekiyor.", false)
            return
        }

        if (busy) {
            status.text = "Bir saniye, önceki isteğini bitiriyorum…"
            return
        }
        busy = true
        status.text = "Düşünüyorum…"
        Thread {
            try {
                val r = gemini.ask(text)
                ui.post { onGeminiReply(r) }
            } catch (e: Exception) {
                ui.post {
                    busy = false
                    val msg = if (e is java.net.UnknownHostException) "İnternet bağlantısı yok gibi görünüyor."
                    else e.message ?: "Bir hata oluştu."
                    reply(msg, false)
                }
            }
        }.start()
    }

    private fun onGeminiReply(r: AssistantReply) {
        busy = false
        val notes = ArrayList<String>()
        var askConfirm = false
        for (a in r.actions) {
            val res = executor.execute(a)
            res.note?.let { notes.add(it) }
            if (res.pending != null) {
                pendingConfirm = res.pending
                askConfirm = true
            }
        }
        val speech = buildString {
            if (r.speech.isNotBlank() && !askConfirm) append(r.speech)
            for (nt in notes) {
                if (isNotEmpty()) append(" ")
                append(nt)
            }
        }.ifBlank { "Tamam." }
        reply(speech, r.expectReply || askConfirm)
    }

    private fun reply(text: String, expectReply: Boolean) {
        addBubble(text, false)
        status.text = if (expectReply) "Cevabını bekliyorum…" else "Dokun ve konuş"
        listenAfterSpeech = expectReply
        if (prefs.speak && ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cevap")
        } else if (expectReply) {
            ui.postDelayed({ startListening() }, 300)
        }
    }

    // ---------------- Konuşma (TTS) ----------------

    private fun initTts() {
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale("tr", "TR"))
                tts?.setSpeechRate(1.05f)
                ttsReady = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (listenAfterSpeech) {
                            listenAfterSpeech = false
                            ui.post { startListening() }
                        }
                    }
                })
            }
        }
    }

    // ---------------- Arayüz ----------------

    private fun addBubble(text: String, user: Boolean) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(0xFFF1F3F9.toInt())
            setBackgroundResource(if (user) R.drawable.bubble_user else R.drawable.bubble_bot)
            val p = dp(14)
            setPadding(p, dp(10), p, dp(10))
            setTextIsSelectable(true)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
            gravity = if (user) Gravity.END else Gravity.START
            if (user) leftMargin = dp(48) else rightMargin = dp(48)
        }
        chat.addView(tv, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun startPulse() {
        pulse.alpha = 0.5f
        val sx = ObjectAnimator.ofFloat(pulse, View.SCALE_X, 1f, 1.6f)
        val sy = ObjectAnimator.ofFloat(pulse, View.SCALE_Y, 1f, 1.6f)
        val al = ObjectAnimator.ofFloat(pulse, View.ALPHA, 0.5f, 0f)
        for (a in listOf(sx, sy, al)) {
            a.repeatCount = ValueAnimator.INFINITE
            a.duration = 1100
        }
        pulseAnim = AnimatorSet().apply { playTogether(sx, sy, al); start() }
    }

    private fun stopPulse() {
        pulseAnim?.cancel()
        pulseAnim = null
        pulse.alpha = 0f
        val mic = findViewById<View>(R.id.btnMic)
        mic.scaleX = 1f
        mic.scaleY = 1f
    }

    companion object {
        const val EXTRA_WAKE = "wake"
        const val EXTRA_COMMAND = "command"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
