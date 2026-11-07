package io.swarmcursors.core.model

/**
 * 快照节点的语义角色（由 :overlay 从 `AccessibilityNodeInfo.className` /
 * `isClickable` / `isEditable` 推断）。
 */
enum class NodeRole {
    BUTTON,
    INPUT,
    TEXT,
    IMAGE,
    CONTAINER,
    GENERIC,
}
