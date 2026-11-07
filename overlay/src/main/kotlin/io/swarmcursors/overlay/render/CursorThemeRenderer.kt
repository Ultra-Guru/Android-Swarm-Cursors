package io.swarmcursors.overlay.render

import android.graphics.Canvas
import android.graphics.Paint
import io.swarmcursors.core.geometry.PointF
import io.swarmcursors.core.model.ActionType
import io.swarmcursors.core.model.CursorFrame

/**
 * 光标主题渲染器 —— 可扩展的主题系统（CursorKit 思路的 Android 化）。
 *
 * 主题**只画** [CursorFrame]（纯几何数据），不参与任何运动计算；
 * 点击涟漪 / 动作标签由 [onAction] 回调驱动（编排层在真实操作执行的
 * 瞬间触发）。Lottie 作为可选 artifact 扩展本接口，默认主题零额外依赖。
 *
 * 线程模型：[draw] 在渲染线程调用；[onAction] 在编排协程（主线程）调用
 * —— 实现方自行保证状态同步（DefaultCursorTheme 内部加锁）。
 */
interface CursorThemeRenderer {

    /** 该主题是否具备拖尾绘制能力（过载降级恢复的依据）。 */
    val isTrailSupported: Boolean

    /** 视觉附加特效（拖尾/动作标签）当前是否开启（过载降级开关）。 */
    val visualExtrasEnabled: Boolean

    /** 过载降级/恢复开关 —— 由渲染循环的帧预算守护自动调用。 */
    fun setVisualExtrasEnabled(enabled: Boolean)

    /** 绘制一帧光标（含拖尾/特效）。 */
    fun draw(canvas: Canvas, frame: CursorFrame, paint: Paint, nowMs: Long)

    /**
     * 真实操作执行瞬间的反馈回调（点击涟漪 / 动作标签）。
     *
     * @param cursorId 触发光标（-1 = 未知，用中性色）
     */
    fun onAction(actionType: ActionType, position: PointF, nowMs: Long, cursorId: Int = -1)

    /** 清空全部特效状态（stopSwarm 时调用）。 */
    fun reset()
}
