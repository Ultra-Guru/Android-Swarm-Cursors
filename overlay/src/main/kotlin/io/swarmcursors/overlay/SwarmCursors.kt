package io.swarmcursors.overlay

import android.content.Context
import io.swarmcursors.core.engine.SwarmEngine
import io.swarmcursors.core.model.ActionEvent
import io.swarmcursors.core.model.ActionResult
import io.swarmcursors.core.model.AgentAction
import io.swarmcursors.core.scheduler.ConcurrencyController
import io.swarmcursors.overlay.orchestrator.ActionOrchestrator
import io.swarmcursors.overlay.render.CursorThemeRenderer
import io.swarmcursors.overlay.service.SwarmAccessibilityService
import io.swarmcursors.overlay.window.OverlayWindowController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/**
 * Swarm Cursors 门面 —— 集成方唯一需要接触的入口对象。
 *
 * 不需要接触 SurfaceView / WindowManager / AccessibilityService 的任何细节。
 *
 * ```
 * // ① 引导启用无障碍服务（一次性）
 * if (!SwarmCursors.isAccessibilityServiceEnabled(context)) {
 *     SwarmCursors.openAccessibilitySettings(context)
 * }
 *
 * // ② Agent 注入真实操作序列，消费事件流（快照 = 界面感知闭环）
 * SwarmCursors.injectActions(context, listOf(
 *     AgentAction(type = ActionType.TAP, targetNodeId = "login"),
 *     AgentAction(cursorId = 1, type = ActionType.TYPE, targetNodeId = "username", text = "agent"),
 * )).collect { event ->
 *     when (event) {
 *         is ActionEvent.Moving   -> renderThinking(event.target)
 *         is ActionEvent.Executed -> agent.observe(event.snapshot)
 *         is ActionEvent.Failed   -> agent.recover(event.reason)
 *     }
 * }
 *
 * SwarmCursors.stopSwarm()
 * ```
 *
 * 会话语义：首次收集 `injectActions` 的流时按 [SwarmConfig] 装配会话
 * （引擎/并发控制/主题/Overlay 窗口 —— Main 线程），后续调用复用同一会话；
 * 换配置需先 [stopSwarm]。事件流的收集与生产都在 Main 分发
 * （无障碍服务调用的线程安全契约）。
 */
object SwarmCursors {

    private val lock = Any()
    private var session: Session? = null

    /** 会话是否在运行（Overlay 已挂载、引擎活跃）。 */
    @JvmStatic
    fun isRunning(): Boolean = synchronized(lock) { session != null }

    /**
     * **主入口**：Agent 注入真实操作序列。
     *
     * 冷流：装配与执行在**开始收集时**发生（Main 线程）。并发分派由
     * SwarmConfig.parallelMode 控制（SAFE：分区并行 + 冲突键降级）。
     *
     * @throws IllegalStateException 无障碍服务未连接（人话指引，配合
     *   [isAccessibilityServiceEnabled] 预检）
     */
    @JvmStatic
    fun injectActions(
        context: Context,
        actions: List<AgentAction>,
        config: SwarmConfig = SwarmConfig(),
    ): Flow<ActionEvent> = orchestrator(config).execute(actions)

    /**
     * 单步操作（Agent 逐步决策时使用）：内部等价于单元素 [injectActions]。
     */
    @JvmStatic
    suspend fun performAction(
        context: Context,
        action: AgentAction,
        config: SwarmConfig = SwarmConfig(),
    ): ActionResult {
        var result: ActionResult? = null
        injectActions(context, listOf(action), config).collect { event ->
            when (event) {
                is ActionEvent.Moving -> Unit
                is ActionEvent.Executed -> result = ActionResult(
                    action = event.action,
                    success = event.success,
                    snapshot = event.snapshot,
                    errorMessage = event.errorMessage,
                )
                is ActionEvent.Failed -> result = ActionResult(
                    action = event.action,
                    success = false,
                    errorMessage = event.reason,
                )
            }
        }
        return result ?: ActionResult(action, false, null, "未收到执行结果（流提前完结）")
    }

    /** 光标可见性（隐藏时引擎照常推进、操作照常执行，仅不绘制）。 */
    @JvmStatic
    fun setCursorVisible(visible: Boolean) {
        synchronized(lock) { session?.overlay?.setCursorsVisible(visible) }
    }

    /** 停止蜂群：卸载 Overlay、清空引擎/并发队列/主题特效、取消会话协程。 */
    @JvmStatic
    fun stopSwarm() {
        val s: Session?
        synchronized(lock) {
            s = session
            session = null
        }
        s?.let {
            runCatching { it.overlay.detach() }
            it.engine.clear()
            it.controller.reset()
            it.theme.reset()
            it.scope.cancel()
        }
    }

    /** 无障碍服务是否已在系统设置启用（无需连接即查，适合 UI 预检）。 */
    @JvmStatic
    fun isAccessibilityServiceEnabled(context: Context): Boolean =
        SwarmAccessibilityService.isEnabled(context)

    /** 引导用户跳转系统无障碍设置页。 */
    @JvmStatic
    fun openAccessibilitySettings(context: Context) =
        SwarmAccessibilityService.openSettings(context)

    // ── 会话装配 ─────────────────────────────────────────────────────────────

    /** 会话：引擎 + 并发 + 主题 + Overlay + 协程域。 */
    private class Session(
        override val engine: SwarmEngine,
        override val controller: ConcurrencyController,
        override val theme: CursorThemeRenderer,
        val overlay: OverlayWindowController,
        val scope: CoroutineScope,
    ) : ActionOrchestrator.SessionParts

    /** 惰性装配（flow 首次收集时在 Main 上执行；Overlay attach 需主线程）。 */
    private fun orchestrator(config: SwarmConfig): ActionOrchestrator {
        val orch = ActionOrchestrator(sessionProvider = {
            ensureSession(config)
        })
        orch.snapshotEnabled = config.snapshotEnabled
        orch.snapshotDelayMs = config.snapshotDelayMs
        orch.snapshotMaxNodes = config.snapshotMaxNodes
        return orch
    }

    private fun ensureSession(config: SwarmConfig): Session {
        synchronized(lock) {
            session?.let { return it }

            val service = SwarmAccessibilityService.instance
                ?: throw IllegalStateException(
                    "Swarm 无障碍服务未连接：请先在系统设置 → 无障碍 中启用 Swarm Cursors。" +
                        "预检：SwarmCursors.isAccessibilityServiceEnabled(context)；" +
                        "跳转：SwarmCursors.openAccessibilitySettings(context)",
                )

            val engine = SwarmEngine(
                maxCursors = config.maxCursors,
                config = config.motion,
                enableTrail = config.enableTrail,
                motionStyle = config.motionStyle,
            )
            val controller = ConcurrencyController(
                mode = config.parallelMode,
                maxParallel = config.maxParallel,
            )
            val theme = config.applyThemeDefaults(config.theme)
            val overlay = OverlayWindowController(service, engine, theme)
            overlay.attach()

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

            return Session(
                engine = engine,
                controller = controller,
                theme = theme,
                overlay = overlay,
                scope = scope,
            ).also { session = it }
        }
    }
}
