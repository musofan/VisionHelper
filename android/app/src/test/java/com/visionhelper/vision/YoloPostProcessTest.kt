package com.visionhelper.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * YOLOv8 后处理单元测试。
 *
 * 期望值由 Python 参考实现（`tests/feasibility/expect.py`，与 `test_yolo_onnx.py` 同源）生成，
 * 用来锁死移植到 Kotlin/Android 时最容易出错的两件事：
 *   1. letterbox 反算（含灰边偏移与 clamp）
 *   2. 逐类别 NMS 抑制
 * 这两条一旦错了，头显里看到的框就会整体偏移或出现重复框。
 */
class YoloPostProcessTest {

    private data class Anchor(
        val cls: Int, val conf: Float, val cx: Float, val cy: Float, val w: Float, val h: Float,
    )

    /** 把若干 anchor 写进一个 [84, 8400] 的零张量（其余 anchor 分数为 0，会被阈值过滤）。 */
    private fun buildOut(cases: List<Anchor>): Array<FloatArray> {
        val out = Array(84) { FloatArray(8400) }
        cases.forEachIndexed { i, a ->
            out[0][i] = a.cx
            out[1][i] = a.cy
            out[2][i] = a.w
            out[3][i] = a.h
            out[4 + a.cls][i] = a.conf
        }
        return out
    }

    @Test
    fun `decode 在 640 原图上按比例映射并抑制重叠同类框`() {
        // anchor0 person 0.90 与 anchor1 person 0.80 高度重叠（IoU≈0.68 > 0.45）→ 只保留 anchor0
        // anchor3 car 0.20 低于阈值 0.35 → 丢弃
        val out = buildOut(
            listOf(
                Anchor(0, 0.90f, 100f, 100f, 50f, 50f),
                Anchor(0, 0.80f, 105f, 105f, 50f, 50f),
                Anchor(1, 0.70f, 300f, 300f, 100f, 100f),
                Anchor(2, 0.20f, 500f, 500f, 60f, 60f),
            )
        )

        val boxes = YoloPostProcess.decode(out, 1f, 0f, 0f, 640, 640, 0.35f)

        assertEquals("应保留 2 个框（1 个被 NMS 抑制，1 个低于阈值）", 2, boxes.size)
        assertEquals(0, boxes[0].classIndex)
        assertEquals(0.90f, boxes[0].confidence, 1e-5f)
        assertEquals(0.1171875f, boxes[0].left, 1e-5f)
        assertEquals(0.1171875f, boxes[0].top, 1e-5f)
        assertEquals(0.1953125f, boxes[0].right, 1e-5f)
        assertEquals(0.1953125f, boxes[0].bottom, 1e-5f)

        assertEquals(1, boxes[1].classIndex)
        assertEquals(0.390625f, boxes[1].left, 1e-5f)
        assertEquals(0.546875f, boxes[1].right, 1e-5f)
    }

    @Test
    fun `decode 正确反算 letterbox 灰边偏移并 clamp 到画面内`() {
        // 1280x640 原图 → scale=0.5, letterbox 后 640x320，上下各填 160 像素
        val out = buildOut(
            listOf(
                Anchor(0, 0.75f, 320f, 200f, 100f, 100f),
                Anchor(3, 0.55f, 600f, 320f, 80f, 80f),
            )
        )

        val boxes = YoloPostProcess.decode(out, 0.5f, 0f, 160f, 1280, 640, 0.35f)

        assertEquals(2, boxes.size)
        // 第一个框上边界落在灰边里 → 必须被 clamp 到 0，否则叠加层会画到画面外
        assertEquals(0.421875f, boxes[0].left, 1e-5f)
        assertEquals(0.0f, boxes[0].top, 1e-5f)
        assertEquals(0.578125f, boxes[0].right, 1e-5f)
        assertEquals(0.28125f, boxes[0].bottom, 1e-5f)

        // 第二个框右边界超出画面 → clamp 到 1
        assertEquals(0.875f, boxes[1].left, 1e-5f)
        assertEquals(0.375f, boxes[1].top, 1e-5f)
        assertEquals(1.0f, boxes[1].right, 1e-5f)
        assertEquals(0.625f, boxes[1].bottom, 1e-5f)
    }

    @Test
    fun `iou 与 Python 参考实现一致`() {
        val a = YoloPostProcess.RawBox(0, 0.9f, 75f / 640f, 75f / 640f, 125f / 640f, 125f / 640f)
        val b = YoloPostProcess.RawBox(0, 0.8f, 80f / 640f, 80f / 640f, 130f / 640f, 130f / 640f)
        assertEquals(0.680672f, YoloPostProcess.iou(a, b), 1e-5f)
    }

    @Test
    fun `低于阈值的输入返回空列表`() {
        val out = buildOut(listOf(Anchor(5, 0.34f, 100f, 100f, 50f, 50f)))
        assertTrue(YoloPostProcess.decode(out, 1f, 0f, 0f, 640, 640, 0.35f).isEmpty())
    }
}