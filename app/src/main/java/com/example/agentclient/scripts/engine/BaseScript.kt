package com.example.agentclient.scripts.engine

import com.example.agentclient.core.Logger
import com.example.agentclient.scripts.behavior.HumanizedAction

/**
 * スクリプト基底クラス
 * すべての自動化スクリプトはこのクラスを継承する
 * 
 * 設計原則：
 * - 統一された実行フレームワークを提供
 * - 例外を処理し、スクリプトのクラッシュを防ぐ
 * - サブクラスで実装するライフサイクルフックを提供
 */
abstract class BaseScript(
    protected val humanizedAction: HumanizedAction,
    protected val logger: Logger
) {
    
    // ステップ実行間隔時間（ミリ秒）
    open val stepIntervalMs: Long = 2000L
    
    // スクリプト完了フラグ
    private var finished: Boolean = false
    
    // エラーカウント（例外が多すぎる場合は自動停止）
    private var errorCount: Int = 0
    private val maxErrors = 5
    
    /**
     * スクリプト起動時に呼び出される
     * サブクラスはここで初期化操作を行う
     */
    abstract suspend fun onStart()
    
    /**
     * 各ステップ実行時に呼び出される
     * サブクラスはここでメインロジックを実装する
     */
    abstract suspend fun onStep()
    
    /**
     * スクリプト停止時に呼び出される
     * サブクラスはここでクリーンアップ操作を行う
     */
    abstract suspend fun onStop()
    
    /**
     * スクリプトを完了としてマーク
     * サブクラスはすべてのステップ完了後にこのメソッドを呼び出す
     */
    fun markFinished() {
        finished = true
        logger.info(getScriptName(), "Script marked as finished")
    }
    
    /**
     * スクリプトが完了したかどうかをチェック
     */
    fun isFinished(): Boolean = finished
    
    /**
     * 現在の状態説明を取得
     * サブクラスはこのメソッドをオーバーライドして詳細な状態情報を提供できる
     */
    open fun getCurrentState(): String? = null

    /**
     * エラーコールバック
     * スクリプト実行エラー時に呼び出される
     */
    var onError: ((Throwable) -> Unit)? = null

    /**
     * ステップを安全に実行
     * 例外をキャッチし、スクリプトのクラッシュを防ぐ
     * 例外が多すぎる場合は自動的に完了としてマーク
     */
    suspend fun runStepSafely() {
        try {
            onStep()
            errorCount = 0 // 正常実行後はエラーカウントをリセット
        } catch (e: Exception) {
            errorCount++
            logger.error(getScriptName(), "Error in onStep (count: $errorCount)", e)
            
            // エラーコールバックを呼び出す
            onError?.invoke(e)
            
            // 例外が多すぎる場合はスクリプトを停止
            if (errorCount >= maxErrors) {
                logger.error(getScriptName(), "Too many errors ($errorCount), marking script as finished")
                markFinished()
            }
        }
    }
    
    /**
     * スクリプト名を取得（ログ用）
     */
    abstract fun getScriptName(): String
}