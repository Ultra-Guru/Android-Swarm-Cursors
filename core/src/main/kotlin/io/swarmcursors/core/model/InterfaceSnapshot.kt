package io.swarmcursors.core.model

/**
 * 操作执行后的界面快照。
 *
 * 采集策略（:overlay）：每次 `executeAction` 成功后延迟 150–250ms（等界面
 * 动画 / 跳转稳定）再抓取 `getRootInActiveWindow()` 的节点摘要，上限裁剪
 * （默认 200 节点）防大树卡顿。界面未变化时快照仍返回当前节点列表 ——
 * 由 Agent 自行与上一帧比对。
 *
 * @param timestampMs 采集时刻（`SystemClock.elapsedRealtime()` 语义）
 */
data class InterfaceSnapshot(
    val nodes: List<NodeSummary>,
    val timestampMs: Long,
    val windowTitle: String? = null,
) {
    /** 按 [NodeSummary.text] 精确匹配查找节点（Agent 定位「输入框里现在是什么」）。 */
    fun findFirstByText(text: String): NodeSummary? = nodes.firstOrNull { it.text == text }

    /** 按 [NodeSummary.nodeId] 查找。 */
    fun find(nodeId: String): NodeSummary? = nodes.firstOrNull { it.nodeId == nodeId }
}
