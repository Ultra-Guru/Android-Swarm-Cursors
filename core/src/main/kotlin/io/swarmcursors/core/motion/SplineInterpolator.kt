package io.swarmcursors.core.motion

import io.swarmcursors.core.geometry.PointF

/**
 * 均匀 Catmull-Rom 样条插值器 —— 纯 Kotlin 实现。
 *
 * 意义：Agent / 无障碍树给出的位置更新是离散的（可能每 50–80ms 一次）。
 * 直接跳转会明显卡顿；Catmull-Rom 在最近控制点队列的相邻两点间插入
 * 计算点，使路径**经过所有控制点**且一阶连续（无折角跳变）—— 消灭
 * 「跳变」的数学根基。
 *
 * 公式（P0..P3 为连续四个控制点，u∈[0,1] 为当前段内参数）：
 *
 * ```
 * P(u) = 0.5 · [ (2·P1) + (-P0 + P2)·u
 *               + (2·P0 - 5·P1 + 4·P2 - P3)·u²
 *               + (-P0 + 3·P1 - 3·P2 + P3)·u³ ]
 * ```
 *
 * 端点策略：首/尾控制点复制（tension 保持均匀 CR 的凸包性）。
 *
 * @param t 全路径归一化参数 [0,1]：t=0 → 首控制点，t=1 → 末控制点，
 *          t = k/(n-1) → 精确穿过第 k 个控制点
 */
class SplineInterpolator {

    /** 在控制点序列定义的样条上采样。要求至少 2 个控制点。 */
    fun sample(controlPoints: List<PointF>, t: Float): PointF {
        require(controlPoints.size >= 2) { "Catmull-Rom 需要至少 2 个控制点（当前 ${controlPoints.size}）" }
        val n = controlPoints.size
        if (t <= 0f) return controlPoints.first().copy()
        if (t >= 1f) return controlPoints.last().copy()

        val scaled = t * (n - 1)
        val seg = scaled.toInt().coerceAtMost(n - 2)
        val u = scaled - seg

        // 段边界外的「虚拟」控制点：端点复制
        val p0 = controlPoints[if (seg == 0) 0 else seg - 1]
        val p1 = controlPoints[seg]
        val p2 = controlPoints[seg + 1]
        val p3 = controlPoints[if (seg + 2 >= n) n - 1 else seg + 2]

        val u2 = u * u
        val u3 = u2 * u

        val x = 0.5f * (
            (2f * p1.x) +
                (-p0.x + p2.x) * u +
                (2f * p0.x - 5f * p1.x + 4f * p2.x - p3.x) * u2 +
                (-p0.x + 3f * p1.x - 3f * p2.x + p3.x) * u3
            )
        val y = 0.5f * (
            (2f * p1.y) +
                (-p0.y + p2.y) * u +
                (2f * p0.y - 5f * p1.y + 4f * p2.y - p3.y) * u2 +
                (-p0.y + 3f * p1.y - 3f * p2.y + p3.y) * u3
            )
        return PointF(x, y)
    }

    /**
     * 整条样条按 [samples] 数均匀采样（旧→新），供拖尾平滑 / 手势路径
     *（SWIPE 的 dispatchGesture 路径点）使用。
     */
    fun samplePath(controlPoints: List<PointF>, samples: Int): List<PointF> {
        require(samples >= 2) { "samples 至少为 2" }
        return (0 until samples).map { i ->
            sample(controlPoints, i.toFloat() / (samples - 1))
        }
    }
}
