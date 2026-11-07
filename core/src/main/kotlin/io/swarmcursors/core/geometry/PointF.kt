package io.swarmcursors.core.geometry

import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 二维浮点坐标点 / 向量 —— 纯 Kotlin 实现。
 *
 * API 形状与 `android.graphics.PointF` 对齐（方便 Android 侧心智迁移），但 **零
 * Android 依赖**：`:core` 运动引擎在逻辑坐标系中工作，全部数学（样条采样 /
 * Fitts 距离 / 弹簧积分 / 微抖动）都基于本类型，可在 JVM 上直接单元测试。
 *
 * 同时承担「点」与「向量」两种语义：[length] / [normalized] / [dot] 服务于
 * 运动规划器，[x]/[y] 语义为屏幕逻辑像素。
 */
class PointF(var x: Float = 0f, var y: Float = 0f) {

    // ── 只读几何量 ──────────────────────────────────────────────────────────

    /** 向量长度（到原点的欧氏距离）。 */
    val length: Float get() = sqrt(x * x + y * y)

    /** 向量长度的平方（比较距离大小时避免开方）。 */
    val lengthSquared: Float get() = x * x + y * y

    // ── 变更操作（返回自身以便链式）───────────────────────────────────────

    fun set(x: Float, y: Float): PointF {
        this.x = x
        this.y = y
        return this
    }

    fun set(p: PointF): PointF = set(p.x, p.y)

    fun offset(dx: Float, dy: Float): PointF {
        x += dx
        y += dy
        return this
    }

    fun negate(): PointF {
        x = -x
        y = -y
        return this
    }

    fun scale(s: Float): PointF {
        x *= s
        y *= s
        return this
    }

    // ── 派生值（不修改自身）──────────────────────────────────────────────

    fun copy(): PointF = PointF(x, y)

    /** 单位向量；零向量返回零向量（运动引擎对静止光标的防御）。 */
    fun normalized(): PointF {
        val len = length
        return if (len > 0f) PointF(x / len, y / len) else PointF(0f, 0f)
    }

    fun distanceTo(other: PointF): Float = hypot(x - other.x, y - other.y)

    fun distanceSquaredTo(other: PointF): Float {
        val dx = x - other.x
        val dy = y - other.y
        return dx * dx + dy * dy
    }

    fun dot(other: PointF): Float = x * other.x + y * other.y

    // ── 运算符 ──────────────────────────────────────────────────────────────

    operator fun plus(other: PointF): PointF = PointF(x + other.x, y + other.y)
    operator fun minus(other: PointF): PointF = PointF(x - other.x, y - other.y)
    operator fun times(scale: Float): PointF = PointF(x * scale, y * scale)
    operator fun div(scale: Float): PointF = PointF(x / scale, y / scale)
    operator fun unaryMinus(): PointF = PointF(-x, -y)

    // ── 相等性 ──────────────────────────────────────────────────────────────

    override fun equals(other: Any?): Boolean =
        other is PointF && other.x == x && other.y == y

    override fun hashCode(): Int {
        var result = x.toRawBits()
        result = 31 * result + y.toRawBits()
        return result
    }

    override fun toString(): String = "PointF($x, $y)"

    companion object {
        /** 线性插值：t=0 返回 a 的副本，t=1 返回 b 的副本（t 不裁剪，调用方决定语义）。 */
        fun lerp(a: PointF, b: PointF, t: Float): PointF =
            PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    }
}
