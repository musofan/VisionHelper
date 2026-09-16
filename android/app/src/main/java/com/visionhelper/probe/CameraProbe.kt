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

    /** 抓取单帧 JPEG 并落盘，返回耗时与分辨率信息（对应产品里的「截图记录」能力）。 */
    fun captureStill(ctx: Context, outFile: File, timeoutMs: Long = 8000): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = try { cm.cameraIdList } catch (e: Exception) { return "读取相机列表失败: ${e.message}" }
        if (ids.isEmpty()) return "无可用相机（Quest 需先授予 HEADSET_CAMERA）"

        val camId = ids.first()
        val background = HandlerThread("camera-probe").also { it.start() }
        val handler = Handler(background.looper)
        val latch = CountDownLatch(1)
        var result = "未收到帧"
        val t0 = System.currentTimeMillis()

        try {
            val chars = cm.getCameraCharacteristics(camId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.JPEG)
            val size: Size = sizes?.firstOrNull { it.width <= 1920 && it.height <= 1440 }
                ?: sizes?.firstOrNull() ?: Size(640, 480)

            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
            reader.setOnImageAvailableListener({ r ->
                r.acquireLatestImage()?.use { img ->
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                    outFile.parentFile?.mkdirs()
                    outFile.writeBytes(bytes)
                    result = "抓帧成功 ${img.width}x${img.height} bytes=${bytes.size} 文件=${outFile.name}"
                }
                latch.countDown()
            }, handler)

            val stateCb = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    val sessionCb = object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            try {
                                val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                req.addTarget(reader.surface)
                                session.capture(req.build(), null, handler)
                            } catch (e: Exception) {
                                result = "capture 失败: ${e.message}"; latch.countDown()
                            }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            result = "会话配置失败"; latch.countDown()
                        }
                    }
                    try {
                        camera.createCaptureSession(listOf(reader.surface), sessionCb, handler)
                    } catch (e: Exception) {
                        result = "createCaptureSession 失败: ${e.message}"; latch.countDown()
                    }
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) {
                    result = "相机错误 code=$error"; camera.close(); latch.countDown()
                }
            }

            try {
                @Suppress("DEPRECATION")
                cm.openCamera(camId, stateCb, handler)
            } catch (e: SecurityException) {
                return "缺少相机权限: ${e.message}（Quest 需 horizonos.permission.HEADSET_CAMERA）"
            }
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) result = "超时 ${timeoutMs}ms 未取到帧"
        } catch (e: Exception) {
            result = "异常: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            background.quitSafely()
        }
        return "$result | 耗时=${System.currentTimeMillis() - t0}ms"
    }

    /** 供后续接入 GPU/CPU 推理管线使用：可复用的预览 target。 */
    fun createPreviewTarget(): SurfaceTexture = SurfaceTexture(false)
}