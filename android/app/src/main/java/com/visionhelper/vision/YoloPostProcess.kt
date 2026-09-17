package com.visionhelper.vision

import kotlin.math.max
import kotlin.math.min

/**
 * YOLOv8 输出解码 + NMS。
 *
 * 刻意做成**纯 Kotlin、零 Android 依赖**：这样这段最容易出错的数学（letterbox 反算、阈值、IoU 抑制）
 * 可以直接在 JVM 单元测试里跑，并与 Python 参考实现（tests/feasibility/test_yolo_onnx.py）逐位比对。
 * Android 相关的部分（Bitmap 预处理、标签中文映射）留在 [YoloOnnxDetector]。
 */
object YoloPostProcess {

    /** 解码后的原始框（类别用索引表示，尚未映射为标签文本）。 */
    data class RawBox(
        val classIndex: Int,
        val confidence: Float,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    /**
     * 解析 [4+numClasses, numAnchors] 的输出张量。
     *
     * YOLOv8 输出没有 objectness，每个 anchor 的 4 个通道是 cx/cy/w/h，
     * 其余通道直接是各类别分数；取最大分数作为该类别的置信度。
     */
    fun decode(
        out: Array<FloatArray>,
        scale: Float,
        dx: Float,
        dy: Float,
        origW: Int,
        origH: Int,
        confThreshold: Float,
        iouThreshold: Float = IOU_THRESHOLD,
    ): List<RawBox> {
        if (out.isEmpty()) return emptyList()
        val numClasses = out.size - 4
        val numAnchors = out[0].size
        val candidates = ArrayList<RawBox>(64)

        for (i in 0 until numAnchors) {
            var best = -Float.MAX_VALUE
            var bestIdx = -1
            for (c in 0 until numClasses) {
                val s = out[4 + c][i]
                if (s > best) { best = s; bestIdx = c }
            }
            if (best < confThreshold || bestIdx < 0) continue

            val cx = out[0][i]
            val cy = out[1][i]
            val w = out[2][i]
            val h = out[3][i]

            candidates += RawBox(
                classIndex = bestIdx,
                confidence = best,
                left = ((cx - w / 2f - dx) / scale / origW).coerceIn(0f, 1f),
                top = ((cy - h / 2f - dy) / scale / origH).coerceIn(0f, 1f),
                right = ((cx + w / 2f - dx) / scale / origW).coerceIn(0f, 1f),
                bottom = ((cy + h / 2f - dy) / scale / origH).coerceIn(0f, 1f),
            )
        }
        return nms(candidates, iouThreshold)
    }

    /** 逐类别 NMS：同类别且 IoU 超过阈值的低分框被抑制。 */
    fun nms(boxes: List<RawBox>, iouThreshold: Float): List<RawBox> {
        val sorted = boxes.sortedByDescending { it.confidence }
        val kept = ArrayList<RawBox>(sorted.size)
        for (b in sorted) {
            var keep = true
            for (k in kept) {
                if (k.classIndex != b.classIndex) continue
                if (iou(k, b) > iouThreshold) { keep = false; break }
            }
            if (keep) kept += b
        }
        return kept
    }

    fun iou(a: RawBox, b: RawBox): Float {
        val ix1 = max(a.left, b.left)
        val iy1 = max(a.top, b.top)
        val ix2 = min(a.right, b.right)
        val iy2 = min(a.bottom, b.bottom)
        val iw = ix2 - ix1
        val ih = iy2 - iy1
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    const val IOU_THRESHOLD = 0.45f
}