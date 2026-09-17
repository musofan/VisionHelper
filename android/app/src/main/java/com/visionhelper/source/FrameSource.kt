package com.visionhelper.source

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.visionhelper.probe.CameraProbe
import java.util.concurrent.Executors

/**
 * 画面输入源抽象。
 *
 * 产品背景（调研结论）：Meta Quest / Pico 的透视摄像头不向第三方 APK 开放原始帧，
 * 因此"识别输入从哪来"必须做成可插拔的多源结构，v0.1 提供三种：
 *   1. DemoFrameSource   —— 内置演示图，保证在任何设备（无相机权限/无相机）都能体验识别效果
 *   2. CameraFrameSource —— Camera2 抓帧（手机 / AI 眼镜 / 已授 HEADSET_CAMERA 的 Quest）
 *   3. 相册选图          —— 由 UI 层直接提供 Bitmap（用户主动"记录看到的画面"）
 *
 * 后续接入外接相机 RTSP 流时，只需新增一个实现，识别与 UI 层不需要改动。
 */
interface FrameSource {
    val name: String
    val description: String

    /** 异步获取一帧；失败时回调 null 并给出原因（写入 log）。 */
    fun acquire(context: Context, onResult: (Bitmap?) -> Unit, log: (String) -> Unit)

    /** 连续模式下的建议间隔（毫秒）；0 表示不支持连续。 */
    val continuousIntervalMs: Long
}

/** 内置演示图源：assets/demo_bus.jpg（含 person/bus 等目标，用于验证"加框打标签"）。 */
class DemoFrameSource(
    private val assetName: String = "demo_bus.jpg",
) : FrameSource {
    override val name = "演示图"
    override val description = "内置示例画面（无需相机权限）"
    override val continuousIntervalMs = 0L

    private var cached: Bitmap? = null

    override fun acquire(context: Context, onResult: (Bitmap?) -> Unit, log: (String) -> Unit) {
        cached?.let { onResult(it); return }
        try {
            val bmp = context.assets.open(assetName).use { BitmapFactory.decodeStream(it) }
            if (bmp == null) {
                log("演示图解码失败: $assetName")
                onResult(null)
            } else {
                cached = bmp
                log("已载入演示图 ${bmp.width}x${bmp.height}")
                onResult(bmp)
            }
        } catch (e: Exception) {
            log("载入演示图异常: ${e.javaClass.simpleName}: ${e.message}")
            onResult(null)
        }
    }
}

/**
 * Camera2 输入源。
 *
 * 每次 acquire 抓一帧 JPEG 并解码为 Bitmap。Quest 上需要 horizonos.permission.HEADSET_CAMERA
 * （Meta 签名权限），否则相机列表为空 —— 此时产品自动降级为演示图/相册源。
 */
class CameraFrameSource(
    private val cameraId: String? = null,
    private val targetMaxWidth: Int = 1280,
) : FrameSource {
    override val name = "相机"
    override val description = "Camera2 抓帧（手机/眼镜/已授权头显）"
    override val continuousIntervalMs = 1500L

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "frame-camera").apply { priority = Thread.NORM_PRIORITY }
    }

    override fun acquire(context: Context, onResult: (Bitmap?) -> Unit, log: (String) -> Unit) {
        io.execute {
            try {
                val jpeg = CameraProbe.captureJpeg(context, cameraId, targetMaxWidth)
                if (jpeg == null) {
                    log("相机抓帧失败（无相机或缺少权限）")
                    onResult(null)
                    return@execute
                }
                val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                if (bmp == null) log("JPEG 解码失败 bytes=${jpeg.size}") else log("相机帧 ${bmp.width}x${bmp.height}")
                onResult(bmp)
            } catch (e: Exception) {
                Log.e("CameraFrameSource", "acquire failed", e)
                log("相机异常: ${e.javaClass.simpleName}: ${e.message}")
                onResult(null)
            }
        }
    }
}