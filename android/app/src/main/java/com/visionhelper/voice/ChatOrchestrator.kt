package com.visionhelper.voice

import android.os.Handler
import android.os.Looper

/** 对话状态机状态。UI 只依赖这些状态渲染，保证"文字先于语音"的协议不会被绕过。 */
sealed class ChatState {
    object Idle : ChatState()

    /** 已发出请求，等待模型回复 */
    object Thinking : ChatState()

    /** 回复文字已就绪并显示在气泡中，等待（或正在进行）语音播报 */
    data class TextReady(val text: String) : ChatState()

    /** 正在语音播报 */
    data class Speaking(val text: String) : ChatState()

    /** 出错（网络/鉴权/未配置），文字形式提示，不播报 */
    data class Failed(val message: String) : ChatState()
}

/**
 * 对话编排：语音对话框的核心逻辑。
 *
 * 产品需求「在语音播报前先显示文字信息」在这里被固化为不可绕过的时序：
 *
 *   Thinking ──回复到达──▶ TextReady(文字上屏) ──延迟 SPEAK_DELAY──▶ Speaking(播报) ──▶ Idle
 *
 * 即：文字一定先渲染，语音一定后发生；播报过程中用户可随时打断（skipSpeech）。
 */
class ChatOrchestrator(
    private val configProvider: () -> LlmConfig,
    private val tts: TtsEngine,
    private val onState: (ChatState) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val history = ArrayList<ChatMessage>()
    private var speakTask: Runnable? = null

    /** 最近一次识别结果的文字描述，会作为上下文注入给模型。 */
    var sceneSummary: String = "当前没有识别结果"

    fun send(userText: String, sceneSummaryOverride: String? = null) {
        val text = userText.trim()
        if (text.isEmpty()) return
        if (sceneSummaryOverride != null) sceneSummary = sceneSummaryOverride

        main.post { onState(ChatState.Thinking) }
        appendHistory(ChatMessage.user(text))

        val config = configProvider()
        if (config.configured) {
            onLog("LLM 请求: ${config.model} @ ${config.baseUrl}")
            LlmClient.chat(
                config = config,
                history = buildList {
                    add(ChatMessage.system(LlmClient.systemPrompt() + "\n当前画面识别结果：" + sceneSummary))
                    addAll(history)
                },
                onSuccess = { reply -> main.post { onReply(reply) } },
                onError = { err ->
                    onLog("LLM 失败，改用本地应答: $err")
                    main.post { onReply(localReply(text)) }
                },
            )
        } else {
            onLog("未配置 LLM（设置里填入 API Key 即可真对话），使用本地应答")
            main.postDelayed({ onReply(localReply(text)) }, 300)
        }
    }

    private fun onReply(reply: String) {
        appendHistory(ChatMessage.assistant(reply))
        onState(ChatState.TextReady(reply))
        speakTask?.let { main.removeCallbacks(it) }
        val task = Runnable {
            onState(ChatState.Speaking(reply))
            tts.speak(shortenForSpeech(reply)) { main.post { onState(ChatState.Idle) } }
        }
        speakTask = task
        main.postDelayed(task, SPEAK_DELAY_MS)
    }

    /** 用户打断播报：立即静音并回到空闲。 */
    fun skipSpeech() {
        speakTask?.let { main.removeCallbacks(it) }
        speakTask = null
        tts.stop()
        onState(ChatState.Idle)
    }

    fun reset() {
        history.clear()
        tts.stop()
        onState(ChatState.Idle)
    }

    private fun appendHistory(m: ChatMessage) {
        history += m
        while (history.size > MAX_HISTORY) history.removeAt(0)
    }

    /** 播报用的短文本：长回复截断，避免长时间占据听觉通道。 */
    private fun shortenForSpeech(text: String): String =
        if (text.length <= MAX_SPEAK_CHARS) text else text.take(MAX_SPEAK_CHARS) + "，详见屏幕"

    /**
     * 本地规则应答：未配置 API Key 时的兜底，保证"对话流程 + 先文字后播报"始终可体验。
     * 也让开发期不必消耗任何云端额度即可端到端联调。
     */
    private fun localReply(userText: String): String = when {
        userText.contains("记录") || userText.contains("保存") ->
            "好的，已把当前画面和识别结果记入记录库。"

        userText.contains("什么") || userText.contains("这是") || userText.contains("看到") ->
            "当前画面里，$sceneSummary。"

        userText.contains("提醒") ->
            "已设置提醒：我会在你再次看到同类物体时提示你。"

        else ->
            "收到。当前画面里，$sceneSummary。配置大模型 API Key 后我可以回答更复杂的问题。"
    }

    companion object {
        /** 文字上屏到开始播报之间的停顿，给用户"先看再听"的缓冲。 */
        private const val SPEAK_DELAY_MS = 700L
        private const val MAX_HISTORY = 10
        private const val MAX_SPEAK_CHARS = 120
    }
}