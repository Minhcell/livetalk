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
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val main = Handler(Looper.getMainLooper())

    // Trạng thái
    private var wantListen = false
    private var currentLang = "vi-VN"
    private var preferOffline = false
    private var micSensitivity = 1.5f
    private var silenceMs = 1200L
    private var beepMuted = false

    // ML Kit
    private val languageId = LanguageIdentification.getClient()
    private val translators = HashMap<String, Translator>()   // cache theo "src>tgt"

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
        translators.values.forEach { it.close() }
        languageId.close()
        super.onDestroy()
    }

    override fun onPause() { muteBeep(false); super.onPause() }

    // ─────────── Quyền mic ───────────
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> emit("perm", JSONObject().put("granted", granted).toString()) }

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
        fun stopListen() { main.post { wantListen = false; stopRecognition() } }

        @JavascriptInterface
        fun speak(text: String, lang: String) { main.post { speakNative(text, lang) } }

        @JavascriptInterface
        fun stopSpeak() { main.post { tts?.stop() } }

        @JavascriptInterface
        fun setSensitivity(v: Float) {
            micSensitivity = v.coerceIn(0.5f, 3.0f)
            silenceMs = (1800 - micSensitivity * 400).toLong().coerceIn(700, 2000)
        }

        @JavascriptInterface
        fun setOffline(v: Boolean) { preferOffline = v }

        // Dịch bằng ML Kit (offline sau khi tải model). reqId để JS ghép kết quả.
        @JavascriptInterface
        fun translate(reqId: String, text: String, srcShort: String, tgtShort: String) {
            main.post { doTranslate(reqId, text, srcShort, tgtShort) }
        }

        // Nhận diện ngôn ngữ của đoạn text (offline), trả mã ngắn hoặc "und"
        @JavascriptInterface
        fun detectLang(reqId: String, text: String) {
            languageId.identifyLanguage(text)
                .addOnSuccessListener { code ->
                    emit("detected", JSONObject().put("id", reqId).put("lang", code).toString())
                }
                .addOnFailureListener {
                    emit("detected", JSONObject().put("id", reqId).put("lang", "und").toString())
                }
        }

        // Tải sẵn model cho 1 cặp (gọi khi bật offline) để dịch offline mượt
        @JavascriptInterface
        fun preloadModel(srcShort: String, tgtShort: String) {
            main.post { getTranslator(srcShort, tgtShort, download = true, reqId = null) }
        }

        @JavascriptInterface
        fun isRecognitionAvailable(): Boolean =
            SpeechRecognizer.isRecognitionAvailable(this@MainActivity)
    }

    // ─────────── ML Kit Dịch ───────────
    private fun mlLang(short: String): String? = when (short.lowercase().split("-")[0]) {
        "vi" -> TranslateLanguage.VIETNAMESE
        "en" -> TranslateLanguage.ENGLISH
        "zh" -> TranslateLanguage.CHINESE
        "ja" -> TranslateLanguage.JAPANESE
        "ko" -> TranslateLanguage.KOREAN
        "th" -> TranslateLanguage.THAI
        "fr" -> TranslateLanguage.FRENCH
        "de" -> TranslateLanguage.GERMAN
        "ru" -> TranslateLanguage.RUSSIAN
        "es" -> TranslateLanguage.SPANISH
        else -> null
    }

    private fun getTranslator(srcShort: String, tgtShort: String, download: Boolean, reqId: String?): Translator? {
        val src = mlLang(srcShort); val tgt = mlLang(tgtShort)
        if (src == null || tgt == null) {
            if (reqId != null) emit("transfail", JSONObject().put("id", reqId).put("msg", "lang_unsupported").toString())
            return null
        }
        val key = "$src>$tgt"
        val existing = translators[key]
        if (existing != null) return existing
        val opts = TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build()
        val tr = Translation.getClient(opts)
        translators[key] = tr
        if (download) {
            tr.downloadModelIfNeeded()
                .addOnSuccessListener { emit("modelready", JSONObject().put("pair", "$srcShort-$tgtShort").toString()) }
                .addOnFailureListener { emit("modelfail", JSONObject().put("pair", "$srcShort-$tgtShort").toString()) }
        }
        return tr
    }

    private fun doTranslate(reqId: String, text: String, srcShort: String, tgtShort: String) {
        val tr = getTranslator(srcShort, tgtShort, download = false, reqId = reqId) ?: return
        // Đảm bảo model có sẵn (offline). Nếu chưa tải, tự tải rồi dịch.
        tr.downloadModelIfNeeded()
            .addOnSuccessListener {
                tr.translate(text)
                    .addOnSuccessListener { out ->
                        emit("translated", JSONObject().put("id", reqId).put("text", out).toString())
                    }
                    .addOnFailureListener { e ->
                        emit("transfail", JSONObject().put("id", reqId).put("msg", e.message ?: "translate_error").toString())
                    }
            }
            .addOnFailureListener {
                emit("transfail", JSONObject().put("id", reqId).put("msg", "model_download_needed").toString())
            }
    }

    // ─────────── Nhận dạng giọng nói ───────────
    private var beepMutedOnce = false
    private fun muteBeep(mute: Boolean) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val streams = intArrayOf(
                AudioManager.STREAM_NOTIFICATION,
                AudioManager.STREAM_SYSTEM,
                AudioManager.STREAM_RING
            )
            if (mute && !beepMuted) {
                for (s in streams) am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
                beepMuted = true
            } else if (!mute && beepMuted) {
                for (s in streams) am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0)
                beepMuted = false
            }
        } catch (_: Exception) {}
    }

    private fun beginRecognition() {
        stopRecognition()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            emit("error", JSONObject().put("msg", "no_recognition").toString())
            return
        }
        muteBeep(true)
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(listener)
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 700)
            if (preferOffline && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
        try { recognizer?.startListening(i) }
        catch (e: Exception) { emit("error", JSONObject().put("msg", e.message ?: "start_failed").toString()) }
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
            main.postDelayed({ muteBeep(false) }, 300)
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rms: Float) {
            emit("rms", JSONObject().put("v", rms * micSensitivity).toString())
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                emit("error", JSONObject().put("msg", "perm").toString()); return
            }
            if (preferOffline && (error == SpeechRecognizer.ERROR_NETWORK ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == 12)) {
                preferOffline = false
                emit("offlinefail", null)
            }
            recognizer?.destroy(); recognizer = null
            if (wantListen) main.postDelayed({ if (wantListen) beginRecognition() }, 120)
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty())
                emit("final", JSONObject().put("text", text).put("lang", currentLang).toString())
            recognizer?.destroy(); recognizer = null
            if (wantListen) main.postDelayed({ if (wantListen) beginRecognition() }, 120)
        }

        override fun onPartialResults(partial: Bundle?) {
            val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) emit("partial", JSONObject().put("text", text).toString())
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ─────────── Đọc loa (ra loa ngoài + Bluetooth) ───────────
    private fun speakNative(text: String, lang: String) {
        if (!ttsReady || text.isBlank()) { emit("ttsend", null); return }
        muteBeep(false)
        stopRecognition()
        val res = tts?.setLanguage(localeOf(lang))
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            emit("ttsmissing", JSONObject().put("lang", lang).toString())
        }
        tts?.setSpeechRate(1.0f)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.currentTimeMillis())
    }

    private fun localeOf(tag: String): Locale = try { Locale.forLanguageTag(tag) } catch (_: Exception) { Locale.US }

    // ─────────── Native → JS ───────────
    // Truyền qua JSON.parse(chuỗi-đã-escape) để KHÔNG vỡ cú pháp với dấu tiếng Việt / ký tự đặc biệt
    private fun emit(type: String, jsonOrNull: String?) {
        val payload = jsonOrNull ?: "null"
        val safe = JSONObject.quote(payload)   // bọc & escape toàn bộ payload thành 1 chuỗi an toàn
        val js = "window.onNative && window.onNative(${JSONObject.quote(type)}, " +
                 "(function(p){try{return JSON.parse(p)}catch(e){return null}})($safe));"
        web.post { web.evaluateJavascript(js, null) }
    }
}

