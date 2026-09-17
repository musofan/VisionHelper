package com.visionhelper

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.visionhelper.probe.CameraProbe
import com.visionhelper.store.RecordStore
import com.visionhelper.source.CameraFrameSource
import com.visionhelper.source.DemoFrameSource
import com.visionhelper.source.FrameSource
import com.visionhelper.ui.ChatBubble
import com.visionhelper.ui.OverlayView
import com.visionhelper.vision.DetectionResult
import com.visionhelper.vision.YoloOnnxDetector
import com.visionhelper.voice.AsrEngine
import com.visionhelper.voice.ChatOrchestrator
import com.visionhelper.voice.ChatState
import com.visionhelper.voice.LlmConfig
import com.visionhelper.voice.TtsEngine
import java.util.concurrent.Executors

/**
 * VisionHelper v0.1 —— 可体验版主界面。
 *
 * 设计约束（来自调研结论）：
 *  - 纯 Android View + 零 AndroidX：唯一依赖是 onnxruntime-android（POM 无传递依赖），
 *    保证在最差网络环境 / 任意 Android 系头显（Quest 3 / Pico / AI 眼镜）都能构建安装。
 *  - 输入源可插拔：演示图（无相机也能体验）、Camera2、相册选图。
 *  - 语音遵循"文字先上屏 → 再播报"协议，播报可打断。
 *  - 所有能力都带可视化日志，方便用户在头显里直接反馈问题。
 */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "vh-io") }

    private lateinit var overlay: OverlayView
    private lateinit var logView: TextView
    private lateinit var chatList: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var statusView: TextView
    private lateinit var micButton: Button
    private lateinit var inputField: EditText
    private lateinit var sourceButton: Button

    private var detector: YoloOnnxDetector? = null
    private lateinit var recordStore: RecordStore
    private lateinit var tts: TtsEngine
    private lateinit var asr: AsrEngine
    private lateinit var orchestrator: ChatOrchestrator

    private var source: FrameSource = DemoFrameSource()
    private var lastResult: DetectionResult? = null
    private var lastBitmap: Bitmap? = null
    private var continuous = false
    private var continuousTask: Runnable? = null

    private val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordStore = RecordStore(this)
        tts = TtsEngine(this)
        asr = AsrEngine(this)
        orchestrator = ChatOrchestrator(
            configProvider = { loadLlmConfig() },
            tts = tts,
            onState = { st -> main.post { renderChatState(st) } },
            onLog = { msg -> log(msg) },
        )

        setContentView(buildLayout())
        tts.init { _, status -> log("TTS: $status") }
        requestPermissions(perms, REQ_PERMS)
        appendBubble(ChatBubble.assistant(this, "你好，我是 VisionHelper。点「识别一帧」看物体标注，或直接问我画面里有什么。"))
        log("VisionHelper v0.1 启动 · 已有记录 ${recordStore.count} 条")
        log(CameraProbe.deviceSummary().replace("\n", " | "))
        loadDetector()
    }

    // ───────────────────────── 布局 ─────────────────────────

    private fun buildLayout(): android.view.View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F14"))
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(text("VisionHelper", 20f, "#E6F1FF", bold = true))
        statusView = text("初始化…", 13f, "#8FA3B8").apply { setPadding(dp(16), dp(6), 0, 0) }
        top.addView(statusView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(smallButton("设备诊断") { startActivity(Intent(this, ProbeActivity::class.java)) })
        top.addView(smallButton("设置") { showSettingsDialog() })
        root.addView(top)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        overlay = OverlayView(this)
        left.addView(overlay, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        left.addView(buildVisionButtons())
        body.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.25f))

        body.addView(buildChatPanel(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        logView = text("", 11f, "#6E8299")
        root.addView(logView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        return root
    }

    private fun buildVisionButtons(): android.view.View {
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sourceButton = smallButton("输入源: ${source.name}") { toggleSource() }
        row1.addView(sourceButton, weight())
        row1.addView(smallButton("识别一帧") { captureAndDetect() }, weight())
        row1.addView(smallButton("连续识别") { toggleContinuous() }, weight())

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        row2.addView(smallButton("截图入档") { saveRecord() }, weight())
        row2.addView(smallButton("相册选图") { pickImage() }, weight())
        row2.addView(smallButton("记录库") { showRecordsDialog() }, weight())

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(row1)
            addView(row2)
        }
    }

    private fun buildChatPanel(): android.view.View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        panel.addView(text("语音对话框", 15f, "#E6F1FF", bold = true))

        chatList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        chatScroll = ScrollView(this).apply { addView(chatList) }
        panel.addView(chatScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        inputField = EditText(this).apply {
            hint = "输入问题，或点麦克风说话"
            setTextColor(Color.parseColor("#E6F1FF"))
            setHintTextColor(Color.parseColor("#5A6B7D"))
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
            setBackgroundColor(Color.parseColor("#151B23"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        inputRow.addView(inputField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(smallButton("发送") { sendFromInput() })
        panel.addView(inputRow)

        val voiceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        micButton = smallButton("🎤 说话") { toggleListen() }
        voiceRow.addView(micButton, weight())
        voiceRow.addView(smallButton("停止播报") { orchestrator.skipSpeech() }, weight())
        voiceRow.addView(smallButton("清空") {
            chatList.removeAllViews(); orchestrator.reset()
        }, weight())
        panel.addView(voiceRow)
        return panel
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private fun text(s: String, size: Float, color: String, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun smallButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.parseColor("#DCE9F7"))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#1B2530"))
                setStroke(dp(1), Color.parseColor("#2E3D4E"))
            }
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { action() }
        }

    private fun weight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(6)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun log(msg: String) {
        main.post {
            val lines = (logView.text.toString().lines() + "· $msg").takeLast(4)
            logView.text = lines.joinToString("\n")
        }
    }

    // ───────────────────────── 视觉管线 ─────────────────────────

    private fun loadDetector() {
        io.execute {
            try {
                val t0 = System.currentTimeMillis()
                val d = YoloOnnxDetector.create(applicationContext)
                detector = d
                val cost = System.currentTimeMillis() - t0
                main.post {
                    statusView.text = "模型就绪 · ${d.name} · 载入 ${cost}ms"
                    log("检测器就绪 ${d.name} (${cost}ms)")
                    captureAndDetect()   // 自动跑第一帧，戴上就能看到"加框打标签"效果
                }
            } catch (e: Exception) {
                main.post {
                    statusView.text = "模型载入失败"
                    log("模型载入失败 ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    private fun captureAndDetect() {
        val d = detector ?: run { log("检测器尚未就绪"); return }
        source.acquire(this, { bmp ->
            if (bmp == null) {
                log("取帧失败（换演示图源或检查相机权限）")
                return@acquire
            }
            lastBitmap = bmp
            main.post {
                overlay.bitmap = bmp
                overlay.detections = emptyList()
            }
            io.execute {
                try {
                    val res = d.detect(bmp)
                    main.post {
                        lastResult = res
                        overlay.detections = res.detections
                        statusView.text = "识别: ${res.summary()}"
                        orchestrator.sceneSummary = sceneText(res)
                        log("识别 ${res.sourceWidth}x${res.sourceHeight} → ${res.detections.size} 个物体 · ${res.inferenceMs}ms")
                    }
                } catch (e: Exception) {
                    main.post { log("推理失败 ${e.javaClass.simpleName}: ${e.message}") }
                }
            }
        }, { msg -> log(msg) })
    }

    /** 把识别结果整理成一句话，既作为 LLM 上下文，也让用户直观看到"记录了什么"。 */
    private fun sceneText(res: DetectionResult): String {
        if (res.detections.isEmpty()) return "没有识别到已知物体"
        val grouped = res.detections.groupingBy { it.labelZh }.eachCount()
        return grouped.entries.joinToString("、") { "${it.key}${it.value}个" }
    }

    private fun toggleSource() {
        source = if (source is DemoFrameSource) CameraFrameSource() else DemoFrameSource()
        sourceButton.text = "输入源: ${source.name}"
        log("切换输入源 → ${source.name}（${source.description}）")
        if (continuous) { stopContinuous(); log("连续识别已停止（切换输入源）") }
        captureAndDetect()
    }

    private fun toggleContinuous() {
        if (continuous) { stopContinuous(); return }
        if (source.continuousIntervalMs <= 0L) {
            log("演示图为静态图，连续识别意义有限；切到相机源再用")
            return
        }
        continuous = true
        log("连续识别开始（间隔 ${source.continuousIntervalMs}ms）")
        val task = object : Runnable {
            override fun run() {
                if (!continuous) return
                captureAndDetect()
                main.postDelayed(this, source.continuousIntervalMs)
            }
        }
        continuousTask = task
        main.post(task)
    }

    private fun stopContinuous() {
        continuous = false
        continuousTask?.let { main.removeCallbacks(it) }
        continuousTask = null
    }

    // ───────────────────────── 记录 ─────────────────────────

    private fun saveRecord() {
        if (overlay.bitmap == null) {
            log("还没有画面，先点「识别一帧」")
            return
        }
        val annotated = overlay.renderToBitmap()
        val detections = lastResult?.detections ?: emptyList()
        io.execute {
            try {
                val entry = recordStore.save(annotated, source.name, detections, note = "")
                main.post {
                    log("已入档 ${entry.id} · ${entry.summary()} · 共 ${recordStore.count} 条")
                    Toast.makeText(this, "已记录：${entry.summary()}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                main.post { log("入档失败: ${e.message}") }
            }
        }
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        io.execute {
            try {
                val bmp = contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }
                if (bmp == null) { main.post { log("图片解码失败") }; return@execute }
                val d = detector
                val res = d?.detect(bmp)
                main.post {
                    lastBitmap = bmp
                    lastResult = res
                    overlay.bitmap = bmp
                    overlay.detections = res?.detections ?: emptyList()
                    statusView.text = res?.let { "识别: ${it.summary()}" } ?: "已载入图片（检测器未就绪）"
                    if (res != null) orchestrator.sceneSummary = sceneText(res)
                    log("相册图片识别完成: ${res?.detections?.size ?: 0} 个物体")
                }
            } catch (e: Exception) {
                main.post { log("读取图片失败: ${e.message}") }
            }
        }
    }

    // ───────────────────────── 语音对话 ─────────────────────────

    private fun appendBubble(v: android.view.View) {
        chatList.addView(v)
        chatScroll.post { chatScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    /**
     * 状态机 → UI：这是"先文字后播报"协议的可视化落点。
     * TextReady 时先把完整回复渲染到气泡，Speaking 时才开始语音，用户可随时打断。
     */
    private fun renderChatState(st: ChatState) {
        when (st) {
            is ChatState.Idle -> statusView.text = "就绪 · TTS: ${tts.status}"
            is ChatState.Thinking -> statusView.text = "思考中…"
            is ChatState.TextReady -> {
                appendBubble(ChatBubble.assistant(this, st.text))
                statusView.text = "文字已显示，${if (tts.status.contains("不可用")) "无法播报" else "即将播报…"}"
            }
            is ChatState.Speaking -> statusView.text = "正在播报…（可点「停止播报」打断）"
            is ChatState.Failed -> {
                appendBubble(ChatBubble.system(this, "⚠ ${st.message}"))
                statusView.text = "对话失败"
            }
        }
    }

    private fun sendFromInput() {
        val t = inputField.text.toString().trim()
        if (t.isEmpty()) return
        inputField.setText("")
        appendBubble(ChatBubble.user(this, t))
        orchestrator.send(t)
    }

    private fun toggleListen() {
        if (!asr.available) {
            log("设备无系统语音识别服务，请用文字输入")
            return
        }
        micButton.text = " 聆听中…"
        asr.listen(
            onPartial = { p -> main.post { inputField.setText(p) } },
            onResult = { text ->
                main.post {
                    micButton.text = "🎤 说话"
                    inputField.setText("")
                    appendBubble(ChatBubble.user(this, text))
                    orchestrator.send(text)
                }
            },
            onError = { err ->
                main.post {
                    micButton.text = "🎤 说话"
                    log("语音识别: $err")
                }
            },
        )
    }

    // ───────────────────────── 设置 / 记录库 ─────────────────────────

    /** LLM 配置入口：任何 OpenAI 兼容服务（OpenAI / 通义 compatible-mode / DeepSeek / 本地 Ollama）。 */
    private fun showSettingsDialog() {
        val cfg = loadLlmConfig()
        val pad = dp(12)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun field(label: String, value: String, hint: String): EditText {
            box.addView(text(label, 12f, "#8FA3B8"))
            return EditText(this).apply {
                setText(value)
                this.hint = hint
                textSize = 13f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#151B23"))
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }.also { box.addView(it) }
        }

        val keyField = field("API Key", cfg.apiKey, "留空则使用本地兜底应答")
        val urlField = field("Base URL", cfg.baseUrl, "https://api.openai.com/v1")
        val modelField = field("模型", cfg.model, "gpt-4o-mini")

        AlertDialog.Builder(this)
            .setTitle("AI 与语音设置")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                saveLlmConfig(
                    LlmConfig(
                        baseUrl = urlField.text.toString().trim().ifBlank { "https://api.openai.com/v1" },
                        apiKey = keyField.text.toString().trim(),
                        model = modelField.text.toString().trim().ifBlank { "gpt-4o-mini" },
                    )
                )
                val c = loadLlmConfig()
                log(if (c.configured) "已配置 LLM: ${c.model} @ ${c.baseUrl}" else "未配置 LLM，将使用本地应答")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun loadLlmConfig(): LlmConfig {
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        return LlmConfig(
            baseUrl = sp.getString("base_url", "https://api.openai.com/v1") ?: "https://api.openai.com/v1",
            apiKey = sp.getString("api_key", "") ?: "",
            model = sp.getString("model", "gpt-4o-mini") ?: "gpt-4o-mini",
        )
    }

    private fun saveLlmConfig(cfg: LlmConfig) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("base_url", cfg.baseUrl)
            .putString("api_key", cfg.apiKey)
            .putString("model", cfg.model)
            .apply()
    }

    private fun showRecordsDialog() {
        val records = recordStore.list(30)
        if (records.isEmpty()) {
            log("记录库为空，先点「截图入档」")
            return
        }
        val items = records.map { "${it.timeText}  ${it.sourceName}\n${it.summary()}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("记录库（共 ${recordStore.count} 条）")
            .setItems(items) { _, i -> log("记录 ${records[i].id}: ${records[i].summary()}") }
            .setPositiveButton("导出 JSON") { _, _ ->
                io.execute {
                    try {
                        val out = java.io.File(filesDir, "visionhelper_records.json")
                        out.writeText(recordStore.exportJson())
                        main.post { log("已导出: ${out.absolutePath} (${out.length()} bytes)") }
                    } catch (e: Exception) {
                        main.post { log("导出失败: ${e.message}") }
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        val granted = permissions.filterIndexed { i, _ -> grantResults.getOrNull(i) == 0 }
        val denied = permissions.filterNot { it in granted }
        log("权限授予: ${granted.joinToString { it.substringAfterLast('.') }}")
        if (denied.isNotEmpty()) {
            log("被拒绝: ${denied.joinToString { it.substringAfterLast('.') }}（相机/语音功能受限）")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopContinuous()
        asr.release()
        tts.shutdown()
        detector?.close()
        tts.stop()
    }

    companion object {
        private const val REQ_PERMS = 1001
        private const val REQ_PICK = 1002
        private const val PREFS = "visionhelper"
    }
}