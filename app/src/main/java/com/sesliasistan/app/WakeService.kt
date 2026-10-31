package com.sesliasistan.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Arka planda sürekli dinler; uyandırma kelimesi ("Harun") duyulunca asistanı açar.
 * "Harun annemi ara" gibi tek cümlede söylenen komutu da direkt iletir.
 */
class WakeService : Service() {

    private var recognizer: SpeechRecognizer? = null
    private val ui = Handler(Looper.getMainLooper())
    private var running = false
    private var active = false
    private lateinit var prefs: Prefs

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        instance = this
        startAsForeground()
        running = true
        scheduleListen(500)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        instance = null
        ui.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    /** Uygulama ekrandayken mikrofonu bırak. */
    fun pause() {
        paused = true
        active = false
        ui.removeCallbacksAndMessages(null)
        recognizer?.cancel()
    }

    fun resume() {
        paused = false
        scheduleListen(800)
    }

    private fun scheduleListen(delay: Long) {
        ui.removeCallbacksAndMessages(null)
        ui.postDelayed({ listen() }, delay)
    }

    private fun listen() {
        if (!running || paused || active) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        active = true
        recognizer?.startListening(i)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            active = false
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                recognizer?.destroy()
                recognizer = null
                scheduleListen(1500)
            } else {
                scheduleListen(250)
            }
        }

        override fun onResults(results: Bundle?) {
            active = false
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
            val wake = Text.norm(prefs.assistantName)
            for (heard in list) {
                val n = Text.norm(heard)
                val words = n.split(' ')
                val idx = words.indexOfFirst { isWakeWord(it, wake) }
                if (idx >= 0) {
                    // Uyandırma kelimesinden sonrası komut olarak kabul edilir
                    val origWords = heard.trim().split(Regex("\\s+"))
                    val command = if (origWords.size == words.size)
                        origWords.drop(idx + 1).joinToString(" ").trim()
                    else words.drop(idx + 1).joinToString(" ").trim()
                    wakeUp(command)
                    return
                }
            }
            scheduleListen(200)
        }
    }

    /** "harun", "harunum", "harun'cuğum", hafif yanlış duyulmuş hâller ("haron") */
    private fun isWakeWord(w: String, wake: String): Boolean {
        if (w == wake || w.startsWith(wake)) return true
        if (wake.length >= 4 && w.length in (wake.length - 1)..(wake.length + 1)) {
            var diff = 0
            for (i in 0 until minOf(w.length, wake.length)) if (w[i] != wake[i]) diff++
            diff += kotlin.math.abs(w.length - wake.length)
            return diff <= 1
        }
        return false
    }

    private fun wakeUp(command: String) {
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_WAKE, true)
            if (command.isNotBlank()) putExtra(MainActivity.EXTRA_COMMAND, command)
        }
        pause() // MainActivity açılınca mikrofonu o kullanacak
        val canStart = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(this)
        if (canStart) {
            try {
                startActivity(i)
                return
            } catch (e: Exception) { /* bildirim yöntemine geç */ }
        }
        // Arka plandan açma izni yoksa: dokunulacak bildirim göster
        val pi = PendingIntent.getActivity(this, 2, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = builder()
            .setContentTitle("${prefs.assistantName} seni dinlemeye hazır")
            .setContentText("Dokun ve konuş")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(2, n)
        resume()
    }

    private fun builder(): Notification.Builder {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Uyandırma kelimesi", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_mic)
    }

    private fun startAsForeground() {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val n = builder()
            .setContentTitle("${prefs.assistantName} dinliyor")
            .setContentText("“${prefs.assistantName}” deyince açılırım")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    companion object {
        private const val CHANNEL = "wake"
        @Volatile var instance: WakeService? = null
        @Volatile var paused = false

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, WakeService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WakeService::class.java))
        }
    }
}
