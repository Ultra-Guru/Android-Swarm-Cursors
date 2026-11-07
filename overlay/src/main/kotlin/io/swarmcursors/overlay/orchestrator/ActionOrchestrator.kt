package io.swarmcursors.overlay.orchestrator

import io.swarmcursors.core.engine.SwarmEngine
import io.swarmcursors.core.geometry.PointF
import io.swarmcursors.core.model.ActionEvent
import io.swarmcursors.core.model.AgentAction
import io.swarmcursors.core.model.ResolvedTarget
import io.swarmcursors.core.scheduler.ConcurrencyController
import io.swarmcursors.overlay.render.CursorThemeRenderer
import io.swarmcursors.overlay.service.SwarmAccessibilityService
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 动作-动画同步执行器 —— 编排「光标先到、操作后执行」的核心时序。
 *
 * ```
 * for action in actions（并行分派，ConcurrencyController 控并发）:
 *   ① resolveTarget   → 节点 id / 坐标 → 屏幕落点
 *   ② acquire         → 分区许可（同格/同冲突键串行）
 *   ③ emit Moving     → 光标丝滑运动（样条+Fitts+弹簧）
 *   ④ onArrival       → 引擎 tick 线程触发 → 恢复协程（时序保证！）
 *   ⑤ executeAction   → 真实无障碍操作（节点动作 / 手势兜底）
 *   ⑥ theme.onAction  → 点击涟漪 + 动作标签
 *   ⑦ settleDrift     → 1–2px 放松漂移
 *   ⑧ delay(快照窗)   → captureSnapshot → emit Executed（Agent 决策闭环）
 *   ⑨ release         → 唤醒下一批等待者
 * ```
 *
 * 时序保证是本库的灵魂：真实操作**只在光标动画到达之后**执行 —— 用户看到
 * 「光标飞过去 → 点到 → 界面响应」，而不是「界面先响应 → 光标后到」。
 */
internal class ActionOrchestrator(
    private val sessionProvider: () -> SessionParts,
) {

    /** 会话部件（引擎/并发/主题）—— 首次收集时装配。 */
    internal interface SessionParts {
        val engine: SwarmEngine
        val controller: ConcurrencyController
        val theme: CursorThemeRenderer
    }

    /**
     * 冷流：**开始收集时**才装配会话（Main 线程 —— Overlay attach 需主线程），
     * 并发分派全部子协程，coroutineScope 挂起至全部完成后流自然完结。
     */
    fun execute(actions: List<AgentAction>): Flow<ActionEvent> = channelFlow {
        val s = sessionProvider() // 未启用服务 → IllegalStateException（人话）
        coroutineScope {
            for (action in actions) {
                launch {
                    runSingle(this@channelFlow, action, s)
                }
            }
        }
    }.flowOn(Dispatchers.Main)

    private suspend fun runSingle(
        scope: ProducerScope<ActionEvent>,
        action: AgentAction,
        s: SessionParts,
    ) {
        val service = SwarmAccessibilityService.instance
        if (service == null) {
            scope.send(ActionEvent.Failed(action, SERVICE_NOT_CONNECTED))
            return
        }
        if (!action.hasTarget) {
            scope.send(ActionEvent.Failed(action, "缺少目标：targetNodeId 与 targetPoint 均为空"))
            return
        }

        // ① 目标解析（节点 id 优先，坐标兜底）
        val target: ResolvedTarget = service.resolveTarget(action)
            ?: run {
                scope.send(ActionEvent.Failed(action, "目标不可达：nodeId=${action.targetNodeId}"))
                return
            }

        // ② 分区许可（同格/同冲突键/超上限 → FIFO 排队）
        val permit = s.controller.acquire(target)
        try {
            // ③ 光标运动（丝滑）
            scope.send(ActionEvent.Moving(action.cursorId, target.point, action))

            // ④ 等待落定（超时兜底见 awaitArrival 注释）
            awaitArrival(action.cursorId, target, s.engine)

            // ⑤ 真实操作（节点动作 / 手势兜底）
            val ok = service.executeAction(action, target)

            // ⑥ 视觉反馈（涟漪 + 标签）
            s.theme.onAction(
                action.type, target.point,
                android.os.SystemClock.elapsedRealtime(), action.cursorId,
            )

            // ⑦ 放松漂移
            s.engine.settleDrift(action.cursorId)

            // ⑧ 界面快照（成功且启用时；延迟等动画稳定）
            val snapshot = if (ok && snapshotEnabled) {
                delay(snapshotDelayMs)
                runCatching { service.captureSnapshot(snapshotMaxNodes) }.getOrNull()
            } else {
                null
            }

            scope.send(
                ActionEvent.Executed(
                    action = action,
                    success = ok,
                    snapshot = snapshot,
                    errorMessage = if (ok) null else "操作未送达（节点不可用且手势派发失败）",
                )
            )
        } finally {
            // ⑨ 归还许可（异常/取消路径也必须执行）
            s.controller.release(permit)
        }
    }

    /** 快照策略由会话构造注入（避免 Orchestrator 依赖 SwarmConfig 造成循环）。 */
    internal var snapshotEnabled: Boolean = true
    internal var snapshotDelayMs: Long = 200
    internal var snapshotMaxNodes: Int = 200

    /**
     * 挂起等待光标落定（引擎 onArrival 回调 → 协程恢复）。
     *
     * 竞态处理：回调可能在挂起点建立**之前**触发（超短距离秒到）——
     * [ArrivalLatch] 双检查（arrived 标志 + continuation 槽位，同一把锁）
     * 保证「先到后挂」「先挂后到」两条路径都恰好恢复一次。
     *
     * 超时兜底：引擎预算 + 400ms（低帧率设备的防御上限）—— 超时仍执行
     * 真实操作（视觉迟到不能阻止任务完成，只是不再完美同步）。
     */
    private suspend fun awaitArrival(
        cursorId: Int,
        target: ResolvedTarget,
        engine: SwarmEngine,
    ) {
        val latch = ArrivalLatch()
        val budgetMs = engine.moveCursorTo(
            cursorId = cursorId,
            to = target.point,
            targetSize = target.targetSize,
            onArrival = { latch.fire() },
        )
        withTimeoutOrNull(budgetMs + ARRIVAL_SLACK_MS) {
            suspendCancellableCoroutine { cont -> latch.await(cont) }
        }
    }

    private class ArrivalLatch {
        private val lock = Any()
        private var arrived = false
        private var continuation: CancellableContinuation<Unit>? = null

        fun fire() {
            val toResume: CancellableContinuation<Unit>?
            synchronized(lock) {
                arrived = true
                toResume = continuation
                continuation = null
            }
            toResume?.let { if (it.isActive) it.resume(Unit) }
        }

        fun await(cont: CancellableContinuation<Unit>) {
            val resumeNow: Boolean
            synchronized(lock) {
                if (arrived) {
                    resumeNow = true
                } else {
                    continuation = cont
                    resumeNow = false
                }
            }
            if (resumeNow && cont.isActive) cont.resume(Unit)
            cont.invokeOnCancellation {
                synchronized(lock) {
                    if (continuation === cont) continuation = null
                }
            }
        }
    }

    private companion object {
        const val ARRIVAL_SLACK_MS = 400L
        const val SERVICE_NOT_CONNECTED =
            "Swarm 无障碍服务未连接：请先在系统设置中启用 Swarm Cursors 服务" +
                "（可用 SwarmCursors.isAccessibilityServiceEnabled() 预检，" +
                "SwarmCursors.openAccessibilitySettings() 引导跳转）"
    }
}
