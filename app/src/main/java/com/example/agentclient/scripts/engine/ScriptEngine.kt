package com.example.agentclient.scripts.engine

import android.content.Context
import com.example.agentclient.core.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * スクリプト実行エンジン
 * スクリプトのライフサイクル管理と実行を担当
 * 
 * 責務：
 * - スクリプトの起動/停止
 * - スクリプト実行のコルーチン管理
 * - スクリプトのライフサイクルメソッド呼び出し
 * - スクリプト実行状態のクエリ提供
 * - 日次実行時間の統計
 */
object ScriptEngine {
    
    private var context: Context? = null
    private var logger: Logger? = null
    
    // スクリプト登録テーブル：スクリプトファクトリ関数を保存
    private val scriptFactories: MutableMap<String, (com.example.agentclient.scripts.behavior.HumanizedAction) -> BaseScript> = mutableMapOf()
    
    // 現在実行中のスクリプト
    private var currentScript: BaseScript? = null
    
    // スクリプト実行のコルーチンタスク
    private var scriptJob: Job? = null
    
    // コルーチンスコープ
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // 当日の累計実行時間（ミリ秒）
    private var dailyRuntimeMs: Long = 0L
    
    // 実行時間統計の日付
    private var runtimeDate: LocalDate? = null
    
    // 前回実行時間更新時のタイムスタンプ
    private var lastUpdateTime: Long = 0L
    
    /**
     * ScriptEngineを初期化
     * 使用前に必ず呼び出す
     */
    fun initialize(ctx: Context) {
        context = ctx
        logger = Logger.getInstance(ctx)
    }
    
    /**
     * スクリプトを起動
     * 既にスクリプトが実行中の場合は、古いスクリプトを先に停止する
     */
    fun startScript(script: BaseScript) {
        val log = logger ?: return
        
        // 既にスクリプトが実行中の場合は先に停止
        if (currentScript != null) {
            log.info("ScriptEngine", "既存スクリプトを停止してから新しいスクリプトを起動")
            stopCurrentScript()
        }
        
        currentScript = script
        
        // コルーチンを起動してスクリプトを実行
        scriptJob = scope.launch {
            val heartbeatService = context?.let { com.example.agentclient.network.HeartbeatService.getInstance(it) }
            val startTime = System.currentTimeMillis()
            
            // 実行時間統計を初期化
            val currentDate = LocalDate.now()
            if (runtimeDate != currentDate) {
                // 新しい日、カウントをリセット
                runtimeDate = currentDate
                dailyRuntimeMs = 0L
            }
            lastUpdateTime = System.currentTimeMillis()
            
            try {
                log.info("ScriptEngine", "スクリプト起動: ${script.getScriptName()}")
                
                // エラーコールバックを設定
                script.onError = { error ->
                    heartbeatService?.reportError("SCRIPT_ERROR", error.message)
                    heartbeatService?.updateScriptStatus(
                        com.example.agentclient.network.HeartbeatService.ScriptStatus(
                            key = script.getScriptName(),
                            status = "error",
                            startTime = startTime,
                            currentState = script.getCurrentState(),
                            dailyRuntimeMin = (dailyRuntimeMs / 60000).toInt()
                        )
                    )
                }
                
                // 初期状態をレポート
                heartbeatService?.updateScriptStatus(
                    com.example.agentclient.network.HeartbeatService.ScriptStatus(
                        key = script.getScriptName(),
                        status = "running",
                        startTime = startTime,
                        currentState = script.getCurrentState(),
                        dailyRuntimeMin = (dailyRuntimeMs / 60000).toInt()
                    )
                )
                
                // スクリプトのonStartを呼び出す
                try {
                    script.onStart()
                } catch (e: Exception) {
                    log.error("ScriptEngine", "onStart()でエラー発生", e)
                    heartbeatService?.reportError("SCRIPT_START_ERROR", e.message)
                    // onStartが失敗してもスクリプトを終了としてマーク
                    script.markFinished()
                }
                
                // スクリプトが完了またはキャンセルされるまでステップをループ実行
                while (isActive && !script.isFinished()) {
                    // runStepSafely()内部でonStep()を呼び出し、例外をキャッチ
                    script.runStepSafely()
                    delay(script.stepIntervalMs)
                    
                    // 実行時間統計を更新
                    val now = System.currentTimeMillis()
                    val nowDate = LocalDate.now()
                    
                    // 日付が変わったかチェック
                    if (nowDate != runtimeDate) {
                        // 日付が変わった、統計をリセット
                        runtimeDate = nowDate
                        dailyRuntimeMs = 0L
                        lastUpdateTime = now
                    } else {
                        // 実行時間を累積
                        dailyRuntimeMs += (now - lastUpdateTime)
                        lastUpdateTime = now
                    }
                    
                    // 実行状態を更新
                    heartbeatService?.updateScriptStatus(
                        com.example.agentclient.network.HeartbeatService.ScriptStatus(
                            key = script.getScriptName(),
                            status = "running",
                            startTime = startTime,
                            currentState = script.getCurrentState(),
                            dailyRuntimeMin = (dailyRuntimeMs / 60000).toInt()
                        )
                    )
                }
                
                log.info("ScriptEngine", "スクリプト完了: ${script.getScriptName()}")
            } catch (e: Exception) {
                // 予期しないエラー（通常はキャンセルやコルーチン例外）
                log.error("ScriptEngine", "スクリプト実行エラー", e)
                heartbeatService?.reportError("SCRIPT_ENGINE_ERROR", e.message)
            } finally {
                // onStopを常に呼び出す（例外が発生してもクリーンアップを保証）
                try {
                    script.onStop()
                } catch (e: Exception) {
                    log.error("ScriptEngine", "onStop()でエラー発生", e)
                }
                
                // 最終状態をレポート
                heartbeatService?.updateScriptStatus(
                    com.example.agentclient.network.HeartbeatService.ScriptStatus(
                        key = script.getScriptName(),
                        status = "stopped",
                        lastSuccess = System.currentTimeMillis(),
                        startTime = startTime,
                        currentState = script.getCurrentState(),
                        dailyRuntimeMin = (dailyRuntimeMs / 60000).toInt()
                    )
                )
                
                // クリーンアップ
                currentScript = null
                scriptJob = null
            }
        }
    }
    
    /**
     * 現在実行中のスクリプトを停止
     */
    fun stopCurrentScript() {
        val log = logger ?: return
        val script = currentScript ?: return
        
        log.info("ScriptEngine", "スクリプトを停止中: ${script.getScriptName()}")
        
        // スクリプトを完了としてマーク（ループを優雅に終了させる）
        script.markFinished()
        
        // コルーチンをキャンセル
        scriptJob?.cancel()
        
        // 注意：currentScriptとscriptJobのクリーンアップはfinally{}ブロックで行われる
    }
    
    /**
     * スクリプトが実行中かどうかをチェック
     */
    fun isRunning(): Boolean {
        return scriptJob?.isActive == true
    }
    
    /**
     * スクリプトファクトリを登録
     * 名前でスクリプトを作成・起動できるようにする
     * 
     * @param name スクリプト名（一意の識別子）
     * @param factory スクリプトファクトリ関数、HumanizedActionを受け取りBaseScriptを返す
     */
    fun registerScript(name: String, factory: (com.example.agentclient.scripts.behavior.HumanizedAction) -> BaseScript) {
        val log = logger
        
        if (scriptFactories.containsKey(name)) {
            log?.warn("ScriptEngine", "スクリプト '$name' は既に存在します、上書きされます")
        }
        
        scriptFactories[name] = factory
        log?.info("ScriptEngine", "スクリプト '$name' 登録成功")
    }
    
    /**
     * 名前でスクリプトを起動
     * 登録テーブルからスクリプトファクトリを検索してインスタンスを作成し起動
     * 
     * @param name スクリプト名
     * @param humanizedAction HumanizedActionインスタンス
     * @return true 起動成功、false スクリプトが未登録
     */
    fun startScriptByName(name: String, humanizedAction: com.example.agentclient.scripts.behavior.HumanizedAction): Boolean {
        val log = logger ?: return false
        
        val factory = scriptFactories[name]
        if (factory == null) {
            log.warn("ScriptEngine", "スクリプト '$name' は未登録、起動できません")
            return false
        }
        
        // ファクトリを使用してスクリプトインスタンスを作成
        val script = factory(humanizedAction)
        
        // スクリプトを起動
        startScript(script)
        log.info("ScriptEngine", "名前でスクリプトを起動: $name")
        
        return true
    }
    
    /**
     * 現在実行中のスクリプト名を取得
     */
    fun getCurrentScriptName(): String? {
        return currentScript?.getScriptName()
    }
}

/**
 * スクリプト例外クラス
 * 異なるタイプのスクリプトエラーを区別するために使用
 */
class ScriptException(
    message: String,
    val type: Type,
    cause: Throwable? = null
) : Exception(message, cause) {
    
    enum class Type {
        RECOVERABLE,  // 回復可能なエラー
        FATAL,        // 致命的なエラー
        TIMEOUT       // タイムアウトエラー
    }
}