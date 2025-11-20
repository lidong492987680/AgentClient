package com.example.agentclient.scripts

import android.content.Context
import com.example.agentclient.accessibility.AgentAccessibilityService
import com.example.agentclient.scripts.engine.ScriptException
import com.example.agentclient.scripts.engine.SimpleStateMachine
import kotlinx.coroutines.delay

/**
 * デモ用テストスクリプト
 * - 今後、各ゲーム用スクリプトはこの構造をテンプレートとして実装する
 */
class TestScript(
    context: Context
) : BaseScript(context), SimpleStateMachine.StateHandler<TestScript.State> {

    companion object {
        private const val SCRIPT_NAME = "TestScript"

        // TODO: 実際のターゲットパッケージ名に差し替える
        private const val TARGET_PACKAGE = "com.example.targetapp"
    }

    /**
     * 状態定義
     */
    enum class State {
        INIT,           // 初期化
        LAUNCH_APP,     // アプリ起動
        MAIN_LOOP,      // メインループ
        ERROR_RECOVERY, // エラー復旧
        COMPLETE        // 完了
    }

    // ループ回数（デモ用）
    private var loopCount: Int = 0

    // FSM 本体
    private val fsm = SimpleStateMachine(
        logger = logger,
        scriptName = SCRIPT_NAME,
        initialState = State.INIT,
        maxStateDurationMs = MAX_STATE_DURATION,
        maxStateRetry = MAX_STATE_RETRY,
        handler = this
    )

    override fun getScriptName(): String = SCRIPT_NAME

    /**
     * ハートビート用に現在の状態名を FSM から返す
     */
    override fun getCurrentStateName(): String = fsm.getCurrentState().name

    override fun getStateRetryCount(): Int = fsm.getStateRetryCount()

    /**
     * TIMEOUT のとき ScriptEngine から呼ばれる
     * FSM を初期状態に戻す
     */
    override fun reset() {
        super.reset()
        loopCount = 0
        fsm.reset()
    }

    /**
     * スクリプト開始時
     */
    override suspend fun onStart(params: Map<String, Any>) {
        logger.info(SCRIPT_NAME, "Script started with params: $params")
        loopCount = 0
        fsm.reset()
    }

    /**
     * ScriptEngine から 1 tick ごとに呼ばれる
     * → FSM に処理を委譲する
     */
    override suspend fun onTick() {
        fsm.tick()
    }

    /**
     * エラー発生時：
     * - 統計カウント・ログは BaseScript に任せる
     * - 状態だけ ERROR_RECOVERY に切り替える
     */
    override fun onError(error: Throwable) {
        super.onError(error)
        // エラーが発生したら復旧状態に遷移
        fsm.moveTo(State.ERROR_RECOVERY)
    }

    /**
     * 「メイン画面」にいるかどうかの判定
     * ここでは簡易的に「ターゲットアプリ上にいるかどうか」で代用
     * 実際のゲームに合わせて条件を調整する
     */
    override fun isAtMainScreen(): Boolean {
        // TODO: 実ゲームに合わせてより厳密な判定を実装する
        return checkInTargetApp(TARGET_PACKAGE)
    }

    // ----------------------------------------------------------------
    // SimpleStateMachine.StateHandler 実装部
    // ----------------------------------------------------------------

    override suspend fun handleState(state: State, fsm: SimpleStateMachine<State>) {
        when (state) {
            State.INIT -> handleInit(fsm)
            State.LAUNCH_APP -> handleLaunchApp(fsm)
            State.MAIN_LOOP -> handleMainLoop(fsm)
            State.ERROR_RECOVERY -> handleErrorRecovery(fsm)
            State.COMPLETE -> {
                // 完了状態：特に何もしない（ScriptEngine 側で stopScript する方針もあり）
                logger.info(SCRIPT_NAME, "State COMPLETE. Waiting for external stop.")
                delay(1000)
            }
        }
    }

    /**
     * INIT 状態の処理
     * - アクセシビリティ有効確認
     * - 軽く待機してから LAUNCH_APP へ
     */
    private suspend fun handleInit(fsm: SimpleStateMachine<State>) {
        logger.info(SCRIPT_NAME, "Initializing...")

        if (!AgentAccessibilityService.isEnabled()) {
            logger.error(SCRIPT_NAME, "Accessibility service not enabled")
            // このエラーは致命的とみなす
            throw ScriptException(
                "Accessibility service not enabled",
                ScriptException.Type.FATAL
            )
        }

        // 少し間をおく（読み込み待ちのイメージ）
        randomDelay(minMs = 500, maxMs = 2000)

        fsm.moveTo(State.LAUNCH_APP)
    }

    /**
     * アプリ起動状態
     * - すでにアプリ上なら MAIN_LOOP へ
     * - そうでなければ Home → アプリ起動（ここでは簡略化）
     */
    private suspend fun handleLaunchApp(fsm: SimpleStateMachine<State>) {
        logger.info(SCRIPT_NAME, "Launching app: $TARGET_PACKAGE")

        val service = accessibilityService

        // すでにターゲットアプリ上にいる場合
        if (service?.isInTargetApp(TARGET_PACKAGE) == true) {
            logger.info(SCRIPT_NAME, "Already in target app")
            fsm.moveTo(State.MAIN_LOOP)
            return
        }

        // Home に戻る
        service?.pressHome()
        randomDelay(minMs = 500, maxMs = 1500)

        // TODO: 実際にはランチャーからアイコンを探してタップする処理を入れる
        logger.info(SCRIPT_NAME, "Assuming app is launched (TODO: 実装)")

        fsm.moveTo(State.MAIN_LOOP)
    }

    /**
     * メインループ状態
     * - デモとして簡単なクリックとスクロールを行う
     * - loopCount が一定数に達したら COMPLETE へ遷移
     */
    private suspend fun handleMainLoop(fsm: SimpleStateMachine<State>) {
        loopCount++
        logger.info(SCRIPT_NAME, "Main loop #$loopCount")

        val service = accessibilityService

        // 例：特定テキストのボタンを探してクリック
        val startButton = service?.findNodeByText("開始", exactMatch = false)
        if (startButton?.node != null) {
            val success = service.clickNode(startButton.node)
            if (success) {
                logger.debug(SCRIPT_NAME, "Clicked start button")
                randomDelay(minMs = 1000, maxMs = 3000)
            }
        }

        // 例：簡単なスクロール
        // 実際には HumanizedAction の scroll を使ってもよい
        val metrics = context.resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val fromY = metrics.heightPixels * 0.7f
        val toY = metrics.heightPixels * 0.3f

        // 少しランダムにずらしてタップ＋ドラッグのように扱う実装も可能
        tapWithOffset(centerX, fromY.toFloat())
        randomDelay(minMs = 300, maxMs = 800)

        // ループ回数が閾値を超えたら完了とみなす
        if (loopCount >= 3) {
            logger.info(SCRIPT_NAME, "Completed $loopCount loops. Move to COMPLETE.")
            fsm.moveTo(State.COMPLETE)
        } else {
            // 次のループまで少し待つ
            randomDelay(minMs = 3000, maxMs = 6000)
        }
    }

    /**
     * エラー復旧状態
     * - Back を押して一階層戻る
     * - 必要であれば Home → 再起動も行う
     * - 最終的に INIT へ戻す
     */
    private suspend fun handleErrorRecovery(fsm: SimpleStateMachine<State>) {
        logger.info(SCRIPT_NAME, "Attempting error recovery...")

        val service = accessibilityService

        // 一旦戻る
        service?.pressBack()
        delay(1000)

        // 必要に応じて Home へ
        if (!checkInTargetApp(TARGET_PACKAGE)) {
            service?.pressHome()
            delay(2000)
        }

        // ループカウンタをリセットして INIT からやり直す
        loopCount = 0
        fsm.moveTo(State.INIT)
    }
}