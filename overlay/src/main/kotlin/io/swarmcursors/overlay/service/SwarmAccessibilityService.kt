package io.swarmcursors.overlay.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.swarmcursors.core.geometry.PointF
import io.swarmcursors.core.model.ActionType
import io.swarmcursors.core.model.AgentAction
import io.swarmcursors.core.model.Direction
import io.swarmcursors.core.model.InterfaceSnapshot
import io.swarmcursors.core.model.ResolvedTarget

/**
 * Swarm 无障碍服务 —— 库的「手」：查询 + 真实执行双重职责。
 *
 * 1. **查询**：[resolveTarget]（节点 id → 屏幕坐标 + 冲突键）、
 *    [captureSnapshot]（操作后的界面快照，供 Agent 下一步决策）；
 * 2. **执行**：[executeAction] —— 光标动画**到达之后**才被调用
 *    （ActionOrchestrator 的 onArrival 时序保证），确保用户看到
 *    「光标飞过去 → 点到 → 界面响应」。
 *
 * 动作映射：
 *  - TAP/LONG_PRESS → `ACTION_CLICK` / `ACTION_LONG_CLICK`（节点缺失时
 *    降级为 `dispatchGesture` 真实手势 —— targetPoint 兜底仍可点击任何界面）
 *  - TYPE → `ACTION_FOCUS` + `ACTION_SET_TEXT`
 *  - SCROLL → `ACTION_SCROLL_FORWARD/BACKWARD`（降级手势滑动）
 *  - SWIPE → `dispatchGesture` 路径手势
 *
 * 线程模型：本类方法由编排层在主线程调用；`onAccessibilityEvent` 只做
 * 轻量窗口跟踪（快照按需抓取，不持续遍历树 —— 性能预算纪律）。
 */
class SwarmAccessibilityService : AccessibilityService() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 轻量：只跟踪窗口切换（快照的 windowTitle 上下文）；不在此遍历树
    }

    override fun onInterrupt() {
        // 无语音反馈通道，无需处理
    }

    // ── 查询侧 ──────────────────────────────────────────────────────────────

    /**
     * 解析 [AgentAction] 的落点。
     * 优先级：targetNodeId（树内匹配，落点与真实点击位置完全一致）→
     * targetPoint 兜底（节点不可用 / 自绘界面）→ null（无法解析）。
     */
    fun resolveTarget(action: AgentAction): ResolvedTarget? {
        val nodeId = action.targetNodeId
        if (nodeId != null) {
            val root = rootInActiveWindow
            if (root != null) {
                NodeTreeWalker.resolveTarget(root, nodeId)?.let { return it }
            }
        }
        return action.targetPoint?.let { ResolvedTarget.fromPoint(it) }
    }

    /**
     * 当前窗口的界面快照（节点摘要，上限裁剪）。
     * 窗口不可用（锁屏/自绘界面）时返回空快照 —— 降级而非异常。
     */
    fun captureSnapshot(maxNodes: Int = NodeTreeWalker.SNAPSHOT_MAX_NODES): InterfaceSnapshot {
        val root = rootInActiveWindow
            ?: return InterfaceSnapshot(emptyList(), android.os.SystemClock.elapsedRealtime(), null)
        return NodeTreeWalker.captureSnapshot(root, maxNodes)
    }

    // ── 执行侧 ──────────────────────────────────────────────────────────────

    /**
     * 执行真实操作（在光标到达后调用）。
     *
     * @return 操作是否成功送达（节点动作返回值 / 手势派发结果）。
     *   送达 ≠ 界面必然变化 —— 界面反馈由随后的快照呈现给 Agent 判断。
     */
    fun executeAction(action: AgentAction, target: ResolvedTarget): Boolean {
        return when (action.type) {
            ActionType.TAP -> performNodeAction(action, AccessibilityNodeInfo.ACTION_CLICK)
                || dispatchTap(target.point)

            ActionType.LONG_PRESS -> performNodeAction(action, AccessibilityNodeInfo.ACTION_LONG_CLICK)
                || dispatchLongPress(target.point, action.durationMs)

            ActionType.TYPE -> performType(action)

            ActionType.SCROLL -> performScroll(action)
                || dispatchSwipe(target.point, scrollDirectionOf(action.direction), action.durationMs)

            ActionType.SWIPE -> dispatchSwipe(target.point, scrollDirectionOf(action.direction), action.durationMs)
        }
    }

    // ── 节点动作 ─────────────────────────────────────────────────────────────

    private fun performNodeAction(action: AgentAction, nodeAction: Int): Boolean {
        val node = findActionableNode(action) ?: return false
        return try {
            node.performAction(nodeAction)
        } catch (e: IllegalStateException) {
            false // 节点已回收/窗口切换：调用方走手势兜底
        }
    }

    private fun performType(action: AgentAction): Boolean {
        val text = action.text ?: return false
        val node = findActionableNode(action) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) &&
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: IllegalStateException) {
            false
        }
    }

    private fun performScroll(action: AgentAction): Boolean {
        val dir = scrollDirectionOf(action.direction)
        val nodeAction = when (dir) {
            Direction.UP -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD // 内容向上滚出 = 前滚
            else -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return performNodeAction(action, nodeAction)
    }

    /** 解析时命中的节点可能在移动完成后已失效 → 执行时重新解析一次。 */
    private fun findActionableNode(action: AgentAction): AccessibilityNodeInfo? {
        val nodeId = action.targetNodeId ?: return null
        val root = rootInActiveWindow ?: return null
        return NodeTreeWalker.findNode(root, nodeId)
    }

    // ── 手势兜底（TYPE_ACCESSIBILITY_OVERLAY 之外的真实触摸通道）────────────

    private fun dispatchTap(point: PointF): Boolean =
        dispatchGesture(buildStroke(point, point, 48L), null, mainHandler)

    private fun dispatchLongPress(point: PointF, durationMs: Long): Boolean =
        dispatchGesture(buildStroke(point, point, durationMs.coerceAtLeast(400L)), null, mainHandler)

    /** 从 [point] 出发按方向划出 [SWIPE_DISTANCE_PX] 的真实手势。 */
    private fun dispatchSwipe(point: PointF, direction: Direction, durationMs: Long): Boolean {
        val d = SWIPE_DISTANCE_PX
        val end = when (direction) {
            Direction.UP -> PointF(point.x, point.y - d)
            Direction.DOWN -> PointF(point.x, point.y + d)
            Direction.LEFT -> PointF(point.x - d, point.y)
            Direction.RIGHT -> PointF(point.x + d, point.y)
        }
        return dispatchGesture(buildStroke(point, end, durationMs.coerceIn(200L, 1200L)), null, mainHandler)
    }

    private fun buildStroke(from: PointF, to: PointF, durationMs: Long): GestureDescription {
        val path = Path().apply {
            moveTo(from.x, from.y)
            lineTo(to.x, to.y)
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
    }

    private fun scrollDirectionOf(direction: Direction?): Direction = direction ?: Direction.DOWN

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── 服务连接状态 ─────────────────────────────────────────────────────────

    companion object {

        /** 已连接的服务实例（onCreate→onDestroy 生命周期内有效）。 */
        @Volatile
        var instance: SwarmAccessibilityService? = null
            private set

        /** 服务是否已在系统设置中启用（无需连接即查）。 */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, SwarmAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { entry ->
                entry.equals(expected.flattenToString(), ignoreCase = true) ||
                    entry.equals(expected.flattenToShortString(), ignoreCase = true)
            }
        }

        /** 引导用户跳转系统无障碍设置页启用本服务。 */
        fun openSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        private const val SWIPE_DISTANCE_PX = 240f
    }
}
