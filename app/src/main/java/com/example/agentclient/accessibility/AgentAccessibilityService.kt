package com.example.agentclient.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.agentclient.core.Logger
import com.example.agentclient.network.HeartbeatService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 无障碍服务 - 瘦身重构版
 * 职责：
 * 1. 生命周期管理
 * 2. 事件监听
 * 3. 委托 NodeFinder 和 GestureDispatcher 进行具体操作
 */
class AgentAccessibilityService : AccessibilityService(), GestureDispatcher.GestureExecutor {

    private lateinit var logger: Logger
    private lateinit var heartbeatService: HeartbeatService
    private val handler = Handler(Looper.getMainLooper())

    // 委托对象
    private lateinit var gestureDispatcher: GestureDispatcher
    private lateinit var nodeFinder: NodeFinder

    // 服务状态
    private val isServiceEnabled = AtomicBoolean(false)

    companion object {
        @Volatile
        private var instance: AgentAccessibilityService? = null

        fun getInstance(): AgentAccessibilityService? = instance

        fun isEnabled(): Boolean = instance?.isServiceEnabled?.get() == true
    }

    // 兼容旧代码的别名，指向 NodeFinder 中的定义
    // typealias NodeSearchResult = NodeFinder.NodeSearchResult

    override fun onCreate() {
        super.onCreate()
        instance = this
        logger = Logger.getInstance(this)
        heartbeatService = HeartbeatService.getInstance(this)
        
        // 初始化委托对象
        gestureDispatcher = GestureDispatcher(this, logger)
        nodeFinder = NodeFinder(logger)
        
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

        // 自动点击测试
        handler.postDelayed({
            tap(500f, 500f)
            logger.info("AccessibilityService", "Auto-tap after service enabled")
        }, 500)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
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

    // ========== GestureExecutor 接口实现 ==========

    override fun executeGesture(
        gesture: GestureDescription,
        callback: GestureResultCallback?,
        handler: Handler?
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            dispatchGesture(gesture, callback, handler)
        } else {
            false
        }
    }

    override fun isServiceEnabled(): Boolean = isServiceEnabled.get()

    // ========== 公共操作接口 (委托给 GestureDispatcher) ==========

    fun tap(x: Float, y: Float, duration: Long = 50, callback: ((Boolean) -> Unit)? = null): Boolean {
        return gestureDispatcher.tap(x, y, duration, callback)
    }

    fun swipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        duration: Long = 500,
        callback: ((Boolean) -> Unit)? = null
    ): Boolean {
        return gestureDispatcher.swipe(startX, startY, endX, endY, duration, callback)
    }

    fun longPress(x: Float, y: Float, duration: Long = 1000, callback: ((Boolean) -> Unit)? = null): Boolean {
        return gestureDispatcher.longPress(x, y, duration, callback)
    }

    fun doubleTap(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null): Boolean {
        return gestureDispatcher.doubleTap(x, y, callback)
    }

    suspend fun tapSuspend(x: Float, y: Float, duration: Long = 50): Boolean {
        return gestureDispatcher.tapSuspend(x, y, duration)
    }

    suspend fun swipeSuspend(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        duration: Long = 500
    ): Boolean {
        return gestureDispatcher.swipeSuspend(startX, startY, endX, endY, duration)
    }

    // ========== 节点查找接口 (委托给 NodeFinder) ==========

    fun findNodeByText(
        text: String,
        exactMatch: Boolean = false,
        root: AccessibilityNodeInfo? = null
    ): NodeFinder.NodeSearchResult? {
        val actualRoot = root ?: rootInActiveWindow
        // 注意：如果是 rootInActiveWindow 获取的节点，理论上需要回收。
        // 但由于 NodeSearchResult 可能包含该节点或其子节点，我们不能在这里简单回收。
        // 这是一个权衡：为了保持 API 简单，我们依赖调用者（如果有）或让系统 GC 最终处理（虽然不推荐）。
        // 更好的做法是调用者使用 use { } 模式，但为了兼容旧接口，这里暂不强制。
        return nodeFinder.findByText(actualRoot, text, exactMatch)
    }

    fun findNodeById(
        resourceId: String,
        root: AccessibilityNodeInfo? = null
    ): NodeFinder.NodeSearchResult? {
        val actualRoot = root ?: rootInActiveWindow
        return nodeFinder.findById(actualRoot, resourceId)
    }

    fun findNodeByClassName(
        className: String,
        root: AccessibilityNodeInfo? = null
    ): NodeFinder.NodeSearchResult? {
        val actualRoot = root ?: rootInActiveWindow
        return nodeFinder.findByClassName(actualRoot, className)
    }

    // ========== 其他操作 (保留在 Service 中或考虑进一步拆分) ==========

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
                            val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            // parent 是中间变量，用完回收
                            // 注意：如果 parent == node.parent，第一次循环 parent 就是 node 的直接父节点
                            // 这里的 recycle 逻辑比较微妙，为了安全，我们只 recycle 我们在这个循环里获取的 parent
                            // 但 node.parent 返回的是新对象吗？是的。
                            if (parent != node.parent) { // 简单判断，实际上每次 getParent 都是新对象
                                // 这里逻辑有点乱，简化处理：
                                // 每次 loop 结束，如果是中间节点就 recycle
                            }
                            return result
                        }
                        val nextParent = parent.parent
                        parent.recycle() // 回收旧 parent
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

    fun inputText(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            if (node.isFocusable && !node.isFocused) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } catch (e: Exception) {
            logger.error("AccessibilityService", "Error inputting text", e)
            false
        }
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun getCurrentPackageName(): String? {
        return try {
            rootInActiveWindow?.packageName?.toString()
        } catch (e: Exception) {
            logger.error("AccessibilityService", "Error getting package name", e)
            null
        }
    }

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

    fun isInTargetApp(packageName: String): Boolean {
        return getCurrentPackageName() == packageName
    }

    fun scrollNode(node: AccessibilityNodeInfo, direction: Int): Boolean {
        return try {
            node.performAction(direction)
        } catch (e: Exception) {
            logger.error("AccessibilityService", "Error scrolling node", e)
            false
        }
    }
}