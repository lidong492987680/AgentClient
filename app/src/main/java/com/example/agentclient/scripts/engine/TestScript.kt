package com.example.agentclient.scripts.engine

import com.example.agentclient.core.Logger
import com.example.agentclient.scripts.behavior.HumanizedAction
import kotlinx.coroutines.delay

/**
 * テストスクリプト
 * スクリプト実行チェーンが正常に動作するか検証する
 * 
 * 機能：
 * - 5回のシンプルな待機操作を実行
 * - 各操作間に遅延を挿入
 * - 完了後に自動停止
 */
class TestScript(
    humanizedAction: HumanizedAction,
    logger: Logger
) : BaseScript(humanizedAction, logger) {
    
    // ステップカウンター
    private var stepCount = 0
    
    // 最大ステップ数
    private val maxSteps = 5
    
    override fun getScriptName(): String = "TestScript"
    
    /**
     * スクリプト開始時に呼び出される
     */
    override suspend fun onStart() {
        logger.info(getScriptName(), "TestScript started")
        stepCount = 0
    }
    
    /**
     * 各ステップで実行されるロジック
     * ここでは単純な待機とログ出力を行う
     */
    override suspend fun onStep() {
        stepCount++
        logger.info(getScriptName(), "Executing step $stepCount/$maxSteps")
        
        // 擬人化された待機時間を挿入
        humanizedAction.waitRandomStep()
        
        // ステップ実行のシミュレーション
        logger.debug(getScriptName(), "Step $stepCount completed")
        
        // 最大ステップ数に達したら完了とマーク
        if (stepCount >= maxSteps) {
            logger.info(getScriptName(), "Reached max steps, marking as finished")
            markFinished()
        }
    }
    
    /**
     * スクリプト停止時に呼び出される
     */
    override suspend fun onStop() {
        logger.info(getScriptName(), "TestScript finished with steps=$stepCount")
    }
}
