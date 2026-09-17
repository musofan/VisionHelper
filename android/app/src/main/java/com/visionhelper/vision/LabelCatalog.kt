package com.visionhelper.vision

import android.graphics.Color

/**
 * 标签词表：把模型输出的英文类别映射为中文业务标签，并分配稳定的显示颜色。
 *
 * COCO-80 覆盖日常办公/生活场景的主要物体；后续接入自定义训练模型时，
 * 只需在这里扩展映射即可（"打标签"能力的产品化出口）。
 */
object LabelCatalog {

    /** COCO-80 顺序与 YOLOv8 输出索引严格对应，不可改动顺序。 */
    val COCO80 = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
        "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
        "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
        "toothbrush"
    )

    private val ZH = mapOf(
        "person" to "人", "bicycle" to "自行车", "car" to "汽车", "motorcycle" to "摩托车",
        "airplane" to "飞机", "bus" to "公交车", "train" to "火车", "truck" to "卡车",
        "boat" to "船", "traffic light" to "红绿灯", "fire hydrant" to "消防栓",
        "stop sign" to "停车标志", "parking meter" to "停车计时器", "bench" to "长椅",
        "bird" to "鸟", "cat" to "猫", "dog" to "狗", "horse" to "马", "sheep" to "羊",
        "cow" to "牛", "elephant" to "大象", "bear" to "熊", "zebra" to "斑马",
        "giraffe" to "长颈鹿", "backpack" to "背包", "umbrella" to "雨伞", "handbag" to "手提包",
        "tie" to "领带", "suitcase" to "行李箱", "frisbee" to "飞盘", "skis" to "滑雪板",
        "snowboard" to "单板滑雪", "sports ball" to "球", "kite" to "风筝",
        "baseball bat" to "棒球棒", "baseball glove" to "棒球手套", "skateboard" to "滑板",
        "surfboard" to "冲浪板", "tennis racket" to "网球拍", "bottle" to "瓶子",
        "wine glass" to "酒杯", "cup" to "杯子", "fork" to "叉子", "knife" to "刀",
        "spoon" to "勺子", "bowl" to "碗", "banana" to "香蕉", "apple" to "苹果",
        "sandwich" to "三明治", "orange" to "橙子", "broccoli" to "西兰花", "carrot" to "胡萝卜",
        "hot dog" to "热狗", "pizza" to "披萨", "donut" to "甜甜圈", "cake" to "蛋糕",
        "chair" to "椅子", "couch" to "沙发", "potted plant" to "盆栽", "bed" to "床",
        "dining table" to "餐桌", "toilet" to "马桶", "tv" to "显示器", "laptop" to "笔记本电脑",
        "mouse" to "鼠标", "remote" to "遥控器", "keyboard" to "键盘", "cell phone" to "手机",
        "microwave" to "微波炉", "oven" to "烤箱", "toaster" to "烤面包机", "sink" to "水槽",
        "refrigerator" to "冰箱", "book" to "书", "clock" to "时钟", "vase" to "花瓶",
        "scissors" to "剪刀", "teddy bear" to "玩偶", "hair drier" to "吹风机",
        "toothbrush" to "牙刷"
    )

    fun zhOf(labelEn: String): String = ZH[labelEn] ?: labelEn

    /** 同一类别永远得到同一颜色，方便用户在多帧之间做视觉追踪。 */
    fun colorOf(labelEn: String): Int {
        val palette = intArrayOf(
            Color.parseColor("#FF5252"), Color.parseColor("#FF8A65"),
            Color.parseColor("#FFD54F"), Color.parseColor("#81C784"),
            Color.parseColor("#4DD0E1"), Color.parseColor("#7986CB"),
            Color.parseColor("#BA68C8"), Color.parseColor("#F06292"),
            Color.parseColor("#4FC3F7"), Color.parseColor("#AED581")
        )
        var h = 0
        for (c in labelEn) h = h * 31 + c.code
        return palette[(h and 0x7fffffff) % palette.size]
    }
}