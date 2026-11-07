package io.swarmcursors.overlay.window

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import io.swarmcursors.core.engine.SwarmEngine
import io.swarmcursors.core.model.CursorFrame
import io.swarmcursors.overlay.render.CursorThemeRenderer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Swarm 渲染面 —— SurfaceView + 独立渲染线程 + `lockHardwareCanvas()`。
 *
 * 为什么不是普通 View 的 invalidate()：多光标高频重绘与宿主 UI 线程竞争
 * 是帧率抖动的主因；SurfaceView 的独立 Surface 把绘制完全搬离 UI 线程，
 * `lockHardwareCanvas()` 拿到 GPU 加速 Canvas，16 光标稳定 60fps。
 *
 * 帧循环（每帧全清屏 —— 避免脏区累积残影）：
 * ```
 * while (rendering) {
 *     val frames = engine.tick(deltaMs)          // 核心引擎产出几何帧
 *     val canvas = holder.lockHardwareCanvas()
 *     canvas.drawColor(TRANSPARENT, CLEAR)        // 全清屏
 *     frames.forEach { theme.draw(canvas, it) }   // 主题绘制
 *     holder.unlockCanvasAndPost(canvas)
 * }
 * ```
 *
 * 过载保护（性能预算纪律）：渲染帧耗时**连续 3 帧 > 8ms** 时自动关闭
 * 拖尾与动作标签（视觉降级，保帧率）；连续 30 帧回到预算内自动恢复。
 */
internal class SwarmSurfaceView(
    context: Context,
    private val engine: SwarmEngine,
    private val theme: CursorThemeRenderer,
) : SurfaceView(context) {

    private val rendering = AtomicBoolean(false)
    private var renderThread: Thread? = null

    /** 光标可见性（隐藏时引擎照常推进，只是不画）。 */
    @Volatile
    var cursorsVisible: Boolean = true

    init {
        setZOrderOnTop(true) // Surface 打孔置顶（窗口本身 NOT_TOUCHABLE，触摸穿透）
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                startRenderLoop()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // 旋转/折叠 → 视口同步给引擎（新光标从正确边缘飞入）
                engine.viewportWidth = width.toFloat()
                engine.viewportHeight = height.toFloat()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                stopRenderLoop()
            }
        })
    }

    private fun startRenderLoop() {
        if (!rendering.compareAndSet(false, true)) return
        renderThread = Thread(this::renderLoop, "SwarmRenderThread").apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
    }

    private fun stopRenderLoop() {
        rendering.set(false)
        renderThread?.join(250)
        renderThread = null
    }

    private fun renderLoop() {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var lastFrameNanos = System.nanoTime()
        var slowStreak = 0
        var recoveredFrames = 0

        while (rendering.get()) {
            val frameStart = System.nanoTime()
            val deltaMs = max(1L, (frameStart - lastFrameNanos) / 1_000_000)
            lastFrameNanos = frameStart

            try {
                val canvas = holder.lockHardwareCanvas()
                if (canvas != null) {
                    try {
                        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                        if (cursorsVisible) {
                            val frames: List<CursorFrame> = engine.tick(deltaMs)
                            val nowMs = frameStart / 1_000_000
                            for (frame in frames) {
                                theme.draw(canvas, frame, paint, nowMs)
                            }
                        }
                    } finally {
                        holder.unlockCanvasAndPost(canvas)
                    }
                }
            } catch (e: IllegalStateException) {
                // surface 在 lock 与 post 之间被销毁（旋转/移除窗口）：跳过本帧
            }

            // ── 过载保护：连续 3 帧 > 8ms → 关拖尾/标签；0.5s 稳定 → 恢复 ──
            val costMs = (System.nanoTime() - frameStart) / 1_000_000
            if (costMs > SLOW_FRAME_MS) {
                slowStreak++
                recoveredFrames = 0
                if (slowStreak == DEGRADE_AFTER) theme.setVisualExtrasEnabled(false)
            } else {
                slowStreak = 0
                if (!theme.visualExtrasEnabled) {
                    recoveredFrames++
                    if (recoveredFrames >= RECOVER_FRAMES) {
                        theme.setVisualExtrasEnabled(true)
                        recoveredFrames = 0
                    }
                }
            }

            // 帧步进：目标 60fps（16.6ms 帧间隔），渲染耗时从睡眠预算中扣除
            val sleepMs = FRAME_INTERVAL_MS - costMs
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }

    private companion object {
        const val FRAME_INTERVAL_MS = 16L // ≈60fps
        const val SLOW_FRAME_MS = 8L // Spec：单帧预算 8ms
        const val DEGRADE_AFTER = 3 // Spec：连续 3 帧超预算 → 降级
        const val RECOVER_FRAMES = 30 // 0.5s 稳定 → 恢复特效
    }
}

/**
 * Overlay 窗口控制器 —— `TYPE_ACCESSIBILITY_OVERLAY` 的创建/移除。
 *
 * 关键收益：该窗口类型**专为无障碍服务设计，无需 SYSTEM_ALERT_WINDOW
 * 悬浮窗权限**（只要无障碍服务启用即可在其他应用之上绘制）。
 *
 * 已知边界（不尝试绕过，文档声明）：Android 12+ 的系统设置界面会主动
 * 遮挡 TYPE_ACCESSIBILITY_OVERLAY —— 系统安全防护，需用户在开发者选项
 * 中显式允许「显示在设置上方」。
 */
internal class OverlayWindowController(
    private val context: Context,
    private val engine: SwarmEngine,
    private val theme: CursorThemeRenderer,
) {
    private var surfaceView: SwarmSurfaceView? = null
    val isAttached: Boolean get() = surfaceView != null

    fun attach() {
        if (surfaceView != null) return
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val sv = SwarmSurfaceView(context, engine, theme)
        val dm: DisplayMetrics = context.resources.displayMetrics
        val params = WindowManager.LayoutParams(
            dm.widthPixels,
            dm.heightPixels,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        try {
            wm.addView(sv, params)
            surfaceView = sv
        } catch (e: IllegalStateException) {
            // 窗口已添加（重复 attach 防御）
        } catch (e: WindowManager.BadTokenException) {
            // 无障碍服务上下文异常：TYPE_ACCESSIBILITY_OVERLAY 不可用
            throw IllegalStateException(
                "TYPE_ACCESSIBILITY_OVERLAY 不可用：请确认无障碍服务已启用（SwarmAccessibilityService）",
                e,
            )
        }
    }

    fun detach() {
        val sv = surfaceView ?: return
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        runCatching { wm.removeView(sv) }
        surfaceView = null
    }

    fun setCursorsVisible(visible: Boolean) {
        surfaceView?.cursorsVisible = visible
    }
}
