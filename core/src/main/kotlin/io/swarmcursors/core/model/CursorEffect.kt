package io.swarmcursors.core.model

/**
 * 单帧光标状态的效果提示 —— 渲染层的主题据此叠加视觉反馈
 * （如 ARRIVING 时轻微放大光标、SETTLING 时画放松漂移）。
 *
 * 纯提示语义：渲染层可以忽略它；点击涟漪 / 动作标签由主题的
 * `onAction()` 回调驱动，不经过本枚举。
 */
enum class CursorEffect {
    /** 静止（无运动、无特效）。 */
    NONE,

    /** 弹道/采集运动中。 */
    MOVING,

    /** 弹簧落停阶段（轻微过冲回弹进行中）。 */
    ARRIVING,

    /** 点击后的放松微漂移（1–2px / 150ms ease-out）。 */
    SETTLING,
}
