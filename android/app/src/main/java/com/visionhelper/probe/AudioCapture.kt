package com.visionhelper.probe

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import kotlin.math.sqrt

/**
 * 音频探针：16kHz / 单声道 / PCM16 采集，落盘为 WAV。
 *
 * 该参数与端侧 ASR 模型（sherpa-onnx SenseVoice / Paraformer / Whisper）完全对齐，
 * 采集结果可直接喂给识别器，无需重采样。同时统计 RMS 用于做 VAD（静音裁剪）。
 */
object AudioCapture {

    private const val TAG = "AudioCapture"
    const val SAMPLE_RATE = 16000
    const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    @SuppressLint("MissingPermission")
    fun recordToWav(outFile: File, seconds: Int = 5): String {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) return "设备不支持 16kHz 单声道采集"
        val bufSize = maxOf(minBuf, SAMPLE_RATE * 2)  // 至少 0.5s
        val total = SAMPLE_RATE * seconds
        val pcm = ShortArray(total)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING, bufSize)

        return try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) return "AudioRecord 初始化失败"
            rec.startRecording()
            var read = 0
            while (read < total) {
                val n = rec.read(pcm, read, minOf(bufSize / 2, total - read))
                if (n <= 0) break
                read += n
            }
            rec.stop()
            outFile.parentFile?.mkdirs()
            writeWav(outFile, pcm, read)
            val rms = sqrt(pcm.take(read).sumOf { (it.toDouble() / 32768.0) * (it.toDouble() / 32768.0) } / read)
            "录音成功 ${"%.2f".format(read.toDouble() / SAMPLE_RATE)}s rms=%.4f 文件=${outFile.name}"
                .format(rms)
        } catch (e: Exception) {
            Log.e(TAG, "record failed", e)
            "录音异常: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            rec.release()
        }
    }

    private fun writeWav(file: File, pcm: ShortArray, samples: Int) {
        val byteRate = SAMPLE_RATE * 2
        file.outputStream().buffered().use { os ->
            fun i32(v: Int) = byteArrayOf(
                (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
                ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte()
            )
            fun i16(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())

            os.write("RIFF".toByteArray()); os.write(i32(36 + samples * 2)); os.write("WAVE".toByteArray())
            os.write("fmt ".toByteArray()); os.write(i32(16)); os.write(i16(1)); os.write(i16(1))
            os.write(i32(SAMPLE_RATE)); os.write(i32(byteRate)); os.write(i16(2)); os.write(i16(16))
            os.write("data".toByteArray()); os.write(i32(samples * 2))
            val bytes = ByteArray(samples * 2)
            for (i in 0 until samples) {
                bytes[i * 2] = (pcm[i].toInt() and 0xff).toByte()
                bytes[i * 2 + 1] = ((pcm[i].toInt() shr 8) and 0xff).toByte()
            }
            os.write(bytes)
        }
    }
}
