package com.visionhelper.voice

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** 对话消息（OpenAI Chat Completions 协议格式）。 */
data class ChatMessage(val role: String, val content: String) {
    companion object {
        fun system(content: String) = ChatMessage("system", content)
        fun user(content: String) = ChatMessage("user", content)
        fun assistant(content: String) = ChatMessage("assistant", content)
    }
}

/** LLM 配置，由 UI 层的设置面板写入（SharedPreferences）。 */
data class LlmConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
) {
    val configured: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank()
}

/**
 * OpenAI 兼容协议的对话客户端（零第三方依赖，HttpURLConnection + 后台线程）。
 *
 * 之所以用"协议兼容"而不是绑定某一家 SDK：调研结论要求方案二不能依赖特定生态，
 * 任何暴露 /chat/completions 的服务（OpenAI / 通义千问 compatible-mode / DeepSeek /
 * 本地 Ollama / vLLM）都只需改 baseUrl + model + key 即可切换。
 */
object LlmClient {

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "llm-client").apply { priority = Thread.NORM_PRIORITY }
    }

    /** 助手人设：让模型知道自己是一个"视觉辅助记录"眼镜助手。 */
    fun systemPrompt(): String = """
        你是 VisionHelper 的语音助手，运行在 AR/VR 眼镜上，帮助用户记录工作时看到的信息。
        回答要求：口语化、简短（尽量 80 字以内）、可以直接被语音播报；不要使用 markdown 列表或代码块。
        如果用户在描述某个物体或场景，先给出识别结论，再给出最多两条实用建议。
    """.trimIndent()

    /**
     * 发起一次对话。回调都在后台线程触发，调用方自行切主线程。
     *
     * @param onSuccess 完整回复文本
     * @param onError 人类可读的错误信息（网络/鉴权/协议）
     */
    fun chat(
        config: LlmConfig,
        history: List<ChatMessage>,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        io.execute {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(config.baseUrl.trimEnd('/') + "/chat/completions")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 45_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                }

                val body = buildRequestBody(config.model, history)
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""

                if (code !in 200..299) {
                    onError("HTTP $code: ${text.take(200)}")
                    return@execute
                }
                val reply = extractContent(text)
                if (reply.isNullOrBlank()) onError("模型返回为空: ${text.take(160)}") else onSuccess(reply)
            } catch (e: Exception) {
                onError("${e.javaClass.simpleName}: ${e.message}")
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
    }

    private fun buildRequestBody(model: String, history: List<ChatMessage>): String = buildString {
        append("{\"model\":\"").append(escape(model)).append("\",\"temperature\":0.6,")
        append("\"messages\":[")
        history.forEachIndexed { i, m ->
            if (i > 0) append(',')
            append("{\"role\":\"").append(escape(m.role)).append("\",\"content\":\"")
                .append(escape(m.content)).append("\"}")
        }
        append("]}")
    }

    /**
     * 从响应中取出 assistant 内容。
     * 手写最小 JSON 提取：避免为了一个字段引入 Gson/Moshi（保持 APK 依赖最小）。
     */
    private fun extractContent(json: String): String? {
        val marker = "\"content\":\""
        val start = json.indexOf(marker)
        if (start < 0) return null
        val sb = StringBuilder()
        var i = start + marker.length
        while (i < json.length) {
            val c = json[i]
            when {
                c == '\\' && i + 1 < json.length -> {
                    when (val n = json[i + 1]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                        'u' -> {
                            if (i + 5 < json.length) {
                                val hex = json.substring(i + 2, i + 6)
                                hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                i += 4
                            }
                        }
                        else -> sb.append(n)
                    }
                    i += 2
                }
                c == '"' -> return sb.toString()
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    private fun escape(s: String): String = buildString(s.length + 8) {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
    }
}