package io.swarmcursors.core.model

/**
 * 多光标并行的安全级别（SwarmConfig.parallelMode）。
 */
enum class ParallelMode {
    /** 严格串行：一个光标完成操作后下一个才开始（最安全，最慢）。 */
    SERIAL,

    /**
     * 分区并行（默认）：3×3 网格分区，同格串行、异格并行；
     * 同 conflictKey（父子/兄弟节点）自动降级串行；上限 [默认 4] 路并行。
     */
    SAFE,

    /** 全并行：仅演示用途 —— 无界面状态竞争保护。 */
    AGGRESSIVE,
}
