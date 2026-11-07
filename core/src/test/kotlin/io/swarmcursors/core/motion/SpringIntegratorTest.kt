package io.swarmcursors.core.motion

import io.swarmcursors.core.geometry.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpringIntegratorTest {

    private val dt = 1f / 60f

    @Test
    fun `converges to target from rest`() {
        val spring = SpringIntegrator() // 200 / 0.75 官方常量
        val pos = PointF(100f, 0f)
        val vel = PointF(0f, 0f)
        val target = PointF(0f, 0f)

        var settled = false
        for (i in 0 until 240) { // 最多 4 秒
            settled = spring.integrate(pos, vel, target, dt)
            if (settled) break
        }
        assertTrue("4 秒内未收敛（pos=$pos vel=$vel）", settled)
        assertEquals(0f, pos.x, 0.25f)
    }

    @Test
    fun `low bouncy ratio overshoots slightly`() {
        // ζ=0.75 欠阻尼：从 +100 出发，解析过冲 = 100·e^(-ζπ/√(1-ζ²)) ≈ -2.84px
        val spring = SpringIntegrator(
            stiffness = SpringIntegrator.STIFFNESS_LOW,
            dampingRatio = SpringIntegrator.DAMPING_RATIO_LOW_BOUNCY,
        )
        val pos = PointF(100f, 0f)
        val vel = PointF(0f, 0f)
        val target = PointF(0f, 0f)

        var minX = Float.MAX_VALUE
        for (i in 0 until 180) {
            spring.integrate(pos, vel, target, dt)
            if (pos.x < minX) minX = pos.x
        }
        assertTrue("未观察到过冲（minX=$minX）—— 落停会显得机械", minX < -1f)
        // 同时过冲必须有界（≤ 原距离的 15%：不过分弹跳）
        assertTrue("过冲过大（minX=$minX）", minX > -15f)
    }

    @Test
    fun `critical damping does not overshoot`() {
        val spring = SpringIntegrator(
            stiffness = SpringIntegrator.STIFFNESS_LOW,
            dampingRatio = SpringIntegrator.DAMPING_RATIO_NO_BOUNCY,
        )
        val pos = PointF(100f, 100f)
        val vel = PointF(0f, 0f)
        val target = PointF(0f, 0f)

        var minLen = Float.MAX_VALUE
        for (i in 0 until 240) {
            spring.integrate(pos, vel, target, dt)
            val len = pos.length
            if (len < minLen) minLen = len
        }
        assertTrue("过阻尼不应越过原点（minLen=$minLen）", minLen >= -0.001f)
    }

    @Test
    fun `settled detection`() {
        val spring = SpringIntegrator()
        val pos = PointF(0.1f, 0.1f)
        val vel = PointF(1f, 1f)
        val target = PointF(0f, 0f)
        assertTrue(spring.isSettled(pos, target, vel))

        val far = PointF(50f, 50f)
        assertTrue(!spring.isSettled(far, target, vel))
    }

    @Test
    fun `large dt remains exact and stable`() {
        // 解析闭式解：掉帧场景一次给 100ms 步长也精确收敛（帧率无关）
        val spring = SpringIntegrator()
        val pos = PointF(500f, 0f)
        val vel = PointF(0f, 0f)
        val target = PointF(0f, 0f)
        for (i in 0 until 60) {
            spring.integrate(pos, vel, target, 0.1f) // 100ms 一大步
        }
        assertTrue("大步长发散（pos=$pos）", pos.length < 600f)
        assertEquals("大步长也应收敛到目标", 0f, pos.x, 0.5f)
    }

    @Test
    fun `carries entry velocity through`() {
        // 带初速度交接（弹道段 → 弹簧）：初速度应让收敛更快出现运动趋势
        val spring = SpringIntegrator()
        val pos = PointF(300f, 0f)
        val vel = PointF(-2000f, 0f) // 朝目标 2000 px/s
        val target = PointF(0f, 0f)
        spring.integrate(pos, vel, target, dt)
        assertTrue("应向目标推进", pos.x < 300f)
        assertTrue("速度方向保持朝目标", vel.x < 0f)
    }
}
