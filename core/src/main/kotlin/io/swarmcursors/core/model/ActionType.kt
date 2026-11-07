package io.swarmcursors.core.model

/**
 * Agent 可执行的真实操作类型。
 *
 * 与无障碍服务动作的映射（由 :overlay 层执行）：
 *  - [TAP]        → `AccessibilityNodeInfo.ACTION_CLICK`
 *  - [LONG_PRESS] → `ACTION_LONG_CLICK`
 *  - [TYPE]       → `ACTION_FOCUS` + `ACTION_SET_TEXT`
 *  - [SCROLL]     → `ACTION_SCROLL_FORWARD` / `ACTION_SCROLL_BACKWARD`
 *  - [SWIPE]      → `dispatchGesture()` 手势路径（无节点语义时的兜底通道）
 */
enum class ActionType {
    TAP,
    LONG_PRESS,
    TYPE,
    SCROLL,
    SWIPE,
}
