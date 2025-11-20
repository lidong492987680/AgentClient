package com.example.agentclient.scripts.behavior

import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.agentclient.accessibility.AgentAccessibilityService
import com.example.agentclient.core.BehaviorProfile
import com.example.agentclient.core.Logger
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * 人类行为模拟执行器 - 精简实用版
 * 所有操作都通过这里，加入人类化特征
 */
class HumanizedAction(
    private val context: Context,
    private var profile: BehaviorProfile = BehaviorProfile.DEFAULT
) {

    private val logger = Logger.getInstance(context)
    private val accessibilityService: AgentAccessibilityService?
        get() = AgentAccessibilityService.getInstance()

    // 操作计数
    private var tapCount = 0
    private var swipeCount = 0
    private var mistapCount = 0

    /**
     * 设置行为配置
     */
    fun setProfile(profile: BehaviorProfile) {
        this.profile = profile
        logger.info("HumanizedAction", "Profile changed to: ${profile.name}")
    }

    /**
     * 人类化点击
     */
    suspend fun tap(x: Float, y: Float, description: String = ""): Boolean {
        val service = accessibilityService
        if (service == null) {
            logger.error("HumanizedAction", "Accessibility service not available")
            return false
        }

        // 思考时间（30%概率）
        if (Random.nextFloat() < 0.3f) {
            val thinkTime = Random.nextLong(profile.thinkingMinMs, profile.thinkingMaxMs)
            logger.debug("HumanizedAction", "Thinking for ${thinkTime}ms")
            delay(thinkTime)
        }

        // 犹豫判定
        if (Random.nextFloat() < profile.hesitateProbability) {
            val hesitateTime = Random.nextLong(profile.hesitateMinMs, profile.hesitateMaxMs)
            logger.debug("HumanizedAction", "Hesitating for ${hesitateTime}ms")
            delay(hesitateTime)
        }

        // 位置偏移
        val offsetX = Random.nextInt(-profile.positionOffsetPx, profile.positionOffsetPx + 1)
        val offsetY = Random.nextInt(-profile.positionOffsetPx, profile.positionOffsetPx + 1)
        val actualX = (x + offsetX).coerceAtLeast(0f)
        val actualY = (y + offsetY).coerceAtLeast(0f)

        // 误点击判定
        if (Random.nextFloat() < profile.misTapProbability) {
            performMisTap(actualX, actualY)
            delay(Random.nextLong(300, 800))
        }

        // 执行点击
        val tapDuration = Random.nextLong(30, 100)
        val success = service.tapSuspend(actualX, actualY, tapDuration)

        if (success) {
            tapCount++
            logger.debug("HumanizedAction", "Tapped at ($actualX, $actualY) - $description")

            // 点击后延迟
            val baseDelay = profile.baseTapDelayMs
            val jitter = Random.nextLong(-profile.tapDelayJitterMs, profile.tapDelayJitterMs + 1)
            val totalDelay = (baseDelay + jitter).coerceAtLeast(50)
            delay(totalDelay)
        } else {
            logger.error("HumanizedAction", "Failed to tap at ($actualX, $actualY)")
        }

        return success
    }

    /**
     * 人类化长按
     */
    suspend fun longPress(x: Float, y: Float, description: String = ""): Boolean {
        val service = accessibilityService
        if (service == null) {
            logger.error("HumanizedAction", "Accessibility service not available")
            return false
        }

        // 位置偏移（长按偏移更小）
        val offsetX = Random.nextInt(-profile.positionOffsetPx / 2, profile.positionOffsetPx / 2 + 1)
        val offsetY = Random.nextInt(-profile.positionOffsetPx / 2, profile.positionOffsetPx / 2 + 1)
        val actualX = (x + offsetX).coerceAtLeast(0f)
        val actualY = (y + offsetY).coerceAtLeast(0f)

        // 长按时长带随机
        val pressDuration = Random.nextLong(800, 1500)
        val success = service.tapSuspend(actualX, actualY, pressDuration)

        if (success) {
            logger.debug("HumanizedAction", "Long pressed at ($actualX, $actualY) for ${pressDuration}ms - $description")
            delay(profile.baseTapDelayMs + Random.nextLong(100, 300))
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
        val service = accessibilityService
        if (service == null) {
            logger.error("HumanizedAction", "Accessibility service not available")
            return false
        }

        // 起点和终点都加入偏移
        val startOffsetX = Random.nextInt(-profile.positionOffsetPx, profile.positionOffsetPx + 1)
        val startOffsetY = Random.nextInt(-profile.positionOffsetPx, profile.positionOffsetPx + 1)
        val endOffsetX = Random.nextInt(-profile.positionOffsetPx, profile.positionOffsetPx + 1)
        val endOffsetY = Random.nextInt(-profile.positionOffsetPx, profile.positionOffsetPx + 1)

        val actualStartX = (startX + startOffsetX).coerceAtLeast(0f)
        val actualStartY = (startY + startOffsetY).coerceAtLeast(0f)
        val actualEndX = (endX + endOffsetX).coerceAtLeast(0f)
        val actualEndY = (endY + endOffsetY).coerceAtLeast(0f)

        // 滑动时长带随机
        val baseDuration = profile.swipeBaseDurationMs
        val jitter = Random.nextLong(-profile.swipeDurationJitterMs, profile.swipeDurationJitterMs + 1)
        val totalDuration = (baseDuration + jitter).coerceAtLeast(200)

        val success = service.swipeSuspend(
            actualStartX, actualStartY,
            actualEndX, actualEndY,
            totalDuration
        )

        if (success) {
            swipeCount++
            logger.debug("HumanizedAction",
                "Swiped from ($actualStartX, $actualStartY) to ($actualEndX, $actualEndY) in ${totalDuration}ms - $description")
            delay(profile.baseTapDelayMs)
        }

        return success
    }

    /**
     * 上下滚动
     */
    suspend fun scroll(direction: ScrollDirection, distance: Float = 300f): Boolean {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels

        val centerX = screenWidth / 2f + Random.nextInt(-50, 51)
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

        // 在节点范围内随机选择点击位置（更自然）
        val randomX = bounds.left + Random.nextInt(bounds.width() / 4, bounds.width() * 3 / 4 + 1)
        val randomY = bounds.top + Random.nextInt(bounds.height() / 4, bounds.height() * 3 / 4 + 1)

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
        val service = accessibilityService
        if (service == null) {
            logger.error("HumanizedAction", "Accessibility service not available")
            return false
        }

        // 先点击输入框
        val tapSuccess = tapNode(node, "Focus input field")
        if (!tapSuccess) {
            return false
        }

        delay(Random.nextLong(200, 500))

        // 输入文本
        val inputSuccess = service.inputText(node, text)
        if (inputSuccess) {
            logger.debug("HumanizedAction", "Input text: '$text' - $description")

            // 模拟打字时间
            val typingTime = (text.length * 1000f / profile.readingSpeedCharsPerSec).toLong()
            delay(typingTime.coerceIn(500, 5000))
        }

        return inputSuccess
    }

    /**
     * 等待阅读时间
     */
    suspend fun waitForReading(estimatedTextLength: Int, pageType: String = "") {
        // 计算基础阅读时间
        val baseReadingTime = (estimatedTextLength * 1000f / profile.readingSpeedCharsPerSec).toLong()

        // 添加随机浮动
        val jitter = (baseReadingTime * profile.readingSpeedJitter).toLong()
        val actualTime = baseReadingTime + Random.nextLong(-jitter, jitter + 1)

        // 限制范围
        val finalTime = actualTime.coerceIn(500, 8000)

        logger.debug("HumanizedAction",
            "Reading $pageType (${estimatedTextLength} chars) for ${finalTime}ms")

        delay(finalTime)
    }

    /**
     * 误点击
     */
    private suspend fun performMisTap(nearX: Float, nearY: Float) {
        val service = accessibilityService ?: return

        // 在目标附近误点
        val mistapX = nearX + Random.nextInt(-50, 51)
        val mistapY = nearY + Random.nextInt(-50, 51)

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
        val service = accessibilityService
        if (service == null) {
            logger.error("HumanizedAction", "Accessibility service not available")
            return false
        }

        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeout) {
            val result = service.findNodeByText(text, exactMatch)
            if (result != null) {
                val success = tapNode(result.node, "Click text: $text")
                if (success) {
                    logger.info("HumanizedAction", "Successfully clicked on text: $text")
                    return true
                }
            }
            delay(500)
        }

        logger.warn("HumanizedAction", "Failed to find text: $text")
        return false
    }

    /**
     * 查找并点击ID
     */
    suspend fun findAndClickId(
        resourceId: String,
        timeout: Long = 5000
    ): Boolean {
        val service = accessibilityService
        if (service == null) {
            logger.error("HumanizedAction", "Accessibility service not available")
            return false
        }

        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeout) {
            val result = service.findNodeById(resourceId)
            if (result != null) {
                val success = tapNode(result.node, "Click id: $resourceId")
                if (success) {
                    logger.info("HumanizedAction", "Successfully clicked on id: $resourceId")
                    return true
                }
            }
            delay(500)
        }

        logger.warn("HumanizedAction", "Failed to find id: $resourceId")
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