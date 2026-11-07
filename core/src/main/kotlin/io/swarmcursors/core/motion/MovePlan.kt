package io.swarmcursors.core.motion

import io.swarmcursors.core.geometry.PointF
import io.swarmcursors.core.model.MotionStyle

/**
 * 单次移动的「离线规划」产物：路径采样器 + 时长 + 交接点 + 弹簧参数。
 * 引擎每帧只做**求值**（O(1) 采样），不做规划 —— 规划/求值分离保证
 * tick 的确定性（可测试）与帧预算（16 光标 < 0.5ms/帧）。
 *
 * @param path u∈[0,1] → 路径上的点（未叠加微抖动）
 * @param durationMs 弹道段时长（Fitts 时序）
 * @param handoffU 交接进度： eased(u) ≥ handoffU 后切换弹簧落停
 * @param useSpring 是否启用弹簧落停（CLASSIC 风格为 false —— 直达目标）
 * @param useJitter 是否叠加 Perlin 微抖动（CLASSIC 为 false）
 */
internal class MovePlan(
    val cursorId: Int,
    val style: MotionStyle,
    val from: PointF,
    val to: PointF,
    val durationMs: Long,
    val path: PathSampler,
    val handoffU: Float,
    val useSpring: Boolean,
    val useJitter: Boolean,
    val spring: SpringIntegrator,
    val springMaxMs: Long,
) {
    /** 编排层的超时预算（弹道 + 弹簧兜底 + 点击后漂移）。 */
    val estimatedTotalMs: Long = durationMs + springMaxMs + 200
}

/** 路径采样器：u∈[0,1] → 屏幕坐标点。 */
internal sealed class PathSampler {

    abstract fun position(u: Float): PointF

    /** 直线路径（CLASSIC / SPRING_SETTLE 风格）。 */
    class Linear(val from: PointF, val to: PointF) : PathSampler() {
        override fun position(u: Float): PointF = PointF.lerp(from, to, u)
    }

    /**
     * 二次贝塞尔弧线路径（SIGNATURE_ARC 招牌风格）：
     * 控制点在弦中点的法向偏移 `bulgePx` 处 —— 光标走弧线「绕」向目标，
     * 与多光标的鼓起方向交替配合形成「蜂群」轨迹。
     */
    class Arc(from: PointF, to: PointF, bulgePx: Float) : PathSampler() {
        private val p0 = from.copy()
        private val p1 = to.copy()
        private val ctrl: PointF

        init {
            val mx = (from.x + to.x) / 2f
            val my = (from.y + to.y) / 2f
            val dx = to.x - from.x
            val dy = to.y - from.y
            // 弦的法向（-dy, dx)/|d| × 鼓起量
            val len = kotlin.math.hypot(dx, dy)
            val nx = if (len > 0f) -dy / len else 0f
            val ny = if (len > 0f) dx / len else 0f
            ctrl = PointF(mx + nx * bulgePx, my + ny * bulgePx)
        }

        override fun position(u: Float): PointF {
            val om = 1f - u
            val a = om * om
            val b = 2f * om * u
            val c = u * u
            return PointF(
                a * p0.x + b * ctrl.x + c * p1.x,
                a * p0.y + b * ctrl.y + c * p1.y,
            )
        }
    }
}
