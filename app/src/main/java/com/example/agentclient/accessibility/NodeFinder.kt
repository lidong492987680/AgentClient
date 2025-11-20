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
     * @return 見つかったノード、タイムアウトした場合null。
     *         注意：戻り値の node は呼び出し側が recycle() する必要があります。
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
                        // 見つかった場合、rootが結果のノードと異なればrootをrecycleする
                        if (result.node != root) {
                            root.recycle()
                        }
                        return result
                    }
                } catch (e: Exception) {
                    logger.error("NodeFinder", "検索中にエラー発生", e)
                }
                // 結果が見つからなかった場合、rootをrecycle
                if (result == null) {
                    root.recycle()
                }
            }

            delay(pollIntervalMs)
        }

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
                var result: NodeSearchResult? = null
                try {
                    result = findById(root, resourceId)
                    if (result != null) {
                        // 見つかった場合、rootが結果のノードと異なればrootをrecycleする
                        if (result.node != root) {
                            root.recycle()
                        }
                        return result
                    }
                } catch (e: Exception) {
                    logger.error("NodeFinder", "検索中にエラー発生", e)
                }
                // 結果が見つからなかった場合、rootをrecycle
                if (result == null) {
                    root.recycle()
                }
            }

            delay(pollIntervalMs)
        }

        return null
    }

    /**
     * ノードが消失するまで待機（サスペンド関数）
     * @return true ノードが消えた、false タイムアウト
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
                        root.recycle()
                        return true
                    } else {
                        // まだ存在している、resultのnodeを回収
                        result.node.recycle()
                    }
                } catch (e: Exception) {
                    logger.error("NodeFinder", "消失待機中にエラー", e)
                } finally {
                    // rootは必ずrecycleする
                    root.recycle()
                }
            }

            delay(pollIntervalMs)
        }

        return false
    }

    /**
     * 再帰的にノードを検索
     * 
     * 注意：このメソッドはrootを回収しないが、
     * 中間ノード（child）で結果として選択されなかったものは内部で回収する。
     * 
     * @param node 検索開始ノード（回収しない）
     * @param predicate マッチング条件
     * @return 見つかったノード情報、見つからなければnull
     */
    private fun findNodeRecursive(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): NodeSearchResult? {
        try {
            // 現在のノードがマッチするかチェック
            if (predicate(node)) {
                return NodeSearchResult(
                    node = node,
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    className = node.className?.toString(),
                    isClickable = node.isClickable,
                    isScrollable = node.isScrollable
                )
            }

            // 子ノードを検索
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue

                val result = findNodeRecursive(child, predicate)
                if (result != null) {
                    // 見つかった！child（またはその子孫）が結果の場合
                    // childが結果のnodeそのものでなければchildをrecycleする
                    if (result.node != child) {
                        child.recycle()
                    }
                    return result
                }
                // このchildの枝では見つからなかったのでrecycle
                child.recycle()
            }
        } catch (e: Exception) {
            logger.debug("NodeFinder", "再帰検索中にエラー", e)
        }

        return null
    }
}