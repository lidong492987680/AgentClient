package com.example.agentclient.scripts.engine

import com.example.agentclient.core.Logger
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 汎用シンプル状態機（1 スクリプト専用）
 *
 * 特徴：
 * - 状態は Enum で表現（State : Enum<State>）
 * - 各状態の処理は StateHandler が担当
 * - ScriptEngine 自体は FSM の中身を知らず、tick() だけを呼び出す
 *
 * 使い方：
 * - スクリプト側で State enum を定義（INIT / LAUNCH_APP / MAIN_LOOP / ...）
 * - StateHandler を実装して handleState() 内で状態ごとの処理を書く
 * - 必要に応じて fsm.moveTo(newState) で遷移
 * - Script の onTick() から fsm.tick() を呼ぶ
 */
class SimpleStateMachine<StateEnum : Enum<StateEnum>>(
    private val logger: Logger,
    private val scriptName: String,
    private val initialState: StateEnum,
    private val maxStateDurationMs: Long,
    private val maxStateRetry: Int,
    private val handler: StateHandler<StateEnum>
) {

    interface StateHandler<StateEnum : Enum<StateEnum>> {

        /**
         * 1 tick 分の状態処理
         * - 現在の状態に応じて必要な処理を行う
         * - 状態遷移が必要な場合は fsm.moveTo(...) を呼び出す
         */
        suspend fun handleState(
            state: StateEnum,
            fsm: SimpleStateMachine<StateEnum>
        )
    }

    private var currentState: StateEnum = initialState
    private val stateStartTime = AtomicLong(System.currentTimeMillis())
    private val stateRetryCount = AtomicInteger(0)

    /**
     * 現在の状態を返す
     */
    fun getCurrentState(): StateEnum = currentState

    /**
     * 現在の状態リトライ回数
     */
    fun getStateRetryCount(): Int = stateRetryCount.get()

    /**
     * 状態を初期状態に戻す
     */
    fun reset() {
        logger.info(scriptName, "FSM reset: $currentState -> $initialState")
        currentState = initialState
        stateStartTime.set(System.currentTimeMillis())
        stateRetryCount.set(0)
    }

    /**
     * 状態遷移
     */
    fun moveTo(newState: StateEnum) {
        if (newState == currentState) return

        logger.info(scriptName, "FSM state change: $currentState -> $newState")
        currentState = newState
        stateStartTime.set(System.currentTimeMillis())
        stateRetryCount.set(0)
    }

    /**
     * ScriptEngine から 1 tick ごとに呼び出される
     *
     * - 状態の滞在時間をチェック
     * - タイムアウトした場合は ScriptException.TIMEOUT を投げる
     * - それ以外は StateHandler に処理を委譲
     */
    suspend fun tick() {
        val now = System.currentTimeMillis()
        val duration = now - stateStartTime.get()

        // 状態滞在時間のチェック（必要であれば）
        if (maxStateDurationMs > 0 && duration > maxStateDurationMs) {
            val retry = stateRetryCount.incrementAndGet()
            logger.warn(
                scriptName,
                "State $currentState timeout after ${duration}ms (retry=$retry/$maxStateRetry)"
            )

            if (retry > maxStateRetry) {
                throw ScriptException(
                    "State $currentState exceeded max retries",
                    ScriptException.Type.TIMEOUT
                )
            }
            // リトライ回数の範囲内であれば、状態自体は維持したまま handleState を継続
            // ただし、stateStartTime はリセットすべきか？
            // ここではリセットせず、次の tick でもタイムアウト判定になるが、
            // handleState 内で何らかのアクション（リトライ処理）が行われることを期待する。
            // あるいは、ここで stateStartTime をリセットして「猶予」を与えるか。
            // シンプルにするため、リセットする。
            stateStartTime.set(now)
        }

        handler.handleState(currentState, this)
    }
}