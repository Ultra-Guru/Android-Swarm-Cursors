package io.swarmcursors.core.engine

import io.swarmcursors.core.geometry.PointF
import io.swarmcursors.core.model.CursorEffect
import io.swarmcursors.core.model.MotionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎集成测试 —— 固定 dt（16ms）确定性仿真：Perlin 稳定 seed + 纯数学推进，
 * 任何机器上逐步复现完全相同的轨迹（这是「核心层零 Android 依赖」的分红）。
 */
class SwarmEngineTest {

    private val dt = 16L // 62.5fps 固定步长

    @Test
    fun `cursor reaches target within estimated budget`() {
        val engine = SwarmEngine(enableTrail = true)
        val target = PointF(800f, 600f)
        var arrivals = 0
        val budget = engine.moveCursorTo(3, target, targetSize = 48f) { arrivals++ }

        var t = 0L
        var arrivalAt: Long? = null
        var lastPos: PointF? = null
        var sawMoving = false
        var sawArriving = false

        while (t < budget + 400) {
            val frame = engine.tick(dt).firstOrNull { it.cursorId == 3 } ?: break
            when (frame.effect) {
                CursorEffect.MOVING -> sawMoving = true
                CursorEffect.ARRIVING -> sawArriving = true
                else -> Unit
            }
            if (arrivals == 1 && arrivalAt == null) arrivalAt = t
            lastPos = frame.position
            t += dt
        }

        assertEquals("onArrival 必须恰好触发一次", 1, arrivals)
        assertNotNull("到达时刻必须被观测到", arrivalAt)
        assertTrue(
            "到达($arrivalAt ms)超出预算($budget ms)",
            arrivalAt!! <= budget,
        )
        assertEquals(target.x, lastPos!!.x, 0.5f)
        assertEquals(target.y, lastPos.y, 0.5f)
        assertTrue("必须经历 MOVING 相位", sawMoving)
        assertTrue("SIGNATURE_ARC 必须经历弹簧 ARRIVING 相位", sawArriving)
    }

    @Test
    fun `classic style arrives without spring phase`() {
        val engine = SwarmEngine(motionStyle = MotionStyle.CLASSIC)
        val target = PointF(500f, 400f)
        var arrivals = 0
        engine.moveCursorTo(0, target, 48f, MotionStyle.CLASSIC) { arrivals++ }

        var sawArriving = false
        var t = 0L
        while (t < 3000 && arrivals == 0) {
            val frame = engine.tick(dt).first { it.cursorId == 0 }
            if (frame.effect == CursorEffect.ARRIVING) sawArriving = true
            t += dt
        }
        assertEquals(1, arrivals)
        assertTrue("CLASSIC 不应进入弹簧相位", !sawArriving)
    }

    @Test
    fun `multiple cursors move in parallel and independently`() {
        val engine = SwarmEngine()
        val targets = mapOf(0 to PointF(200f, 900f), 1 to PointF(700f, 200f), 2 to PointF(500f, 1000f))
        val arrivals = mutableSetOf<Int>()
        targets.forEach { (id, tgt) ->
            engine.moveCursorTo(id, tgt, 48f) { arrivals.add(id) }
        }

        var t = 0L
        while (t < 3000 && arrivals.size < 3) {
            val frames = engine.tick(dt)
            assertEquals("每帧必须产出全部 3 只光标的帧", 3, frames.size)
            t += dt
        }
        assertEquals(setOf(0, 1, 2), arrivals)
    }

    @Test
    fun `replanning mid flight never teleports`() {
        val engine = SwarmEngine()
        engine.moveCursorTo(0, PointF(900f, 900f), 48f)

        var prev: PointF? = null
        for (i in 0 until 20) { // 移动 ~320ms
            prev = engine.tick(dt).first { it.cursorId == 0 }.position
        }
        assertNotNull(prev)
        assertTrue("先跑一段才有意义", prev!!.distanceTo(PointF(0f, 0f)) > 50f)

        // 流式重规划：改道去新目标
        engine.moveCursorTo(0, PointF(100f, 100f), 48f)
        val next = engine.tick(dt).first { it.cursorId == 0 }.position
        assertEquals(
            "改道帧必须从当前位置出发（消灭跳变）",
            0f,
            prev.distanceTo(next),
            1.0f,
        )
    }

    @Test
    fun `settle drift wiggles slightly then idles`() {
        val engine = SwarmEngine()
        val target = PointF(400f, 400f)
        var arrivals = 0
        engine.moveCursorTo(1, target, 48f) { arrivals++ }
        while (arrivals == 0) engine.tick(dt)

        engine.settleDrift(1)
        var maxDrift = 0f
        var settledIdle = false
        for (i in 0 until 30) {
            val f = engine.tick(dt).first { it.cursorId == 1 }
            if (f.effect == CursorEffect.SETTLING) {
                maxDrift = maxOf(maxDrift, f.position.distanceTo(target))
            }
            if (f.effect == CursorEffect.NONE && i > 5) settledIdle = true
        }
        assertTrue("漂移幅度应在 0–2.5px（模拟手部放松）", maxDrift in 0.5f..2.5f)
        assertTrue("150ms 后应回到 IDLE", settledIdle)
    }

    @Test
    fun `trail is bounded and grows during motion`() {
        val engine = SwarmEngine(enableTrail = true)
        engine.moveCursorTo(2, PointF(600f, 400f), 48f)
        var maxTrail = 0
        for (i in 0 until 200) {
            val f = engine.tick(dt).first { it.cursorId == 2 }
            maxTrail = maxOf(maxTrail, f.trailPoints.size)
        }
        assertTrue("拖尾应被填充（max=$maxTrail）", maxTrail > 4)
        assertTrue("拖尾容量上限（max=$maxTrail）", maxTrail <= 14)
    }

    @Test
    fun `remove cursor stops producing frames`() {
        val engine = SwarmEngine()
        engine.moveCursorTo(0, PointF(300f, 300f), 48f)
        assertTrue(engine.tick(dt).any { it.cursorId == 0 })
        engine.removeCursor(0)
        assertTrue(!engine.tick(dt).any { it.cursorId == 0 })
        assertEquals(0, engine.cursorCount)
    }

    @Test
    fun `empty engine ticks cheaply`() {
        val engine = SwarmEngine()
        assertEquals(0, engine.tick(16L).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `cursor id beyond max is rejected`() {
        val engine = SwarmEngine(maxCursors = 4)
        engine.moveCursorTo(4, PointF(0f, 0f), 48f)
    }

    @Test
    fun `velocity is non-zero during motion`() {
        val engine = SwarmEngine()
        engine.moveCursorTo(0, PointF(800f, 0f), 48f)
        var sawSpeed = false
        for (i in 0 until 60) {
            val f = engine.tick(dt).first { it.cursorId == 0 }
            if (f.velocity.length > 100f) sawSpeed = true // px/s
        }
        assertTrue("运动中必须有可观速度", sawSpeed)
    }
}
