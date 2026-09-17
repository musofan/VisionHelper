package com.visionhelper.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * 语音识别（ASR）。
 *
 * v0.1 走系统 SpeechRecognizer（免依赖、免账号）。它是"在线/离线由系统决定"的黑盒，
 * 所以产品层保留文字输入作为兜底通道；Phase 2 换 sherpa-onnx SenseVoice
 * （完全离线中文，本机实测 RTF 0.04）后这条通道即可全离线。
 */
class AsrEngine(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun listen(
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!available) {
            onError("设备无系统语音识别服务，请用文字输入")
            return
        }
        main.post {
            if (active) return@post
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        active = false
                        onError(errorText(error))
                    }

                    override fun onResults(results: Bundle?) {
                        active = false
                        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = list?.firstOrNull().orEmpty()
                        if (text.isBlank()) onError("没有听清，请再说一次") else onResult(text)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        list?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onPartial)
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                active = true
                startListening(intent)
            }
        }
    }

    fun stop() {
        main.post {
            runCatching { recognizer?.stopListening() }
            active = false
        }
    }

    fun release() {
        main.post {
            runCatching { recognizer?.destroy() }
            recognizer = null
            active = false
        }
    }

    private fun errorText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "录音错误"
        SpeechRecognizer.ERROR_CLIENT -> "识别客户端错误"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
        SpeechRecognizer.ERROR_NETWORK -> "网络错误（系统识别需联网）"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
        SpeechRecognizer.ERROR_NO_MATCH -> "没有识别到内容"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙"
        SpeechRecognizer.ERROR_SERVER -> "识别服务端错误"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到语音"
        else -> "识别失败(code=$error)"
    }
}