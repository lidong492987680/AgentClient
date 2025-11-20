package com.example.agentclient.scripts.engine

import com.example.agentclient.scripts.behavior.HumanizedAction
import com.example.agentclient.core.Logger
import com.example.agentclient.accessibility.GestureDispatcher

/**
 * ゲームスクリプトテンプレート
 * 実際のゲーム自動化スクリプトの基盤となる抽象クラス
 * 
 * 標準的なゲーム自動化フロー：
 * 1. 初期化（Init）
 * 2. ゲーム起動（LaunchGame）
 * 3. ログイン確認（CheckLogin）
 * 4. メインUI待機（WaitMainUi）
 * 5. タスクループ（TaskLoop）
 * 6. BAN対策休憩（AntiBanRest）
 * 7. 終了（Exit）
 * 
 * 変更点：
 * - SimpleStateMachine を使用して状態管理を行うように変更
 */
abstract class GameScriptTemplate(
    humanizedAction: HumanizedAction,
    logger: Logger, protected val gestureDispatcher: GestureDispatcher
) : BaseScript(humanizedAction, logger), SimpleStateMachine.StateHandler<GameScriptTemplate.GameState> {

    /**
     * ゲーム状態を定義するEnum
     */
    enum class GameState {
        INIT,           // 初期化
        LAUNCH_GAME,    // ゲーム起動
        CHECK_LOGIN,    // ログイン状態確認
        WAIT_MAIN_UI,   // メインUI待機
        TASK_LOOP,      // タスク実行ループ
        ANTI_BAN_REST,  // BAN対策休憩
        EXIT            // 終了
    }

    // 状態マシン
    private lateinit var fsm: SimpleStateMachine<GameState>

    // 状態ごとのリトライカウンタ（互換性のため維持）
    private val retryCounters = mutableMapOf<GameState, Int>()

    // 状態ごとの最大リトライ回数
    protected open val maxRetries: Map<GameState, Int> = mapOf(
        GameState.LAUNCH_GAME to 3,
        GameState.CHECK_LOGIN to 5,
        GameState.WAIT_MAIN_UI to 10
    )

    /**
     * スクリプト開始時の処理
     */
    override suspend fun onStart() {
        logger.info(getScriptName(), "ゲームスクリプト開始")
        retryCounters.clear()
        
        // 状態マシンを初期化
        fsm = SimpleStateMachine(
            logger = logger,
            scriptName = getScriptName(),
            initialState = GameState.INIT,
            maxStateDurationMs = 300000, // 5分（安全装置）
            maxStateRetry = 100,         // 内部リトライは多めに設定し、ロジック側で制御
            handler = this
        )
    }

    /**
     * 各ステップで実行される処理
     * 状態マシンの tick を呼び出す
     */
    override suspend fun onStep() {
        // 状態マシンを進行させる
        if (::fsm.isInitialized) {
            fsm.tick()
        }
    }

    /**
     * 状態マシンのハンドラ実装
     */
    override suspend fun handleState(state: GameState, fsm: SimpleStateMachine<GameState>) {
        // 擬人化された待機時間を挿入
        humanizedAction.waitRandomStep()

        // 現在の状態に応じた処理を実行
        val nextState = when (state) {
            GameState.INIT -> handleInit()
            GameState.LAUNCH_GAME -> handleLaunchGame()
            GameState.CHECK_LOGIN -> handleCheckLogin()
            GameState.WAIT_MAIN_UI -> handleWaitMainUi()
            GameState.TASK_LOOP -> handleTaskLoop()
            GameState.ANTI_BAN_REST -> handleAntiBanRest()
            GameState.EXIT -> {
                markFinished()
                GameState.EXIT
            }
        }

        // 状態遷移が必要な場合
        if (nextState != state) {
            transitionTo(nextState)
            fsm.moveTo(nextState)
        }
    }

    /**
     * スクリプト終了時の処理
     */
    override suspend fun onStop() {
        val finalState = if (::fsm.isInitialized) fsm.getCurrentState() else "UNKNOWN"
        logger.info(getScriptName(), "ゲームスクリプト終了: 最終状態=$finalState")
    }

    /**
     * 現在の内部状態を返す（HeartbeatService用）
     */
    override fun getCurrentState(): String = if (::fsm.isInitialized) fsm.getCurrentState().name else "INIT"

    /**
     * 状態遷移ログ出力とリトライカウンタリセット
     */
    private fun transitionTo(newState: GameState) {
        // logger.info(getScriptName(), "状態遷移: ... -> ${newState}") // FSM側でもログが出るので抑制してもよい
        retryCounters[newState] = 0
    }

    /**
     * リトライ処理
     * @return リトライ可能ならtrue、最大回数を超えた場合false
     */
    protected fun shouldRetry(state: GameState): Boolean {
        val currentRetry = retryCounters.getOrDefault(state, 0)
        val maxRetry = maxRetries[state] ?: 3
        retryCounters[state] = currentRetry + 1

        return if (currentRetry < maxRetry) {
            logger.warn(getScriptName(), "状態${state}をリトライ: ${currentRetry + 1}/$maxRetry")
            true
        } else {
            logger.error(getScriptName(), "状態${state}の最大リトライ回数到達")
            false
        }
    }

    // --- 各状態のハンドラ（サブクラスで実装） ---

    /**
     * 初期化処理
     * @return 次の状態
     */
    protected abstract suspend fun handleInit(): GameState

    /**
     * ゲーム起動処理
     * @return 次の状態
     */
    protected abstract suspend fun handleLaunchGame(): GameState

    /**
     * ログイン確認処理
     * @return 次の状態
     */
    protected abstract suspend fun handleCheckLogin(): GameState

    /**
     * メインUI待機処理
     * @return 次の状態
     */
    protected abstract suspend fun handleWaitMainUi(): GameState

    /**
     * タスクループ処理
     * @return 次の状態
     */
    protected abstract suspend fun handleTaskLoop(): GameState

    /**
     * BAN対策休憩処理
     * @return 次の状態
     */
    protected abstract suspend fun handleAntiBanRest(): GameState
}
