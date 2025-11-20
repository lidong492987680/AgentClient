package com.example.agentclient.scripts.behavior

import android.content.Context
import com.example.agentclient.scripts.behavior.BehaviorProfile
import com.example.agentclient.core.Logger
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * 拟人化动作逻辑
 * 根据 BehaviorProfile 计算随机等待时间、插入停顿等
 */
class HumanizedAction(
    private val context: Context,
    private var profile: BehaviorProfile
) {
    private val logger = Logger.getInstance(context)

    /**
     * 更新配置
     */
    fun setProfile(newProfile: BehaviorProfile) {
        this.profile = newProfile
        logger.info("HumanizedAction", "Profile updated to: ${newProfile.name}")
    }

    /**
     * 获取当前配置
     */
    fun getProfile(): BehaviorProfile = profile

    /**
     * 随机等待一个步骤间隔
     * 基于 minStepInterval 和 maxStepInterval
     */
    suspend fun waitRandomStep() {
        val min = profile.minStepInterval
        val max = profile.maxStepInterval
        val waitTime = Random.nextLong(min, max + 1)
        
        // 10% 概率触发额外的小停顿（模拟思考）
        if (Random.nextFloat() < profile.thinkingProbability) {
            val thinkingTime = Random.nextLong(profile.thinkingMinMs, profile.thinkingMaxMs)
            logger.debug("HumanizedAction", "Thinking for ${thinkingTime}ms + step wait ${waitTime}ms")
            delay(thinkingTime + waitTime)
        } else {
            delay(waitTime)
        }
    }

    /**
     * 获取点击前的随机延迟
     */
    fun getPreTapDelay(): Long {
        // 基础延迟 + 随机抖动
        return Random.nextLong(50, 200)
    }

    /**
     * 获取点击后的随机延迟
     */
    fun getPostTapDelay(): Long {
        return Random.nextLong(profile.minTapIntervalMs, profile.maxTapIntervalMs)
    }

    /**
     * 计算带偏移的随机坐标
     * @param x 目标X
     * @param y 目标Y
     * @return Pair(randomX, randomY)
     */
    fun randomizePoint(x: Int, y: Int): Pair<Int, Int> {
        val offset = profile.positionOffsetPx
        val randomX = x + Random.nextInt(-offset, offset + 1)
        val randomY = y + Random.nextInt(-offset, offset + 1)
        return Pair(randomX, randomY)
    }

    /**
     * 检查是否应该休息（防封策略）
     * @param runtimeMinutes 当前运行时长（分钟）
     * @return 建议休息时长（毫秒），0表示不需要休息
     */
    fun checkRest(runtimeMinutes: Int): Long {
        // 简单示例：每运行60分钟，有20%概率休息5-10分钟
        if (runtimeMinutes > 0 && runtimeMinutes % 60 == 0) {
            if (Random.nextFloat() < 0.2f) {
                val restTime = Random.nextLong(profile.longRestIntervalMin, profile.longRestIntervalMax)
                logger.info("HumanizedAction", "Triggering anti-ban rest for ${restTime / 1000}s")
                return restTime
            }
        }
        return 0L
    }
}