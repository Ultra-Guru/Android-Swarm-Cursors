package io.swarmcursors.core.model

import io.swarmcursors.core.geometry.PointF

/**
 * 核心引擎每帧产出的单只光标的**纯几何数据**。
 *
 * 渲染层只负责「画」这一帧：不参与任何运动计算。坐标为屏幕像素
 * （TYPE_ACCESSIBILITY_OVERLAY 铺满屏时与屏幕坐标原点一致）。
 *
 * @param velocity 速度向量（px/s）—— 主题可用于拉伸/倾斜形变
 * @param trailPoints 拖尾轨迹采样点（旧→新；`enableTrail=false` 时恒为空）
 * @param effect 状态提示，见 [CursorEffect]
 */
data class CursorFrame(
    val cursorId: Int,
    val position: PointF,
    val velocity: PointF = PointF(0f, 0f),
    val trailPoints: List<PointF> = emptyList(),
    val effect: CursorEffect = CursorEffect.NONE,
)
