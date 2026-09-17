package com.visionhelper.store

import android.content.Context
import android.graphics.Bitmap
import com.visionhelper.vision.Detection
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一条记录：截图 + 识别结果 + 备注（对应产品目标"辅助记录工作时看到的信息"）。 */
data class RecordEntry(
    val id: String,
    val timestamp: Long,
    val sourceName: String,
    val detections: List<Detection>,
    val note: String,
    val imageFile: String,
) {
    val timeText: String
        get() = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))

    fun summary(): String =
        if (detections.isEmpty()) "无识别结果" else detections.joinToString("、") { it.labelZh }
}

/**
 * 记录库（v0.1 用文件系统：每条记录 = 一个 JPEG + 一个同名 JSON）。
 *
 * 选型说明：暂不引入 Room/SQLite —— 记录条数是"人工量级"（每天几十条），
 * 文件方案零依赖、可直接导出、便于用户拿走数据；Phase 3 若需要检索/统计再迁移 Room。
 */
class RecordStore(context: Context) {

    private val dir = File(context.filesDir, "records").apply { mkdirs() }
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    val count: Int get() = dir.listFiles { f -> f.name.endsWith(".json") }?.size ?: 0

    /**
     * 保存一条记录。
     *
     * @param annotated 已经把识别框画上去的位图（作为"记录下来的画面"）
     * @return 生成的记录
     */
    fun save(
        annotated: Bitmap,
        sourceName: String,
        detections: List<Detection>,
        note: String,
    ): RecordEntry {
        val ts = System.currentTimeMillis()
        val id = "rec_${stamp.format(Date(ts))}"
        val img = File(dir, "$id.jpg")
        img.outputStream().buffered().use { annotated.compress(Bitmap.CompressFormat.JPEG, 88, it) }

        val entry = RecordEntry(
            id = id,
            timestamp = ts,
            sourceName = sourceName,
            detections = detections,
            note = note,
            imageFile = img.name,
        )
        File(dir, "$id.json").writeText(toJson(entry))
        return entry
    }

    /** 按时间倒序读取最近的记录。 */
    fun list(limit: Int = 50): List<RecordEntry> {
        val files = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.name }
            ?: return emptyList()
        return files.take(limit).mapNotNull { f -> runCatching { parse(f.readText()) }.getOrNull() }
    }

    fun imageOf(entry: RecordEntry): File = File(dir, entry.imageFile)

    /** 导出全量记录为单个 JSON 数组文本（便于用户取出/上传）。 */
    fun exportJson(): String =
        list(limit = Int.MAX_VALUE).joinToString(",", "[", "]") { toJson(it) }

    private fun toJson(e: RecordEntry): String = buildString {
        append("{\"id\":\"").append(e.id).append("\",")
        append("\"ts\":").append(e.timestamp).append(',')
        append("\"time\":\"").append(e.timeText).append("\",")
        append("\"source\":\"").append(e.sourceName).append("\",")
        append("\"note\":\"").append(e.note.replace("\"", "'")).append("\",")
        append("\"image\":\"").append(e.imageFile).append("\",")
        append("\"objects\":[")
        e.detections.forEachIndexed { i, d -> if (i > 0) append(','); append(d.toJson()) }
        append("]}")
    }

    private fun parse(json: String): RecordEntry {
        val id = str(json, "id")
        val ts = num(json, "ts")
        val source = str(json, "source")
        val note = str(json, "note")
        val image = str(json, "image")
        return RecordEntry(id, ts, source, emptyList(), note, image)
    }

    private fun str(json: String, key: String): String {
        val i = json.indexOf("\"$key\":\"")
        if (i < 0) return ""
        val start = i + key.length + 4
        val end = json.indexOf('"', start)
        return if (end < 0) "" else json.substring(start, end)
    }

    private fun num(json: String, key: String): Long {
        val i = json.indexOf("\"$key\":")
        if (i < 0) return 0L
        val start = i + key.length + 3
        var end = start
        while (end < json.length && json[end].isDigit()) end++
        return json.substring(start, end).toLongOrNull() ?: 0L
    }
}