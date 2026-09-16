package com.visionhelper.probe

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * VisionHelper 端侧能力探针。
 *
 * 目的：在真实 / 目标设备上快速回答四个问题：
 *   1. 设备暴露了几个相机？分辨率是多少？（Quest 通透相机 / Pico / 眼镜）
 *   2. 能否抓一帧并存盘？（截图记录链路）
 *   3. 能否以 16kHz 单声道录音？（端侧 ASR 输入链路）
 *   4. 设备 ROM / API / ABI / SoC 是什么？（决定模型与推理后端选型）
 */
class MainActivity : Activity() {

    private lateinit var logView: TextView
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(TextView(this).apply {
            text = "VisionHelper 端侧能力探针 v0.1"
            textSize = 20f
        })
        root.addView(TextView(this).apply {
            text = CameraProbe.deviceSummary()
            textSize = 13f
        })

        fun addButton(label: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener { action() }
            })
        }

        addButton("① 申请权限 (CAMERA / RECORD_AUDIO)") {
            val missing = CameraProbe.missingPermissions(this, perms)
            if (missing.isEmpty()) log("权限已全部授予") else requestPermissions(missing.toTypedArray(), 1001)
        }
        addButton("② 枚举相机") {
            log(CameraProbe.enumerate(this))
        }
        addButton("③ 抓取单帧并落盘") {
            val out = File(filesDir, "captures/${stamp()}.jpg")
            io.execute { log("截图: " + CameraProbe.captureStill(this, out)) }
        }
        addButton("④ 录制 5s 16kHz 音频") {
            val out = File(filesDir, "audio/${stamp()}.wav")
            io.execute { log("录音: " + AudioCapture.recordToWav(out, 5)) }
        }
        addButton("⑤ 清空日志") { logView.text = "" }

        val logText = TextView(this).apply {
            textSize = 12f
            setPadding(0, 16, 0, 0)
        }
        logView = logText
        root.addView(ScrollView(this).apply {
            addView(logText)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        setContentView(root)
        log("文件目录: ${filesDir.absolutePath}")
        log(CameraProbe.enumerate(this))
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun log(msg: String) {
        ui.post { logView.append("$msg\n") }
    }
}
