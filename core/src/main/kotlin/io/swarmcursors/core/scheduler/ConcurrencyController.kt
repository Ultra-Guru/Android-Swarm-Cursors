package io.swarmcursors.core.scheduler

import io.swarmcursors.core.geometry.RectF
import io.swarmcursors.core.model.ParallelMode
import io.swarmcursors.core.model.ResolvedTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * 多光标并发控制器 —— 真实操作场景下「允许多光标同时操作不同控件，
 * 但避免界面状态竞争」的调度核心。
 *
 * 三种模式（[ParallelMode]）：
 *  - **SAFE**（默认）：屏幕按 [gridRows]×[gridCols] 网格分区，**同格串行、
 *    异格并行**；同一 [冲突键][ResolvedTarget.conflictKey]（同父容器的
 *    兄弟节点）自动降级串行；并行度上限 [maxParallel]（默认 4）。
 *  - **SERIAL**：严格串行 —— 全局单一许可。
 *  - **AGGRESSIVE**：全并行，无竞争保护（仅演示用途）。
 *
 * 语义契约：
 *  - [acquire] 挂起直到拿到许可（FIFO 公平：先到先得）；
 *  - [tryAcquire] 非挂起探测；
 *  - [release] 归还许可并立即唤醒队首满足条件的等待者；
 *  - 等待中被取消的协程**不泄漏许可**（已获准未领取的自动回收）。
 *
 * 线程安全：全部状态转移经同一把锁；等待者的恢复在锁外完成（防死锁）。
 */
class ConcurrencyController(
    val mode: ParallelMode = ParallelMode.SAFE,
    val maxParallel: Int = 4,
    val gridRows: Int = 3,
    val gridCols: Int = 3,
    gridWidth: Float = 1080f,
    gridHeight: Float = 1920f,
) {

    private val lock = Any()

    @Volatile
    var gridWidth: Float = gridWidth
        private set

    @Volatile
    var gridHeight: Float = gridHeight
        private set

    private val activeCells = HashSet<String>()
    private val activeConflicts = HashSet<String>()
    private var activeCount = 0
    private val waiters = ArrayDeque<Waiter>()

    /** 当前并行执行中的操作数（诊断/性能面板用）。 */
    val activePermits: Int get() = synchronized(lock) { activeCount }

    /** 队列中的等待者数。 */
    val waitingCount: Int get() = synchronized(lock) { waiters.size }

    /**
     * 挂起获取许可：同格/同冲突键/超上限时排队等待（FIFO）。
     * 调用方必须在 finally 中 [release]。
     */
    suspend fun acquire(target: ResolvedTarget): Permit {
        val cell = cellKey(target.nodeBounds)
        val conflict = conflictKey(target.conflictKey)
        val waiter = synchronized(lock) {
            val w = Waiter(cell, conflict, CompletableDeferred())
            if (admitLocked(cell, conflict)) {
                w.granted = Permit(cell, conflict)
                w.deferred.complete(w.granted!!)
            } else {
                waiters.addLast(w)
            }
            w
        }

        try {
            return waiter.deferred.await()
        } catch (e: CancellationException) {
            // 取消防泄漏：等待中被取消 → 撤出队列；已获准未领取 → 归还许可
            val leaked = synchronized(lock) {
                if (waiters.remove(waiter)) {
                    null // 仍在排队：直接撤出
                } else {
                    waiter.granted // 已获准：归还，否则许可永久丢失
                }
            }
            if (leaked != null) releaseInternal(leaked)
            throw e
        }
    }

    /** 非挂起探测：拿不到立即返回 null（不排队）。 */
    fun tryAcquire(target: ResolvedTarget): Permit? {
        val cell = cellKey(target.nodeBounds)
        val conflict = conflictKey(target.conflictKey)
        return synchronized(lock) {
            if (admitLocked(cell, conflict)) Permit(cell, conflict) else null
        }
    }

    /** 归还许可并唤醒队首满足条件的等待者（可连环唤醒多个空位）。重复释放安全（幂等）。 */
    fun release(permit: Permit) {
        releaseInternal(permit)
    }

    /** 清空全部状态（视口旋转重建 / 停止蜂群时调用）。等待者全部取消回队外语义。 */
    fun reset() {
        val toCancel: List<Waiter>
        synchronized(lock) {
            toCancel = waiters.toList()
            waiters.clear()
            activeCells.clear()
            activeConflicts.clear()
            activeCount = 0
        }
        // 等待者不再被授予（由调用方生命周期取消兜底）；这里不做恢复，
        // 避免在 reset 语义下又放行新操作。
        toCancel.forEach { it.deferred.cancel() }
    }

    /** 视口尺寸变化（旋转/折叠屏）→ 更新网格映射。进行中的许可不受影响。 */
    fun reconfigure(newWidth: Float, newHeight: Float) {
        synchronized(lock) {
            gridWidth = newWidth.coerceAtLeast(1f)
            gridHeight = newHeight.coerceAtLeast(1f)
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun releaseInternal(permit: Permit) {
        val toGrant: List<Waiter>
        synchronized(lock) {
            if (permit.released) return // 幂等：双重释放防御
            permit.released = true
            if (permit.cell != null) activeCells.remove(permit.cell)
            if (permit.conflict != null) activeConflicts.remove(permit.conflict)
            activeCount--

            // FIFO 扫描：把腾出的空位立即分配给队首满足条件的等待者
            val granted = ArrayList<Waiter>()
            val iter = waiters.iterator()
            while (iter.hasNext()) {
                val w = iter.next()
                if (admitLocked(w.cell, w.conflict)) {
                    w.granted = Permit(w.cell, w.conflict)
                    granted.add(w)
                    iter.remove()
                }
            }
            toGrant = granted
        }
        // 锁外恢复等待协程（防持锁 resume 引发的死锁/竞态）
        toGrant.forEach { it.deferred.complete(it.granted!!) }
    }

    private fun admitLocked(cell: String?, conflict: String?): Boolean {
        val ok = when (mode) {
            ParallelMode.AGGRESSIVE -> true // 无保护：全并行（仅演示）
            ParallelMode.SERIAL -> activeCount == 0
            ParallelMode.SAFE ->
                activeCount < maxParallel &&
                    (cell == null || cell !in activeCells) &&
                    (conflict == null || conflict !in activeConflicts)
        }
        if (ok) {
            activeCount++
            if (cell != null) activeCells.add(cell)
            if (conflict != null) activeConflicts.add(conflict)
        }
        return ok
    }

    private fun cellKey(bounds: RectF): String? {
        if (mode != ParallelMode.SAFE) return null // SERIAL/AGGRESSIVE 不分区
        val w = gridWidth.coerceAtLeast(1f)
        val h = gridHeight.coerceAtLeast(1f)
        // floor 语义（toInt 截断）：[0, w/3) → 第 0 列 —— roundToInt 会把边界值进位错格
        val col = (bounds.centerX / w * gridCols).toInt().coerceIn(0, gridCols - 1)
        val row = (bounds.centerY / h * gridRows).toInt().coerceIn(0, gridRows - 1)
        return "r${row}c${col}"
    }

    private fun conflictKey(raw: String?): String? =
        raw?.takeIf { it.isNotBlank() }

    /** 许可凭据：携带分区/冲突键，[release] 时据此精确归还。双重释放安全。 */
    class Permit internal constructor(
        internal val cell: String?,
        internal val conflict: String?,
    ) {
        internal var released = false

        override fun toString(): String = "Permit(cell=$cell, conflict=$conflict)"
    }

    private class Waiter(
        val cell: String?,
        val conflict: String?,
        val deferred: CompletableDeferred<Permit>,
    ) {
        var granted: Permit? = null
    }
}
