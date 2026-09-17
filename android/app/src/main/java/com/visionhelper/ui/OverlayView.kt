package com.visionhelper.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.visionhelper.vision.Detection
import com.visionhelper.vision.LabelCatalog
import kotlin.math.min

/**
 * 识别结果叠加视图：绘制画面 + 物体框 + 中文标签 + 置信度。
 *
 * 关键实现：归一化坐标（0..1）按"fitCenter 后的图像显示矩形"映射，
 * 因此无论输入是 810x1080 的演示图还是 1280x960 的相机帧，框都精确贴合。
 * 该视图同时充当"截图入档"的渲染器 —— 把本视图内容画进 Bitmap 就得到带标注的记录图。
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var bitmap: Bitmap? = null
        set(value) { field = value; invalidate() }

    var detections: List<Detection> = emptyList()
        set(value) { field = value; invalidate() }

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = dp(14f)
        isFakeBoldText = true
    }
    private var destRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap
        if (bmp == null || bmp.isRecycled) {
            canvas.drawColor(Color.parseColor("#101418"))
            return
        }
        canvas.drawColor(Color.BLACK)
        computeDestRect(bmp)
        canvas.drawBitmap(bmp, null, destRect, null)

        for (d in detections) {
            val color = LabelCatalog.colorOf(d.labelEn)
            val l = destRect.left + d.left * destRect.width()
            val t = destRect.top + d.top * destRect.height()
            val r = destRect.left + d.right * destRect.width()
            val b = destRect.top + d.bottom * destRect.height()

            boxPaint.color = color
            canvas.drawRect(l, t, r, b, boxPaint)

            val text = "${d.labelZh} ${(d.confidence * 100).toInt()}%"
            val tw = labelTextPaint.measureText(text)
            val th = labelTextPaint.textSize
            val labelTop = if (t - th - dp(8f) < 0f) t else t - th - dp(8f)

            labelBgPaint.color = color
            canvas.drawRoundRect(
                l, labelTop, r.coerceAtLeast(l + tw + dp(12f)), labelTop + th + dp(6f),
                dp(3f), dp(3f), labelBgPaint
            )
            canvas.drawText(text, l + dp(6f), labelTop + th, labelTextPaint)
        }
    }

    /** fitCenter：保持画面比例完整显示，这也是坐标映射的唯一依据。 */
    private fun computeDestRect(bmp: Bitmap) {
        val vw = width.toFloat()
        val vh = height.toFloat()
        val scale = min(vw / bmp.width, vh / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = (vw - w) / 2f
        val top = (vh - h) / 2f
        destRect.set(left, top, left + w, top + h)
    }

    /**
     * 把当前"画面 + 标注"渲染成一张位图，用于记录入档。
     * 尺寸与视图一致，因此记录下来的画面与用户看到的完全一致。
     */
    fun renderToBitmap(): Bitmap {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(out))
        return out
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}