package com.visionhelper.probe

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Camera2 探针：验证「取景 → 抓帧 → 落盘」这条记录链路在目标设备上是否可用。
 *
 * 设计意图：
 *  - Quest 3/3S：通透相机经 Passthrough Camera API 暴露为 Camera2 设备，
 *    需要 horizonos.permission.HEADSET_CAMERA（Meta 签名权限，Horizon OS v74+）。
 *  - Pico / AI 眼镜 / 手机：走标准 android.permission.CAMERA，代码完全一致。
 */
object CameraProbe {

    fun enumerate(ctx: Context): String {
        val sb = StringBuilder()
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ids = cm.cameraIdList
            sb.append("相机数量=${ids.size} ids=${ids.joinToString(",")}\n")
            for (id in ids) {
                val c = cm.getCameraCharacteristics(id)
                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraMetadata.LENS_FACING_FRONT -> "front"
                    CameraMetadata.LENS_FACING_BACK -> "back"
                    CameraMetadata.LENS_FACING_EXTERNAL -> "external"
                    else -> "?"
                }
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val jpeg = map?.getOutputSizes(ImageFormat.JPEG)
                    ?.joinToString(",") { "${it.width}x${it.height}" }
                sb.append("  id=$id facing=$facing jpeg=[$jpeg]\n")
            }
        } catch (e: Exception) {
            sb.append("枚举相机失败: ${e.javaClass.simpleName}: ${e.message}\n")
        }
        return sb.toString()
    }

    fun deviceSummary(): String = buildString {
        append("机型=${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("Android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        append("ABI=${Build.SUPPORTED_ABIS.joinToString(",")}\n")
        append("SoC=${Build.SOC_MODEL} 硬件=${Build.HARDWARE}")
    }

    fun hasPermission(ctx: Context, perm: String): Boolean =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    fun missingPermissions(activity: Activity, perms: Array<String>): List<String> =
        perms.filter { !hasPermission(activity, it) }

    /** 抓帧结果：失败时 message 携带可直接显示给用户的原因（远端设备调试友好）。 */
    data class FrameResult(val bytes: ByteArray?, val message: String) {
        // ByteArray 需要手写 equals/hashCode，否则 data class 比较会退化为引用比较
        override fun equals(other: Any?): Boolean =
            other is FrameResult && message == other.message &&
                (bytes?.contentEquals(other.bytes ?: ByteArray(0)) ?: (other.bytes == null))

        override fun hashCode(): Int = 31 * message.hashCode() + (bytes?.contentHashCode() ?: 0)
    }

    /**
     * 抓取一帧的统一实现：Camera2 → JPEG 字节。
     *
     * @param cameraId 指定相机；null 表示用第一个可用相机
     * @param targetMaxWidth 期望的最大宽度，用于挑一个不过大的输出尺寸（避免 4K 帧拖慢推理）
     */
    fun captureFrame(
        ctx: Context,
        cameraId: String? = null,
        targetMaxWidth: Int = 1280,
        timeoutMs: Long = 6000,
    ): FrameResult {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = try { cm.cameraIdList } catch (e: Exception) {
            return FrameResult(null, "读取相机列表失败: ${e.message}")
        }
        if (ids.isEmpty()) {
            return FrameResult(null, "无可用相机（Quest 需先授予 HEADSET_CAMERA）")
        }

        val camId = cameraId?.takeIf { ids.contains(it) } ?: ids.first()
        val background = HandlerThread("camera-frame").also { it.start() }
        val handler = Handler(background.looper)
        val latch = CountDownLatch(1)
        var bytes: ByteArray? = null
        var message = "未收到帧"
        var device: CameraDevice? = null
        var reader: ImageReader? = null

        try {
            val chars = cm.getCameraCharacteristics(camId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.JPEG)
            val size: Size = sizes?.filter { it.width <= targetMaxWidth }
                ?.minByOrNull { it.width.toLong() * it.height }
                ?: sizes?.minByOrNull { it.width.toLong() * it.height }
                ?: Size(640, 480)

            val imgReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
            reader = imgReader
            imgReader.setOnImageAvailableListener({ r ->
                r.acquireLatestImage()?.use { img ->
                    val buf = img.planes[0].buffer
                    val arr = ByteArray(buf.remaining())
                    buf.get(arr)
                    bytes = arr
                    message = "抓帧成功 ${img.width}x${img.height} bytes=${arr.size}"
                }
                latch.countDown()
            }, handler)

            val stateCb = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    val sessionCb = object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            try {
                                val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                req.addTarget(imgReader.surface)
                                session.capture(req.build(), null, handler)
                            } catch (e: Exception) {
                                message = "capture 失败: ${e.message}"
                                latch.countDown()
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            message = "会话配置失败"
                            latch.countDown()
                        }
                    }
                    try {
                        camera.createCaptureSession(listOf(imgReader.surface), sessionCb, handler)
                    } catch (e: Exception) {
                        message = "createCaptureSession 失败: ${e.message}"
                        latch.countDown()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) { camera.close() }

                override fun onError(camera: CameraDevice, error: Int) {
                    message = "相机错误 code=$error"
                    camera.close()
                    latch.countDown()
                }
            }

            try {
                @Suppress("DEPRECATION")
                cm.openCamera(camId, stateCb, handler)
            } catch (e: SecurityException) {
                return FrameResult(null, "缺少相机权限: ${e.message}（Quest 需 horizonos.permission.HEADSET_CAMERA）")
            }
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) message = "超时 ${timeoutMs}ms 未取到帧"
        } catch (e: Exception) {
            message = "异常: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            runCatching { reader?.close() }
            runCatching { device?.close() }
            background.quitSafely()
        }
        return FrameResult(bytes, message)
    }

    /** 便捷方法：只要字节流（输入源层使用）。 */
    fun captureJpeg(
        ctx: Context,
        cameraId: String? = null,
        targetMaxWidth: Int = 1280,
        timeoutMs: Long = 6000,
    ): ByteArray? = captureFrame(ctx, cameraId, targetMaxWidth, timeoutMs).bytes

    /** 抓取单帧 JPEG 并落盘，返回耗时与分辨率信息（对应产品里的「截图记录」能力）。 */
    fun captureStill(ctx: Context, outFile: File, timeoutMs: Long = 8000): String {
        val t0 = System.currentTimeMillis()
        val res = captureFrame(ctx, null, 1920, timeoutMs)
        val bytes = res.bytes
        if (bytes != null) {
            outFile.parentFile?.mkdirs()
            outFile.writeBytes(bytes)
            return "${res.message} 文件=${outFile.name} | 耗时=${System.currentTimeMillis() - t0}ms"
        }
        return "${res.message} | 耗时=${System.currentTimeMillis() - t0}ms"
    }

    /** 供后续接入 GPU/CPU 推理管线使用：可复用的预览 target。 */
    fun createPreviewTarget(): SurfaceTexture = SurfaceTexture(false)
}