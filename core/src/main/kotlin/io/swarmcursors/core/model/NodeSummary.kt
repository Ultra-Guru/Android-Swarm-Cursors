package io.swarmcursors.core.model

import io.swarmcursors.core.geometry.RectF

/**
 * 操作后界面快照中的单个节点摘要 —— Agent 决策层「看到」界面的最小单位。
 *
 * @param nodeId 树路径标识（如 `0/3/1/2`，无障碍树无稳定 hash id，用路径代位）
 * @param bounds 屏幕像素 bounds
 * @param text 节点可见文本（`text` / `contentDescription` 合取）
 */
data class NodeSummary(
    val nodeId: String,
    val bounds: RectF,
    val text: String? = null,
    val role: NodeRole = NodeRole.GENERIC,
    val clickable: Boolean = false,
)
