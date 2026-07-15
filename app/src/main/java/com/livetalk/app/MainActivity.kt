package com.livetalk.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val main = Handler(Looper.getMainLooper())

    // Trạng thái nhận dạng
    private var listening = false
    private var wantListen = false          // JS muốn mic đang bật
    private var currentLang = "vi-VN"
    private var preferOffline = false        // chế độ offline
    private var micSensitivity = 1.5f        // hệ số độ nhạy mic (0.5–3.0), JS chỉnh
    private var lastRms = 0f
    private var silenceMs = 1200L            // ngưỡng im lặng để chốt câu (ms) — tính theo độ nhạy
    private var lastSpeechAt = 0L
    private var gotAnySpeech = false

    // ─────────────────────────── Vòng đời ───────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        volumeControlStream = AudioManager.STREAM_MUSIC   // nút âm lượng chỉnh loa media

        web = WebView(this)
        setContentView(web)

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "Native")

        // Nạp trang giao diện đóng gói sẵn trong assets
        web.loadUrl("file:///android_asset/index.html")

        // Khởi tạo TTS native (đọc loa, không tiếng bíp)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { main.post { emit("ttsend", null) } }
                    @Deprecated("deprecated") override fun onError(id: String?) { main.post { emit("ttsend", null) } }
                    override fun onError(id: String?, code: Int) { main.post { emit("ttsend", null) } }
                })
            }
        }

        ensureMicPermission()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    // ─────────────────────────── Quyền mic ───────────────────────────
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        emit("perm", JSONObject().put("granted", granted).toString())
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun ensureMicPermission() {
        if (!hasMic()) permLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // ─────────────────────────── Cầu nối JS → Native ───────────────────────────
    inner class Bridge {
        @JavascriptInterface
        fun startListen(lang: String) {
            main.post {
                currentLang = lang
                wantListen = true
                if (!hasMic()) { ensureMicPermission(); return@post }
                beginRecognition()
            }
        }

        @JavascriptInterface
        fun stopListen() {
            main.post {
                wantListen = false
                stopRecognition()
            }
        }

        @JavascriptInterface
        fun speak(text: String, lang: String) {
            main.post { speakNative(text, lang) }
        }

        @JavascriptInterface
        fun stopSpeak() {
            main.post { tts?.stop() }
        }

        // JS chỉnh độ nhạy mic (0.5 = ít nhạy/chỉ tiếng lớn, 3.0 = rất nhạy)
        @JavascriptInterface
        fun setSensitivity(v: Float) {
            micSensitivity = v.coerceIn(0.5f, 3.0f)
            // Độ nhạy cao → chốt câu nhanh hơn (ngưỡng im lặng ngắn hơn)
            silenceMs = (1800 - micSensitivity * 400).toLong().coerceIn(700, 2000)
        }

        @JavascriptInterface
        fun setOffline(v: Boolean) { preferOffline = v }

        @JavascriptInterface
        fun isRecognitionAvailable(): Boolean =
            SpeechRecognizer.isRecognitionAvailable(this@MainActivity)

        @JavascriptInterface
        fun ttsLanguages(): String {
            val sb = StringBuilder()
            try {
                tts?.availableLanguages?.forEach { sb.append(it.toLanguageTag()).append(",") }
            } catch (_: Exception) {}
            return sb.toString()
        }
    }

    // ─────────────────────────── Nhận dạng giọng nói ───────────────────────────
    private fun beginRecognition() {
        stopRecognition()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            emit("error", JSONObject().put("msg", "no_recognition").toString())
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(listener)

        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Ngưỡng im lặng tính theo độ nhạy mic
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800)
            // Chế độ offline (Android 6+)
            if (preferOffline && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
        gotAnySpeech = false
        lastSpeechAt = System.currentTimeMillis()
        try {
            recognizer?.startListening(i)
            listening = true
        } catch (e: Exception) {
            emit("error", JSONObject().put("msg", e.message ?: "start_failed").toString())
        }
    }

    private fun stopRecognition() {
        listening = false
        try { recognizer?.stopListening() } catch (_: Exception) {}
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { emit("listening", null) }
        override fun onBeginningOfSpeech() { gotAnySpeech = true }

        override fun onRmsChanged(rms: Float) {
            // Áp hệ số độ nhạy do người dùng chỉnh, đẩy mức âm lượng lên JS vẽ thanh sóng
            lastRms = rms * micSensitivity
            emit("rms", JSONObject().put("v", lastRms).toString())
        }

        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            // Hết tiếng nói / hết giờ chờ → tự khởi động lại phiên nếu JS vẫn muốn nghe
            val fatal = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            if (fatal) {
                emit("error", JSONObject().put("msg", "perm").toString())
                return
            }
            listening = false
            recognizer?.destroy()
            recognizer = null
            if (wantListen) main.postDelayed({ if (wantListen) beginRecognition() }, 120)
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = list?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                emit("final", JSONObject().put("text", text).put("lang", currentLang).toString())
            }
            listening = false
            recognizer?.destroy()
            recognizer = null
            if (wantListen) main.postDelayed({ if (wantListen) beginRecognition() }, 120)
        }

        override fun onPartialResults(partial: Bundle?) {
            val list = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = list?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty())
                emit("partial", JSONObject().put("text", text).toString())
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ─────────────────────────── Đọc loa native ───────────────────────────
    private fun speakNative(text: String, lang: String) {
        if (!ttsReady || text.isBlank()) { emit("ttsend", null); return }
        // Nhả mic trước khi đọc (không bíp, không tự nghe lại)
        stopRecognition()
        val loc = localeOf(lang)
        val res = tts?.setLanguage(loc)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            emit("ttsmissing", JSONObject().put("lang", lang).toString())
        }
        tts?.setSpeechRate(1.0f)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.currentTimeMillis())
    }

    private fun localeOf(tag: String): Locale = try {
        Locale.forLanguageTag(tag)
    } catch (_: Exception) { Locale.US }

    // ─────────────────────────── Native → JS ───────────────────────────
    private fun emit(type: String, jsonOrNull: String?) {
        val payload = jsonOrNull ?: "null"
        val js = "window.onNative && window.onNative('$type', $payload);"
        web.post { web.evaluateJavascript(js, null) }
    }
}
