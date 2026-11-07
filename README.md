# 🐝 Android-Swarm-Cursors

> **Swarm Cursors** —— AI Agent 真实操作执行器 + 多光标可视化代理层（Android 库）
>
> 为 [Android-Guru-Agent](https://github.com/Ultra-Guru/Android-Guru-Agent) 而生的独立库模块。

[![CI](https://github.com/Ultra-Guru/Android-Swarm-Cursors/actions/workflows/ci.yml/badge.svg)](https://github.com/Ultra-Guru/Android-Swarm-Cursors/actions/workflows/ci.yml)
[![Project](https://img.shields.io/badge/Project-Swarm%20Cursors%20Development-blue)](https://github.com/orgs/Ultra-Guru/projects/10)

## 定位

Agent 在真实 Android 界面上执行操作（点击 / 输入 / 滚动 / 滑动），Swarm Cursors 负责：

1. **运动可视化** —— 多只精致光标丝滑飞向目标（Catmull-Rom 样条 + Fitts 定律时序 + 弹簧落停）；
2. **真实操作执行** —— 光标到达后通过无障碍服务执行真实 `ACTION_CLICK` / `ACTION_SET_TEXT` 等；
3. **结果回传** —— 操作后的界面快照（节点摘要）供 Agent 下一步决策。

```
Agent 决策层  ──List<AgentAction>──▶  SwarmCursors 库  ──▶  真实 Android 界面
              ◀──Flow<ActionEvent>──  (运动规划+执行编排+光标渲染)
```

## 模块

| 模块 | 说明 |
|---|---|
| `:core` | 纯 Kotlin JVM 运动引擎（零 Android 依赖，可独立单测） |
| `:overlay` | Android 渲染层（无障碍服务 / `TYPE_ACCESSIBILITY_OVERLAY` / Canvas 主题） |
| `:sample` | 演示 App |

## 状态

🚧 v0.1.0 开发中 —— 完整文档（架构 / 快速开始 / API / 已知边界）随开发进度补全。

任务跟踪：[Ultra-Guru Projects #10](https://github.com/orgs/Ultra-Guru/projects/10)
