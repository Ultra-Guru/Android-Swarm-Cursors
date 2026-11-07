package io.swarmcursors.overlay.coordinate

import io.swarmcursors.core.geometry.PointF
import io.swarmcursors.core.geometry.RectF

/**
 * 屏幕 ↔ Overlay 坐标映射。
 *
 * 本库的 Overlay 窗口以 `TYPE_ACCESSIBILITY_OVERLAY` 铺满
 * `Display.getRealSize()` 且原点 (0,0)，因此两坐标系在绝大多数情况下
 * **原点一致**（`getBoundsInScreen` 可直接用于 Canvas 绘制）。本类存在的
 * 意义是把这个「一致」显式化为契约，并为未来的 inset / 多显示器 / 折叠屏
 * 留出唯一改动点：
 *
 * ```
 * 无障碍节点 bounds（屏幕像素）
 *     → screenToOverlay()
 * → Overlay Canvas 坐标（engine.tick 的光标位置也在这一坐标系）
 * ```
 *
 * 屏幕旋转由 SurfaceView 的 surfaceChanged 回调重建视口；若 overlay
 * 存在 inset，构造时传入真实原点即可，核心引擎与主题渲染无需任何改动。
 */
class ScreenCoordinateMapper(
    private val overlayOriginX: Float = 0f,
    private val overlayOriginY: Float = 0f,
) {

    fun screenToOverlay(p: PointF): PointF =
        PointF(p.x - overlayOriginX, p.y - overlayOriginY)

    fun overlayToScreen(p: PointF): PointF =
        PointF(p.x + overlayOriginX, p.y + overlayOriginY)

    fun screenToOverlay(r: RectF): RectF =
        RectF(
            r.left - overlayOriginX,
            r.top - overlayOriginY,
            r.right - overlayOriginX,
            r.bottom - overlayOriginY,
        )

    override fun toString(): String = "ScreenCoordinateMapper(origin=($overlayOriginX, $overlayOriginY))"

    companion object {
        /** 默认全屏 overlay：两坐标系重合（本库标准布局）。 */
        val IDENTITY = ScreenCoordinateMapper(0f, 0f)
    }
}
