package com.livetalk.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
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
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val main = Handler(Looper.getMainLooper())

    // Trạng thái nhận dạng
    private var wantListen = false
    private var currentLang = "vi-VN"
    private var preferOffline = false
    private var micSensitivity = 1.5f
    private var silenceMs = 1200L
    private var beepMuted = false

    // ─────────── Vòng đời ───────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC

        web = WebView(this)
        setContentView(web)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "Native")
        web.loadUrl("file:///android_asset/index.html")

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                try {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    tts?.setAudioAttributes(attrs)
                } catch (_: Exception) {}
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
        muteBeep(false)
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onPause() {
        muteBeep(false)
        super.onPause()
    }

    // ─────────── Quyền mic ───────────
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

    // ─────────── Cầu nối JS → Native ───────────
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
            main.post { wantListen = false; stopRecognition() }
        }

        @JavascriptInterface
        fun speak(text: String, lang: String) {
            main.post { speakNative(text, lang) }
        }

        @JavascriptInterface
        fun stopSpeak() {
            main.post { try { tts?.stop() } catch (_: Exception) {} }
        }

        @JavascriptInterface
        fun setSensitivity(v: Float) {
            micSensitivity = v.coerceIn(0.5f, 3.0f)
            silenceMs = (1800 - micSensitivity * 400).toLong().coerceIn(700L, 2000L)
        }

        @JavascriptInterface
        fun setOffline(v: Boolean) { preferOffline = v }

        // Dịch qua HTTP (chạy trong native → KHÔNG bị CORS như fetch trong WebView)
        @JavascriptInterface
        fun translate(reqId: String, text: String, srcShort: String, tgtShort: String) {
            thread {
                try {
                    val out = httpTranslate(text, tgtShort)
                    emit("translated", JSONObject().put("id", reqId).put("text", out).toString())
                } catch (e: Exception) {
                    emit("transfail", JSONObject().put("id", reqId).put("msg", e.message ?: "err").toString())
                }
            }
        }

        @JavascriptInterface
        fun isRecognitionAvailable(): Boolean =
            SpeechRecognizer.isRecognitionAvailable(this@MainActivity)
    }

    // ─────────── Dịch online qua HTTP ───────────
    private fun httpTranslate(text: String, tgt: String): String {
        val q = URLEncoder.encode(text, "UTF-8")
        val urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$tgt&dt=t&q=$q"
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        val body = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
        conn.disconnect()
        // Kết quả: [[["bản dịch","gốc",...],...],...] → ghép các phần dịch
        val arr = JSONArray(body)
        val segs = arr.getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until segs.length()) {
            val seg = segs.getJSONArray(i)
            if (!seg.isNull(0)) sb.append(seg.getString(0))
        }
        return sb.toString()
    }

    // ─────────── Tắt tiếng bíp ───────────
    private var savedMusicVol = -1
    private fun muteBeep(mute: Boolean) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val streams = intArrayOf(
                AudioManager.STREAM_NOTIFICATION,
                AudioManager.STREAM_SYSTEM,
                AudioManager.STREAM_RING
            )
            if (mute && !beepMuted) {
                for (st in streams) am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, 0)
                beepMuted = true
            } else if (!mute && beepMuted) {
                for (st in streams) am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0)
                beepMuted = false
            }
        } catch (_: Exception) {}
    }

    // Chặn bíp Xiaomi (phát qua kênh MUSIC lúc mic bật): hạ MUSIC về 0 trong ~450ms rồi khôi phục.
    // Loa đọc xảy ra SAU bước này nên vẫn kêu bình thường.
    private fun suppressMusicBeepBriefly() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (savedMusicVol < 0) savedMusicVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            main.postDelayed({
                try {
                    if (savedMusicVol >= 0) {
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVol, 0)
                        savedMusicVol = -1
                    }
                } catch (_: Exception) {}
            }, 450)
        } catch (_: Exception) {}
    }


    // ─────────── Nhận dạng giọng nói ───────────
    private fun beginRecognition() {
        stopRecognition()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            emit("error", JSONObject().put("msg", "no_recognition").toString())
            return
        }
        muteBeep(true)
        suppressMusicBeepBriefly()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(listener)

        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800)
            if (preferOffline && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
        try {
            recognizer?.startListening(i)
        } catch (e: Exception) {
            emit("error", JSONObject().put("msg", e.message ?: "start_failed").toString())
        }
    }

    private fun stopRecognition() {
        try { recognizer?.stopListening() } catch (_: Exception) {}
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            emit("listening", null)
            // KHÔNG mở mute ở đây — giữ tắt bíp suốt lúc nghe. Chỉ mở khi đọc loa (speakNative).
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rms: Float) {
            emit("rms", JSONObject().put("v", rms * micSensitivity).toString())
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                emit("error", JSONObject().put("msg", "perm").toString())
                return
            }
            // Ngôn ngữ chưa hỗ trợ / thiếu gói → báo rõ, KHÔNG restart liên tục (gây bíp)
            // 12=ERROR_LANGUAGE_NOT_SUPPORTED, 13=ERROR_LANGUAGE_UNAVAILABLE (API31+)
            if (error == 12 || error == 13) {
                if (preferOffline) { preferOffline = false; emit("offlinefail", null) }
                else { emit("error", JSONObject().put("msg", "lang").put("lang", currentLang).toString()) }
                recognizer?.destroy(); recognizer = null
                // thử lại 1 lần sau 600ms (đủ để đổi cờ offline→online), không dồn dập
                if (wantListen) main.postDelayed({ if (wantListen) beginRecognition() }, 600)
                return
            }
            if (preferOffline && error == SpeechRecognizer.ERROR_NETWORK) {
                preferOffline = false
                emit("offlinefail", null)
            }
            recognizer?.destroy()
            recognizer = null
            // ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT: bình thường, nghe tiếp
            if (wantListen) main.postDelayed({ if (wantListen) beginRecognition() }, 150)
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = list?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty())
                emit("final", JSONObject().put("text", text).toString())
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

    // ─────────── Đọc loa native ───────────
    private fun speakNative(text: String, lang: String) {
        if (!ttsReady || text.isBlank()) { emit("ttsend", null); return }
        muteBeep(false)
        // Khôi phục ngay âm lượng MUSIC (phòng khi đang trong 450ms hạ tạm) để loa đọc rõ
        try {
            if (savedMusicVol >= 0) {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVol, 0)
                savedMusicVol = -1
            }
        } catch (_: Exception) {}
        stopRecognition()
        val res = tts?.setLanguage(localeOf(lang))
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            emit("ttsmissing", JSONObject().put("lang", lang).toString())
        }
        tts?.setSpeechRate(1.0f)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.currentTimeMillis())
    }

    private fun localeOf(tag: String): Locale = try {
        Locale.forLanguageTag(tag)
    } catch (_: Exception) { Locale.US }

    // ─────────── Native → JS ───────────
    private fun emit(type: String, jsonOrNull: String?) {
        val payload = jsonOrNull ?: "null"
        val safe = JSONObject.quote(payload)
        val js = "window.onNative && window.onNative(" + JSONObject.quote(type) + ", " +
                 "(function(p){try{return JSON.parse(p)}catch(e){return null}})(" + safe + "));"
        web.post { web.evaluateJavascript(js, null) }
    }
}
