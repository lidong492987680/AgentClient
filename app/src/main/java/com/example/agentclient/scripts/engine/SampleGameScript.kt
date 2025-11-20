package com.example.agentclient.scripts.engine

import android.content.Context
import com.example.agentclient.scripts.behavior.HumanizedAction
import com.example.agentclient.core.Logger
import com.example.agentclient.core.BehaviorProfile
import com.example.agentclient.core.UiDriver
import com.example.agentclient.accessibility.GestureDispatcher
import kotlinx.coroutines.delay

/**
 * サンプルゲームスクリプト
 * GameScriptTemplateを使用した実際の実装例
 * 
 * このスクリプトは実際のゲーム自動化の動作を模擬します
 * NodeFinder + UiDriverを使用して画面状態を検出し、delay駆動から状態駆動に変更
 */
class SampleGameScript(
    private val context: Context,
    gestureDispatcher: GestureDispatcher
) : GameScriptTemplate(
    HumanizedAction(context, BehaviorProfile.DEFAULT),
    Logger.getInstance(context),
    gestureDispatcher
) {

    // タスクループの実行回数
    private var taskLoopCount = 0
    private val maxTaskLoops = 10 // 10回タスク実行後に休憩

    // 開始時刻（分単位での実行時間計算用）
    private var startTimeMs = 0L

    // UiDriverインスタンス（UI状態検出用）
    private val uiDriver = UiDriver.getInstance(context)

    override fun getScriptName(): String = "SampleGameScript"

    /**
     * 初期化処理
     */
    override suspend fun handleInit(): GameState {
        logger.info(getScriptName(), "初期化中...")
        startTimeMs = System.currentTimeMillis()
        taskLoopCount = 0
        
        // 短い待機（初期化シミュレーション）
        delay(1000)
        
        return GameState.LAUNCH_GAME
    }

    /**
     * ゲーム起動処理
     */
    override suspend fun handleLaunchGame(): GameState {
        logger.info(getScriptName(), "ゲームアプリを起動中...")
        
        // 実際のゲーム起動ロジックをここに実装
        // 例：画面中央をタップしてアプリアイコンをクリック
        try {
            val (randomX, randomY) = humanizedAction.randomizePoint(540, 960)
            val success = gestureDispatcher.click(randomX, randomY)
            
            if (success) {
                logger.info(getScriptName(), "ゲームアプリタップ成功")
                
                // 固定delayの代わりに、ログイン画面が出現するのを待つ
                // ここでは簡略化のため、短い待機のみ（実際は「タイトル画面」などの検出も可能）
                delay(3000)
                
                return GameState.CHECK_LOGIN
            } else {
                return if (shouldRetry(GameState.LAUNCH_GAME)) {
                    delay(humanizedAction.getProfile().errorRetryInterval)
                    GameState.LAUNCH_GAME // リトライ
                } else {
                    logger.error(getScriptName(), "ゲーム起動失敗、終了します")
                    GameState.EXIT
                }
            }
        } catch (e: Exception) {
            logger.error(getScriptName(), "ゲーム起動中にエラー", e)
            return if (shouldRetry(GameState.LAUNCH_GAME)) {
                GameState.LAUNCH_GAME
            } else {
                GameState.EXIT
            }
        }
    }

    /**
     * ログイン状態確認
     * 固定delayではなく、ログイン画面が消えたことをNodeFinderで確認
     */
    override suspend fun handleCheckLogin(): GameState {
        logger.info(getScriptName(), "ログイン状態を確認中...")
        
        // UiDriverを使用してログイン画面の消失を待機
        val loginGone = uiDriver.waitForLoginScreenGone(timeoutMs = 15000)
        
        if (loginGone) {
            logger.info(getScriptName(), "ログイン完了確認")
            return GameState.WAIT_MAIN_UI
        } else {
            logger.warn(getScriptName(), "ログイン画面消失タイムアウト")
            
            // リトライするか判定
            return if (shouldRetry(GameState.CHECK_LOGIN)) {
                logger.info(getScriptName(), "CHECK_LOGINをリトライ")
                delay(humanizedAction.getProfile().errorRetryInterval)
                GameState.CHECK_LOGIN
            } else {
                logger.error(getScriptName(), "ログイン確認失敗、終了します")
                GameState.EXIT
            }
        }
    }

    /**
     * メインUI待機
     * 固定delayではなく、メインメニューが出現したことをNodeFinderで確認
     */
    override suspend fun handleWaitMainUi(): GameState {
        logger.info(getScriptName(), "メインUIを待機中...")
        
        // UiDriverを使用してメインメニューの出現を待機
        val mainMenuAppeared = uiDriver.waitForMainMenu(timeoutMs = 20000)
        
        if (mainMenuAppeared) {
            logger.info(getScriptName(), "メインUI検出完了")
            return GameState.TASK_LOOP
        } else {
            logger.warn(getScriptName(), "メインUI待機タイムアウト")
            
            // リトライするか判定
            return if (shouldRetry(GameState.WAIT_MAIN_UI)) {
                logger.info(getScriptName(), "WAIT_MAIN_UIをリトライ")
                delay(humanizedAction.getProfile().errorRetryInterval)
                GameState.WAIT_MAIN_UI
            } else {
                logger.error(getScriptName(), "メインUI検出失敗、終了します")
                GameState.EXIT
            }
        }
    }

    /**
     * タスクループ実行
     */
    override suspend fun handleTaskLoop(): GameState {
        taskLoopCount++
        logger.info(getScriptName(), "タスク実行中 (${taskLoopCount}/${maxTaskLoops})...")
        
        // 実際のゲームタスクをここに実装
        // 例：クエスト自動実行、資源収集など
        try {
            // タスク1：画面上部をタップ
            val (x1, y1) = humanizedAction.randomizePoint(540, 400)
            gestureDispatcher.click(x1, y1)
            delay(humanizedAction.getPostTapDelay())
            
            // タスク2：画面中央をスワイプ
            val success = gestureDispatcher.swipe(300, 960, 780, 960, 800)
            if (!success) {
                logger.warn(getScriptName(), "スワイプ失敗")
            }
            delay(humanizedAction.getPostTapDelay())
            
            // タスク3：確認ボタンをタップ
            val (x3, y3) = humanizedAction.randomizePoint(540, 1600)
            gestureDispatcher.click(x3, y3)
            
        } catch (e: Exception) {
            logger.error(getScriptName(), "タスク実行中にエラー", e)
        }
        
        // 実行時間をチェックして休憩判定
        val runtimeMinutes = ((System.currentTimeMillis() - startTimeMs) / 60000).toInt()
        val restTime = humanizedAction.checkRest(runtimeMinutes)
        
        return when {
            restTime > 0 -> {
                logger.info(getScriptName(), "BAN対策休憩に入ります")
                GameState.ANTI_BAN_REST
            }
            taskLoopCount >= maxTaskLoops -> {
                logger.info(getScriptName(), "タスクループ完了、スクリプト終了")
                GameState.EXIT
            }
            else -> {
                GameState.TASK_LOOP // 継続
            }
        }
    }

    /**
     * BAN対策休憩
     */
    override suspend fun handleAntiBanRest(): GameState {
        logger.info(getScriptName(), "BAN対策休憩中...")
        
        // 5-10分間休憩（簡略化のため5秒）
        val restTime = (5000..10000).random()
        delay(restTime.toLong())
        
        logger.info(getScriptName(), "休憩終了、タスクループに戻ります")
        return GameState.TASK_LOOP
    }
}