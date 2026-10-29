package com.example.traductor

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream

class CaptureService : Service() {

    companion object {
        private const val CHANNEL = "traductor"
        private const val RATE = 16000
        private val MODELS = mapOf(
            "ru" to "vosk-model-small-ru-0.22",
            "uk" to "vosk-model-small-uk-v3-small",
            "zh" to "vosk-model-small-cn-0.22",
            "ja" to "vosk-model-small-ja-0.22"
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var translator: Translator? = null
    private var overlay: TextView? = null
    @Volatile private var running = false
    private val lines = ArrayDeque<String>()
    private var partial = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY

        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        val code = intent?.getIntExtra("code", 0) ?: 0
        val lang = intent?.getStringExtra("lang") ?: "ru"
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        // En Android 14 hay que llamar a startForeground ANTES de obtener la proyección.
        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data)
        // Android 14 exige registrar un callback antes de capturar.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, main)
        projection = proj

        running = true
        showOverlay("Preparando…")
        Thread { work(lang) }.start()
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Traductor", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Traductor activo")
            .setContentText("Toca para abrir y detener")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun work(lang: String) {
        try {
            show("Descargando modelo de voz (solo la primera vez)…")
            val model = Model(ensureModel(lang))
            val recognizer = Recognizer(model, RATE.toFloat())

            show("Descargando traductor (solo la primera vez)…")
            val opts = TranslatorOptions.Builder()
                .setSourceLanguage(mlLang(lang))
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
            val tr = Translation.getClient(opts)
            translator = tr
            Tasks.await(tr.downloadModelIfNeeded(DownloadConditions.Builder().build()))

            val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(minBuf * 4)
                .build()
            recorder = rec
            rec.startRecording()
            show("Escuchando… reproduce el video")

            val buf = ByteArray(4096)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (recognizer.acceptWaveForm(buf, n)) {
                    val text = clean(JSONObject(recognizer.result).optString("text"), lang)
                    partial = ""
                    if (text.isNotBlank()) {
                        val en = try { Tasks.await(tr.translate(text)) } catch (e: Exception) { "(error de traducción)" }
                        lines.addLast(en)
                        while (lines.size > 3) lines.removeFirst()
                    }
                } else {
                    partial = clean(JSONObject(recognizer.partialResult).optString("partial"), lang)
                }
                render()
            }
            recognizer.close()
            model.close()
        } catch (e: Exception) {
            show("Error: ${e.message}")
        }
    }

    private fun clean(s: String, lang: String) =
        if (lang == "zh" || lang == "ja") s.replace(" ", "") else s

    private fun mlLang(lang: String) = when (lang) {
        "ru" -> TranslateLanguage.RUSSIAN
        "uk" -> TranslateLanguage.UKRAINIAN
        "zh" -> TranslateLanguage.CHINESE
        else -> TranslateLanguage.JAPANESE
    }

    private fun ensureModel(lang: String): String {
        val name = MODELS.getValue(lang)
        val base = File(filesDir, "models").apply { mkdirs() }
        val dir = File(base, name)
        val ok = File(dir, ".ok")
        if (ok.exists()) return dir.path
        dir.deleteRecursively()
        ZipInputStream(URL("https://alphacephei.com/vosk/models/$name.zip").openStream()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val out = File(base, e.name)
                if (!out.canonicalPath.startsWith(base.canonicalPath)) throw SecurityException("zip")
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
                e = zip.nextEntry
            }
        }
        ok.createNewFile()
        return dir.path
    }

    private fun render() {
        val shown = lines.joinToString("\n") +
            if (partial.isNotBlank()) "\n… $partial" else ""
        show(shown)
    }

    private fun show(text: String) { main.post { overlay?.text = text } }

    private fun showOverlay(initial: String) {
        val tv = TextView(this).apply {
            text = initial
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            setPadding(24, 16, 24, 16)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM; y = 120 }
        getSystemService(WindowManager::class.java).addView(tv, lp)
        overlay = tv
    }

    override fun onDestroy() {
        running = false
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release()
        translator?.close()
        try { projection?.stop() } catch (_: Exception) {}
        overlay?.let { getSystemService(WindowManager::class.java).removeView(it) }
        super.onDestroy()
    }
}
