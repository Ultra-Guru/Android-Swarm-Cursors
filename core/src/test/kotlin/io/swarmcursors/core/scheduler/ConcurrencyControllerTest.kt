package io.swarmcursors.core.scheduler

import io.swarmcursors.core.geometry.PointF
import io.swarmcursors.core.geometry.RectF
import io.swarmcursors.core.model.ParallelMode
import io.swarmcursors.core.model.ResolvedTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcurrencyControllerTest {

    private fun targetAt(x: Float, y: Float, conflict: String? = null): ResolvedTarget =
        ResolvedTarget(
            point = PointF(x, y),
            nodeBounds = RectF.fromCenter(PointF(x, y), 48f, 48f),
            conflictKey = conflict,
        )

    // ── SAFE：分区并行 ───────────────────────────────────────────────────────

    @Test
    fun `safe mode admits different cells in parallel`() = runTest {
        val c = ConcurrencyController(mode = ParallelMode.SAFE, maxParallel = 4, gridWidth = 900f, gridHeight = 1800f)
        // 3×3 网格：(100,100)=r0c0，(450,900)=r1c1，(800,1700)=r2c2 —— 三个不同区
        val p1 = c.acquire(targetAt(100f, 100f))
        val p2 = c.acquire(targetAt(450f, 900f))
        val p3 = c.acquire(targetAt(800f, 1700f))
        assertEquals(3, c.activePermits)
        c.release(p1); c.release(p2); c.release(p3)
        assertEquals(0, c.activePermits)
    }

    @Test
    fun `safe mode serializes same cell`() = runTest {
        val c = ConcurrencyController(mode = ParallelMode.SAFE, gridWidth = 900f, gridHeight = 1800f)
        val p1 = c.acquire(targetAt(100f, 100f)) // r0c0

        var stolen: ConcurrencyController.Permit? = null
        val job = launch { stolen = c.acquire(targetAt(150f, 150f)) } // 同 r0c0 → 排队
        advanceUntilIdle()
        assertNull("同区必须串行等待", stolen)
        assertEquals(1, c.waitingCount)

        c.release(p1)
        advanceUntilIdle()
        assertNotNull("释放后等待者应立即获准", stolen)
        assertEquals(0, c.waitingCount)
        c.release(stolen!!)
    }

    @Test
    fun `same conflict key serializes across cells`() = runTest {
        val c = ConcurrencyController(mode = ParallelMode.SAFE, gridWidth = 900f, gridHeight = 1800f)
        // 兄弟节点（同父容器）即使分处不同网格区也必须串行
        val p1 = c.acquire(targetAt(100f, 100f, conflict = "parent-7"))
        var blocked: ConcurrencyController.Permit? = null
        launch { blocked = c.acquire(targetAt(800f, 1700f, conflict = "parent-7")) }
        advanceUntilIdle()
        assertNull("同冲突键必须降级串行", blocked)
        c.release(p1)
        advanceUntilIdle()
        assertNotNull(blocked)
        c.release(blocked!!)
    }

    @Test
    fun `max parallel caps total concurrency`() = runTest {
        val c = ConcurrencyController(
            mode = ParallelMode.SAFE, maxParallel = 2,
            gridWidth = 900f, gridHeight = 1800f,
        )
        val p1 = c.acquire(targetAt(100f, 100f))   // r0c0
        val p2 = c.acquire(targetAt(450f, 900f))   // r1c1 → 达上限
        var third: ConcurrencyController.Permit? = null
        launch { third = c.acquire(targetAt(800f, 1700f)) } // r2c2 虽异区但超上限 → 等待
        advanceUntilIdle()
        assertNull("并行度上限必须生效", third)

        c.release(p1)
        advanceUntilIdle()
        assertNotNull("空位出现后应获准", third)
        c.release(p2); c.release(third!!)
    }

    @Test
    fun `fifo fairness holds`() = runTest {
        val c = ConcurrencyController(mode = ParallelMode.SAFE, maxParallel = 1, gridWidth = 900f, gridHeight = 1800f)
        val first = c.acquire(targetAt(100f, 100f))

        val order = mutableListOf<Int>()
        val jobs = (1..3).map { i ->
            launch {
                val p = c.acquire(targetAt(i * 250f + 50f, i * 500f))
                order.add(i)
                c.release(p)
            }
        }
        advanceUntilIdle()
        assertTrue(order.isEmpty())

        c.release(first)
        advanceUntilIdle()
        assertEquals("FIFO：先到先得", listOf(1, 2, 3), order)
        jobs.forEach { it.cancel() }
    }

    // ── SERIAL / AGGRESSIVE ──────────────────────────────────────────────────

    @Test
    fun `serial mode allows only one at a time`() = runTest {
        val c = ConcurrencyController(mode = ParallelMode.SERIAL)
        val p = c.acquire(targetAt(100f, 100f))
        var second: ConcurrencyController.Permit? = null
        launch { second = c.acquire(targetAt(800f, 800f)) }
        advanceUntilIdle()
        assertNull(second)
        c.release(p)
        advanceUntilIdle()
        assertNotNull(second)
        c.release(second!!)
    }

    @Test
    fun `aggressive mode admits everything`() = runTest {
        val c = ConcurrencyController(mode = ParallelMode.AGGRESSIVE)
        val permits = (0 until 16).map { c.acquire(targetAt(it * 60f, it * 100f)) }
        assertEquals(16, c.activePermits)
        permits.forEach { c.release(it) }
        assertEquals(0, c.activePermits)
    }

    // ── tryAcquire / 取消 / 幂等 ─────────────────────────────────────────────

    @Test
    fun `tryAcquire returns null when cell busy`() = runTest {
        val c = ConcurrencyController(mode = ParallelMode.SAFE, gridWidth = 900f, gridHeight = 1800f)
        val p = c.acquire(targetAt(100f, 100f))
        assertNull(c.tryAcquire(targetAt(120f, 120f))) // 同区
        assertNotNull(c.tryAcquire(targetAt(450f, 900f))) // 异区
        c.release(p)
    }

    @Test
    fun `cancelled waiter does not leak permits`() = runTest {
        val c = ConcurrencyController(mode = ParallelMode.SAFE, maxParallel = 1, gridWidth = 900f, gridHeight = 1800f)
        val first = c.acquire(targetAt(100f, 100f))

        val job = launch { c.acquire(targetAt(450f, 900f)) }
        advanceUntilIdle()
        assertEquals(1, c.waitingCount)
        job.cancel()
        advanceUntilIdle()
        assertEquals("取消的等待者应撤出队列", 0, c.waitingCount)

        c.release(first)
        advanceUntilIdle()
        assertEquals("许可未泄漏：下一个 acquire 应立即成功", 0, c.activePermits)
        val again = c.acquire(targetAt(450f, 900f))
        assertNotNull(again)
        c.release(again)
    }

    @Test
    fun `double release is idempotent`() = runTest {
        val c = ConcurrencyController(mode = ParallelMode.SAFE, gridWidth = 900f, gridHeight = 1800f)
        val p = c.acquire(targetAt(100f, 100f))
        c.release(p)
        c.release(p) // 不得把计数打成负数
        assertEquals(0, c.activePermits)
    }

    @Test
    fun `reconfigure updates grid mapping`() {
        val c = ConcurrencyController(mode = ParallelMode.SAFE, gridWidth = 900f, gridHeight = 1800f)
        // 旋转：900×1800 → 1800×900；(850, 100) 原为 r0c2 → 新网格 r0c1
        c.reconfigure(1800f, 900f)
        assertEquals(1800f, c.gridWidth)
        assertEquals(900f, c.gridHeight)
    }
}
