package com.example.agentclient.scripts

import android.content.Context
import com.example.agentclient.accessibility.AgentAccessibilityService
import com.example.agentclient.core.Config
import com.example.agentclient.core.Logger
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/**
 * スクリプト基底クラス
 * すべての自動化スクリプトの共通機能を提供する
 *
 * 役割：
 * - ログ出力
 * - 設定アクセス
 * - アクセシビリティ操作のヘルパー
 * - ランダムディレイ／クリックずらし（防封対策）
 * - 実行件数・エラー件数の統計
 *
 * 注意：
 * - 状態遷移（INIT → MAIN_LOOP など）はここでは持たない
 *   → 状態管理は SimpleStateMachine に任せる
 * - 各スクリプトは必要に応じて FSM と連携して状態を管理する
 */
abstract class BaseScript(protected val context: Context) {

    protected val logger = Logger.getInstance(context)
    protected val config = Config.getInstance(context)
    protected val accessibilityService = AgentAccessibilityService.getInstance()

    // 統計情報
    private val taskCompletedCount = AtomicInteger(0)
    private val errorCount = AtomicInteger(0)

    companion object {
        // 状態機関連のデフォルト値（必要であれば FSM 側から利用）
        const val STATE_INIT = "init"
        const val STATE_ERROR = "error"
        const val STATE_RECOVERY = "recovery"

        const val MAX_STATE_DURATION = 60000L // 1 状態あたりのデフォルト最大滞在時間（ms）
        const val MAX_STATE_RETRY = 3        // 同一状態の最大リトライ回数
    }

    /**
     * スクリプト開始時に呼ばれる
     */
    abstract suspend fun onStart(params: Map<String, Any>)

    /**
     * ScriptEngine からの 1 tick ごとの呼び出し
     * 実際の処理は各スクリプトで実装する
     */
    abstract suspend fun onTick()

    /**
     * スクリプト停止時に呼ばれる
     */
    open fun onStop() {
        logger.info(getScriptName(), "Script stopped. Tasks completed: ${taskCompletedCount.get()}")
    }

    /**
     * スクリプト一時停止
     */
    open fun onPause() {
        logger.info(getScriptName(), "Script paused")
    }

    /**
     * スクリプト再開
     */
    open fun onResume() {
        logger.info(getScriptName(), "Script resumed")
    }

    /**
     * エラー発生時に ScriptEngine から呼ばれる
     * ここでは件数カウントとログのみ行う
     * 状態遷移（ERROR → RECOVERY など）は各スクリプト側で必要に応じて上書きする
     */
    open fun onError(error: Throwable) {
        errorCount.incrementAndGet()
        logger.error(getScriptName(), "Script error", error)
    }

    /**
     * TIMEOUT 等で ScriptEngine からリセット要求が来たときに呼ばれる
     * デフォルト実装はログのみ。FSM を使うスクリプトは override して FSM を reset する。
     */
    open fun reset() {
        logger.info(getScriptName(), "Script reset requested")
    }

    /**
     * ログ用のスクリプト名
     */
    abstract fun getScriptName(): String

    /**
     * 現在の状態名を返す（ハートビート用）
     * デフォルトは "unknown"。FSM 利用スクリプトは override して FSM の状態を返す。
     */
    protected open fun getCurrentStateName(): String = "unknown"

    /**
     * 現在の状態リトライ回数（ハートビート用）
     */
    protected open fun getStateRetryCount(): Int = 0

    /**
     * ランダムディレイ（防封対策）
     */
    protected suspend fun randomDelay(minMs: Long = 500, maxMs: Long = 2000) {
        val rhythmConfig = config.getRhythmConfig()
        val actualMin = minMs.coerceAtLeast(rhythmConfig.minDelay)
        val actualMax = maxMs.coerceAtMost(rhythmConfig.maxDelay)
        val delayTime = (actualMin..actualMax).random()
        delay(delayTime)
    }

    /**
     * クリック座標にランダムオフセットを加える（防封対策）
     */
    protected fun tapWithOffset(x: Float, y: Float) {
        val rhythmConfig = config.getRhythmConfig()
        val offset = rhythmConfig.clickOffsetPixels

        val randomX = x + (-offset..offset).random()
        val randomY = y + (-offset..offset).random()

        accessibilityService?.tap(randomX, randomY)
    }

    /**
     * テキストを探してクリックする
     */
    protected suspend fun findAndClickText(
        text: String,
        exactMatch: Boolean = false,
        timeout: Long = 5000
    ): Boolean {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeout) {
            val result = accessibilityService?.findNodeByText(text, exactMatch)
            if (result?.node != null) {
                val success = accessibilityService.clickNode(result.node)
                if (success) {
                    logger.debug(getScriptName(), "Clicked on text: $text")
                    taskCompletedCount.incrementAndGet()
                    return true
                }
            }
            delay(500)
        }

        logger.warn(getScriptName(), "Failed to find text: $text")
        return false
    }

    /**
     * リソースIDを探してクリックする
     */
    protected suspend fun findAndClickId(
        resourceId: String,
        timeout: Long = 5000
    ): Boolean {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeout) {
            val result = accessibilityService?.findNodeById(resourceId)
            if (result?.node != null) {
                val success = accessibilityService.clickNode(result.node)
                if (success) {
                    logger.debug(getScriptName(), "Clicked on id: $resourceId")
                    taskCompletedCount.incrementAndGet()
                    return true
                }
            }
            delay(500)
        }

        logger.warn(getScriptName(), "Failed to find id: $resourceId")
        return false
    }

    /**
     * 対象アプリ上にいるかどうかを確認する
     */
    protected fun checkInTargetApp(packageName: String): Boolean {
        return accessibilityService?.isInTargetApp(packageName) == true
    }

    /**
     * 「メイン画面」に戻る
     * 何をメインとみなすかは isAtMainScreen() の実装に依存する
     */
    protected suspend fun backToMainScreen() {
        repeat(5) { // 最大 5 回 Back を押す
            accessibilityService?.pressBack()
            delay(500)

            // メイン画面に戻れたかどうかを判定
            if (isAtMainScreen()) {
                return
            }
        }

        // まだ戻れていない場合は Home を押してから再度開く前提
        accessibilityService?.pressHome()
        delay(1000)
    }

    /**
     * 「メイン画面」にいるかどうか
     * 各ゲーム／アプリに合わせてサブクラスで実装する
     */
    protected abstract fun isAtMainScreen(): Boolean

    /**
     * 統計情報を取得する（ハートビート等で利用）
     */
    fun getStatistics(): ScriptStatistics {
        return ScriptStatistics(
            taskCompleted = taskCompletedCount.get(),
            errors = errorCount.get(),
            currentState = getCurrentStateName(),
            stateRetries = getStateRetryCount()
        )
    }

    data class ScriptStatistics(
        val taskCompleted: Int,
        val errors: Int,
        val currentState: String,
        val stateRetries: Int
    )
}