package io.swarmcursors.core.motion

import io.swarmcursors.core.geometry.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class SplineInterpolatorTest {

    private val spline = SplineInterpolator()

    private val square = listOf(
        PointF(0f, 0f),
        PointF(100f, 0f),
        PointF(100f, 100f),
        PointF(0f, 100f),
    )

    @Test
    fun `endpoints are exact`() {
        assertEquals(PointF(0f, 0f), spline.sample(square, 0f))
        assertEquals(PointF(0f, 100f), spline.sample(square, 1f))
    }

    @Test
    fun `passes through interior control points`() {
        // t = k/(n-1) 处精确穿过第 k 个控制点（Catmull-Rom 的穿越性）
        assertEquals(PointF(100f, 0f), spline.sample(square, 1f / 3f))
        assertEquals(PointF(100f, 100f), spline.sample(square, 2f / 3f))
    }

    @Test
    fun `curve is first-order continuous`() {
        // 300 个采样点的最大步长应远小于路径总长（~300px）—— 无折角跳变
        val pts = spline.samplePath(square, 300)
        var maxStep = 0f
        for (i in 1 until pts.size) {
            val step = hypot((pts[i].x - pts[i - 1].x).toDouble(), (pts[i].y - pts[i - 1].y).toDouble())
            if (step > maxStep) maxStep = step.toFloat()
        }
        assertTrue("存在跳变（maxStep=$maxStep px）", maxStep < 5f)
    }

    @Test
    fun `samplePath returns requested count`() {
        assertEquals(50, spline.samplePath(square, 50).size)
        assertEquals(2, spline.samplePath(square, 2).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `single control point rejected`() {
        spline.sample(listOf(PointF(0f, 0f)), 0.5f)
    }

    @Test
    fun `two points degenerate to interpolation`() {
        val two = listOf(PointF(0f, 0f), PointF(10f, 20f))
        assertEquals(PointF(5f, 10f), spline.sample(two, 0.5f))
    }
}
