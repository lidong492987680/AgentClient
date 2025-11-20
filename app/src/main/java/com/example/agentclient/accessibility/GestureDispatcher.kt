package com.example.agentclient.accessibility

import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import com.example.agentclient.core.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * ジェスチャーディスパッチャー
 * すべての手動操作の唯一の入り口となり、直列実行を保証する
 */
class GestureDispatcher(
    private val executor: GestureExecutor,
    private val logger: Logger
) {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * ジェスチャー実行インターフェース
     * AccessibilityServiceによって実装される
     */
    interface GestureExecutor {
        fun executeGesture(gesture: GestureDescription, callback: GestureResultCallback?, handler: Handler?): Boolean
        fun isServiceEnabled(): Boolean
    }

    // 並行実行を防ぐためのMutex
    private val mutex = Mutex()

    /**
     * クリックを実行する（サスペンド関数）
     * @param x X座標
     * @param y Y座標
     * @param duration 持続時間（ミリ秒）
     * @param timeoutMs タイムアウト時間（ミリ秒）
     * @return 実行成功可否
     */
    suspend fun click(x: Int, y: Int, duration: Long = 100, timeoutMs: Long = 2000): Boolean {
        if (!executor.isServiceEnabled()) {
            logger.error("GestureDispatcher", "Service not enabled")
            return false
        }

        // Mutexで保護して直列実行を保証する
        return mutex.withLock {
            try {
                // タイムアウト付きで実行
                withTimeout(timeoutMs) {
                    suspendCoroutine { cont ->
                        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                        val builder = GestureDescription.Builder()
                        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
                        builder.addStroke(stroke)
                        
                        val accepted = executor.executeGesture(
                            builder.build(),
                            object : GestureResultCallback() {
                                override fun onCompleted(gestureDescription: GestureDescription?) {
                                    super.onCompleted(gestureDescription)
                                    logger.debug("GestureDispatcher", "Tap completed: ($x, $y)")
                                    try {
                                        cont.resume(true)
                                    } catch (e: IllegalStateException) {
                                        // 既にresumeされている場合は無視
                                    }
                                }

                                override fun onCancelled(gestureDescription: GestureDescription?) {
                                    super.onCancelled(gestureDescription)
                                    logger.warn("GestureDispatcher", "Tap cancelled: ($x, $y)")
                                    try {
                                        cont.resume(false)
                                    } catch (e: IllegalStateException) {
                                        // 既にresumeされている場合は無視
                                    }
                                }
                            },
                            handler
                        )
                        
                        if (!accepted) {
                            logger.error("GestureDispatcher", "Failed to dispatch tap gesture")
                            try {
                                cont.resume(false)
                            } catch (e: IllegalStateException) {
                                // 既にresumeされている場合は無視
                            }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                logger.error("GestureDispatcher", "Tap timeout: ($x, $y)")
                false
            } catch (e: Exception) {
                logger.error("GestureDispatcher", "Tap error: ($x, $y)", e)
                false
            }
        }
    }

    /**
     * スワイプを実行する（サスペンド関数）
     * @param startX 開始X座標
     * @param startY 開始Y座標
     * @param endX 終了X座標
     * @param endY 終了Y座標
     * @param duration 持続時間（ミリ秒）
     * @param timeoutMs タイムアウト時間（ミリ秒）
     * @return 実行成功可否
     */
    suspend fun swipe(
        startX: Int, startY: Int,
        endX: Int, endY: Int,
        duration: Long = 500,
        timeoutMs: Long = 3000
    ): Boolean {
        if (!executor.isServiceEnabled()) {
            logger.error("GestureDispatcher", "Service not enabled")
            return false
        }

        return mutex.withLock {
            try {
                withTimeout(timeoutMs) {
                    suspendCoroutine { cont ->
                        val path = Path().apply {
                            moveTo(startX.toFloat(), startY.toFloat())
                            lineTo(endX.toFloat(), endY.toFloat())
                        }
                        val builder = GestureDescription.Builder()
                        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
                        builder.addStroke(stroke)

                        val accepted = executor.executeGesture(
                            builder.build(),
                            object : GestureResultCallback() {
                                override fun onCompleted(gestureDescription: GestureDescription?) {
                                    super.onCompleted(gestureDescription)
                                    logger.debug("GestureDispatcher", "Swipe completed")
                                    try {
                                        cont.resume(true)
                                    } catch (e: IllegalStateException) {
                                        // 既にresumeされている場合は無視
                                    }
                                }

                                override fun onCancelled(gestureDescription: GestureDescription?) {
                                    super.onCancelled(gestureDescription)
                                    logger.warn("GestureDispatcher", "Swipe cancelled")
                                    try {
                                        cont.resume(false)
                                    } catch (e: IllegalStateException) {
                                        // 既にresumeされている場合は無視
                                    }
                                }
                            },
                            handler
                        )

                        if (!accepted) {
                            logger.error("GestureDispatcher", "Failed to dispatch swipe gesture")
                            try {
                                cont.resume(false)
                            } catch (e: IllegalStateException) {
                                // 既にresumeされている場合は無視
                            }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                logger.error("GestureDispatcher", "Swipe timeout")
                false
            } catch (e: Exception) {
                logger.error("GestureDispatcher", "Swipe error", e)
                false
            }
        }
    }

    // 互換性のために古いメソッドを残すが、非推奨とする
    @Deprecated("Use suspend click() instead")
    fun tap(x: Float, y: Float, duration: Long = 50, callback: ((Boolean) -> Unit)? = null): Boolean {
        // 簡易実装：メインスレッドで実行するだけ（直列化なし）
        return false 
    }

    @Deprecated("Use suspend swipe() instead")
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 500, callback: ((Boolean) -> Unit)? = null): Boolean {
        return false
    }

    @Deprecated("Use suspend click() instead")
    fun longPress(x: Float, y: Float, duration: Long = 1000, callback: ((Boolean) -> Unit)? = null): Boolean {
        return false
    }

    @Deprecated("Use suspend click() instead")
    fun doubleTap(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null): Boolean {
        return false
    }

    @Deprecated("Use suspend click() instead")
    suspend fun tapSuspend(x: Float, y: Float, duration: Long = 50): Boolean {
        return click(x.toInt(), y.toInt(), duration)
    }

    @Deprecated("Use suspend swipe() instead")
    suspend fun swipeSuspend(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 500): Boolean {
        return swipe(startX.toInt(), startY.toInt(), endX.toInt(), endY.toInt(), duration)
    }
}
