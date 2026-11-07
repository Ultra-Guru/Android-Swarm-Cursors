package io.swarmcursors.core.model

import io.swarmcursors.core.geometry.PointF
import io.swarmcursors.core.geometry.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * [AgentAction] 经 :overlay 层解析后的落点（屏幕像素坐标）。
 *
 * 注意：核心层**不持有** `AccessibilityNodeInfo`（那是 Android 类型）——
 * 节点引用由 overlay 层自行保留用于执行真实操作；core 只消费纯几何数据，
 * 保证 JVM 可测。
 *
 * @param point 最终落点（节点 bounds 中心，或 targetPoint 兜底值）
 * @param nodeBounds 目标节点的屏幕 bounds；坐标兜底时按 [RectF.fromCenter] 合成
 * @param nodeId 命中的节点标识（快照/日志用）
 * @param conflictKey 并发冲突键：同一父容器下的兄弟节点共享同一 key ——
 *   ConcurrencyController 检测到同 key 并行时自动降级串行，避免界面状态竞争
 */
data class ResolvedTarget(
    val point: PointF,
    val nodeBounds: RectF,
    val nodeId: String? = null,
    val conflictKey: String? = null,
) {
    /**
     * Fitts 定律的目标宽度 `W`：取 bounds 短边，下限 1px（零尺寸节点在真实
     * 无障碍树中并不罕见，防 log2(0) / 负时长）。
     */
    val targetSize: Float
        get() = max(1f, min(nodeBounds.width, nodeBounds.height))

    companion object {
        /** 坐标兜底构造：以 [point] 为中心合成一个 `size x size` 的虚拟 bounds。 */
        fun fromPoint(point: PointF, size: Float = 48f, conflictKey: String? = null): ResolvedTarget =
            ResolvedTarget(
                point = point,
                nodeBounds = RectF.fromCenter(point, size, size),
                conflictKey = conflictKey,
            )
    }
}
