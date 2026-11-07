package io.swarmcursors.core.model

/**
 * 单个 [AgentAction] 的执行结果。
 *
 * @param success 操作是否成功送达（无障碍 `performAction` 返回值 / 手势派发结果）
 * @param snapshot 成功操作后延迟采集的界面快照（节点摘要）；失败或禁用快照时为 null
 * @param errorMessage 失败原因的简短人话（如「节点不可点击」「无障碍服务未连接」）
 */
data class ActionResult(
    val action: AgentAction,
    val success: Boolean,
    val snapshot: InterfaceSnapshot? = null,
    val errorMessage: String? = null,
)
