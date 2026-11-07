# 🐝 Android-Swarm-Cursors

> **AI Agent 真实操作执行器 + 多光标可视化代理层** —— Agent 下发 `List<AgentAction>`，蜂群光标丝滑飞向目标并**真实操作** Android 界面，操作后回传界面快照供 Agent 下一步决策。
>
> 为 [Android-Guru-Agent](https://github.com/Ultra-Guru/Android-Guru-Agent) 而生的独立库模块。

[![CI](https://github.com/Ultra-Guru/Android-Swarm-Cursors/actions/workflows/ci.yml/badge.svg)](https://github.com/Ultra-Guru/Android-Swarm-Cursors/actions/workflows/ci.yml)
[![Project](https://img.shields.io/badge/Project-Swarm%20Cursors%20Development-blue)](https://github.com/orgs/Ultra-Guru/projects/10)
[![License](https://img.shields.io/badge/License-Apache--2.0-blue)](LICENSE)

---

## 🎯 它解决什么问题

让 AI Agent 的操作「看得见、是真的」：

1. **真实操作执行** —— Agent 的操作指令通过无障碍服务（`AccessibilityService`）真实作用于任意 Android 界面：`ACTION_CLICK` / `ACTION_LONG_CLICK` / `ACTION_FOCUS + ACTION_SET_TEXT` / `ACTION_SCROLL_FORWARD/BACKWARD`，节点语义不可用时降级为 `dispatchGesture()` 真实触摸手势 —— 光标不是「演示动画」，点到的按钮**真的会被点下去**；
2. **可视化反馈** —— 多只精致光标（Catmull-Rom 样条路径 + Fitts 定律时序 + 弹簧落停）丝滑飞向目标，用户实时看见 Agent 正在做什么、在哪里做；
3. **感知闭环** —— 每次操作执行完毕后延迟采集界面快照（节点摘要 `InterfaceSnapshot`）回传 Agent，作为下一步决策的「眼睛」。

**核心时序保证（本库的灵魂）**：真实操作**只在光标动画到达之后**执行 —— 用户看到的是「光标飞过去 → 点到 → 界面响应」，而不是「界面先响应、光标迟到补画」。该顺序由 `ActionOrchestrator` 的 `onArrival` 时序编排强制保证（超时兜底：引擎预算 + 400ms 后仍会执行操作，视觉迟到不阻止任务完成）。

## 🏗️ 架构

```
┌───────────────────────────────────────────────────────────────────────┐
│  Agent 决策层（Android-Guru-Agent / 任意 LLM 编排器）                  │
│                                                                        │
│        List<AgentAction>  ─────────────────────────▶                  │
│        ◀─────────────────  Flow<ActionEvent>  （Moving/Executed/Failed │
│                                                  + InterfaceSnapshot）│
└──────────────┬─────────────────────────────────────▲──────────────────┘
               │ 注入操作序列                          │ 回传事件 + 快照
┌──────────────▼─────────────────────────────────────┴──────────────────┐
│  SwarmCursors 库                                                       │
│                                                                        │
│  ActionOrchestrator —— 九步时序编排（见下），保证「光标先到 → 真实操作」   │
│  SwarmEngine (:core) —— 纯 Kotlin 运动引擎：样条/Fitts/弹簧/Perlin      │
│  ConcurrencyController —— SERIAL/SAFE/AGGRESSIVE 并发控制              │
│  渲染层 (:overlay) —— TYPE_ACCESSIBILITY_OVERLAY + SurfaceView         │
│                       独立渲染线程 + lockHardwareCanvas()               │
└──────────────┬─────────────────────────────────────────────────────────┘
               │ 无障碍节点动作 / dispatchGesture 真实手势
┌──────────────▼─────────────────────────────────────────────────────────┐
│  真实 Android 界面（任意 App —— 点击/输入/滚动真实发生）              │
└────────────────────────────────────────────────────────────────────────┘
```

**编排器九步时序**（`ActionOrchestrator`，每个动作一条协程，`ConcurrencyController` 控并发）：

```
① resolveTarget  → 节点 id / 坐标 → 屏幕落点（含冲突键）
② acquire        → 分区许可（同格 / 同冲突键 / 超上限 → FIFO 排队）
③ emit Moving    → 光标丝滑运动（样条 + Fitts + 弹簧落停）
④ onArrival      → 引擎 tick 线程触发 → 恢复协程（时序保证！）
⑤ executeAction  → 真实无障碍操作（节点动作 / 手势兜底）
⑥ theme.onAction → 点击涟漪 + 动作语义标签
⑦ settleDrift    → 1–2px 放松微漂移
⑧ 快照窗          → delay → captureSnapshot → emit Executed（决策闭环）
⑨ release        → 归还许可，唤醒下一批等待者
```

## 📦 模块

| 模块 | 类型 | 说明 |
|---|---|---|
| `:core` | 纯 Kotlin JVM | 运动引擎（`SwarmEngine` / `FittsMotionPlanner` / `SpringIntegrator` / `SplineInterpolator` / `ConcurrencyController`）—— **零 Android 依赖**，唯一依赖 `kotlinx-coroutines-core`，任意 JVM 上可直接单测 |
| `:overlay` | Android library | 渲染与执行层（`SwarmCursors` 门面 / `ActionOrchestrator` / `SwarmAccessibilityService` / `TYPE_ACCESSIBILITY_OVERLAY` 窗口 / Canvas 主题）；`api(project(":core"))` 全量透出核心 API |
| `:sample` | 演示 App | 多光标真实操作演示（4 光标 SAFE 分区并行打满一屏按钮） |

## 🚀 快速开始

### ① 添加依赖

```kotlin
// Maven 坐标（maven-publish，release + sources jar）
implementation("io.swarmcursors:swarm-overlay:0.1.0")
```

尚未发布到远端仓库？直接用 composite build 接入（见下文「接入主项目」一节）。

### ② 启用无障碍服务（一次性，用户侧引导）

```kotlin
if (!SwarmCursors.isAccessibilityServiceEnabled(context)) {
    SwarmCursors.openAccessibilitySettings(context) // 引导跳转系统无障碍设置页
}
```

服务声明已内置于库 AAR 的 Manifest（`SwarmAccessibilityService` + `swarm_accessibility_service_config.xml`），**Manifest 合并后集成方零手写** —— 只需引导用户在系统设置中启用一次。Overlay 使用 `TYPE_ACCESSIBILITY_OVERLAY` 窗口类型：无需 `SYSTEM_ALERT_WINDOW` 悬浮窗权限。

### ③ 注入操作 + 消费事件流

```kotlin
val actions = listOf(
    AgentAction(cursorId = 0, type = ActionType.TAP, targetNodeId = "btnHello"),
    AgentAction(cursorId = 1, type = ActionType.TYPE, targetNodeId = "inputField", text = "蜂群光标注入 🐝"),
    AgentAction(cursorId = 2, type = ActionType.TAP, targetNodeId = "btnCelebrate"),
    AgentAction(cursorId = 3, type = ActionType.SCROLL, targetNodeId = "demoScroll", direction = Direction.DOWN),
)

scope.launch {
    SwarmCursors.injectActions(
        this@MainActivity,
        actions,
        SwarmConfig(maxCursors = 4, enableTrail = true),
    ).catch { e ->
        log("✗ 会话异常：${e.message}")
    }.collect { event ->
        when (event) {
            is ActionEvent.Moving ->
                log("🐝 光标${event.cursorId} → (${event.target.x.toInt()}, ${event.target.y.toInt()})")

            is ActionEvent.Executed -> {
                val snapshotInfo = event.snapshot?.let { " · 快照 ${it.nodes.size} 节点" } ?: ""
                log(if (event.success) "✓ ${event.action.type} 完成$snapshotInfo"
                    else "✗ ${event.action.type} 未送达：${event.errorMessage}")
            }

            is ActionEvent.Failed ->
                log("✗ 失败：${event.reason}")
        }
    }
}
```

`injectActions` 返回冷流 `Flow<ActionEvent>`：首次收集时按 `SwarmConfig` 装配会话（引擎 / 并发控制 / 主题 / Overlay 窗口），全部动作完成后流自然完结。目标用**短名 viewId**（如 `btnHello`）即可 —— 按 `viewIdResourceName` 后缀在无障碍树中解析，光标落点与真实点击位置完全一致。

### ④ 停止蜂群

```kotlin
SwarmCursors.stopSwarm() // 卸载 Overlay、清空引擎/并发队列/主题特效、取消会话协程
```

## 📖 API 一览

### `AgentAction` —— Agent 决策层输出的单个真实操作指令

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `cursorId` | `Int` | `0` | 绑定的光标编号（`0..maxCursors-1`）；同号光标串行执行其任务 |
| `type` | `ActionType` | — | 操作类型：`TAP` / `LONG_PRESS` / `TYPE` / `SCROLL` / `SWIPE` |
| `targetNodeId` | `String?` | `null` | 无障碍节点标识（`viewIdResourceName`，如 `pkg:id/login`，也接受短名 `login`） |
| `targetPoint` | `PointF?` | `null` | 兜底屏幕像素坐标（节点不可用 / 自绘界面时直接使用） |
| `text` | `String?` | `null` | `TYPE` 的输入内容 |
| `direction` | `Direction?` | `null` | `SCROLL` / `SWIPE` 的方向：`UP` / `DOWN` / `LEFT` / `RIGHT` |
| `durationMs` | `Long` | `300` | `LONG_PRESS` / `SWIPE` 的持续时长 |

**目标解析优先级**：`targetNodeId`（树内匹配，命中则光标落点与真实点击位置**完全一致**，同 id 多实例优先可见节点）→ `targetPoint`（坐标兜底，合成 48px 虚拟 bounds）→ 均为空则 `Failed` 事件。校验辅助：`hasTarget` / `isWellFormed`（`TYPE` 必须带 `text`，`SCROLL`/`SWIPE` 必须带 `direction`）。

**动作映射**：`TAP`→`ACTION_CLICK`（兜底真实点击手势）；`LONG_PRESS`→`ACTION_LONG_CLICK`；`TYPE`→`ACTION_FOCUS + ACTION_SET_TEXT`；`SCROLL`→`ACTION_SCROLL_FORWARD/BACKWARD`（兜底手势滑动 240px）；`SWIPE`→`dispatchGesture()` 路径手势。执行时若节点已失效会**重新解析一次**再降级手势。

### `ActionEvent` —— `injectActions` 事件流（sealed class）

| 事件 | 字段 | 语义 |
|---|---|---|
| `ActionEvent.Moving` | `cursorId` / `target: PointF` / `action` | 光标开始向目标移动（含并行分派完成的落点） |
| `ActionEvent.Executed` | `action` / `success: Boolean` / `snapshot: InterfaceSnapshot?` / `errorMessage: String?` | 真实操作已执行完毕（光标已到达并触发无障碍操作）；`success` = 送达，界面是否变化由 Agent 比对快照判断 |
| `ActionEvent.Failed` | `action` / `reason: String` | 整条链路失败（目标解析失败 / 缺少目标 / 服务未连接 / 任务被取消） |

### `ActionResult` —— `performAction` 的单步结果

| 字段 | 说明 |
|---|---|
| `action` | 原始指令 |
| `success` | 操作是否成功送达（`performAction` 返回值 / 手势派发结果） |
| `snapshot` | 成功操作后延迟采集的界面快照；失败或禁用快照时为 `null` |
| `errorMessage` | 失败原因的简短人话 |

`InterfaceSnapshot`（`nodes: List<NodeSummary>` + `timestampMs` + `windowTitle?`）提供 `findFirstByText(text)` / `find(nodeId)` 查询；`NodeSummary` 含 `nodeId`（树路径标识如 `0/3/1/2`）/ `bounds` / `text` / `role` / `clickable`。

### `SwarmConfig` —— 会话配置（12 个参数）

| 参数 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `maxCursors` | `Int` | `16` | 光标数上限（允许 `1..32`，性能预算见下） |
| `parallelMode` | `ParallelMode` | `SAFE` | 并行安全级别：`SERIAL` / `SAFE` / `AGGRESSIVE` |
| `maxParallel` | `Int` | `4` | SAFE 模式并行度上限 |
| `motionStyle` | `MotionStyle` | `SIGNATURE_ARC` | 运动风格：`SIGNATURE_ARC` / `SPRING_SETTLE` / `CLASSIC` |
| `cursorScale` | `Float` | `1f` | 光标尺寸缩放 |
| `enableTrail` | `Boolean` | `false` | 拖尾（渲染过载时也会被自动关闭） |
| `enableActionLabel` | `Boolean` | `true` | 动作语义标签（「点击」「输入」…） |
| `snapshotEnabled` | `Boolean` | `true` | 操作成功后是否回传界面快照（Agent 决策闭环） |
| `snapshotDelayMs` | `Long` | `200` | 快照延迟（等界面动画/跳转稳定，建议 150–250ms，允许 `0..2000`） |
| `snapshotMaxNodes` | `Int` | `200` | 快照节点上限（防自绘界面巨型树卡顿） |
| `motion` | `MotionPlannerConfig` | — | 运动规划参数（Fitts 系数 / 抖动 / 弹簧 —— 高级调参） |
| `theme` | `CursorThemeRenderer` | `DefaultCursorTheme()` | 光标主题（实现 `CursorThemeRenderer` 接口可换 Lottie 等自定义主题） |

配置在首次 `injectActions` / `performAction` 时生效并贯穿整个会话；**换配置需先 `stopSwarm()`**。

### `SwarmCursors` —— 门面对象（集成方唯一入口）

| 方法 | 签名 | 说明 |
|---|---|---|
| `injectActions` | `(context, actions: List<AgentAction>, config): Flow<ActionEvent>` | **主入口**：注入操作序列，冷流，Main 分发；服务未启用抛 `IllegalStateException`（含人话指引） |
| `performAction` | `suspend (context, action, config): ActionResult` | 单步操作（Agent 逐步决策时使用），内部等价单元素 `injectActions` |
| `isAccessibilityServiceEnabled` | `(context): Boolean` | 服务是否已在系统设置启用（无需连接即查，适合 UI 预检） |
| `openAccessibilitySettings` | `(context)` | 引导跳转系统无障碍设置页 |
| `setCursorVisible` | `(visible: Boolean)` | 光标可见性（隐藏时引擎照常推进、操作照常执行，仅不绘制） |
| `stopSwarm` | `()` | 停止蜂群：卸载 Overlay、清空引擎/并发队列/主题特效、取消会话协程 |
| `isRunning` | `(): Boolean` | 会话是否在运行（Overlay 已挂载、引擎活跃） |

## 🧠 运动引擎 —— 丝滑的三级递进

所有运动计算都在 `:core` 纯 Kotlin 层完成，渲染层只「画」帧（`CursorFrame` 纯几何数据）。

| 层级 | 技术 | 解决什么 |
|---|---|---|
| ① 路径 | **Catmull-Rom 样条**（`SplineInterpolator`，均匀 CR 插值） | Agent / 无障碍树的位置更新是离散的（可能每 50–80ms 一次），直接跳变明显卡顿；样条路径**经过所有控制点**且一阶连续（无折角）—— 消灭「跳变」的数学根基 |
| ② 时序 | **Fitts 定律**（`FittsMotionPlanner`） | 移动时长随距离 D 与目标尺寸 W 缩放：`MT = a + b·log₂(2D/W)`（`a=0.1s`，`b=0.08s`，钳制 120–1000ms）—— 短距大目标快、长距小目标自然减速靠近；进度曲线用 min-jerk（`10u³−15u⁴+6u⁵`），人手峰值速度居中的钟形速度 |
| ③ 落停 | **弹簧积分器**（`SpringIntegrator`，**解析闭式解、帧率无关**） | 线性减速停止是「机械感」的根源；欠阻尼弹簧产生轻微过冲 + 回弹收敛。参数对齐 Android 官方常量：`dampingRatio = 0.75`（`DAMPING_RATIO_LOW_BOUNCY`）、`stiffness = 200`（`STIFFNESS_LOW`） |

**为什么弹簧用解析闭式解而非数值积分**：半隐式 Euler 在 dt=1/60（60fps）时数值阻尼会吞掉约 **2/3 的理论过冲**（实测 −0.93px vs 解析 −2.84px），且 30/60/120fps 设备上手感不一致。闭式解对**任意 dt 单步精确**（欠阻尼 `x(τ) = x_t + e^(−λτ)·(A·cos ω_d τ + B·sin ω_d τ)`，临界/过阻尼同理），无需子步，30fps 老设备与 120fps 高刷机落停手感完全一致。

**细节层**：2px Perlin 微抖动（模拟手部自然震颤，接近交接点淡出）；两阶段运动（>300px 长距离：弹道段推进到 88% 路程 → 带着末速度交接进弹簧采集段完成最后 12%）；点击后 1.6px / 150ms 放松漂移；速度感应的形变拉伸（±12%）；多光标弧线交替鼓起方向（蜂群「各奔东西」感）。

**三种 `MotionStyle`**：

| 风格 | 路径 | 特效 | 手感 |
|---|---|---|---|
| `SIGNATURE_ARC`（默认） | 样条弧（弧顶 16% 弦长） | 抖动 + 弹簧落停 | 「招牌」手感 |
| `SPRING_SETTLE` | 直线 | 抖动 + 更柔弹簧（提前交接、过冲更明显） | 落停强化 |
| `CLASSIC` | 直线 | 无抖动、无过冲 | 简洁基线（演示/对比） |

引擎确定性：固定 seed 的 Perlin + 纯数学推进 —— JVM 单测可用固定 dt 循环精确复现整段轨迹。

## 🔀 并发控制 —— 多光标「同时操作但不打架」

`ConcurrencyController`（`:core`）三种 `ParallelMode`：

| 模式 | 语义 | 适用 |
|---|---|---|
| `SERIAL` | 严格串行：一个光标完成操作后下一个才开始（全局单一许可） | 最安全，最慢 |
| `SAFE`（默认） | **3×3 网格分区**：同格串行、异格并行；同 `conflictKey`（同一父容器下的兄弟节点）自动降级串行；`maxParallel`（默认 4）并行度上限 | 生产默认 |
| `AGGRESSIVE` | 全并行，无界面状态竞争保护 | **仅演示用途** |

SAFE 的三重保护：① 分区 —— 目标 bounds 中心落入同一网格格子的操作串行；② 冲突键 —— 兄弟节点（如同一对话框的确定/取消按钮）共享父路径前缀，并行操作会竞争界面状态，自动降级串行；③ 上限 —— 全局并行度封顶。语义契约：`acquire` FIFO 公平挂起、`release` 立即连环唤醒队首满足条件者、等待中被取消的协程**不泄漏许可**。

## ⚡ 性能预算

| 指标 | 预算 | 实现手段 |
|---|---|---|
| 光标规模 | 16 光标 @ 60fps（上限 32） | 每帧每光标 O(1) 采样 + O(trailCapacity) 拷贝 |
| 引擎 tick | < 0.5ms / 帧（16 光标；弹簧 16 光标 < 0.05ms） | 纯数学推进，无分配热路径 |
| 每帧绘制 | < 4ms | SurfaceView 独立渲染线程 + `lockHardwareCanvas()` GPU Canvas，绘制完全搬离宿主 UI 线程 |
| 渲染过载保护 | 连续 3 帧 > 8ms → 自动关闭拖尾与动作标签；0.5s（30 帧）稳定 → 自动恢复 | 帧预算守护，视觉降级保帧率 |
| 快照采集 | 上限 200 节点裁剪；按需抓取，不持续遍历树 | 自绘界面巨型树防卡顿 |

## 🔗 接入主项目 Android-Guru-Agent（composite build）

本库版本矩阵与主项目 [Android-Guru-Agent](https://github.com/Ultra-Guru/Android-Guru-Agent) **精确对齐**，composite build 接入零版本摩擦：

| Gradle | AGP | Kotlin | coroutines | JDK | compileSdk / minSdk |
|---|---|---|---|---|---|
| 8.14 | 8.13.2 | 2.3.21 | 1.10.2 | 17+ | 36 / 26 |

主项目 `settings.gradle.kts` 以兄弟目录 includeBuild（与 apex-kit-* 的接线模式一致）：

```kotlin
// Android-Guru-Agent/settings.gradle.kts
val swarmKitSibling = file("../Android-Swarm-Cursors")
if (swarmKitSibling.isDirectory) includeBuild(swarmKitSibling)
```

随后按库自身发布坐标依赖（composite build 按 group:module 自动替换为本地构件）：

```kotlin
implementation("io.swarmcursors:swarm-overlay:0.1.0") // :core 经 api 传递透出
```

## ⚠️ 已知边界

1. **Android 12+ 系统设置界面遮挡**：系统设置页会主动遮挡 `TYPE_ACCESSIBILITY_OVERLAY` 窗口 —— 这是系统安全防护，本库**不尝试绕过**；需要光标可见于设置页之上的用户，须在开发者选项中显式允许「显示在设置上方」。真实操作不受影响（手势通道独立于 Overlay）。
2. **自绘界面无障碍树不完整**：Flutter / 游戏引擎等自绘 UI 暴露的无障碍树可能残缺 —— `resolveTarget` 有 `targetPoint` 坐标兜底（合成虚拟 bounds，手势兜底仍可点击任何位置），快照可能返回**部分数据**（降级而非异常，由 Agent 自行判断信息量）。
3. **SAFE ≠ 绝对无竞争**：分区 + 冲突键 + 并行上限**大幅降低**但无法完全消除界面状态竞争（跨格且非兄弟的语义耦合控件仍可能竞争）；`AGGRESSIVE` 无任何保护，仅限演示用途。

## 📌 版本与许可

- **v0.1.0** —— 🚧 开发中（API 可能调整）
- **License**: [Apache-2.0](LICENSE)
- 任务跟踪：[Ultra-Guru Projects #10](https://github.com/orgs/Ultra-Guru/projects/10)
