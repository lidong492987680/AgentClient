package com.example.agentclient.accessibility

import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.agentclient.core.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 手势分发器
 * 负责构建手势并请求执行，不直接依赖 AccessibilityService 的具体实现
 */
class GestureDispatcher(
    private val executor: GestureExecutor,
    private val logger: Logger
) {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 手势执行接口
     * 由 AccessibilityService 实现
     */
    interface GestureExecutor {
        fun executeGesture(gesture: GestureDescription, callback: GestureResultCallback?, handler: Handler?): Boolean
        fun isServiceEnabled(): Boolean
    }

    /**
     * 点击坐标
     */
    fun tap(x: Float, y: Float, duration: Long = 50, callback: ((Boolean) -> Unit)? = null): Boolean {
        if (!executor.isServiceEnabled()) {
            logger.error("GestureDispatcher", "Service not enabled")
            callback?.invoke(false)
            return false
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            logger.error("GestureDispatcher", "Tap not supported on API < 24")
            callback?.invoke(false)
            return false
        }

        val path = Path().apply {
            moveTo(x, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        val accepted = executor.executeGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    logger.debug("GestureDispatcher", "Tap completed: ($x, $y)")
                    callback?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    logger.warn("GestureDispatcher", "Tap cancelled: ($x, $y)")
                    callback?.invoke(false)
                }
            },
            handler
        )

        if (!accepted) {
            logger.error("GestureDispatcher", "Failed to dispatch tap gesture")
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
        if (!executor.isServiceEnabled()) {
            logger.error("GestureDispatcher", "Service not enabled")
            callback?.invoke(false)
            return false
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            logger.error("GestureDispatcher", "Swipe not supported on API < 24")
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

        val accepted = executor.executeGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    logger.debug(
                        "GestureDispatcher",
                        "Swipe completed: ($startX, $startY) -> ($endX, $endY)"
                    )
                    callback?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    logger.warn(
                        "GestureDispatcher",
                        "Swipe cancelled: ($startX, $startY) -> ($endX, $endY)"
                    )
                    callback?.invoke(false)
                }
            },
            handler
        )

        if (!accepted) {
            logger.error("GestureDispatcher", "Failed to dispatch swipe gesture")
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
