package com.example.agentclient.core

import com.google.gson.Gson
// import com.google.gson.reflect.TypeToken

/**
 * 行为配置文件 - 增强版
 * 定义不同"人设"的操作特征，包含风控和拟人化参数
 */
data class BehaviorProfile(
    val name: String,
    val description: String = "",

    // --- 基础节奏参数 (毫秒) ---
    // 两次点击之间的间隔范围
    val minTapIntervalMs: Long = 500,
    val maxTapIntervalMs: Long = 3000,

    // 滑动持续时间范围
    val minSwipeDurationMs: Long = 300,
    val maxSwipeDurationMs: Long = 1000,

    // --- 拟人化细节 ---
    // 位置偏移（像素）
    val positionOffsetPx: Int = 10,

    // 误触偏移（像素）
    val misTapOffsetPx: Int = 50,

    // 犹豫行为 (操作前突然停顿)
    val hesitateProbability: Float = 0.1f,
    val hesitateMinMs: Long = 500,
    val hesitateMaxMs: Long = 2000,

    // 思考时间 (一系列操作前的长停顿)
    val thinkingMinMs: Long = 1000,
    val thinkingMaxMs: Long = 5000,

    // 误操作概率
    val misTapProbability: Float = 0.02f,

    // 阅读速度（字符/秒）
    val readingSpeedCharsPerSec: Float = 10f,
    val readingSpeedJitter: Float = 0.2f,

    // --- 风控策略 ---
    // 单次挂机最长时长 (分钟)，0表示不限制
    val sessionMaxDurationMinutes: Int = 120,

    // 夜间禁止运行时间段 (24小时制，例如 23 到 7)
    // startHour = -1 表示不启用
    val nightOffStartHour: Int = -1,
    val nightOffEndHour: Int = -1,

    // 允许的连续错误次数 (超过此值应停止或报警)
    val errorTolerance: Int = 10,

    // 思考时间触发概率 (新增)
    val thinkingProbability: Float = 0.05f
) {
    /**
     * 返回归一化后的配置副本
     * 确保所有参数在合理范围内，防止错误配置导致异常
     */
    fun normalized(): BehaviorProfile {
        // 1. 概率字段约束到 [0, 1]
        val safeHesitateProb = hesitateProbability.coerceIn(0f, 1f)
        val safeMisTapProb = misTapProbability.coerceIn(0f, 1f)
        val safeReadingJitter = readingSpeedJitter.coerceIn(0f, 1f)
        val safeThinkingProb = thinkingProbability.coerceIn(0f, 1f)

        // 2. 时间区间约束 (min >= 0, max >= min)
        val safeMinTap = minTapIntervalMs.coerceAtLeast(0)
        val safeMaxTap = maxTapIntervalMs.coerceAtLeast(safeMinTap)

        val safeMinSwipe = minSwipeDurationMs.coerceAtLeast(0)
        val safeMaxSwipe = maxSwipeDurationMs.coerceAtLeast(safeMinSwipe)

        val safeHesitateMin = hesitateMinMs.coerceAtLeast(0)
        val safeHesitateMax = hesitateMaxMs.coerceAtLeast(safeHesitateMin)

        val safeThinkingMin = thinkingMinMs.coerceAtLeast(0)
        val safeThinkingMax = thinkingMaxMs.coerceAtLeast(safeThinkingMin)

        // 3. 其他约束
        val safeSessionMax = if (sessionMaxDurationMinutes < 0) 0 else sessionMaxDurationMinutes
        val safeErrorTolerance = errorTolerance.coerceAtLeast(1)
        val safePositionOffset = positionOffsetPx.coerceAtLeast(0)
        val safeMisTapOffset = misTapOffsetPx.coerceAtLeast(0)

        return copy(
            hesitateProbability = safeHesitateProb,
            misTapProbability = safeMisTapProb,
            readingSpeedJitter = safeReadingJitter,
            thinkingProbability = safeThinkingProb,
            minTapIntervalMs = safeMinTap,
            maxTapIntervalMs = safeMaxTap,
            minSwipeDurationMs = safeMinSwipe,
            maxSwipeDurationMs = safeMaxSwipe,
            hesitateMinMs = safeHesitateMin,
            hesitateMaxMs = safeHesitateMax,
            thinkingMinMs = safeThinkingMin,
            thinkingMaxMs = safeThinkingMax,
            sessionMaxDurationMinutes = safeSessionMax,
            errorTolerance = safeErrorTolerance,
            positionOffsetPx = safePositionOffset,
            misTapOffsetPx = safeMisTapOffset
        )
    }

    companion object {
        // 预定义的行为配置

        val FAST_YOUNG = BehaviorProfile(
            name = "FAST_YOUNG",
            description = "年轻人快速操作型",
            minTapIntervalMs = 200,
            maxTapIntervalMs = 1500,
            minSwipeDurationMs = 200,
            maxSwipeDurationMs = 600,
            positionOffsetPx = 8,
            misTapOffsetPx = 40,
            hesitateProbability = 0.05f,
            hesitateMinMs = 300,
            hesitateMaxMs = 1000,
            thinkingMinMs = 500,
            thinkingMaxMs = 2000,
            misTapProbability = 0.01f,
            readingSpeedCharsPerSec = 20f,
            sessionMaxDurationMinutes = 180,
            errorTolerance = 15,
            thinkingProbability = 0.03f
        )

        val SLOW_CAREFUL = BehaviorProfile(
            name = "SLOW_CAREFUL",
            description = "慢速谨慎型",
            minTapIntervalMs = 800,
            maxTapIntervalMs = 4000,
            minSwipeDurationMs = 500,
            maxSwipeDurationMs = 1200,
            positionOffsetPx = 5,
            misTapOffsetPx = 30,
            hesitateProbability = 0.25f,
            hesitateMinMs = 1000,
            hesitateMaxMs = 3000,
            thinkingMinMs = 2000,
            thinkingMaxMs = 8000,
            misTapProbability = 0.005f,
            readingSpeedCharsPerSec = 5f,
            sessionMaxDurationMinutes = 90,
            errorTolerance = 5,
            thinkingProbability = 0.1f
        )

        val DEFAULT = FAST_YOUNG

        // 获取所有预定义配置
        fun getAllProfiles(): List<BehaviorProfile> {
            return listOf(FAST_YOUNG, SLOW_CAREFUL)
        }

        // 根据名称获取配置
        fun getByName(name: String): BehaviorProfile {
            return getAllProfiles().find { it.name == name } ?: DEFAULT
        }

        // 从 JSON 解析
        fun fromJson(json: String): BehaviorProfile? {
            return try {
                Gson().fromJson(json, BehaviorProfile::class.java)?.normalized()
            } catch (e: Exception) {
                null
            }
        }
        
        // 从 Map 解析 (用于 Config 更新)
        fun fromMap(map: Map<String, Any>): BehaviorProfile? {
            return try {
                val gson = Gson()
                val json = gson.toJson(map)
                gson.fromJson(json, BehaviorProfile::class.java)?.normalized()
            } catch (e: Exception) {
                null
            }
        }
    }
}