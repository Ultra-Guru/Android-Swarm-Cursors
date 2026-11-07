package io.swarmcursors.core.geometry

/**
 * 浮点矩形（left/top/right/bottom）—— 纯 Kotlin 实现。
 *
 * API 形状与 `android.graphics.RectF` 对齐。服务于：
 *  - 蓝图 / 快照节点的 `bounds`（[io.swarmcursors.core.model.NodeSummary]）；
 *  - Fitts 定律的目标尺寸 `W`（取 [width]/[height] 的较小值近似）；
 *  - 并发控制器的屏幕网格分区（同格串行、异格并行）。
 *
 * 构造时自动归一化倒置的边（left>right / top>bottom）——无障碍树的脏 bounds
 * （零尺寸、坐标倒挂）是真实场景，宁可容错也不让分区/命中判断静默失效。
 */
class RectF(
    var left: Float = 0f,
    var top: Float = 0f,
    var right: Float = 0f,
    var bottom: Float = 0f,
) {

    init {
        if (left > right) {
            val t = left; left = right; right = t
        }
        if (top > bottom) {
            val t = top; top = bottom; bottom = t
        }
    }

    // ── 只读几何量 ──────────────────────────────────────────────────────────

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun isEmpty(): Boolean = right <= left || bottom <= top

    // ── 变更操作 ────────────────────────────────────────────────────────────

    fun set(left: Float, top: Float, right: Float, bottom: Float): RectF {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom
        if (this.left > this.right) { val t = this.left; this.left = this.right; this.right = t }
        if (this.top > this.bottom) { val t = this.top; this.top = this.bottom; this.bottom = t }
        return this
    }

    fun set(other: RectF): RectF = set(other.left, other.top, other.right, other.bottom)

    /** 四边外扩（dx/dy 可为负即内缩）。 */
    fun expand(dx: Float, dy: Float): RectF {
        left -= dx; top -= dy; right += dx; bottom += dy
        return this
    }

    fun offset(dx: Float, dy: Float): RectF {
        left += dx; top += dy; right += dx; bottom += dy
        return this
    }

    // ── 派生值 ──────────────────────────────────────────────────────────────

    fun center(): PointF = PointF(centerX, centerY)

    fun copy(): RectF = RectF(left, top, right, bottom)

    /** 半开区间命中判定（与 android.graphics.Rect 语义一致：含左上、不含右下）。 */
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x < right && y >= top && y < bottom

    fun contains(p: PointF): Boolean = contains(p.x, p.y)

    fun contains(other: RectF): Boolean =
        other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom

    fun intersects(other: RectF): Boolean =
        other.left < right && other.right > left && other.top < bottom && other.bottom > top

    // ── 相等性 ──────────────────────────────────────────────────────────────

    override fun equals(other: Any?): Boolean =
        other is RectF &&
            other.left == left && other.top == top &&
            other.right == right && other.bottom == bottom

    override fun hashCode(): Int {
        var result = left.toRawBits()
        result = 31 * result + top.toRawBits()
        result = 31 * result + right.toRawBits()
        result = 31 * result + bottom.toRawBits()
        return result
    }

    override fun toString(): String = "RectF($left, $top, $right, $bottom)"

    companion object {
        /** 两矩形是否相交（静态形式，便于函数式调用）。 */
        fun intersects(a: RectF, b: RectF): Boolean = a.intersects(b)

        /** 以中心点 + 宽高构造（AgentAction 目标兜底 / Fitts 目标尺寸场景）。 */
        fun fromCenter(center: PointF, width: Float, height: Float): RectF =
            RectF(
                left = center.x - width / 2f,
                top = center.y - height / 2f,
                right = center.x + width / 2f,
                bottom = center.y + height / 2f,
            )
    }
}
