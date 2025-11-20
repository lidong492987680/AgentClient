package com.example.agentclient.core

/**
 * 行为配置文件 - 精简实用版
 * 定义不同"人设"的操作特征
 */
data class BehaviorProfile(
    val name: String,
    val description: String = "",

    // 点击行为参数（毫秒）
    val baseTapDelayMs: Long = 200,
    val tapDelayJitterMs: Long = 100,

    // 滑动行为参数（毫秒）
    val swipeBaseDurationMs: Long = 400,
    val swipeDurationJitterMs: Long = 150,

    // 位置偏移（像素）
    val positionOffsetPx: Int = 10,

    // 犹豫行为
    val hesitateProbability: Float = 0.1f,
    val hesitateMinMs: Long = 500,
    val hesitateMaxMs: Long = 2000,

    // 阅读速度（字符/秒）
    val readingSpeedCharsPerSec: Float = 10f,
    val readingSpeedJitter: Float = 0.2f,

    // 误操作概率
    val misTapProbability: Float = 0.02f,

    // 轨迹相关
    val trajectoryPoints: Int = 20,
    val bezierControlOffset: Float = 50f,

    // 思考时间（毫秒）
    val thinkingMinMs: Long = 300,
    val thinkingMaxMs: Long = 1000
) {
    companion object {
        // 预定义的行为配置

        val FAST_YOUNG = BehaviorProfile(
            name = "FAST_YOUNG",
            description = "年轻人快速操作型",
            baseTapDelayMs = 150,
            tapDelayJitterMs = 100,
            swipeBaseDurationMs = 300,
            swipeDurationJitterMs = 100,
            positionOffsetPx = 8,
            hesitateProbability = 0.05f,
            hesitateMinMs = 500,
            hesitateMaxMs = 1200,
            readingSpeedCharsPerSec = 15f,
            readingSpeedJitter = 0.15f,
            misTapProbability = 0.01f,
            trajectoryPoints = 20,
            bezierControlOffset = 30f,
            thinkingMinMs = 200,
            thinkingMaxMs = 600
        )

        val SLOW_CAREFUL = BehaviorProfile(
            name = "SLOW_CAREFUL",
            description = "慢速谨慎型",
            baseTapDelayMs = 300,
            tapDelayJitterMs = 200,
            swipeBaseDurationMs = 500,
            swipeDurationJitterMs = 150,
            positionOffsetPx = 5,
            hesitateProbability = 0.25f,
            hesitateMinMs = 1000,
            hesitateMaxMs = 3000,
            readingSpeedCharsPerSec = 8f,
            readingSpeedJitter = 0.25f,
            misTapProbability = 0.005f,
            trajectoryPoints = 30,
            bezierControlOffset = 20f,
            thinkingMinMs = 500,
            thinkingMaxMs = 1500
        )

        val CLUMSY = BehaviorProfile(
            name = "CLUMSY",
            description = "笨拙操作型",
            baseTapDelayMs = 250,
            tapDelayJitterMs = 300,
            swipeBaseDurationMs = 450,
            swipeDurationJitterMs = 200,
            positionOffsetPx = 15,
            hesitateProbability = 0.20f,
            hesitateMinMs = 800,
            hesitateMaxMs = 2500,
            readingSpeedCharsPerSec = 7f,
            readingSpeedJitter = 0.3f,
            misTapProbability = 0.05f,
            trajectoryPoints = 15,
            bezierControlOffset = 60f,
            thinkingMinMs = 600,
            thinkingMaxMs = 2000
        )

        val HYPER_FAST = BehaviorProfile(
            name = "HYPER_FAST",
            description = "超高速连续操作型",
            baseTapDelayMs = 80,
            tapDelayJitterMs = 50,
            swipeBaseDurationMs = 250,
            swipeDurationJitterMs = 80,
            positionOffsetPx = 5,
            hesitateProbability = 0.02f,
            hesitateMinMs = 300,
            hesitateMaxMs = 800,
            readingSpeedCharsPerSec = 20f,
            readingSpeedJitter = 0.1f,
            misTapProbability = 0.03f,
            trajectoryPoints = 15,
            bezierControlOffset = 20f,
            thinkingMinMs = 100,
            thinkingMaxMs = 300
        )

        val DEFAULT = FAST_YOUNG

        // 获取所有预定义配置
        fun getAllProfiles(): List<BehaviorProfile> {
            return listOf(FAST_YOUNG, SLOW_CAREFUL, CLUMSY, HYPER_FAST)
        }

        // 根据名称获取配置
        fun getByName(name: String): BehaviorProfile {
            return getAllProfiles().find { it.name == name } ?: DEFAULT
        }

        // 按权重随机选择
        fun getRandomByWeight(weights: Map<String, Float>): BehaviorProfile {
            val totalWeight = weights.values.sum()
            if (totalWeight <= 0) return DEFAULT

            var random = kotlin.random.Random.nextFloat() * totalWeight

            for ((name, weight) in weights) {
                random -= weight
                if (random <= 0) {
                    return getByName(name)
                }
            }

            return DEFAULT
        }
    }
}