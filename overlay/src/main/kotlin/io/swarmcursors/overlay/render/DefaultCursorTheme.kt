package io.swarmcursors.overlay.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import io.swarmcursors.core.geometry.PointF
import io.swarmcursors.core.model.ActionType
import io.swarmcursors.core.model.CursorEffect
import io.swarmcursors.core.model.CursorFrame
import kotlin.math.atan2
import kotlin.math.min

/**
 * 默认主题 —— 全 Canvas 矢量绘制，零位图缓存，零额外依赖。
 *
 * 视觉清单（Spec §3.4 全量）：
 *  - **矢量光标**：经典箭头（热点在尖端）或圆点；8 色调色板按 cursorId
 *    轮转 —— 蜂群的「各色其色」；速度感应的轻微拉伸（沿速度轴 ±12%）；
 *    ARRIVING 相位微放大（落停的挤压感）；
 *  - **点击涟漪**：onAction(TAP/LONG_PRESS) 时 280ms 扩散圆环 + 透明度衰减；
 *  - **动作标签**：操作瞬间在光标旁显示语义标签（「点击」「输入」…），
 *    800ms 淡出，深色底圆角徽章 + 白字；
 *  - **轻量拖尾**：CursorFrame.trailPoints 渐隐渐细折线（旧→新）。
 *
 * 所有尺寸以 dp 定义（构造时从 [Context] 取 density；无 Context 时按
 * density=1 的 px 处理，便于预览/测试）。
 */
class DefaultCursorTheme(
    context: Context? = null,
    private val config: Config = Config(),
) : CursorThemeRenderer {

    private val density: Float = context?.resources?.displayMetrics?.density ?: 1f

    override val isTrailSupported: Boolean = true

    @Volatile
    private var extrasEnabled: Boolean = true

    override val visualExtrasEnabled: Boolean get() = extrasEnabled

    override fun setVisualExtrasEnabled(enabled: Boolean) {
        extrasEnabled = enabled
    }

    private val lock = Any()
    private val ripples = ArrayList<Ripple>(8)
    private val labels = ArrayList<ActionLabel>(4)
    private val arrowPath: Path = buildArrowPath()
    private val labelBgRect = RectF()

    // ── 绘制 ────────────────────────────────────────────────────────────────

    override fun draw(canvas: Canvas, frame: CursorFrame, paint: Paint, nowMs: Long) {
        // ① 拖尾（最底层）
        if (extrasEnabled && frame.trailPoints.size >= 2) {
            drawTrail(canvas, frame, paint)
        }

        // ② 光标本体
        drawCursor(canvas, frame, paint)

        // ③ 特效（涟漪 + 标签，最顶层）—— 过期项就地剪枝
        drawEffects(canvas, paint, nowMs)
    }

    override fun onAction(actionType: ActionType, position: PointF, nowMs: Long, cursorId: Int) {
        val color = colorOf(cursorId, actionType)
        synchronized(lock) {
            when (actionType) {
                ActionType.TAP, ActionType.LONG_PRESS ->
                    ripples.add(Ripple(position.copy(), nowMs, color))
                else -> Unit
            }
            if (extrasEnabled && config.labelEnabled) {
                labels.add(ActionLabel(config.labels[actionType] ?: actionType.name, position.copy(), nowMs, color))
            }
            if (ripples.size > 16) ripples.removeAt(0)
            if (labels.size > 8) labels.removeAt(0)
        }
    }

    override fun reset() {
        synchronized(lock) {
            ripples.clear()
            labels.clear()
        }
    }

    // ── 光标本体 ─────────────────────────────────────────────────────────────

    private fun drawCursor(canvas: Canvas, frame: CursorFrame, paint: Paint) {
        val color = colorOf(frame.cursorId, null)
        val scale = baseScale() * effectScale(frame.effect)

        canvas.save()
        canvas.translate(frame.position.x, frame.position.y)

        // 速度感应拉伸：沿速度轴拉伸 ±12%（静止/低速无形变）
        val speed = frame.velocity.length
        if (speed > STRETCH_MIN_SPEED) {
            val stretch = min(0.12f, speed / 9000f)
            val angle = atan2(frame.velocity.y, frame.velocity.x) * DEG
            canvas.rotate(angle)
            canvas.scale(1f + stretch, 1f - stretch * 0.55f)
            canvas.rotate(-angle)
        }

        when (config.shape) {
            Shape.ARROW -> {
                canvas.scale(scale, scale)
                paint.style = Paint.Style.FILL
                paint.color = color
                canvas.drawPath(arrowPath, paint)
                // 白描边（深浅背景通吃的轮廓）——单位空间描边（canvas 已含 density 缩放）
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = OUTLINE_DP
                paint.color = Color.WHITE
                canvas.drawPath(arrowPath, paint)
            }

            Shape.DOT -> {
                val r = DOT_RADIUS_DP * density * config.cursorScale
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                canvas.drawCircle(0f, 0f, r, paint)
                paint.color = color
                canvas.drawCircle(0f, 0f, r * 0.62f, paint)
            }
        }
        canvas.restore()
    }

    private fun drawTrail(canvas: Canvas, frame: CursorFrame, paint: Paint) {
        val pts = frame.trailPoints
        val color = colorOf(frame.cursorId, null)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        for (i in 1 until pts.size) {
            val t = i.toFloat() / pts.size // 旧→新：α 与宽度同步增长
            paint.color = (color and 0x00FFFFFF) or ((ALPHA_TRAIL_MAX * t).toInt() shl 24)
            paint.strokeWidth = config.trailWidthDp * density * t
            canvas.drawLine(pts[i - 1].x, pts[i - 1].y, pts[i].x, pts[i].y, paint)
        }
    }

    private fun drawEffects(canvas: Canvas, paint: Paint, nowMs: Long) {
        synchronized(lock) {
            // 涟漪：280ms 扩散圆环
            val rippleIter = ripples.iterator()
            while (rippleIter.hasNext()) {
                val r = rippleIter.next()
                val t = (nowMs - r.startMs) / RIPPLE_DURATION_MS
                if (t >= 1f) {
                    rippleIter.remove()
                    continue
                }
                val radius = RIPPLE_MIN_DP * density + (RIPPLE_MAX_DP * density - RIPPLE_MIN_DP * density) * t
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = RIPPLE_STROKE_DP * density * (1f - t)
                paint.color = (r.color and 0x00FFFFFF) or ((255 * (1f - t)).toInt() shl 24)
                canvas.drawCircle(r.position.x, r.position.y, radius, paint)
            }

            // 动作标签：800ms 淡出
            val labelIter = labels.iterator()
            while (labelIter.hasNext()) {
                val l = labelIter.next()
                val t = (nowMs - l.startMs) / LABEL_DURATION_MS
                if (t >= 1f) {
                    labelIter.remove()
                    continue
                }
                drawLabel(canvas, paint, l, t)
            }
        }
    }

    private fun drawLabel(canvas: Canvas, paint: Paint, label: ActionLabel, t: Float) {
        paint.textSize = LABEL_TEXT_SP * density
        paint.textAlign = Paint.Align.LEFT
        val textWidth = paint.measureText(label.text)
        val w = textWidth + LABEL_PADDING_DP * density * 2
        val h = LABEL_TEXT_SP * density * 1.6f
        // 上浮 4dp + 淡出
        val lift = 4f * density * t
        val cx = label.position.x + LABEL_OFFSET_DP * density
        val cy = label.position.y - h - LABEL_OFFSET_DP * density - lift

        paint.style = Paint.Style.FILL
        paint.color = (LABEL_BG_COLOR and 0x00FFFFFF) or ((LABEL_BG_ALPHA * (1f - t)).toInt() shl 24)
        labelBgRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        canvas.drawRoundRect(labelBgRect, h / 2f, h / 2f, paint)

        paint.color = (Color.WHITE and 0x00FFFFFF) or ((255 * (1f - t)).toInt() shl 24)
        canvas.drawText(label.text, cx - textWidth / 2f, cy + LABEL_TEXT_SP * density * 0.35f, paint)
    }

    // 度量文本宽度的辅助（避免在接口签名里泄漏 textSize 状态）

    // ── 几何/颜色 ─────────────────────────────────────────────────────────────

    private fun baseScale(): Float = density * config.cursorScale

    private fun effectScale(effect: CursorEffect): Float = when (effect) {
        CursorEffect.ARRIVING -> 1.12f // 落停挤压感
        CursorEffect.SETTLING -> 0.94f // 放松收缩
        else -> 1f
    }

    private fun colorOf(cursorId: Int, action: ActionType?): Int {
        val idx = if (cursorId >= 0) cursorId else (action?.ordinal ?: 0)
        return config.palette[idx.mod(config.palette.size)]
    }

    private fun buildArrowPath(): Path = Path().apply {
        // 经典箭头轮廓（热点 = 尖端 0,0；单位坐标，绘制时按 dp 缩放）
        moveTo(0f, 0f)
        lineTo(0f, 16.5f)
        lineTo(4.1f, 12.6f)
        lineTo(6.9f, 18.9f)
        lineTo(9.4f, 17.7f)
        lineTo(6.6f, 11.5f)
        lineTo(11.9f, 11.0f)
        close()
    }

    // ── 数据 ─────────────────────────────────────────────────────────────────

    private class Ripple(val position: PointF, val startMs: Long, val color: Int)

    private class ActionLabel(
        val text: String,
        val position: PointF,
        val startMs: Long,
        val color: Int,
    )

    /** 主题外观配置。 */
    data class Config(
        val shape: Shape = Shape.ARROW,
        val cursorScale: Float = 1f,
        /** 8 色调色板（按 cursorId 轮转）。 */
        val palette: IntArray = intArrayOf(
            0xFFF59E0B.toInt(), // 琥珀
            0xFFEF5350.toInt(), // 珊瑚红
            0xFF26A69A.toInt(), // 青
            0xFFEC407A.toInt(), // 品红
            0xFF8BC34A.toInt(), // 草绿
            0xFFFFB300.toInt(), // 金
            0xFF26C6DA.toInt(), // 孔雀青
            0xFFFF8A65.toInt(), // 深橙
        ),
        val trailWidthDp: Float = 2.2f,
        val labelEnabled: Boolean = true,
        /** 动作语义标签（默认中文）。 */
        val labels: Map<ActionType, String> = mapOf(
            ActionType.TAP to "点击",
            ActionType.LONG_PRESS to "长按",
            ActionType.TYPE to "输入",
            ActionType.SCROLL to "滚动",
            ActionType.SWIPE to "滑动",
        ),
    )

    enum class Shape { ARROW, DOT }

    private companion object {
        const val DEG = (180.0 / Math.PI).toFloat()
        const val STRETCH_MIN_SPEED = 120f // px/s：低于此速度不形变

        const val OUTLINE_DP = 1.2f
        const val DOT_RADIUS_DP = 7f

        const val RIPPLE_DURATION_MS = 280f
        const val RIPPLE_MIN_DP = 4f
        const val RIPPLE_MAX_DP = 26f
        const val RIPPLE_STROKE_DP = 2.4f

        const val LABEL_DURATION_MS = 800f
        const val LABEL_BG_COLOR = 0xEE101418.toInt()
        const val LABEL_BG_ALPHA = 238
        const val LABEL_TEXT_SP = 11f
        const val LABEL_PADDING_DP = 6f
        const val LABEL_OFFSET_DP = 14f

        const val ALPHA_TRAIL_MAX = 130f
    }
}
