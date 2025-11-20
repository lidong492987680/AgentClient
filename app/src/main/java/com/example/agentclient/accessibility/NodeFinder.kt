package com.example.agentclient.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.agentclient.core.Logger

/**
 * 节点查找器
 * 负责在 AccessibilityNodeInfo 树中查找节点
 * 
 * Recycle 策略说明：
 * 1. 传入的 root 节点由调用者负责回收（如果需要）。
 * 2. 内部递归过程中产生的中间节点（child），如果未被选中作为结果返回，则在内部 recycle。
 * 3. 返回的 NodeSearchResult 包含的 node，由调用者负责 recycle。
 */
class NodeFinder(private val logger: Logger) {

    /**
     * 节点查找结果
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
     * 通过文本查找节点
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
     * 通过ID查找节点
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
            // findAccessibilityNodeInfosByViewId 返回的是新创建的节点列表，需要负责回收
            val nodes = root.findAccessibilityNodeInfosByViewId(resourceId)
            if (!nodes.isNullOrEmpty()) {
                val target = nodes[0]
                // 回收其他不需要的节点
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
     * 通过类名查找节点
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
     * 递归查找节点
     * 注意：此方法不会回收 root，但会回收遍历过程中创建的未匹配子节点
     */
    fun findNodeRecursive(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): NodeSearchResult? {
        try {
            // 检查当前节点
            if (predicate(node)) {
                // 找到了！返回结果。注意：这里不 recycle node，因为它是结果的一部分。
                // 如果 node 是 root，则由调用者决定 root 的生命周期。
                // 如果 node 是 child，则它现在被结果引用，所有权转移给结果。
                return NodeSearchResult(
                    node = node,
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    className = node.className?.toString(),
                    isClickable = node.isClickable,
                    isScrollable = node.isScrollable
                )
            }

            // 递归查找子节点
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue

                val result = findNodeRecursive(child, predicate)
                if (result != null) {
                    // 找到了！child (或其后代) 被 result 引用，不能回收 child。
                    return result
                }

                // 没找到，且 child 不是我们需要的结果，回收它。
                child.recycle()
            }
        } catch (e: Exception) {
            logger.debug("NodeFinder", "Error in recursive search", e)
        }

        return null
    }
}
