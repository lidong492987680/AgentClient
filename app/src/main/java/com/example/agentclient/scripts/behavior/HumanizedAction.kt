package com.example.agentclient.scripts.behavior

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.agentclient.accessibility.AgentAccessibilityService
import com.example.agentclient.core.BehaviorProfile
import com.example.agentclient.core.Logger
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.random.Random

/**
 * 人类行为模拟执行器 - 增强版
 * 负责：
 * 1. 拟人化操作（随机延迟、抖动、误触）
 * 2. 风控策略执行（夜间休眠、时长限制、错误熔断）
 */
class HumanizedAction(
    private val context: Context,
    private var profile: BehaviorProfile = BehaviorProfile.Companion.DEFAULT
) {

    private val logger = Logger.Companion.getInstance(context)
    private val accessibilityService: AgentAccessibilityService?
        get() = AgentAccessibilityService.Companion.getInstance()

    // 状态追踪
    private var sessionStartTime: Long = System.currentTimeMillis()
    private var consecutiveErrors: Int = 0

    // 统计
    private var tapCount = 0
    private var swipeCount = 0
    private var mistapCount = 0

    /**
     * 设置行为配置
     */
    fun setProfile(profile: BehaviorProfile) {
        val normalized = profile.normalized()
        this.profile = normalized
        logger.info("HumanizedAction", "Profile changed to: ${normalized.name}")
    }

    /**
     * 重置会话状态 (例如开始新脚本时调用)
     */
    fun resetSession() {
        sessionStartTime = System.currentTimeMillis()
        consecutiveErrors = 0
        resetStatistics()
        logger.info("HumanizedAction", "Session reset")
    }

    // ==================== 策略查询接口 (供外部或内部使用) ====================

    /**
     * 计算下一次点击的延迟时间
     */
    fun nextTapDelayMs(): Long {
        return Random.Default.nextLong(profile.minTapIntervalMs, profile.maxTapIntervalMs)
    }

    /**
     * 计算下一次滑动的延迟时间
     */
    fun nextSwipeDelayMs(): Long {
        // 滑动通常比点击间隔稍长
        return Random.Default.nextLong(profile.minTapIntervalMs, profile.maxTapIntervalMs) + 200
    }

    /**
     * 计算滑动持续时间
     */
    fun nextSwipeDurationMs(): Long {
        return Random.Default.nextLong(profile.minSwipeDurationMs, profile.maxSwipeDurationMs)
    }

    /**
     * 检查是否需要休息 (风控)
     */
    fun needRestNow(): Boolean {
        if (profile.sessionMaxDurationMinutes <= 0) return false

        val durationMinutes = (System.currentTimeMillis() - sessionStartTime) / 60000
        if (durationMinutes >= profile.sessionMaxDurationMinutes) {
            logger.warn("HumanizedAction", "Session duration exceeded: $durationMinutes >= ${profile.sessionMaxDurationMinutes} min")
            return true
        }
        return false
    }

    /**
     * 检查是否在夜间禁止运行时间段 (风控)
     */
    fun isNightOff(): Boolean {
        if (profile.nightOffStartHour == -1 || profile.nightOffEndHour == -1) return false

        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        // 处理跨午夜的情况 (例如 23:00 - 07:00)
        return if (profile.nightOffStartHour > profile.nightOffEndHour) {
            currentHour >= profile.nightOffStartHour || currentHour < profile.nightOffEndHour
        } else {
            // 不跨午夜 (例如 01:00 - 05:00)
            currentHour in profile.nightOffStartHour until profile.nightOffEndHour
        }
    }

    /**
     * 检查是否触发错误熔断
     */
    fun isErrorToleranceExceeded(): Boolean {
        return consecutiveErrors >= profile.errorTolerance
    }

    // ==================== 核心操作接口 ====================

    /**
     * 执行前置检查 (风控 + 拟人化延迟)
     */
    private suspend fun preActionCheck(actionName: String): Boolean {
        // 1. 风控检查
        if (isNightOff()) {
            logger.warn("HumanizedAction", "Action blocked: Night off time")
            return false
        }
        if (needRestNow()) {
            logger.warn("HumanizedAction", "Action blocked: Need rest")
            return false
        }
        if (isErrorToleranceExceeded()) {
            logger.error("HumanizedAction", "Action blocked: Too many errors ($consecutiveErrors)")
            return false
        }

        // 2. 思考时间 (基于配置的概率触发)
        if (Random.Default.nextFloat() < profile.thinkingProbability) {
            val thinkTime = Random.Default.nextLong(profile.thinkingMinMs, profile.thinkingMaxMs)
            logger.debug("HumanizedAction", "Thinking for ${thinkTime}ms")
            delay(thinkTime)
        }

        // 3. 犹豫 (概率触发)
        if (Random.Default.nextFloat() < profile.hesitateProbability) {
            val hesitateTime = Random.Default.nextLong(profile.hesitateMinMs, profile.hesitateMaxMs)
            logger.debug("HumanizedAction", "Hesitating for ${hesitateTime}ms")
            delay(hesitateTime)
        }

        return true
    }

    /**
     * 人类化点击
     */
    suspend fun tap(x: Float, y: Float, description: String = ""): Boolean {
        if (!preActionCheck("tap")) return false

        val service = accessibilityService ?: return false

        // 位置偏移
        val offsetX = Random.Default.nextInt(-profile.positionOffsetPx, profile.positionOffsetPx + 1)
        val offsetY = Random.Default.nextInt(-profile.positionOffsetPx, profile.positionOffsetPx + 1)
        val actualX = (x + offsetX).coerceAtLeast(0f)
        val actualY = (y + offsetY).coerceAtLeast(0f)

        // 误点击判定
        if (Random.Default.nextFloat() < profile.misTapProbability) {
            performMisTap(actualX, actualY)
            delay(Random.Default.nextLong(300, 800))
        }

        // 执行点击
        val tapDuration = Random.Default.nextLong(50, 150) // 点击本身通常很快
        val success = service.tapSuspend(actualX, actualY, tapDuration)

        if (success) {
            tapCount++
            consecutiveErrors = 0 // 重置错误计数
            logger.debug("HumanizedAction", "Tapped at ($actualX, $actualY) - $description")

            // 点击后延迟 (使用配置的间隔)
            delay(nextTapDelayMs())
        } else {
            consecutiveErrors++
            logger.error("HumanizedAction", "Failed to tap at ($actualX, $actualY). Errors: $consecutiveErrors")
        }

        return success
    }

    /**
     * 人类化长按
     */
    suspend fun longPress(x: Float, y: Float, description: String = ""): Boolean {
        if (!preActionCheck("longPress")) return false
        val service = accessibilityService ?: return false

        val offsetX = Random.Default.nextInt(-profile.positionOffsetPx / 2, profile.positionOffsetPx / 2 + 1)
        val offsetY = Random.Default.nextInt(-profile.positionOffsetPx / 2, profile.positionOffsetPx / 2 + 1)
        val actualX = (x + offsetX).coerceAtLeast(0f)
        val actualY = (y + offsetY).coerceAtLeast(0f)

        val pressDuration = Random.Default.nextLong(800, 1500)
        val success = service.tapSuspend(actualX, actualY, pressDuration)

        if (success) {
            consecutiveErrors = 0
            logger.debug("HumanizedAction", "Long pressed at ($actualX, $actualY) for ${pressDuration}ms - $description")
            delay(nextTapDelayMs())
        } else {
            consecutiveErrors++
        }

        return success
    }

    /**
     * 人类化滑动
     */
    suspend fun swipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        description: String = ""
    ): Boolean {
        if (!preActionCheck("swipe")) return false
        val service = accessibilityService ?: return false

        val startOffsetX = Random.Default.nextInt(-profile.positionOffsetPx, profile.positionOffsetPx + 1)
        val startOffsetY = Random.Default.nextInt(-profile.positionOffsetPx, profile.positionOffsetPx + 1)
        val endOffsetX = Random.Default.nextInt(-profile.positionOffsetPx, profile.positionOffsetPx + 1)
        val endOffsetY = Random.Default.nextInt(-profile.positionOffsetPx, profile.positionOffsetPx + 1)

        val actualStartX = (startX + startOffsetX).coerceAtLeast(0f)
        val actualStartY = (startY + startOffsetY).coerceAtLeast(0f)
        val actualEndX = (endX + endOffsetX).coerceAtLeast(0f)
        val actualEndY = (endY + endOffsetY).coerceAtLeast(0f)

        val duration = nextSwipeDurationMs()

        val success = service.swipeSuspend(
            actualStartX, actualStartY,
            actualEndX, actualEndY,
            duration
        )

        if (success) {
            swipeCount++
            consecutiveErrors = 0
            logger.debug("HumanizedAction", "Swiped in ${duration}ms - $description")
            delay(nextSwipeDelayMs())
        } else {
            consecutiveErrors++
            logger.error("HumanizedAction", "Failed to swipe")
        }

        return success
    }

    /**
     * 上下滚动
     */
    suspend fun scroll(direction: ScrollDirection, distance: Float = 300f): Boolean {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels

        val centerX = screenWidth / 2f + Random.Default.nextInt(-profile.positionOffsetPx, profile.positionOffsetPx + 1)
        val startY = when (direction) {
            ScrollDirection.UP -> screenHeight * 0.7f
            ScrollDirection.DOWN -> screenHeight * 0.3f
        }
        val endY = startY + (if (direction == ScrollDirection.UP) -distance else distance)

        return swipe(centerX, startY, centerX, endY, "Scroll $direction")
    }

    /**
     * 点击节点
     */
    suspend fun tapNode(node: AccessibilityNodeInfo, description: String = ""): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (bounds.width() <= 0 || bounds.height() <= 0) {
            logger.error("HumanizedAction", "Node has invalid bounds")
            return false
        }

        val randomX = bounds.left + Random.Default.nextInt(bounds.width() / 4, bounds.width() * 3 / 4 + 1)
        val randomY = bounds.top + Random.Default.nextInt(bounds.height() / 4, bounds.height() * 3 / 4 + 1)

        return tap(randomX.toFloat(), randomY.toFloat(), description)
    }

    /**
     * 输入文本
     */
    suspend fun inputText(
        node: AccessibilityNodeInfo,
        text: String,
        description: String = ""
    ): Boolean {
        if (!preActionCheck("inputText")) return false
        val service = accessibilityService ?: return false

        // 先点击输入框
        val tapSuccess = tapNode(node, "Focus input field")
        if (!tapSuccess) return false

        delay(Random.Default.nextLong(200, 500))

        val inputSuccess = service.inputText(node, text)
        if (inputSuccess) {
            consecutiveErrors = 0
            logger.debug("HumanizedAction", "Input text: '$text' - $description")

            // 模拟打字时间
            val jitter = 1f + Random.Default.nextFloat() * profile.readingSpeedJitter * 2 - profile.readingSpeedJitter
            val typingTime = (text.length * 1000f / profile.readingSpeedCharsPerSec * jitter)
                .toLong()
                .coerceIn(500, 5000)
            delay(typingTime)
        } else {
            consecutiveErrors++
        }

        return inputSuccess
    }

    /**
     * 误点击
     */
    private suspend fun performMisTap(nearX: Float, nearY: Float) {
        val service = accessibilityService ?: return

        val mistapX = nearX + Random.Default.nextInt(-profile.misTapOffsetPx, profile.misTapOffsetPx + 1)
        val mistapY = nearY + Random.Default.nextInt(-profile.misTapOffsetPx, profile.misTapOffsetPx + 1)

        service.tapSuspend(mistapX, mistapY)
        mistapCount++

        logger.debug("HumanizedAction", "Mistap at ($mistapX, $mistapY)")
    }

    /**
     * 查找并点击文本
     */
    suspend fun findAndClickText(
        text: String,
        exactMatch: Boolean = false,
        timeout: Long = 5000
    ): Boolean {
        val service = accessibilityService ?: return false
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeout) {
            val result = service.findNodeByText(text, exactMatch)
            if (result != null) {
                val success = tapNode(result.node, "Click text: $text")
                if (success) return true
            }
            delay(500)
        }
        return false
    }

    /**
     * 查找并点击ID
     */
    suspend fun findAndClickId(
        resourceId: String,
        timeout: Long = 5000
    ): Boolean {
        val service = accessibilityService ?: return false
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeout) {
            val result = service.findNodeById(resourceId)
            if (result != null) {
                val success = tapNode(result.node, "Click id: $resourceId")
                if (success) return true
            }
            delay(500)
        }
        return false
    }

    /**
     * 获取统计信息
     */
    fun getStatistics(): ActionStatistics {
        return ActionStatistics(
            tapCount = tapCount,
            swipeCount = swipeCount,
            mistapCount = mistapCount,
            successRate = if (tapCount + swipeCount > 0) {
                (tapCount + swipeCount - mistapCount).toFloat() / (tapCount + swipeCount)
            } else {
                1.0f
            }
        )
    }

    /**
     * 重置统计
     */
    fun resetStatistics() {
        tapCount = 0
        swipeCount = 0
        mistapCount = 0
    }

    data class ActionStatistics(
        val tapCount: Int,
        val swipeCount: Int,
        val mistapCount: Int,
        val successRate: Float
    )

    enum class ScrollDirection {
        UP, DOWN
    }
}