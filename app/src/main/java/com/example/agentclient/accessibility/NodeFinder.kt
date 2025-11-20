package com.example.agentclient.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.agentclient.core.Logger
import kotlinx.coroutines.delay

/**
 * ノードファインダー
 * AccessibilityNodeInfoツリー内でノードを検索する
 * 
 * Recycle戦略：
 * 1. 渡されたrootノードは呼び出し側が回収する責任を持つ（必要な場合）
 * 2. 内部の再帰処理中に生成された中間ノード（child）は、結果として選択されなかった場合は内部でrecycleする
 * 3. 返されたNodeSearchResultに含まれるnodeは、呼び出し側がrecycleする責任を持つ
 */
class NodeFinder(
    private val logger: Logger,
    private val serviceProvider: () -> AgentAccessibilityService?
) {

    /**
     * ノード検索結果
     */
    data class NodeSearchResult(
        val node: AccessibilityNodeInfo,
        val bounds: Rect = Rect(),
        val text: String? = null,
        val contentDescription: String? = null,
        val className: String? = null,
        val isClickable: Boolean = false,
        val isScrollable: Boolean = false
    ) {
        init {
            node.getBoundsInScreen(bounds)
        }
    }

    /**
     * 現在のアクティブウィンドウのルートノードを取得
     * 注意：返されたノードは呼び出し側がrecycleする必要がある
     */
    fun getRootNode(): AccessibilityNodeInfo? {
        return try {
            serviceProvider()?.rootInActiveWindow
        } catch (e: Exception) {
            logger.error("NodeFinder", "ルートノード取得エラー", e)
            null
        }
    }

    /**
     * テキストでノードを検索
     */
    fun findByText(
        root: AccessibilityNodeInfo?,
        text: String,
        exactMatch: Boolean = false
    ): NodeSearchResult? {
        if (root == null) {
            logger.warn("NodeFinder", "Root node is null")
            return null
        }

        return try {
            if (exactMatch) {
                findNodeRecursive(root) { node ->
                    node.text?.toString() == text ||
                            node.contentDescription?.toString() == text
                }
            } else {
                findNodeRecursive(root) { node ->
                    node.text?.toString()?.contains(text, ignoreCase = true) == true ||
                            node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true
                }
            }
        } catch (e: Exception) {
            logger.error("NodeFinder", "Error finding node by text: $text", e)
            null
        }
    }

    /**
     * IDでノードを検索
     */
    fun findById(
        root: AccessibilityNodeInfo?,
        resourceId: String
    ): NodeSearchResult? {
        if (root == null) {
            logger.warn("NodeFinder", "Root node is null")
            return null
        }

        return try {
            // findAccessibilityNodeInfosByViewIdは新しく作成されたノードリストを返すので、回収する必要がある
            val nodes = root.findAccessibilityNodeInfosByViewId(resourceId)
            if (!nodes.isNullOrEmpty()) {
                val target = nodes[0]
                // 他の不要なノードを回収
                for (i in 1 until nodes.size) {
                    nodes[i].recycle()
                }
                
                NodeSearchResult(
                    node = target,
                    text = target.text?.toString(),
                    contentDescription = target.contentDescription?.toString(),
                    className = target.className?.toString(),
                    isClickable = target.isClickable,
                    isScrollable = target.isScrollable
                )
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error("NodeFinder", "Error finding node by id: $resourceId", e)
            null
        }
    }

    /**
     * クラス名でノードを検索
     */
    fun findByClassName(
        root: AccessibilityNodeInfo?,
        className: String
    ): NodeSearchResult? {
        if (root == null) {
            logger.warn("NodeFinder", "Root node is null")
            return null
        }

        return try {
            findNodeRecursive(root) { node ->
                node.className?.toString() == className
            }
        } catch (e: Exception) {
            logger.error("NodeFinder", "Error finding node by class: $className", e)
            null
        }
    }

    /**
     * テキストでノードが出現するまで待機（サスペンド関数）
     * @param text 検索するテキスト
     * @param exactMatch 完全一致か部分一致か
     * @param timeoutMs タイムアウト時間（ミリ秒）
     * @param pollIntervalMs ポーリング間隔（ミリ秒）
     * @return 見つかったノード、タイムアウトした場合null
     */
    suspend fun waitForNodeByText(
        text: String,
        exactMatch: Boolean = false,
        timeoutMs: Long = 10000,
        pollIntervalMs: Long = 500
    ): NodeSearchResult? {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val root = getRootNode()

            if (root != null) {
                var result: NodeSearchResult? = null
                try {
                    result = findByText(root, text, exactMatch)
                    if (result != null) {
                        logger.debug("NodeFinder", "テキスト '$text' を持つノードを検出")
                        // root はここでは recycle しない。result.node を使い終わったら呼び出し側が recycle
                        return result
                    }
                } finally {
                    // 結果が null の場合は root を回収する。
                    // 結果が非 null だが root == result.node のケースでは、
                    // 呼び出し側の利用後に recycle されることを期待する。
                    if (result == null) {
                        root.recycle()
                    }
                }
            }

            delay(pollIntervalMs)
        }

        logger.warn("NodeFinder", "テキスト '$text' のノード待機タイムアウト")
        return null
    }

    /**
     * IDでノードが出現するまで待機（サスペンド関数）
     */
    suspend fun waitForNodeById(
        resourceId: String,
        timeoutMs: Long = 10000,
        pollIntervalMs: Long = 500
    ): NodeSearchResult? {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val root = getRootNode()
            
            if (root != null) {
                try {
                    val result = findById(root, resourceId)
                    if (result != null) {
                        logger.debug("NodeFinder", "ID '$resourceId' を持つノードを検出")
                        root.recycle()
                        return result
                    }
                } finally {
                    root.recycle()
                }
            }
            
            delay(pollIntervalMs)
        }
        
        logger.warn("NodeFinder", "ID '$resourceId' のノード待機タイムアウト")
        return null
    }

    /**
     * 指定テキストのノードが消えるまで待機（サスペンド関数）
     * ログイン画面の消失判定などに使用
     */
    suspend fun waitForNodeDisappear(
        text: String,
        exactMatch: Boolean = false,
        timeoutMs: Long = 10000,
        pollIntervalMs: Long = 500
    ): Boolean {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val root = getRootNode()
            
            if (root != null) {
                try {
                    val result = findByText(root, text, exactMatch)
                    if (result == null) {
                        // ノードが見つからない = 消えた
                        logger.debug("NodeFinder", "テキスト '$text' のノード消失を確認")
                        root.recycle()
                        return true
                    } else {
                        // 結果のノードを回収
                        result.node.recycle()
                    }
                } finally {
                    root.recycle()
                }
            }
            
            delay(pollIntervalMs)
        }
        
        logger.warn("NodeFinder", "テキスト '$text' のノード消失待機タイムアウト")
        return false
    }

    /**
     * ノードを再帰的に検索
     * 注意：このメソッドはrootを回収しないが、走査中に作成された未マッチの子ノードは回収する
     */
    fun findNodeRecursive(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): NodeSearchResult? {
        try {
            // 現在のノードをチェック
            if (predicate(node)) {
                // 見つかった！結果を返す。注意：ここではnodeをrecycleしない、結果の一部だから
                // nodeがrootの場合は、呼び出し側がrootのライフサイクルを決定
                // nodeがchildの場合は、今は結果に参照されているので、所有権が結果に移る
                return NodeSearchResult(
                    node = node,
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    className = node.className?.toString(),
                    isClickable = node.isClickable,
                    isScrollable = node.isScrollable
                )
            }

            // 子ノードを再帰的に検索
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue

                val result = findNodeRecursive(child, predicate)
                if (result != null) {
                    // 見つかった！child（またはその子孫）がresultに参照されているので、childを回収しない
                    return result
                }

                // 見つからなかった、かつchildは必要な結果でないので、回収する
                child.recycle()
            }
        } catch (e: Exception) {
            logger.debug("NodeFinder", "Error in recursive search", e)
        }

        return null
    }
}