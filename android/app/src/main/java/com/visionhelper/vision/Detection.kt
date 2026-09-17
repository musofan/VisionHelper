package com.visionhelper.vision

/**
 * 一次物体检测的结果。
 *
 * 坐标使用 0..1 归一化（相对原图），好处是 overlay 可以按任意 View 尺寸直接缩放，
 * 不需要在 UI 层关心图像分辨率（相机帧 / 演示图 / 相册图都能统一处理）。
 */
data class Detection(
    val labelZh: String,
    val labelEn: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** 给记录 JSON 用的紧凑表示。 */
    fun toJson(): String = buildString {
        append("{\"label\":\"").append(labelZh).append("\",")
        append("\"label_en\":\"").append(labelEn).append("\",")
        append("\"conf\":").append("%.3f".format(confidence)).append(',')
        append("\"box\":[")
        append("%.4f".format(left)).append(',').append("%.4f".format(top)).append(',')
        append("%.4f".format(right)).append(',').append("%.4f".format(bottom))
        append("]}")
    }
}

/** 一次检测（一帧）的完整输出，用于 overlay 渲染 + 记录落盘。 */
data class DetectionResult(
    val detections: List<Detection>,
    val inferenceMs: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
) {
    fun summary(): String =
        if (detections.isEmpty()) "未检出物体（${inferenceMs}ms）"
        else detections.joinToString("、") { "${it.labelZh}${"%.0f".format(it.confidence * 100)}%" } +
            "（${inferenceMs}ms）"
}

/** 检测器抽象：v0.1 是 YOLOv8n-ONNX，后续可替换为 MediaPipe / 云端 VL API 而 UI 不变。 */
interface Detector : AutoCloseable {
    val name: String
    fun detect(bitmap: android.graphics.Bitmap): DetectionResult
}