package io.swarmcursors.core.model

import io.swarmcursors.core.geometry.PointF

/**
 * Agent 决策层输出的单个真实操作指令。
 *
 * 目标解析优先级（由 :overlay 的 SwarmAccessibilityService 执行）：
 *  1. [targetNodeId] —— 无障碍节点标识（`viewIdResourceName`，如 `pkg:id/login`，
 *     也接受短名 `login`）；命中则光标落点与真实点击位置**完全一致**；
 *  2. [targetPoint] —— 兜底屏幕像素坐标（节点不可用 / 自绘界面时直接使用）。
 *
 * @param cursorId 绑定的光标编号（0..maxCursors-1）；同号光标串行执行其任务
 * @param type 操作类型，见 [ActionType]
 * @param text [ActionType.TYPE] 的输入内容
 * @param direction [ActionType.SCROLL]/[ActionType.SWIPE] 的方向
 * @param durationMs LONG_PRESS / SWIPE 的持续时长（默认 300ms）
 */
data class AgentAction(
    val cursorId: Int = 0,
    val type: ActionType,
    val targetNodeId: String? = null,
    val targetPoint: PointF? = null,
    val text: String? = null,
    val direction: Direction? = null,
    val durationMs: Long = 300,
) {
    /** 是否携带任一形式的落点信息（节点 id 或屏幕坐标）。 */
    val hasTarget: Boolean
        get() = targetNodeId != null || targetPoint != null

    /** TYPE 动作必须携带输入文本。 */
    val isWellFormed: Boolean
        get() = when (type) {
            ActionType.TYPE -> !text.isNullOrBlank() && hasTarget
            ActionType.SCROLL, ActionType.SWIPE -> hasTarget && direction != null
            else -> hasTarget
        }
}
