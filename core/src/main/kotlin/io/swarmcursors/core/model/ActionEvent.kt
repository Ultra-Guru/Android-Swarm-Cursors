package io.swarmcursors.core.model

import io.swarmcursors.core.geometry.PointF

/**
 * `injectActions` 事件流的事件类型 —— Agent 实时消费用于决策调整。
 */
sealed class ActionEvent {

    /** 光标开始向目标移动（含并行分派完成的落点）。 */
    data class Moving(
        val cursorId: Int,
        val target: PointF,
        val action: AgentAction,
    ) : ActionEvent()

    /** 真实操作已执行完毕（光标已到达并触发无障碍操作）。 */
    data class Executed(
        val action: AgentAction,
        val success: Boolean,
        val snapshot: InterfaceSnapshot? = null,
        val errorMessage: String? = null,
    ) : ActionEvent()

    /** 整个动作链路失败（目标解析失败 / 执行异常 / 任务被取消）。 */
    data class Failed(
        val action: AgentAction,
        val reason: String,
    ) : ActionEvent()
}
