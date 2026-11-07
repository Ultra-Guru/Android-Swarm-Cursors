package io.swarmcursors.core.motion

/**
 * 运动规划器的全部可调参数（集中一处，SwarmConfig → 引擎透传）。
 *
 * 默认值即「人类手感」标定：Fitts a=0.1s / b=0.08s（短距离大目标快、
 * 长距离小目标自然减速）；2px 微抖动；0.88 弹簧交接点（88% 路程后
 * 从弹道运动切换为弹簧落停）。
 */
data class MotionPlannerConfig(
    // ── Fitts 定律：MT = a + b·log₂(2D/W) ────────────────────────────────────
    /** 截距 a（秒）。 */
    val fittsA: Double = 0.1,
    /** 斜率 b（秒/比特）。 */
    val fittsB: Double = 0.08,
    /** 移动时长下限（ms）—— 防超短距离「瞬移感」。 */
    val minDurationMs: Long = 120,
    /** 移动时长上限（ms）—— 防超长距离拖沓。 */
    val maxDurationMs: Long = 1000,

    // ── 两阶段运动（弹道段 → 采集段）───────────────────────────────────────
    /** 长距离阈值（px）：超过后启用两阶段拆分（弹道 + 采集）。 */
    val longDistanceThreshold: Float = 300f,
    /** 弹道段结束时切换弹簧落停的路程进度（0.88 = 88% 路程处交接）。 */
    val handoffThreshold: Float = 0.88f,
    /** 弹簧落停的时长兜底（ms）：超时直接吸附目标，防低帧率设备悬停。 */
    val springSettleMaxMs: Long = 700,

    // ── SIGNATURE_ARC 弧线 ────────────────────────────────────────────────────
    /** 弧线鼓起系数：0.16 = 弧顶距弦中点为 16% 弦长。 */
    val arcBulgeFactor: Float = 0.16f,

    // ── 微抖动（手部自然震颤）───────────────────────────────────────────────
    /** 抖动振幅（px）。约 2px —— 视觉可感但不干扰点击精度。 */
    val jitterAmplitudePx: Float = 2f,
    /** 抖动时间频率（噪声参数随进度的缩放）。 */
    val jitterFrequency: Float = 3.2f,

    // ── 点击后放松漂移 ────────────────────────────────────────────────────────
    /** 漂移距离（px）：1–2px。 */
    val settleDriftPx: Float = 1.6f,
    /** 漂移时长（ms）。 */
    val settleDriftDurationMs: Long = 150,

    // ── 拖尾 ─────────────────────────────────────────────────────────────────
    /** 拖尾采样点容量（旧→新；超容量淘汰最旧点）。 */
    val trailCapacity: Int = 14,
)
