package io.swarmcursors.overlay

import io.swarmcursors.core.model.MotionStyle
import io.swarmcursors.core.model.ParallelMode
import io.swarmcursors.core.motion.MotionPlannerConfig
import io.swarmcursors.overlay.render.CursorThemeRenderer
import io.swarmcursors.overlay.render.DefaultCursorTheme

/**
 * Swarm 会话配置 —— 首次 [SwarmCursors.injectActions] / [SwarmCursors.performAction]
 * 时生效并贯穿整个会话（切换配置需先 [SwarmCursors.stopSwarm]）。
 *
 * @param maxCursors 光标数上限（默认 16，可至 32 —— 性能预算见 README）
 * @param parallelMode 并行安全级别（默认 SAFE：分区并行 + 冲突键降级）
 * @param maxParallel SAFE 模式的并行度上限（默认 4）
 * @param motionStyle 运动风格（默认 SIGNATURE_ARC：弧线 + Fitts + 弹簧落停）
 * @param enableTrail 拖尾（默认关 —— 渲染过载时也会被自动关闭）
 * @param enableActionLabel 动作语义标签（「点击」「输入」…，默认开）
 * @param snapshotEnabled 操作成功后是否回传界面快照（Agent 决策闭环）
 * @param snapshotDelayMs 快照延迟（等界面动画/跳转稳定，150–250ms）
 * @param snapshotMaxNodes 快照节点上限（防自绘界面巨型树）
 * @param motion 运动规划参数（Fitts 系数/抖动/弹簧 —— 高级调参）
 * @param theme 光标主题（默认 DefaultCursorTheme；Lottie 主题可选引入）
 */
data class SwarmConfig(
    val maxCursors: Int = 16,
    val parallelMode: ParallelMode = ParallelMode.SAFE,
    val maxParallel: Int = 4,
    val motionStyle: MotionStyle = MotionStyle.SIGNATURE_ARC,
    val cursorScale: Float = 1f,
    val enableTrail: Boolean = false,
    val enableActionLabel: Boolean = true,
    val snapshotEnabled: Boolean = true,
    val snapshotDelayMs: Long = 200,
    val snapshotMaxNodes: Int = 200,
    val motion: MotionPlannerConfig = MotionPlannerConfig(),
    val theme: CursorThemeRenderer = DefaultCursorTheme(),
) {
    init {
        require(maxCursors in 1..32) { "maxCursors 允许范围 1..32（性能预算上限 32）" }
        require(maxParallel >= 1) { "maxParallel 必须 ≥ 1" }
        require(snapshotDelayMs in 0..2000) { "snapshotDelayMs 建议 150–250ms（界面动画稳定窗）" }
    }

    /** 内部：主题构造时读不到 enableActionLabel —— 由会话装配处同步主题开关。 */
    internal fun applyThemeDefaults(theme: CursorThemeRenderer): CursorThemeRenderer {
        if (theme is DefaultCursorTheme) {
            // 标签开关与拖尾交给主题的视觉附加层（过载降级复用同一开关）
            if (!enableActionLabel) theme.setVisualExtrasEnabled(false)
        }
        return theme
    }
}
