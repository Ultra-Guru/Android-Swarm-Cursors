package io.swarmcursors.core.motion

import io.swarmcursors.core.geometry.PointF
import io.swarmcursors.core.model.MotionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FittsMotionPlannerTest {

    private val planner = FittsMotionPlanner()

    @Test
    fun `fitts formula exact value`() {
        // MT = 0.1 + 0.08·log2(2·100/48) = 0.1 + 0.08·2.0589 ≈ 0.2647s → 265ms
        assertEquals(265L, planner.fittsDurationMs(distancePx = 100f, targetSizePx = 48f))
    }

    @Test
    fun `longer distance takes longer`() {
        val short = planner.fittsDurationMs(100f, 48f)
        val long = planner.fittsDurationMs(1000f, 48f)
        assertTrue("距离越远应越慢（$short vs $long）", long > short)
    }

    @Test
    fun `larger target is faster`() {
        val smallTarget = planner.fittsDurationMs(500f, 24f)
        val bigTarget = planner.fittsDurationMs(500f, 96f)
        assertTrue("大目标应更快（$smallTarget vs $bigTarget）", bigTarget < smallTarget)
    }

    @Test
    fun `duration clamped to bounds`() {
        val cfg = MotionPlannerConfig(minDurationMs = 120, maxDurationMs = 1000)
        val p = FittsMotionPlanner(cfg)
        assertEquals(120L, p.fittsDurationMs(1f, 2000f))   // 超短距离 → 下限
        assertEquals(1000L, p.fittsDurationMs(500_000f, 1f)) // 超远距离 → 上限
    }

    @Test
    fun `zero sized target is defended`() {
        // 零尺寸节点（真实无障碍树常见）不得产生 NaN/负时长
        val d = planner.fittsDurationMs(300f, 0f)
        assertTrue(d in 120..1000)
    }

    @Test
    fun `signature arc uses arc path with spring and jitter`() {
        val plan = planner.plan(0, PointF(0f, 0f), PointF(500f, 300f), 48f, MotionStyle.SIGNATURE_ARC)
        assertTrue(plan.path is PathSampler.Arc)
        assertTrue(plan.useSpring)
        assertTrue(plan.useJitter)
        assertTrue(plan.estimatedTotalMs > plan.durationMs)
    }

    @Test
    fun `classic uses linear path without spring`() {
        val plan = planner.plan(1, PointF(0f, 0f), PointF(500f, 300f), 48f, MotionStyle.CLASSIC)
        assertTrue(plan.path is PathSampler.Linear)
        assertTrue(!plan.useSpring)
        assertTrue(!plan.useJitter)
        assertEquals(0L, plan.springMaxMs)
    }

    @Test
    fun `spring settle hands off earlier and softer`() {
        val signature = planner.plan(0, PointF(0f, 0f), PointF(500f, 300f), 48f, MotionStyle.SIGNATURE_ARC)
        val springStyle = planner.plan(0, PointF(0f, 0f), PointF(500f, 300f), 48f, MotionStyle.SPRING_SETTLE)
        assertTrue(springStyle.handoffU < signature.handoffU)
        assertTrue(springStyle.spring.stiffness < signature.spring.stiffness)
    }

    @Test
    fun `odd cursors bulge opposite direction`() {
        val even = planner.plan(0, PointF(0f, 0f), PointF(400f, 0f), 48f, MotionStyle.SIGNATURE_ARC, bulgeDirection = 1)
        val odd = planner.plan(1, PointF(0f, 0f), PointF(400f, 0f), 48f, MotionStyle.SIGNATURE_ARC, bulgeDirection = -1)
        val evenMid = even.path.position(0.5f)
        val oddMid = odd.path.position(0.5f)
        // 同一水平弦：两侧鼓起 → 中点 y 相对弦(=0)符号相反
        assertTrue(evenMid.y * oddMid.y < 0f)
    }
}
