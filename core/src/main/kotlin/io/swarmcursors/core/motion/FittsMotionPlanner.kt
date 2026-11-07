package io.swarmcursors.core.motion

import io.swarmcursors.core.geometry.PointF
import io.swarmcursors.core.model.MotionStyle
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Fitts 运动规划器 —— 丝滑核心的第一/二级实现。
 *
 * ① **Fitts 定律时序**：移动时长随距离 D 与目标尺寸 W 缩放
 *    （短距离到大目标快，长距离到小目标自然减速）：
 *
 *    ```
 *    MT = a + b · log₂(2D / W)      （a=0.1s，b=0.08s）
 *    ```
 *
 * ② **两阶段运动**：长距离（>300px）拆分为弹道段（min-jerk 时序推进到
 *    88% 路程）+ 采集段（弹簧落停完成最后 12% —— 轻微过冲 + 修正）。
 *
 * 三种 [MotionStyle] 的差异都体现在 [plan] 产出的 [MovePlan] 里：
 *  - SIGNATURE_ARC：弧线路径 + 抖动 + 弹簧
 *  - SPRING_SETTLE：直线路径 + 抖动 + 更柔的弹簧（提前交接）
 *  - CLASSIC：直线路径 + min-jerk 直达 + 无抖动无弹簧
 */
class FittsMotionPlanner(
    val config: MotionPlannerConfig = MotionPlannerConfig(),
) {

    /**
     * Fitts 时序：给定距离与目标宽度求弹道段时长（ms）。
     * 距离/宽度下限 1px（零尺寸节点防御），结果钳制在
     * [MotionPlannerConfig.minDurationMs]..[MotionPlannerConfig.maxDurationMs]。
     */
    fun fittsDurationMs(distancePx: Float, targetSizePx: Float): Long {
        val d = max(distancePx, 1f).toDouble()
        val w = max(targetSizePx, 1f).toDouble()
        val mtSec = config.fittsA + config.fittsB * log2(2.0 * d / w)
        val ms = (mtSec * 1000.0).roundToLong()
        return ms.coerceIn(config.minDurationMs, config.maxDurationMs)
    }

    /**
     * 规划一次从 [from] 到 [to] 的移动。
     *
     * @param targetSize 目标尺寸 W（Fitts 计算；小目标自然更慢更谨慎）
     * @param bulgeDirection 弧线鼓起方向（+1/-1）—— 多光标交替使用，
     *   避免所有光标弧线同侧弯曲（蜂群的「各奔东西」感）
     */
    internal fun plan(
        cursorId: Int,
        from: PointF,
        to: PointF,
        targetSize: Float,
        style: MotionStyle = MotionStyle.SIGNATURE_ARC,
        bulgeDirection: Int = if (cursorId % 2 == 0) 1 else -1,
    ): MovePlan {
        val distance = from.distanceTo(to)
        var duration = fittsDurationMs(distance, targetSize).toFloat()

        // 两阶段时长分配：长距离的弹道段略提速（人手对远目标的峰值速度更高），
        // 把省下的时间留给弹簧采集段。短距离保持 Fitts 原值（整体已很快）。
        if (distance > config.longDistanceThreshold) {
            duration *= 0.9f
        }

        val path: PathSampler = when (style) {
            MotionStyle.SIGNATURE_ARC ->
                PathSampler.Arc(from, to, config.arcBulgeFactor * distance * bulgeDirection)
            else -> PathSampler.Linear(from, to)
        }

        val useSpring = style != MotionStyle.CLASSIC
        val useJitter = style != MotionStyle.CLASSIC
        val handoff = when (style) {
            MotionStyle.SPRING_SETTLE -> (config.handoffThreshold - 0.08f).coerceAtLeast(0.6f)
            else -> config.handoffThreshold
        }

        val spring = when (style) {
            MotionStyle.SPRING_SETTLE -> SpringIntegrator(
                stiffness = SpringIntegrator.STIFFNESS_LOW * 0.75f, // 更柔
                dampingRatio = SpringIntegrator.DAMPING_RATIO_LOW_BOUNCY,
            )
            else -> SpringIntegrator() // 200 / 0.75 官方常量
        }

        return MovePlan(
            cursorId = cursorId,
            style = style,
            from = from.copy(),
            to = to.copy(),
            durationMs = duration.toLong().coerceAtLeast(config.minDurationMs),
            path = path,
            handoffU = handoff,
            useSpring = useSpring,
            useJitter = useJitter,
            spring = spring,
            springMaxMs = if (useSpring) config.springSettleMaxMs else 0L,
        )
    }
}
