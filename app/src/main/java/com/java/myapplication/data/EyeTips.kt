package com.java.myapplication.data

// 健康用眼知识卡片
data class EyeTip(
    val title: String,
    val content: String,
    val emoji: String
)

object EyeTips {
    val tips = listOf(
        EyeTip(
            emoji = "🌿",
            title = "20-20-20 法则",
            content = "每用眼 20 分钟，抬头眺望 20 英尺（约 6 米）外的远方 20 秒，让睫状肌彻底放松，缓解视疲劳。"
        ),
        EyeTip(
            emoji = "💧",
            title = "记得眨眼",
            content = "专注时眨眼频率会从每分钟 20 次降到 7 次。有意识地多眨眼，能有效补充泪膜，预防干眼症。"
        ),
        EyeTip(
            emoji = "💡",
            title = "屏幕亮度",
            content = "屏幕亮度应与环境光接近，避免在黑暗中看强光屏幕。夜间请开启护眼/夜间模式，减少蓝光刺激。"
        ),
        EyeTip(
            emoji = "📏",
            title = "保持距离",
            content = "眼睛与屏幕距离应保持在 50-70 厘米，屏幕顶端略低于视线水平，让眼睛自然向下看。"
        ),
        EyeTip(
            emoji = "🍃",
            title = "远眺绿色",
            content = "远眺绿色植物有助于放松眼睛。绿色光波长适中，落在视网膜前方，能让紧张的睫状肌得到休息。"
        ),
        EyeTip(
            emoji = "🥕",
            title = "营养护眼",
            content = "多摄入富含维生素 A、叶黄素、花青素的食物，如胡萝卜、蓝莓、菠菜，有助于保护视网膜。"
        ),
        EyeTip(
            emoji = "☀️",
            title = "户外活动",
            content = "每天保证 2 小时户外活动，自然光照能刺激多巴胺分泌，是预防和控制近视最有效的方法之一。"
        ),
        EyeTip(
            emoji = "🧘",
            title = "眼保健操",
            content = "休息时做一做眼保健操或远近距离交替注视练习，促进眼周血液循环，缓解眼部疲劳。"
        ),
        EyeTip(
            emoji = "🛏️",
            title = "充足睡眠",
            content = "睡眠不足会让眼睛无法充分休息。成人每天应保证 7-8 小时睡眠，儿童青少年需要更多。"
        ),
        EyeTip(
            emoji = "👁️",
            title = "定期检查",
            content = "建议每年定期进行视力检查。若出现看远模糊、眼睛干涩酸痛等症状，应及时就医。"
        ),
        EyeTip(
            emoji = "🌫️",
            title = "警惕干眼",
            content = "干眼表现为干涩、异物感、畏光、视物波动。空调房与暖气房空气干燥，建议配合加湿器并刻意增加眨眼次数。"
        ),
        EyeTip(
            emoji = "🎚️",
            title = "调低色温",
            content = "把屏幕色温调向暖黄（约 4000K 以下），可减少短波蓝光比例。夜晚尤其推荐，帮助减轻刺眼感与入睡干扰。"
        ),
        EyeTip(
            emoji = "⏳",
            title = "间歇远眺",
            content = "长时间剪辑、写代码时，可以把「远眺」拆成更小的片段：每 10 分钟抬眼看向窗外 10 秒，累积效果同样可观。"
        ),
        EyeTip(
            emoji = "📐",
            title = "坐姿与视线",
            content = "背部贴靠椅背，屏幕中心略低于水平视线 15-20°，双肩放松。视线平视或略微下俯，眼睑覆盖更充分，更不容易干。"
        ),
        EyeTip(
            emoji = "🚶",
            title = "起身活动",
            content = "久坐会让颈肩与眼部同时紧张。每 45 分钟起身走动 1-2 分钟，活动颈肩，眼睛与身体一起松一口气。"
        ),
        EyeTip(
            emoji = "🌙",
            title = "睡前少屏",
            content = "睡前 1 小时尽量远离手机与电脑。屏幕强光会抑制褪黑素分泌，既伤眼也影响睡眠质量。"
        ),
        EyeTip(
            emoji = "🫧",
            title = "热敷放松",
            content = "感到眼睛干涩时，可用 40℃ 左右温热毛巾敷眼 5 分钟，促进睑板腺分泌，缓解干涩与酸胀。"
        )
    )

    // 随机取一条（避免连续重复）
    private var lastIndex = -1
    fun random(): EyeTip {
        if (tips.isEmpty()) return EyeTip("", "", "")
        var idx = (0 until tips.size).random()
        if (tips.size > 1 && idx == lastIndex) {
            idx = (idx + 1) % tips.size
        }
        lastIndex = idx
        return tips[idx]
    }

    // 按索引取（后台提醒恢复时使用，保证与界面展示一致）
    fun byIndex(index: Int): EyeTip {
        if (index in tips.indices) return tips[index]
        return random()
    }

    // 取下一条的索引（顺序轮换，不依赖内存状态，可跨进程恢复）
    fun nextIndex(current: Int): Int {
        if (tips.isEmpty()) return -1
        return ((current + 1).coerceAtLeast(0)) % tips.size
    }
}