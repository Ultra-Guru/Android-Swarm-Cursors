package io.swarmcursors.core.model

/**
 * 光标运动风格（SwarmConfig.motionStyle）。
 */
enum class MotionStyle {
    /** 弧线轨迹（默认）：样条弧 + Fitts 时序 + 微抖动 + 弹簧落停 —— 「招牌」手感。 */
    SIGNATURE_ARC,

    /** 弹簧落停强化：直线路径为主，末端弹簧更柔，过冲更明显。 */
    SPRING_SETTLE,

    /** 简洁直线 + Fitts 时序：无抖动、无过冲（演示/对比基线）。 */
    CLASSIC,
}
