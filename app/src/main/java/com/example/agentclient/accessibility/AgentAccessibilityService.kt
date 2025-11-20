package com.example.agentclient.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.agentclient.core.Logger
import com.example.agentclient.network.HeartbeatService
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * 无障碍服务 - 完整实现版
 * 提供所有自动化操作能力
 */
class AgentAccessibilityService : AccessibilityService() {

    private lateinit var logger: Logger
    private lateinit var heartbeatService: HeartbeatService
    private val handler = Handler(Looper.getMainLooper())

    // 服务状态
    private val isServiceEnabled = AtomicBoolean(false)
    // private val lastGestureTime = AtomicLong(0) // ※ Phase3 暂不使用手势间隔控制
    // private val gestureQueue = ConcurrentLinkedQueue<GestureTask>() // ※ Phase3 暂时停用队列

    // 手势限制
    // private val minGestureInterval = 50L   // ※ 队列版用，现已停用
    // private val gestureTimeout = 5000L     // ※ 队列版用，现已停用

    companion object {
        @Volatile
        private var instance: AgentAccessibilityService? = null

        fun getInstance(): AgentAccessibilityService? = instance

        fun isEnabled(): Boolean = instance?.isServiceEnabled?.get() == true
    }

    /**
     * 手势任务（队列版用，现阶段不使用）
     */
    /*
    private data class GestureTask(
        val gesture: GestureDescription,
        val callback: ((Boolean) -> Unit)? = null,
        val timestamp: Long = System.currentTimeMillis()
    )
    */

    /**
     * 节点查找结果
     */
    data class NodeSearchResult(
        val node: AccessibilityNodeInfo,
        val bounds: Rect = Rect(),
        val text: String? = null,
        val contentDescription: String? = null,
        val className: String? = null,
        val isClickable: Boolean = false,
        val isScrollable: Boolean = false
    ) {
        init {
            node.getBoundsInScreen(bounds)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        logger = Logger.getInstance(this)
        heartbeatService = HeartbeatService.getInstance(this)
        logger.info("AccessibilityService", "Service created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceEnabled.set(true)
        logger.info("AccessibilityService", "Service connected")

        // 配置服务
        serviceInfo?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                flags = flags or FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        }

        // 通知心跳服务
        // heartbeatService.updateAccessibilityStatus(true)
        // ★★★★★ 关键：无障碍真正开启后自动点击一次
        handler.postDelayed({
            tap(500f, 500f)
            logger.info("AccessibilityService", "Auto-tap after service enabled")
        }, 500)
        // 启动手势处理器（队列版，Phase3 暂时停用）
        // startGestureProcessor()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 记录关键事件用于调试
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString()
                val className = event.className?.toString()
                logger.debug("AccessibilityService", "Window changed: $packageName - $className")
            }
        }
    }

    override fun onInterrupt() {
        logger.warn("AccessibilityService", "Service interrupted")
    }

    override fun onDestroy() {
        isServiceEnabled.set(false)
        instance = null
        heartbeatService.updateAccessibilityStatus(false)
        logger.info("AccessibilityService", "Service destroyed")
        super.onDestroy()
    }

    /**
     * 启动手势处理器（旧：队列 + 轮询实现，现阶段停用）
     */
    /*
    private fun startGestureProcessor() {
        handler.post(object : Runnable {
            override fun run() {
                processNextGesture()
                handler.postDelayed(this, minGestureInterval)
            }
        })
    }

    /**
     * 处理下一个手势（旧：队列实现，现阶段停用）
     */
    private fun processNextGesture() {
        val task = gestureQueue.poll() ?: return

        // 检查手势频率限制
        val now = System.currentTimeMillis()
        val timeSinceLastGesture = now - lastGestureTime.get()

        if (timeSinceLastGesture < minGestureInterval) {
            // 重新入队
            gestureQueue.offer(task)
            return
        }

        // 检查是否超时
        if (now - task.timestamp > gestureTimeout) {
            task.callback?.invoke(false)
            logger.warn("AccessibilityService", "Gesture timeout, discarded")
            return
        }

        // 执行手势
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val success = dispatchGesture(task.gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    task.callback?.invoke(true)
                    logger.debug("AccessibilityService", "Gesture completed")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    task.callback?.invoke(false)
                    logger.warn("AccessibilityService", "Gesture cancelled")
                }
            }, handler)

            if (success) {
                lastGestureTime.set(now)
            } else {
                task.callback?.invoke(false)
                logger.error("AccessibilityService", "Failed to dispatch gesture")
            }
        } else {
            task.callback?.invoke(false)
            logger.error("AccessibilityService", "Gesture not supported on this API level")
        }
    }
    */

    // ========== 公共操作接口 ==========

    /**
     * 点击坐标
     */
    fun tap(x: Float, y: Float, duration: Long = 50, callback: ((Boolean) -> Unit)? = null): Boolean {
        if (!isServiceEnabled.get()) {
            logger.error("AccessibilityService", "Service not enabled")
            callback?.invoke(false)
            return false
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            logger.error("AccessibilityService", "Tap not supported on API < 24")
            callback?.invoke(false)
            return false
        }

        val path = Path().apply {
            moveTo(x, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        // 直接 dispatchGesture，不再入队
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    logger.debug("AccessibilityService", "Tap completed: ($x, $y)")
                    callback?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    logger.warn("AccessibilityService", "Tap cancelled: ($x, $y)")
                    callback?.invoke(false)
                }
            },
            handler
        )

        if (!accepted) {
            logger.error("AccessibilityService", "Failed to dispatch tap gesture")
            callback?.invoke(false)
        }

        return accepted
    }

    /**
     * 滑动
     */
    fun swipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        duration: Long = 500,
        callback: ((Boolean) -> Unit)? = null
    ): Boolean {
        if (!isServiceEnabled.get()) {
            logger.error("AccessibilityService", "Service not enabled")
            callback?.invoke(false)
            return false
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            logger.error("AccessibilityService", "Swipe not supported on API < 24")
            callback?.invoke(false)
            return false
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        // 直接 dispatchGesture，不再入队
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    logger.debug(
                        "AccessibilityService",
                        "Swipe completed: ($startX, $startY) -> ($endX, $endY)"
                    )
                    callback?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    logger.warn(
                        "AccessibilityService",
                        "Swipe cancelled: ($startX, $startY) -> ($endX, $endY)"
                    )
                    callback?.invoke(false)
                }
            },
            handler
        )

        if (!accepted) {
            logger.error("AccessibilityService", "Failed to dispatch swipe gesture")
            callback?.invoke(false)
        }

        return accepted
    }

    /**
     * 长按
     */
    fun longPress(x: Float, y: Float, duration: Long = 1000, callback: ((Boolean) -> Unit)? = null): Boolean {
        return tap(x, y, duration, callback)
    }

    /**
     * 双击
     */
    fun doubleTap(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null): Boolean {
        tap(x, y, 50) { success1 ->
            if (success1) {
                handler.postDelayed({
                    tap(x, y, 50, callback)
                }, 100)
            } else {
                callback?.invoke(false)
            }
        }
        return true
    }

    /**
     * 通过文本查找节点
     */
    fun findNodeByText(
        text: String,
        exactMatch: Boolean = false,
        root: AccessibilityNodeInfo? = null
    ): NodeSearchResult? {
        val rootNode = root ?: rootInActiveWindow
        if (rootNode == null) {
            logger.warn("AccessibilityService", "No root node available")
            return null
        }

        return try {
            if (exactMatch) {
                findNodeRecursive(rootNode) { node ->
                    node.text?.toString() == text ||
                            node.contentDescription?.toString() == text
                }
            } else {
                findNodeRecursive(rootNode) { node ->
                    node.text?.toString()?.contains(text, ignoreCase = true) == true ||
                            node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true
                }
            }
        } catch (e: Exception) {
            logger.error("AccessibilityService", "Error finding node by text: $text", e)
            null
        }
    }

    /**
     * 通过ID查找节点
     */
    fun findNodeById(
        resourceId: String,
        root: AccessibilityNodeInfo? = null
    ): NodeSearchResult? {
        val rootNode = root ?: rootInActiveWindow
        if (rootNode == null) {
            logger.warn("AccessibilityService", "No root node available")
            return null
        }

        return try {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(resourceId)
            nodes?.firstOrNull()?.let { node ->
                NodeSearchResult(
                    node = node,
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    className = node.className?.toString(),
                    isClickable = node.isClickable,
                    isScrollable = node.isScrollable
                )
            }
        } catch (e: Exception) {
            logger.error("AccessibilityService", "Error finding node by id: $resourceId", e)
            null
        }
    }

    /**
     * 通过类名查找节点
     */
    fun findNodeByClassName(
        className: String,
        root: AccessibilityNodeInfo? = null
    ): NodeSearchResult? {
        val rootNode = root ?: rootInActiveWindow
        if (rootNode == null) {
            logger.warn("AccessibilityService", "No root node available")
            return null
        }

        return try {
            findNodeRecursive(rootNode) { node ->
                node.className?.toString() == className
            }
        } catch (e: Exception) {
            logger.error("AccessibilityService", "Error finding node by class: $className", e)
            null
        }
    }

    /**
     * 点击节点
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        return try {
            when {
                node.isClickable -> {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }

                else -> {
                    // 尝试点击父节点
                    var parent = node.parent
                    while (parent != null) {
                        if (parent.isClickable) {
                            return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                        val nextParent = parent.parent
                        parent.recycle()
                        parent = nextParent
                    }

                    // 最后尝试通过坐标点击
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                        true
                    } else {
                        false
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("AccessibilityService", "Error clicking node", e)
            false
        }
    }

    /**
     * 输入文本
     */
    fun inputText(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            // 先获取焦点
            if (node.isFocusable && !node.isFocused) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }

            // 设置文本
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } catch (e: Exception) {
            logger.error("AccessibilityService", "Error inputting text", e)
            false
        }
    }

    /**
     * 返回
     */
    fun pressBack(): Boolean {
        return try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (e: Exception) {
            logger.error("AccessibilityService", "Error pressing back", e)
            false
        }
    }

    /**
     * 回到主屏幕
     */
    fun pressHome(): Boolean {
        return try {
            performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (e: Exception) {
            logger.error("AccessibilityService", "Error pressing home", e)
            false
        }
    }

    /**
     * 打开最近任务
     */
    fun openRecents(): Boolean {
        return try {
            performGlobalAction(GLOBAL_ACTION_RECENTS)
        } catch (e: Exception) {
            logger.error("AccessibilityService", "Error opening recents", e)
            false
        }
    }

    /**
     * 打开通知栏
     */
    fun openNotifications(): Boolean {
        return try {
            performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        } catch (e: Exception) {
            logger.error("AccessibilityService", "Error opening notifications", e)
            false
        }
    }

    /**
     * 获取当前包名
     */
    fun getCurrentPackageName(): String? {
        return try {
            rootInActiveWindow?.packageName?.toString()
        } catch (e: Exception) {
            logger.error("AccessibilityService", "Error getting package name", e)
            null
        }
    }

    /**
     * 获取当前Activity
     */
    fun getCurrentActivity(): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                windows?.firstOrNull { it.isActive }?.let { window ->
                    window.root?.className?.toString()
                }
            } else {
                rootInActiveWindow?.className?.toString()
            }
        } catch (e: Exception) {
            logger.error("AccessibilityService", "Error getting current activity", e)
            null
        }
    }

    /**
     * 检查是否在目标应用
     */
    fun isInTargetApp(packageName: String): Boolean {
        return getCurrentPackageName() == packageName
    }

    /**
     * 滚动节点
     */
    fun scrollNode(node: AccessibilityNodeInfo, direction: Int): Boolean {
        return try {
            node.performAction(direction)
        } catch (e: Exception) {
            logger.error("AccessibilityService", "Error scrolling node", e)
            false
        }
    }

    // ========== 私有辅助方法 ==========

    /**
     * 递归查找节点（已修复 child recycle 问题）
     */
    private fun findNodeRecursive(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): NodeSearchResult? {
        try {
            // 条件に一致するノードかどうかをチェック
            if (predicate(node)) {
                return NodeSearchResult(
                    node = node,
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    className = node.className?.toString(),
                    isClickable = node.isClickable,
                    isScrollable = node.isScrollable
                )
            }

            // 子ノードを再帰的に探索
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue

                // 再帰的に検索を実行
                val result = findNodeRecursive(child, predicate)
                if (result != null) {
                    // ★ 見つかった場合は child を recycle しない
                    //    → NodeSearchResult.node が child（またはその子孫）を参照している可能性があるため
                    return result
                }

                // ★ 見つからなかった場合だけ child を解放
                child.recycle()
            }
        } catch (e: Exception) {
            logger.debug("AccessibilityService", "Error in recursive search", e)
        }

        return null
    }

    /**
     * 挂起函数版本的点击
     */
    suspend fun tapSuspend(x: Float, y: Float, duration: Long = 50): Boolean {
        return suspendCoroutine { cont ->
            tap(x, y, duration) { success ->
                cont.resume(success)
            }
        }
    }

    /**
     * 挂起函数版本的滑动
     */
    suspend fun swipeSuspend(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        duration: Long = 500
    ): Boolean {
        return suspendCoroutine { cont ->
            swipe(startX, startY, endX, endY, duration) { success ->
                cont.resume(success)
            }
        }
    }
}