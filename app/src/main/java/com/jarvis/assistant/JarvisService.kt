package com.jarvis.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.Locale

/**
 * Servicio en primer plano: mantiene vivo el proceso con el micrófono,
 * escucha con el reconocimiento de voz de Android y habla con el motor de voz del teléfono.
 * La lógica (palabra "Jarvis", IA) sigue en la página.
 */
class JarvisService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "com.jarvis.assistant.START"
        const val ACTION_STOP = "com.jarvis.assistant.STOP"
        private const val CHANNEL = "jarvis_listen"
        private const val NOTIF_ID = 1
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFailed = false
    private val pendingSpeak = ArrayList<Pair<String, String>>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false
    private val mutedByUs = ArrayList<Int>()
    private var overlay: JarvisOverlay? = null
    private var lastState = "idle"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            JarvisHub.js("window.__nativeStopped&&window.__nativeStopped()")
            shutdown()
            return START_NOT_STICKY
        }
        if (!ensureForeground()) {
            JarvisHub.js("window.__nativeStopped&&window.__nativeStopped()")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun ensureForeground(): Boolean {
        if (foreground) return true
        return try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Jarvis escuchando", NotificationManager.IMPORTANCE_LOW)
            )
            val open = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val stop = PendingIntent.getService(
                this, 1,
                Intent(this, JarvisService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Jarvis activo")
                .setContentText("Di “Jarvis” y tu pregunta")
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(open)
                .addAction(0, "Detener", stop)
                .build()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            foreground = true
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:listen").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
            JarvisHub.serviceReady(this)
            refreshOverlay()
            true
        } catch (e: Exception) {
            false
        }
    }

    /* ---------- burbuja flotante ---------- */

    /** Muestra la burbuja si está permitida y la app no está a la vista; si no, la esconde. */
    fun refreshOverlay() {
        val wanted = foreground &&
            !JarvisHub.appVisible &&
            getSharedPreferences("jarvis", 0).getBoolean("overlay", true)
        val o = overlay ?: JarvisOverlay(this).also { overlay = it }
        if (wanted && o.canShow()) {
            o.show()
            o.setState(lastState, null)
        } else {
            o.hide()
        }
    }

    fun overlaySet(state: String, text: String) {
        lastState = state
        overlay?.setState(state, text)
    }

    /* ---------- reconocimiento de voz ---------- */

    fun recStart(lang: String) {
        destroyRecognizer()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            JarvisHub.rec("error", JSONObject().put("error", "service-not-allowed"))
            JarvisHub.rec("end")
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (recognizer === r) JarvisHub.rec("start")
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                if (recognizer !== r) return
                val text = firstText(partialResults)
                if (text.isNotEmpty()) {
                    JarvisHub.rec("result", JSONObject().put("text", text).put("final", false))
                }
            }

            override fun onResults(results: Bundle?) {
                if (recognizer !== r) return
                recognizer = null
                val text = firstText(results)
                if (text.isNotEmpty()) {
                    JarvisHub.rec("result", JSONObject().put("text", text).put("final", true))
                }
                JarvisHub.rec("end")
                main.post { try { r.destroy() } catch (_: Exception) {} }
            }

            override fun onError(error: Int) {
                if (recognizer !== r) return
                recognizer = null
                val code = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
                    SpeechRecognizer.ERROR_AUDIO -> "audio-capture"
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language-not-supported"
                    else -> "aborted"
                }
                JarvisHub.rec("error", JSONObject().put("error", code))
                JarvisHub.rec("end")
                main.post { try { r.destroy() } catch (_: Exception) {} }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
        muteBeep(true)
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            recognizer = null
            JarvisHub.rec("error", JSONObject().put("error", "aborted"))
            JarvisHub.rec("end")
        }
        main.postDelayed({ muteBeep(false) }, 700)
    }

    fun recStop() {
        try { recognizer?.stopListening() } catch (_: Exception) {}
    }

    fun recAbort() = destroyRecognizer()

    private fun firstText(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()

    private fun destroyRecognizer() {
        val r = recognizer ?: return
        recognizer = null
        try { r.cancel() } catch (_: Exception) {}
        try { r.destroy() } catch (_: Exception) {}
    }

    /** Silencia unos instantes los sonidos del sistema para que no "pite" cada vez que reinicia la escucha. */
    private fun muteBeep(mute: Boolean) {
        try {
            if (mute) {
                for (s in intArrayOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
                    if (!audio.isStreamMute(s)) {
                        audio.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
                        mutedByUs.add(s)
                    }
                }
            } else {
                for (s in mutedByUs) audio.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0)
                mutedByUs.clear()
            }
        } catch (_: Exception) {
        }
    }

    /* ---------- voz de salida ---------- */

    override fun onInit(status: Int) {
        val t = tts
        if (status != TextToSpeech.SUCCESS || t == null) {
            ttsFailed = true
            pendingSpeak.forEach { JarvisHub.ttsDone(it.first) }
            pendingSpeak.clear()
            return
        }
        val locale = listOf(
            Locale.forLanguageTag("es-419"),
            Locale.forLanguageTag("es-MX"),
            Locale.forLanguageTag("es-US"),
            Locale.forLanguageTag("es-ES"),
            Locale.forLanguageTag("es")
        ).firstOrNull { t.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
        if (locale != null) t.language = locale
        t.setSpeechRate(1.05f)
        t.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { utteranceId?.let { JarvisHub.ttsDone(it) } }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) { utteranceId?.let { JarvisHub.ttsDone(it) } }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { JarvisHub.ttsDone(it) }
            }
        })
        ttsReady = true
        val list = ArrayList(pendingSpeak)
        pendingSpeak.clear()
        list.forEach { speak(it.first, it.second) }
    }

    fun speak(id: String, text: String) {
        if (ttsFailed) { JarvisHub.ttsDone(id); return }
        if (!ttsReady) { pendingSpeak.add(id to text); return }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stopSpeak() {
        pendingSpeak.clear()
        try { tts?.stop() } catch (_: Exception) {}
    }

    /* ---------- cierre ---------- */

    fun shutdown() {
        overlay?.hide(); overlay = null
        destroyRecognizer()
        stopSpeak()
        muteBeep(false)
        releaseWakeLock()
        foreground = false
        JarvisHub.serviceGone()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onDestroy() {
        overlay?.hide(); overlay = null
        destroyRecognizer()
        muteBeep(false)
        releaseWakeLock()
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        JarvisHub.serviceGone()
        super.onDestroy()
    }
}
