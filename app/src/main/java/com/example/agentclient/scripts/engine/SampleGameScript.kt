package com.example.agentclient.scripts.engine

import android.content.Context
import com.example.agentclient.scripts.behavior.HumanizedAction
import com.example.agentclient.core.Logger
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
    humanizedAction: HumanizedAction,
    logger: Logger,
    gestureDispatcher: GestureDispatcher
) : GameScriptTemplate(humanizedAction, logger, gestureDispatcher) {

    // タスクループの実行回数
    private var taskLoopCount = 0
    private val maxTaskLoops = 3 // テスト用に少なめに設定

    // 開始時刻（分単位での実行時間計算用）
    private var startTimeMs = 0L

    // UiDriverインスタンス（UI状態検出用）
    private val uiDriver = UiDriver.getInstance(context)

    override fun getScriptName(): String = "SampleGameScript"

    /**
     * 初期化処理
     * スクリプト開始時の準備を行う
     */
    override suspend fun handleInit(): GameState {
        logger.info(getScriptName(), "初期化中...")
        startTimeMs = System.currentTimeMillis()
        taskLoopCount = 0
        
        // 初期化シミュレーション
        delay(500)
        
        // 次の状態に遷移
        return GameState.LAUNCH_GAME
    }

    /**
     * ゲーム起動処理
     * アプリアイコンをタップしてゲームを起動する
     */
    override suspend fun handleLaunchGame(): GameState {
        logger.info(getScriptName(), "ゲームアプリを起動中...")
        
        try {
            // 方法1: UiDriverを使ってアプリアイコンを探してタップ
            // 実環境では実際のアプリ名に変更する必要がある
            val found = uiDriver.tapWhenVisible(
                UiDriver.NodeCondition.Text("GameAgentClient", false),
                timeoutMs = 3000
            )
            
            if (!found) {
                // 方法2: アイコンが見つからない場合は座標タップにフォールバック
                logger.info(getScriptName(), "アプリアイコンが見つからないため、座標タップを試行")
                val (randomX, randomY) = humanizedAction.randomizePoint(540, 960)
                gestureDispatcher.click(randomX, randomY)
            }
            
            // アプリ起動待ち
            delay(3000)
            return GameState.CHECK_LOGIN

        } catch (e: Exception) {
            logger.error(getScriptName(), "ゲーム起動中にエラー", e)
            // リトライ判定
            return if (shouldRetry(GameState.LAUNCH_GAME)) {
                GameState.LAUNCH_GAME
            } else {
                GameState.EXIT
            }
        }
    }

    /**
     * ログイン状態確認
     * ログインボタンがあれば押す、なければログイン済みと判断
     */
    override suspend fun handleCheckLogin(): GameState {
        logger.info(getScriptName(), "ログイン状態を確認中...")
        
        try {
            // ログインボタンを探してタップを試みる（テスト用のモック処理）
            val loginButtonTapped = uiDriver.tapWhenVisible(
                UiDriver.NodeCondition.Text("Login", false),
                timeoutMs = 5000
            )
            
            if (loginButtonTapped) {
                logger.info(getScriptName(), "ログインボタンをタップしました")
                // ログイン処理の完了を待つ
                delay(2000)
            } else {
                // ログインボタンが見つからない = 既にログイン済み
                logger.info(getScriptName(), "ログインボタンが見つかりません、既にログイン済みと判断")
            }
            
            // ログイン完了後、メインUIへ遷移
            return GameState.WAIT_MAIN_UI
            
        } catch (e: Exception) {
            logger.error(getScriptName(), "ログイン確認中にエラー", e)
            return if (shouldRetry(GameState.CHECK_LOGIN)) {
                GameState.CHECK_LOGIN
            } else {
                GameState.EXIT
            }
        }
    }

    /**
     * メインUI待機
     * メインメニューが表示されるまで待機する
     */
    override suspend fun handleWaitMainUi(): GameState {
        logger.info(getScriptName(), "メインUIを待機中...")
        
        try {
            // 実際のゲームでは「Start」「開始」「Menu」などのテキストを待つ
            // ここではテスト用に簡略化
            // 方法1: 特定のテキストを待つ
            val mainUiDetected = uiDriver.waitForText("Start", timeoutMs = 5000)
            
            if (mainUiDetected) {
                logger.info(getScriptName(), "メインUI検出完了")
                return GameState.TASK_LOOP
            } else {
                // テスト環境では特定のUI要素がないため、短い遅延後に進む
                logger.warn(getScriptName(), "メインUIが検出できませんが、タスクループに進みます")
                delay(1000)
                return GameState.TASK_LOOP
            }
            
        } catch (e: Exception) {
            logger.error(getScriptName(), "メインUI待機中にエラー", e)
            return if (shouldRetry(GameState.WAIT_MAIN_UI)) {
                GameState.WAIT_MAIN_UI
            } else {
                GameState.EXIT
            }
        }
    }

    /**
     * タスクループ実行
     * テスト用の簡易タスクを実行する
     * 実際のゲームでは、ここにゲーム固有のロジックを実装する
     */
    override suspend fun handleTaskLoop(): GameState {
        taskLoopCount++
        logger.info(getScriptName(), "タスク実行中 (${taskLoopCount}/${maxTaskLoops})...")
        
        try {
            // タスク1：画面をランダムにタップ
            val (x1, y1) = humanizedAction.randomizePoint(540, 500)
            gestureDispatcher.click(x1, y1)
            delay(humanizedAction.getPostTapDelay())
            
            // タスク2：スワイプ操作
            val (startX, startY) = humanizedAction.randomizePoint(300, 1000)
            val (endX, endY) = humanizedAction.randomizePoint(800, 1000)
            gestureDispatcher.swipe(startX, startY, endX, endY, 500)
            delay(humanizedAction.getPostTapDelay())
            
            logger.debug(getScriptName(), "タスク${taskLoopCount}完了")
            
        } catch (e: Exception) {
            logger.error(getScriptName(), "タスク実行中にエラー", e)
        }
        
        // 終了判定
        if (taskLoopCount >= maxTaskLoops) {
            logger.info(getScriptName(), "タスクループ完了、スクリプトを終了します")
            return GameState.EXIT
        }
        
        // まだループ継続可能な場合
        // 一定確率でBAN対策休憩を挟む（実際のゲームではより精巧なロジックを使用）
        val shouldRest = taskLoopCount % 5 == 0 // 5回ごとに休憩
        return if (shouldRest) {
            GameState.ANTI_BAN_REST
        } else {
            GameState.TASK_LOOP
        }
    }

    /**
     * BAN対策休憩
     * ゲームのBAN対策として不定期に休憩を入れる
     */
    override suspend fun handleAntiBanRest(): GameState {
        logger.info(getScriptName(), "BAN対策休憩中...")
        
        // テスト用に短い休憩、実際のゲームではより長い休憩を入れる
        val restDuration = humanizedAction.getPostTapDelay() * 3
        delay(restDuration)
        
        logger.info(getScriptName(), "休憩完了、タスクループに戻ります")
        return GameState.TASK_LOOP
    }
}