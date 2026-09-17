package com.visionhelper.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 语音播报（TTS）。
 *
 * v0.1 走系统 TextToSpeech：零依赖、绝大多数 Android 设备直接可用。
 * 已知限制（调研已记录）：Quest / Pico 的 ROM 可能不带中文 TTS 引擎，
 * 因此产品协议强制"先显示文字、再尝试播报"—— 播报不可用时用户依然能读到信息。
 * Phase 2 将替换为 sherpa-onnx VITS 离线中文 TTS（本机已验证 RTF 0.74）。
 */
class TtsEngine(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var onDoneCallback: (() -> Unit)? = null

    /** 引擎状态描述，显示在 UI 上（方便远端设备排查"为什么没声音"）。 */
    var status: String = "初始化中…"
        private set

    fun init(onReady: (Boolean, String) -> Unit) {
        tts = TextToSpeech(context.applicationContext) { code ->
            if (code != TextToSpeech.SUCCESS) {
                status = "TTS 引擎不可用(code=$code)，仅文字模式"
                onReady(false, status)
                return@TextToSpeech
            }
            val engine = tts ?: return@TextToSpeech
            val zh = Locale.CHINA
            val avail = engine.isLanguageAvailable(zh)
            status = if (avail >= TextToSpeech.LANG_AVAILABLE) {
                engine.language = zh
                "中文语音就绪"
            } else {
                engine.language = Locale.US
                "无中文引擎，回退英文"
            }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    main.post { onDoneCallback?.invoke(); onDoneCallback = null }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    main.post { onDoneCallback?.invoke(); onDoneCallback = null }
                }
            })
            ready = true
            onReady(true, status)
        }
    }

    /** 播报一段文字；onDone 在播报结束（或失败）后回调。 */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val engine = tts
        if (engine == null || !ready) {
            onDone?.invoke()
            return
        }
        onDoneCallback = onDone
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "vh-${System.currentTimeMillis()}")
    }

    fun stop() {
        onDoneCallback = null
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }
}