package io.swarmcursors.core.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 几何基元的 JVM 单测 —— 插值端点 / 距离 / 归一化 / 命中判定 / 倒置容错。
 * 这些是样条采样与 Fitts 时序的地基，必须先钉死。
 */
class GeometryTest {

    // ── PointF ──────────────────────────────────────────────────────────────

    @Test
    fun `lerp endpoints are exact`() {
        val a = PointF(0f, 0f)
        val b = PointF(100f, 200f)
        assertEquals(a, PointF.lerp(a, b, 0f))
        assertEquals(b, PointF.lerp(a, b, 1f))
        assertEquals(PointF(50f, 100f), PointF.lerp(a, b, 0.5f))
    }

    @Test
    fun `distance is euclidean`() {
        assertEquals(5f, PointF(0f, 0f).distanceTo(PointF(3f, 4f)), 1e-6f)
        assertEquals(25f, PointF(0f, 0f).distanceSquaredTo(PointF(3f, 4f)), 1e-6f)
    }

    @Test
    fun `normalized yields unit vector`() {
        val n = PointF(3f, 4f).normalized()
        assertEquals(1f, n.length, 1e-5f)
        // 零向量防御：归一化返回零而非 NaN
        assertEquals(PointF(0f, 0f), PointF(0f, 0f).normalized())
    }

    @Test
    fun `operators do not mutate operands`() {
        val a = PointF(1f, 2f)
        val b = PointF(10f, 20f)
        val sum = a + b
        assertEquals(PointF(11f, 22f), sum)
        assertEquals(PointF(1f, 2f), a) // 原值未被改动
        assertEquals(PointF(5f, 10f), b * 0.5f)
    }

    @Test
    fun `equals distinguishes coordinates`() {
        assertNotEquals(PointF(1f, 2f), PointF(2f, 1f))
        assertEquals(PointF(1f, 2f), PointF(1f, 2f))
    }

    // ── RectF ───────────────────────────────────────────────────────────────

    @Test
    fun `contains is half-open like android`() {
        val r = RectF(0f, 0f, 100f, 100f)
        assertTrue(r.contains(0f, 0f))
        assertTrue(r.contains(99.999f, 99.999f))
        assertFalse(r.contains(100f, 50f)) // 右边界不含
        assertFalse(r.contains(50f, 100f)) // 下边界不含
    }

    @Test
    fun `inverted bounds are normalized on construction`() {
        // left=20>right=10 → 交换后 left=10/right=20；top=15>bottom=5 → 交换后 top=5/bottom=15
        val r = RectF(right = 10f, left = 20f, bottom = 5f, top = 15f)
        assertTrue(r.width > 0f)
        assertTrue(r.height > 0f)
        assertEquals(10f, r.left, 1e-6f)
        assertEquals(20f, r.right, 1e-6f)
        assertEquals(5f, r.top, 1e-6f)
        assertEquals(15f, r.bottom, 1e-6f)
    }

    @Test
    fun `fromCenter builds symmetric rect`() {
        val r = RectF.fromCenter(PointF(50f, 60f), width = 20f, height = 10f)
        assertEquals(40f, r.left, 1e-6f)
        assertEquals(60f, r.right, 1e-6f)
        assertEquals(55f, r.top, 1e-6f)
        assertEquals(65f, r.bottom, 1e-6f)
        assertEquals(PointF(50f, 60f), r.center())
    }

    @Test
    fun `intersects detects overlap both ways`() {
        val a = RectF(0f, 0f, 100f, 100f)
        val b = RectF(50f, 50f, 150f, 150f)
        val c = RectF(200f, 200f, 300f, 300f)
        assertTrue(a.intersects(b))
        assertTrue(RectF.intersects(a, b)) // 交换调用方
        assertFalse(a.intersects(c))
    }

    @Test
    fun `expand offsets all edges`() {
        val r = RectF(40f, 40f, 60f, 60f).expand(10f, 5f)
        assertEquals(30f, r.left, 1e-6f)
        assertEquals(35f, r.top, 1e-6f)
        assertEquals(70f, r.right, 1e-6f)
        assertEquals(65f, r.bottom, 1e-6f)
    }
}
