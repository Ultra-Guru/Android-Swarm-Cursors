package io.swarmcursors.overlay.service

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import io.swarmcursors.core.geometry.PointF
import io.swarmcursors.core.geometry.RectF
import io.swarmcursors.core.model.InterfaceSnapshot
import io.swarmcursors.core.model.NodeRole
import io.swarmcursors.core.model.NodeSummary
import io.swarmcursors.core.model.ResolvedTarget

/**
 * 无障碍树遍历器 —— 查询侧（解析 / 快照）的纯算法封装。
 *
 * 与 [SwarmAccessibilityService] 的执行侧分离：本类不触碰
 * `performAction` / `dispatchGesture`，只做树遍历与几何转换，
 * 便于单独审查遍历上限与降级路径。
 *
 * 树路径标识：无障碍树没有稳定 hash id，用索引路径（如 `0/3/1/2`）
 * 代位 —— 同一棵树内路径稳定。**兄弟节点共享同一父路径前缀**，
 * 这正是 [ResolvedTarget.conflictKey] 的来源：同父兄弟并行操作会被
 * ConcurrencyController 自动降级串行（界面状态竞争保护）。
 */
internal object NodeTreeWalker {

    /** 目标解析/查找的遍历上限（比快照更宽，查找可以走得更深）。 */
    const val RESOLVE_MAX_NODES = 600

    /** 快照采集的节点上限 —— 防自绘界面（Flutter/游戏引擎）巨型树卡顿。 */
    const val SNAPSHOT_MAX_NODES = 200

    /**
     * 按节点 id 查找节点句柄（可见优先）—— 执行侧用（performAction 需要句柄本体）。
     */
    fun findNode(root: AccessibilityNodeInfo, nodeId: String): AccessibilityNodeInfo? =
        findByIdWithPath(root, nodeId)?.first

    /**
     * 按节点 id 解析目标：支持全限定名（`pkg:id/name`）与短名（`name`，
     * 按 `viewIdResourceName` 后缀匹配）。同 id 多实例时**优先可见节点**。
     *
     * @return 落点几何 + 冲突键（父路径前缀）；找不到返回 null（调用方走
     *         `targetPoint` 兜底或 Failed 事件）
     */
    fun resolveTarget(root: AccessibilityNodeInfo, nodeId: String): ResolvedTarget? =
        findByIdWithPath(root, nodeId)?.let { (node, path) -> buildTarget(node, path) }

    /**
     * 界面快照：上限裁剪的节点摘要采集（Agent 决策层「看到」界面）。
     * 自绘界面树不完整时返回**部分快照**而非异常 —— 降级而非失败。
     */
    fun captureSnapshot(
        root: AccessibilityNodeInfo,
        maxNodes: Int = SNAPSHOT_MAX_NODES,
    ): InterfaceSnapshot {
        val out = ArrayList<NodeSummary>(64)
        var visited = 0
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, String>>()
        stack.addLast(root to "0")

        while (stack.isNotEmpty() && visited < maxNodes) {
            val (node, path) = stack.removeLast()
            visited++
            val bounds = boundsOf(node)
            if (bounds != null) {
                val text = node.text?.toString() ?: node.contentDescription?.toString()
                out.add(
                    NodeSummary(
                        nodeId = path,
                        bounds = bounds,
                        text = text,
                        role = roleOf(node),
                        clickable = node.isClickable,
                    )
                )
            }
            // 逆序入栈 → 出栈正序（children 左→右）
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it to "$path/$i") }
            }
        }
        return InterfaceSnapshot(
            nodes = out,
            timestampMs = SystemClock.elapsedRealtime(),
            windowTitle = root.packageName?.toString(),
        )
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 带路径的节点查找（resolveTarget / findNode 的共用内核）：
     * 左→右深度优先，上限 [RESOLVE_MAX_NODES]，可见实例优先于首个实例。
     */
    private fun findByIdWithPath(
        root: AccessibilityNodeInfo,
        nodeId: String,
    ): Pair<AccessibilityNodeInfo, String>? {
        if (nodeId.isBlank()) return null
        val shortSuffix = ":id/$nodeId"
        var visited = 0
        var firstMatch: Pair<AccessibilityNodeInfo, String>? = null
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, String>>()
        stack.addLast(root to "0")

        while (stack.isNotEmpty() && visited < RESOLVE_MAX_NODES) {
            val (node, path) = stack.removeLast()
            visited++
            val vid = node.viewIdResourceName
            if (vid != null && (vid == nodeId || vid.endsWith(shortSuffix))) {
                if (node.isVisibleToUser) return node to path
                if (firstMatch == null) firstMatch = node to path
            }
            // 逆序入栈 → 出栈正序（children 左→右）
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it to "$path/$i") }
            }
        }
        return firstMatch
    }

    private fun buildTarget(node: AccessibilityNodeInfo, path: String): ResolvedTarget? {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return null
        val bounds = RectF(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
        // 冲突键 = 父路径前缀（"0/3/1" → "parent@0/3"）—— 同父兄弟共享同一键
        val conflict = "parent@" + path.substringBeforeLast('/')
        return ResolvedTarget(
            point = PointF(bounds.centerX, bounds.centerY),
            nodeBounds = bounds,
            nodeId = node.viewIdResourceName,
            conflictKey = conflict,
        )
    }

    private fun boundsOf(node: AccessibilityNodeInfo): RectF? {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return null // 不可见/零尺寸节点（真实树常见）→ 跳过
        return RectF(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
    }

    private fun roleOf(node: AccessibilityNodeInfo): NodeRole {
        val cls = node.className?.toString() ?: return NodeRole.GENERIC
        return when {
            cls.contains("Button", ignoreCase = true) -> NodeRole.BUTTON
            cls.contains("EditText", ignoreCase = true) -> NodeRole.INPUT
            cls.contains("TextView", ignoreCase = true) -> NodeRole.TEXT
            cls.contains("Image", ignoreCase = true) -> NodeRole.IMAGE
            node.isEditable -> NodeRole.INPUT
            node.isClickable -> NodeRole.BUTTON
            else -> NodeRole.CONTAINER
        }
    }
}
