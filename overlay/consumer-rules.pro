# Swarm Cursors overlay —— 无反射/无混淆敏感 API：公开 API 全部 @Keep
# （AgentAction/ActionEvent 等被集成方与 LLM 工具层直接构造/消费）
-keep class io.swarmcursors.core.model.** { *; }
-keep class io.swarmcursors.core.geometry.** { *; }
-keep class io.swarmcursors.overlay.** { *; }
