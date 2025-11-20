package com.example.agentclient.core

import android.content.Context
import com.example.agentclient.accessibility.AgentAccessibilityService
import com.example.agentclient.accessibility.NodeFinder
import com.example.agentclient.scripts.behavior.HumanizedAction
import com.example.agentclient.scripts.behavior.BehaviorProfile
import com.example.agentclient.scripts.engine.ScriptEngine
import com.example.agentclient.scripts.engine.TestScript

/**
 * UI操作統一エントリーポイント
 * ファサードパターンとして、外部にシンプルなスクリプト制御インターフェースを提供
 * 
 * 責務：
 * - HumanizedActionインスタンスの管理
 * - スクリプトの起動/停止インターフェース提供
 * - MainActivityとScriptEngineのブリッジ
 * - すべての利用可能なスクリプトの登録
 * - UI状態検出のビジネスセマンティクス提供
 */
class UiDriver private constructor(private val context: Context) {
    
    private val logger = Logger.getInstance(context)
    private var humanizedAction: HumanizedAction? = null
    
    // NodeFinderインスタンス（UI検出用）
    private val nodeFinder: NodeFinder by lazy {
        NodeFinder(logger) {
            AgentAccessibilityService.getInstance()
        }
    }
    
    // スクリプト登録済みフラグ
    private var scriptsRegistered = false
    
    companion object {
        @Volatile
        private var instance: UiDriver? = null
        
        fun getInstance(context: Context): UiDriver {
            return instance ?: synchronized(this) {
                instance ?: UiDriver(context.applicationContext).also {
                    instance = it
                    // 初期化時にすべてのスクリプトを登録
                    it.registerAllScripts()
                }
            }
        }
    }
    
    /**
     * すべての利用可能なスクリプトを登録
     * アプリケーション起動時に一度呼び出される
     */
    private fun registerAllScripts() {
        if (scriptsRegistered) {
            logger.warn("UiDriver", "スクリプトは既に登録されています、重複登録をスキップ")
            return
        }
        
        // テストスクリプトを登録
        ScriptEngine.registerScript("test_script") { humanizedAction ->
            TestScript(humanizedAction, logger)
        }
        
        // 将来ここでより多くのスクリプトを登録できる
        // ScriptEngine.registerScript("game_a_script") { humanizedAction ->
        //     GameAScript(humanizedAction, logger)
        // }
        
        scriptsRegistered = true
        logger.info("UiDriver", "すべてのスクリプト登録完了")
    }
    
    /**
     * テストスクリプトを起動
     * スクリプト登録システムを使用して名前で起動
     */
    fun startTestScript() {
        // HumanizedActionを初期化（まだ初期化されていない場合）
        if (humanizedAction == null) {
            humanizedAction = HumanizedAction(context, BehaviorProfile.DEFAULT)
            logger.info("UiDriver", "HumanizedAction initialized with DEFAULT profile")
        }
        
        // 名前でスクリプトを起動
        val success = ScriptEngine.startScriptByName("test_script", humanizedAction!!)
        
        if (success) {
            logger.info("UiDriver", "テストスクリプト起動成功")
        } else {
            logger.error("UiDriver", "テストスクリプト起動失敗：スクリプトが未登録")
        }
    }
    
    /**
     * 名前でスクリプトを起動（汎用インターフェース、CommandProcessorなどが使用）
     * 
     * @param scriptName スクリプト名
     * @return true 起動成功の場合
     */
    fun startScriptByName(scriptName: String): Boolean {
        // HumanizedActionを初期化（まだ初期化されていない場合）
        if (humanizedAction == null) {
            humanizedAction = HumanizedAction(context, BehaviorProfile.DEFAULT)
            logger.info("UiDriver", "HumanizedAction initialized with DEFAULT profile")
        }
        
        val success = ScriptEngine.startScriptByName(scriptName, humanizedAction!!)
        
        if (success) {
            logger.info("UiDriver", "スクリプト '$scriptName' 起動成功")
        } else {
            logger.error("UiDriver", "スクリプト '$scriptName' 起動失敗：スクリプトが未登録")
        }
        
        return success
    }
    
    /**
     * 現在のスクリプトを停止
     */
    fun stopScript() {
        ScriptEngine.stopCurrentScript()
        logger.info("UiDriver", "現在のスクリプトを停止")
    }
    
    /**
     * スクリプトが実行中かチェック
     */
    fun isScriptRunning(): Boolean {
        return ScriptEngine.isRunning()
    }
    
    /**
     * 現在実行中のスクリプト名を取得
     */
    fun getCurrentScriptName(): String? {
        return ScriptEngine.getCurrentScriptName()
    }
    
    /**
     * 行動設定を設定
     * HumanizedActionが使用するBehaviorProfileを切り替え
     * 
     * @param profileName 設定名（DEFAULT / FAST_YOUNG / SLOW_CAREFUL）
     * @return true 切り替え成功の場合
     */
    fun setBehaviorProfile(profileName: String): Boolean {
        val profile = when (profileName.uppercase()) {
            "DEFAULT" -> BehaviorProfile.DEFAULT
            "FAST_YOUNG" -> BehaviorProfile.FAST_YOUNG
            "SLOW_CAREFUL" -> BehaviorProfile.SLOW_CAREFUL
            else -> {
                logger.warn("UiDriver", "未知の行動設定: $profileName")
                return false
            }
        }
        
        // HumanizedActionがまだ初期化されていない場合は先に初期化
        if (humanizedAction == null) {
            humanizedAction = HumanizedAction(context, profile)
            logger.info("UiDriver", "HumanizedAction initialized with $profileName profile")
        } else {
            humanizedAction!!.setProfile(profile)
            logger.info("UiDriver", "行動設定切替: $profileName")
        }
        
        return true
    }
    
    // ========== UI状態検出のビジネスセマンティクス関数 ==========

    /**
     * ノード検索条件
     * 画面上の要素を特定するための条件を定義
     */
    sealed class NodeCondition {
        data class Text(val text: String, val exactMatch: Boolean = false) : NodeCondition()
        data class Id(val id: String) : NodeCondition()
    }

    /**
     * 汎用ノード検索（サスペンド関数）
     * 指定された条件に一致するノードを検索する
     * 
     * @param condition 検索条件
     * @param timeoutMs タイムアウト時間（ミリ秒）
     * @return 見つかったノード。注意：呼び出し側が recycle() する必要があります。
     */
    suspend fun findNode(condition: NodeCondition, timeoutMs: Long = 10000): android.view.accessibility.AccessibilityNodeInfo? {
        val result = when (condition) {
            is NodeCondition.Text -> nodeFinder.waitForNodeByText(condition.text, condition.exactMatch, timeoutMs)
            is NodeCondition.Id -> nodeFinder.waitForNodeById(condition.id, timeoutMs)
        }
        // NodeFinder は NodeSearchResult を返す。
        // result.node は呼び出し側（ここ）が所有権を持つ。
        // ここでは AccessibilityNodeInfo を直接返すが、所有権はさらに呼び出し側に移譲する。
        return result?.node
    }

    /**
     * ノードが表示されるまで待機（サスペンド関数）
     * findNode のエイリアス（意味合いを明確にするため）
     */
    suspend fun waitNodeVisible(condition: NodeCondition, timeoutMs: Long = 10000): android.view.accessibility.AccessibilityNodeInfo? {
        return findNode(condition, timeoutMs)
    }

    /**
     * ノードが表示されたらタップする（サスペンド関数）
     * 
     * @param condition 検索条件
     * @param timeoutMs タイムアウト時間
     * @return タップ成功なら true
     */
    suspend fun tapWhenVisible(condition: NodeCondition, timeoutMs: Long = 10000): Boolean {
        val node = findNode(condition, timeoutMs) ?: return false
        try {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            val x = rect.centerX().toFloat()
            val y = rect.centerY().toFloat()
            
            logger.info("UiDriver", "ノードを検出、タップを実行: ($x, $y)")
            
            val service = AgentAccessibilityService.getInstance()
            if (service != null) {
                return service.tapSuspend(x, y)
            } else {
                logger.error("UiDriver", "AccessibilityService is null")
                return false
            }
        } catch (e: Exception) {
            logger.error("UiDriver", "タップ実行中にエラー", e)
            return false
        } finally {
            // ノードは必ず recycle する
            node.recycle()
        }
    }
    
    /**
     * ログイン画面が消えるまで待機
     * ログイン画面特有のUI要素（例：「ログイン」ボタン）が消失したことを確認
     * 
     * @param timeoutMs タイムアウト時間（ミリ秒）
     * @return true ログイン画面が消えた、false タイムアウト
     */
    suspend fun waitForLoginScreenGone(timeoutMs: Long = 10000): Boolean {
        logger.info("UiDriver", "ログイン画面の消失を待機中...")
        
        // 「ログイン」「LOGIN」などのテキストが消えるのを待つ
        // 実際のゲームに合わせて調整が必要
        val gone = nodeFinder.waitForNodeDisappear(
            text = "ログイン",  // 実際のゲームのログインボタンテキストに変更
            exactMatch = false,
            timeoutMs = timeoutMs
        )
        
        if (gone) {
            logger.info("UiDriver", "ログイン画面消失確認")
        } else {
            logger.warn("UiDriver", "ログイン画面消失待機タイムアウト")
        }
        
        return gone
    }
    
    /**
     * メインメニュー画面が出現するまで待機
     * メインメニュー特有のUI要素（例：「開始」「スタート」ボタン）が出現することを確認
     * 
     * @param timeoutMs タイムアウト時間（ミリ秒）
     * @return true メインメニュー検出成功、false タイムアウト
     */
    suspend fun waitForMainMenu(timeoutMs: Long = 15000): Boolean {
        logger.info("UiDriver", "メインメニューUIを待機中...")
        
        // 「開始」「スタート」「ホーム」などのテキストを待つ
        // 実際のゲームに合わせて複数の候補をチェックすることも可能
        val result = nodeFinder.waitForNodeByText(
            text = "開始",  // 実際のゲームのメインメニューボタンテキストに変更
            exactMatch = false,
            timeoutMs = timeoutMs
        )
        
        // 結果のノードを回収
        result?.node?.recycle()
        
        if (result != null) {
            logger.info("UiDriver", "メインメニューUI検出完了")
            return true
        } else {
            logger.warn("UiDriver", "メインメニューUI待機タイムアウト")
            return false
        }
    }
    
    /**
     * 特定のテキストを持つノードが出現するまで待機（汎用）
     * 
     * @param text 検索するテキスト
     * @param timeoutMs タイムアウト時間
     * @return true ノード検出成功
     */
    suspend fun waitForText(text: String, timeoutMs: Long = 10000): Boolean {
        logger.debug("UiDriver", "テキスト '$text' を待機中...")
        
        val result = nodeFinder.waitForNodeByText(
            text = text,
            exactMatch = false,
            timeoutMs = timeoutMs
        )
        
        result?.node?.recycle()
        
        return result != null
    }
}