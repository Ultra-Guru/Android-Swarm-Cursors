package io.swarmcursors.core.engine

import io.swarmcursors.core.geometry.PointF
import io.swarmcursors.core.geometry.RectF
import io.swarmcursors.core.model.CursorEffect
import io.swarmcursors.core.model.CursorFrame
import io.swarmcursors.core.model.MotionStyle
import io.swarmcursors.core.motion.FittsMotionPlanner
import io.swarmcursors.core.motion.MovePlan
import io.swarmcursors.core.motion.MotionPlannerConfig
import io.swarmcursors.core.motion.PerlinNoise
import io.swarmcursors.core.motion.SpringIntegrator

/**
 * 多光标运动引擎 —— 核心层的「心脏」。
 *
 * 职责边界：
 *  - **只做运动**：每帧 [tick] 产出全部光标的纯几何帧（[CursorFrame]）；
 *  - **不做渲染**：渲染层（:overlay）拿着帧去画 Canvas，不参与任何运动计算；
 *  - **不做决策**：编排层（ActionOrchestrator）在 `onArrival` 回调里执行真实
 *    无障碍操作 —— 保证「光标飞到 → 真实操作 → 界面响应」的视觉顺序。
 *
 * 线程模型：[tick] 在渲染线程调用，[moveCursorTo]/[settleDrift]/[removeCursor]
 * 可能来自任意协程线程 —— 全部公开方法经同一把锁串行化；`onArrival` 回调
 * **在 tick 线程同步触发**（只做协程恢复等轻量工作，勿阻塞）。
 *
 * 确定性：固定 seed 的 Perlin + 纯数学推进 —— JVM 单测可用固定 dt 循环
 * 精确复现整段轨迹（见 SwarmEngineTest）。
 *
 * 每帧成本：每光标 O(1) 采样 + O(trailCapacity) 拷贝，16 光标 < 0.5ms。
 */
class SwarmEngine(
    val maxCursors: Int = 16,
    val config: MotionPlannerConfig = MotionPlannerConfig(),
    val enableTrail: Boolean = false,
    val motionStyle: MotionStyle = MotionStyle.SIGNATURE_ARC,
) {

    private val lock = Any()
    private val planner = FittsMotionPlanner(config)
    private val noise = PerlinNoise(seed = 0xC0FFEE2026L) // 稳定 seed → 可复现

    /** 视口（逻辑像素）—— 新光标从最近的屏幕边缘「飞入」。由 :overlay 在 surface 创建时同步。 */
    @Volatile
    var viewportWidth: Float = 1080f

    @Volatile
    var viewportHeight: Float = 1920f

    private val cursors = LinkedHashMap<Int, CursorState>()

    /** 当前活跃光标数（含运动中与静止未移除的）。 */
    val cursorCount: Int get() = synchronized(lock) { cursors.size }

    /** 光标是否存在（渲染层据此决定是否绘制）。 */
    fun hasCursor(cursorId: Int): Boolean = synchronized(lock) { cursors.containsKey(cursorId) }

    /**
     * 规划并启动一次移动。若该光标正在移动则**就地重规划**（流式跟随语义：
     * Agent 连续给出新目标时光标丝滑改道，绝不跳变）。
     *
     * @param from 出发点；null 时新光标从距目标最近的屏幕边缘飞入
     * @param targetSize Fitts 目标宽度 W（节点短边；小目标自然减速）
     * @param onArrival 光标**完全落定**（弹簧收敛/直达）后的单次回调，
     *   在 tick 线程触发 —— 编排层在此执行真实无障碍操作
     * @return 本次移动的超时预算（弹道 + 弹簧兜底 + 漂移余量，ms）
     */
    fun moveCursorTo(
        cursorId: Int,
        to: PointF,
        targetSize: Float = 48f,
        style: MotionStyle = motionStyle,
        from: PointF? = null,
        onArrival: (() -> Unit)? = null,
    ): Long {
        require(cursorId in 0 until maxCursors) {
            "cursorId $cursorId 超出上限 maxCursors=$maxCursors"
        }
        synchronized(lock) {
            val state = cursors.getOrPut(cursorId) { spawnCursor(cursorId, to) }
            val start = from?.copy() ?: state.position.copy()
            state.plan = planner.plan(cursorId, start, to, targetSize, style)
            state.phase = Phase.MOVING
            state.phaseTimeMs = 0
            state.arrivalFired = false
            state.onArrival = onArrival
            state.driftStart = null
            state.driftDir = null
            return state.plan!!.estimatedTotalMs
        }
    }

    /**
     * 真实操作执行完毕后的「放松微漂移」：1–2px / 150ms ease-out
     * （模拟手部点击后的放松 —— 丝滑细节之一）。
     */
    fun settleDrift(cursorId: Int) {
        synchronized(lock) {
            val state = cursors[cursorId] ?: return
            if (state.phase == Phase.IDLE || state.phase == Phase.SETTLING) {
                state.phase = Phase.SETTLING
                state.phaseTimeMs = 0
                state.driftStart = state.position.copy()
                state.driftDir = driftDirection(cursorId)
            }
        }
    }

    /** 移除光标（下一次 tick 起不再产出该光标的帧 —— 渲染层自然消失）。 */
    fun removeCursor(cursorId: Int) {
        synchronized(lock) { cursors.remove(cursorId) }
    }

    /** 清空全部光标。 */
    fun clear() {
        synchronized(lock) { cursors.clear() }
    }

    /**
     * 推进一帧：对每只光标按其相位求值，产出渲染帧列表（按 cursorId 升序）。
     *
     * @param deltaMs 距上一帧的时长（钳制 1..100ms，掉帧保护）
     */
    fun tick(deltaMs: Long): List<CursorFrame> {
        if (deltaMs <= 0) return emptyList()
        val dtMs = deltaMs.coerceAtMost(100L)
        val dtSec = dtMs / 1000f

        synchronized(lock) {
            if (cursors.isEmpty()) return emptyList()
            val frames = ArrayList<CursorFrame>(cursors.size)
            for (state in cursors.values.sortedBy { it.id }) {
                val prevX = state.position.x
                val prevY = state.position.y

                advance(state, dtMs, dtSec)

                // 帧速度：有限差分（与半隐式 Euler 的弹簧速度精确一致）
                when (state.phase) {
                    Phase.IDLE -> state.velocity.set(0f, 0f)
                    else -> state.velocity.set(
                        (state.position.x - prevX) / dtSec,
                        (state.position.y - prevY) / dtSec,
                    )
                }

                if (enableTrail && state.phase != Phase.IDLE) {
                    state.trail.addLast(state.position.copy())
                    while (state.trail.size > config.trailCapacity) state.trail.removeFirst()
                }

                frames.add(
                    CursorFrame(
                        cursorId = state.id,
                        position = state.position.copy(),
                        velocity = state.velocity.copy(),
                        trailPoints = if (enableTrail) state.trail.toList() else emptyList(),
                        effect = state.phase.effect,
                    )
                )
            }
            return frames
        }
    }

    // ── 内部：相位推进 ────────────────────────────────────────────────────────

    private fun advance(state: CursorState, dtMs: Long, dtSec: Float) {
        when (state.phase) {
            Phase.IDLE -> Unit

            Phase.MOVING -> {
                val plan = state.plan ?: run { state.phase = Phase.IDLE; return }
                state.phaseTimeMs += dtMs
                val u = (state.phaseTimeMs.toFloat() / plan.durationMs).coerceAtMost(1f)
                val eased = minJerk(u)
                val p = plan.path.position(eased)

                if (plan.useJitter) {
                    // 抖动随接近交接点淡出 → 弹簧拿到干净的交接几何
                    val fade = (1f - eased / plan.handoffU).coerceIn(0f, 1f)
                    val amp = config.jitterAmplitudePx * fade
                    val nx = noise.noise2(state.noiseSeed + eased * config.jitterFrequency, state.noiseSeed * 0.7)
                    val ny = noise.noise2(state.noiseSeed * 1.3 + eased * config.jitterFrequency * 1.17, state.noiseSeed * 0.31)
                    p.x += (nx * amp).toFloat()
                    p.y += (ny * amp).toFloat()
                }
                state.position.set(p)

                if (plan.useSpring && eased >= plan.handoffU) {
                    state.phase = Phase.SPRING // 交接：带着上一帧差分速度进弹簧（帧速度在 advance 外统一计算）
                    state.phaseTimeMs = 0
                } else if (!plan.useSpring && u >= 1f) {
                    snapArrive(state)
                } else if (u >= 1f) {
                    // 理论不达（handoffU < 1），防御性兜底
                    state.phase = Phase.SPRING
                    state.phaseTimeMs = 0
                }
            }

            Phase.SPRING -> {
                val plan = state.plan ?: run { state.phase = Phase.IDLE; return }
                state.phaseTimeMs += dtMs
                val settled = plan.spring.integrate(state.position, state.velocity, plan.to, dtSec)
                if (settled || state.phaseTimeMs > plan.springMaxMs) {
                    snapArrive(state)
                }
            }

            Phase.SETTLING -> {
                state.phaseTimeMs += dtMs
                val t = (state.phaseTimeMs.toFloat() / config.settleDriftDurationMs).coerceAtMost(1f)
                val e = easeOutCubic(t)
                val start = state.driftStart ?: state.position.copy()
                val dir = state.driftDir ?: driftDirection(state.id)
                state.position.set(
                    start.x + dir.x * config.settleDriftPx * e,
                    start.y + dir.y * config.settleDriftPx * e,
                )
                if (t >= 1f) {
                    state.phase = Phase.IDLE
                    state.plan = null
                }
            }
        }
    }

    private fun snapArrive(state: CursorState) {
        val plan = state.plan
        if (plan != null) state.position.set(plan.to)
        state.velocity.set(0f, 0f)
        state.phase = Phase.IDLE
        if (!state.arrivalFired) {
            state.arrivalFired = true
            val cb = state.onArrival
            state.onArrival = null
            state.plan = null
            cb?.invoke() // tick 线程同步触发 —— 编排层只做协程恢复
        } else {
            state.plan = null
        }
    }

    private fun spawnCursor(cursorId: Int, firstTarget: PointF): CursorState {
        // 从距目标最近的竖直边缘飞入（目标在左半屏→左缘，右半屏→右缘），
        // y 取目标 y ± 光标号偏移 —— 多光标错落入场。
        val fromLeft = firstTarget.x < viewportWidth / 2f
        val edgeX = if (fromLeft) -CURSOR_OFFSCREEN else viewportWidth + CURSOR_OFFSCREEN
        val edgeY = (firstTarget.y + (cursorId - maxCursors / 2f) * 64f)
            .coerceIn(-CURSOR_OFFSCREEN, viewportHeight + CURSOR_OFFSCREEN)
        return CursorState(
            id = cursorId,
            position = PointF(edgeX, edgeY),
            noiseSeed = cursorId * 31.7,
        )
    }

    /** 每光标固定的漂移方向（黄金角散列 —— 多光标漂移方向各不相同）。 */
    private fun driftDirection(cursorId: Int): PointF {
        val angle = Math.toRadians((cursorId * 137.508).toDouble())
        return PointF(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
    }

    private enum class Phase(val effect: CursorEffect) {
        IDLE(CursorEffect.NONE),
        MOVING(CursorEffect.MOVING),
        SPRING(CursorEffect.ARRIVING),
        SETTLING(CursorEffect.SETTLING),
    }

    private class CursorState(
        val id: Int,
        var position: PointF,
        val noiseSeed: Double,
    ) {
        var velocity = PointF(0f, 0f)
        var phase: Phase = Phase.IDLE
        var phaseTimeMs: Long = 0
        var plan: MovePlan? = null
        var onArrival: (() -> Unit)? = null
        var arrivalFired: Boolean = false
        var driftStart: PointF? = null
        var driftDir: PointF? = null
        val trail = ArrayDeque<PointF>()
    }

    private companion object {
        /** 新光标从屏幕外 24px 处飞入（入场感）。 */
        const val CURSOR_OFFSCREEN = 24f

        /** min-jerk 位置 eased(u) = 10u³ − 15u⁴ + 6u⁵（人手峰值速度居中的钟形速度）。 */
        fun minJerk(u: Float): Float {
            val u2 = u * u
            val u3 = u2 * u
            val u4 = u3 * u
            val u5 = u4 * u
            return 10f * u3 - 15f * u4 + 6f * u5
        }

        fun easeOutCubic(t: Float): Float {
            val om = 1f - t
            return 1f - om * om * om
        }
    }
}
