package com.visionhelper.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * YOLOv8n 端侧检测器（ONNX Runtime，纯 CPU，无 PyTorch 依赖）。
 *
 * 与 `tests/feasibility/test_yolo_onnx.py` 完全一致的后处理逻辑：
 *   Bitmap --letterbox(640x640, 灰边114)--> NCHW Float(0..1)
 *         --onnxruntime--> [1,84,8400]  (4 box + 80 class, 无 objectness)
 *         --阈值筛选 + NMS--> 归一化框 + 中文标签
 *
 * Mac CPU 实测 38.6ms/帧；Quest 3 / Pico 的 ARM CPU 上以 5~10FPS 节流运行即可满足"加框打标签"。
 */
class YoloOnnxDetector private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val confThreshold: Float,
    private val iouThreshold: Float,
    override val name: String,
) : Detector {

    private val inputName: String = session.inputNames.first()
    private val inputBuffer: FloatBuffer = FloatBuffer.allocate(3 * INPUT_SIZE * INPUT_SIZE)
    private val pixelBuffer = IntArray(INPUT_SIZE * INPUT_SIZE)

    override fun detect(bitmap: Bitmap): DetectionResult {
        val t0 = System.currentTimeMillis()
        val origW = bitmap.width
        val origH = bitmap.height
        val lb = preprocess(bitmap)

        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        var detections: List<Detection> = emptyList()

        OnnxTensor.createTensor(env, inputBuffer, shape).use { input ->
            session.run(mapOf(inputName to input)).use { outputs ->
                @Suppress("UNCHECKED_CAST")
                val out = (outputs.get(0).value as Array<Array<FloatArray>>)[0]
                detections = postprocess(out, lb, origW, origH)
            }
        }
        return DetectionResult(
            detections = detections.sortedByDescending { it.confidence },
            inferenceMs = System.currentTimeMillis() - t0,
            sourceWidth = origW,
            sourceHeight = origH,
        )
    }

    /** letterbox 缩放 + 填灰边，并把像素写成 NCHW 的 0..1 浮点。 */
    private fun preprocess(bitmap: Bitmap): Letterbox {
        val origW = bitmap.width
        val origH = bitmap.height
        val scale = min(INPUT_SIZE / origW.toFloat(), INPUT_SIZE / origH.toFloat())
        val nw = max(1, (origW * scale).roundToInt())
        val nh = max(1, (origH * scale).roundToInt())
        val dx = (INPUT_SIZE - nw) / 2
        val dy = (INPUT_SIZE - nh) / 2

        val scaled = Bitmap.createScaledBitmap(bitmap, nw, nh, true)
        val canvasBmp = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(canvasBmp).apply {
            drawColor(Color.rgb(114, 114, 114))
            drawBitmap(scaled, dx.toFloat(), dy.toFloat(), null)
        }
        canvasBmp.getPixels(pixelBuffer, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val area = INPUT_SIZE * INPUT_SIZE
        inputBuffer.rewind()
        for (i in 0 until area) {
            val p = pixelBuffer[i]
            inputBuffer.put(i, ((p shr 16) and 0xFF) / 255f)       // R
            inputBuffer.put(area + i, ((p shr 8) and 0xFF) / 255f) // G
            inputBuffer.put(area * 2 + i, (p and 0xFF) / 255f)     // B
        }
        inputBuffer.rewind()

        if (scaled !== bitmap) scaled.recycle()
        canvasBmp.recycle()
        return Letterbox(scale, dx.toFloat(), dy.toFloat())
    }

    /**
     * 解析输出张量：数学部分交给可单测的 [YoloPostProcess]，这里只做"类别索引 → 中文标签"映射。
     */
    private fun postprocess(
        out: Array<FloatArray>,
        lb: Letterbox,
        origW: Int,
        origH: Int,
    ): List<Detection> =
        YoloPostProcess.decode(out, lb.scale, lb.dx, lb.dy, origW, origH, confThreshold, iouThreshold)
            .map { raw ->
                val labelEn = LabelCatalog.COCO80.getOrElse(raw.classIndex) { "class_${raw.classIndex}" }
                Detection(
                    labelZh = LabelCatalog.zhOf(labelEn),
                    labelEn = labelEn,
                    confidence = raw.confidence,
                    left = raw.left,
                    top = raw.top,
                    right = raw.right,
                    bottom = raw.bottom,
                )
            }

    override fun close() {
        runCatching { session.close() }
    }

    private data class Letterbox(val scale: Float, val dx: Float, val dy: Float)

    companion object {
        const val INPUT_SIZE = 640
        private const val NUM_ANCHORS = 8400

        /** 从 assets 加载 onnx 模型并创建会话（首次调用约 0.3~1s）。 */
        fun create(
            context: Context,
            modelAsset: String = "yolov8n.onnx",
            confThreshold: Float = 0.35f,
            iouThreshold: Float = 0.45f,
            threads: Int = 4,
        ): YoloOnnxDetector {
            val bytes = context.assets.open(modelAsset).use { it.readBytes() }
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val session = env.createSession(bytes, opts)
            return YoloOnnxDetector(
                env, session, confThreshold, iouThreshold,
                name = "YOLOv8n-ONNX",
            )
        }
    }
}
