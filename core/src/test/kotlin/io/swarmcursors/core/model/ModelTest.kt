package io.swarmcursors.core.model

import io.swarmcursors.core.geometry.PointF
import io.swarmcursors.core.geometry.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {

    // ── AgentAction ──────────────────────────────────────────────────────────

    @Test
    fun `action with node id has target`() {
        val a = AgentAction(type = ActionType.TAP, targetNodeId = "login")
        assertTrue(a.hasTarget)
        assertTrue(a.isWellFormed)
    }

    @Test
    fun `action with point fallback has target`() {
        val a = AgentAction(type = ActionType.TAP, targetPoint = PointF(100f, 200f))
        assertTrue(a.hasTarget)
    }

    @Test
    fun `type action requires text`() {
        assertFalse(
            AgentAction(type = ActionType.TYPE, targetNodeId = "input", text = null).isWellFormed
        )
        assertTrue(
            AgentAction(type = ActionType.TYPE, targetNodeId = "input", text = "hello").isWellFormed
        )
    }

    @Test
    fun `scroll action requires direction`() {
        assertFalse(AgentAction(type = ActionType.SCROLL, targetNodeId = "list").isWellFormed)
        assertTrue(
            AgentAction(type = ActionType.SCROLL, targetNodeId = "list", direction = Direction.DOWN).isWellFormed
        )
    }

    @Test
    fun `default cursor id is zero and duration 300ms`() {
        val a = AgentAction(type = ActionType.TAP, targetPoint = PointF(0f, 0f))
        assertEquals(0, a.cursorId)
        assertEquals(300L, a.durationMs)
    }

    // ── ResolvedTarget ───────────────────────────────────────────────────────

    @Test
    fun `targetSize clamps zero bounds to 1px`() {
        val t = ResolvedTarget(
            point = PointF(10f, 10f),
            nodeBounds = RectF(10f, 10f, 10f, 10f), // 零尺寸节点（真实无障碍树常见）
        )
        assertEquals(1f, t.targetSize, 1e-6f)
    }

    @Test
    fun `targetSize is min edge`() {
        val t = ResolvedTarget(
            point = PointF(0f, 0f),
            nodeBounds = RectF(0f, 0f, 200f, 80f),
        )
        assertEquals(80f, t.targetSize, 1e-6f)
    }

    @Test
    fun `fromPoint synthesizes bounds around point`() {
        val t = ResolvedTarget.fromPoint(PointF(100f, 100f), size = 48f)
        assertEquals(PointF(100f, 100f), t.point)
        assertEquals(48f, t.nodeBounds.width, 1e-6f)
        assertEquals(48f, t.nodeBounds.height, 1e-6f)
        assertEquals(48f, t.targetSize, 1e-6f)
    }

    // ── InterfaceSnapshot ────────────────────────────────────────────────────

    @Test
    fun `snapshot lookup by text and id`() {
        val n1 = NodeSummary(nodeId = "0/1", bounds = RectF(0f, 0f, 10f, 10f), text = "登录")
        val n2 = NodeSummary(nodeId = "0/2", bounds = RectF(0f, 20f, 10f, 30f), text = "注册")
        val s = InterfaceSnapshot(nodes = listOf(n1, n2), timestampMs = 42L, windowTitle = "Demo")

        assertEquals(n2, s.findFirstByText("注册"))
        assertEquals(n1, s.find("0/1"))
        assertNull(s.findFirstByText("不存在"))
        assertEquals("Demo", s.windowTitle)
        assertEquals(42L, s.timestampMs)
    }

    // ── ActionEvent ──────────────────────────────────────────────────────────

    @Test
    fun `executed event carries optional snapshot`() {
        val a = AgentAction(type = ActionType.TAP, targetPoint = PointF(5f, 5f))
        val ok = ActionEvent.Executed(action = a, success = true)
        val bad = ActionEvent.Executed(action = a, success = false, errorMessage = "节点不可点击")

        assertTrue(ok.snapshot == null)
        assertEquals("节点不可点击", bad.errorMessage)
        assertFalse(bad.success)
    }
}
