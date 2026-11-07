package io.swarmcursors.core.motion

import io.swarmcursors.core.geometry.PointF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 阻尼弹簧 —— **解析闭式解**步进器（帧率无关）。
 *
 * 用途：光标到达目标后的「弹簧落停」。线性减速停止是机械感的根源；
 * 欠阻尼弹簧产生轻微过冲 + 回弹收敛 —— 「落地的顿挫感」。
 *
 * 为什么用解析解而不是数值积分：半隐式 Euler 在 dt=1/60 时数值阻尼会
 * 吞掉约 2/3 的理论过冲（实测 -0.93px vs 解析 -2.84px），且 30/60/120fps
 * 设备上手感不一致。闭式解对任意 dt 单步精确：
 *
 *  - 欠阻尼（ζ<1，本库主路径）：`x(τ) = x_t + e^(-λτ)·(A·cos ω_d τ + B·sin ω_d τ)`
 *  - 临界阻尼（ζ=1）：`x(τ) = x_t + (A + Bτ)·e^(-ωτ)`
 *  - 过阻尼（ζ>1）：两个实指数之和
 *
 * 其中 `λ = ζω`，`ω_d = ω√(1-ζ²)`，`A = x₀ - x_t`，`B = (v₀ + λA)/ω_d`。
 *
 * 参数与 Android 官方常量对齐（集成方零心智迁移）：
 *  - [dampingRatio] = `SpringForce.DAMPING_RATIO_LOW_BOUNCY` = 0.75 → 轻微回弹
 *  - [stiffness]    = `SpringForce.STIFFNESS_LOW`             = 200  → 柔和落停
 *
 * 每步成本：2 轴 × (1 exp + 1 sin + 1 cos + 若干乘加) —— 16 光标 < 0.05ms/帧。
 */
class SpringIntegrator(
    val stiffness: Float = STIFFNESS_LOW,
    val dampingRatio: Float = DAMPING_RATIO_LOW_BOUNCY,
) {

    private val omega: Float = sqrt(stiffness)

    /**
     * 就地推进一步（解析解，任意 dt 单步精确，无需子步）。
     *
     * @param dtSec 步长（秒）；钳制 ≤ 0.5s（后台切回时的野值防御）
     * @return 是否已收敛（见 [isSettled]）—— 调用方据此触发 onArrival
     */
    fun integrate(position: PointF, velocity: PointF, target: PointF, dtSec: Float): Boolean {
        val dt = dtSec.coerceIn(0f, MAX_STEP_SEC)

        val (nx, nv) = stepAxis(position.x, velocity.x, target.x, dt)
        val (ny, nvy) = stepAxis(position.y, velocity.y, target.y, dt)
        position.set(nx, ny)
        velocity.set(nv, nvy)

        return isSettled(position, target, velocity)
    }

    /** 收敛判定：距目标 < [positionEpsilon] 且动能 < [velocityEpsilon]。 */
    fun isSettled(
        position: PointF,
        target: PointF,
        velocity: PointF,
        positionEpsilon: Float = 0.25f,
        velocityEpsilon: Float = 24f,
    ): Boolean =
        position.distanceSquaredTo(target) < positionEpsilon * positionEpsilon &&
            velocity.lengthSquared < velocityEpsilon * velocityEpsilon

    /** 单轴解析解：返回 (新位置, 新速度)。 */
    private fun stepAxis(pos: Float, vel: Float, targetPos: Float, dt: Float): Pair<Float, Float> {
        val a = pos - targetPos // A = x₀ - x_t
        if (dt <= 0f) return pos to vel

        return when {
            dampingRatio < 1f -> {
                // 欠阻尼：x = x_t + e^(-λτ)(A·cos ω_d τ + B·sin ω_d τ)
                val lambda = dampingRatio * omega
                val wd = omega * sqrt(1f - dampingRatio * dampingRatio)
                val e = exp(-lambda * dt)
                val c = cos(wd * dt)
                val s = sin(wd * dt)
                val b = (vel + lambda * a) / wd
                val newPos = targetPos + e * (a * c + b * s)
                val newVel = e * ((-lambda * a + wd * b) * c + (-lambda * b - wd * a) * s)
                newPos to newVel
            }

            abs(dampingRatio - 1f) < 1e-6f -> {
                // 临界阻尼：x = x_t + (A + Bτ)e^(-ωτ)，B = v₀ + ωA
                val e = exp(-omega * dt)
                val b = vel + omega * a
                val newPos = targetPos + (a + b * dt) * e
                val newVel = (b - omega * (a + b * dt)) * e
                newPos to newVel
            }

            else -> {
                // 过阻尼：x = x_t + C·e^(r₁τ) + D·e^(r₂τ)
                val s = sqrt(dampingRatio * dampingRatio - 1f)
                val r1 = -omega * (dampingRatio + s)
                val r2 = -omega * (dampingRatio - s)
                val c = (vel - r2 * a) / (r1 - r2)
                val d = a - c
                val e1 = exp(r1 * dt)
                val e2 = exp(r2 * dt)
                val newPos = targetPos + c * e1 + d * e2
                val newVel = c * r1 * e1 + d * r2 * e2
                newPos to newVel
            }
        }
    }

    companion object {
        const val DAMPING_RATIO_NO_BOUNCY = 1.0f
        const val DAMPING_RATIO_LOW_BOUNCY = 0.75f
        const val DAMPING_RATIO_MEDIUM_BOUNCY = 0.5f
        const val DAMPING_RATIO_HIGH_BOUNCY = 0.2f

        const val STIFFNESS_HIGH = 10_000f
        const val STIFFNESS_MEDIUM = 1500f
        const val STIFFNESS_LOW = 200f

        /** 单步上限 0.5s（后台切回时 dt 野值的防御性钳制）。 */
        const val MAX_STEP_SEC = 0.5f
    }
}
