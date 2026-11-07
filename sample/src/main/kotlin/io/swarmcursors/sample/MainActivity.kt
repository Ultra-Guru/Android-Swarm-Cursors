package io.swarmcursors.sample

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.swarmcursors.core.model.ActionEvent
import io.swarmcursors.core.model.ActionType
import io.swarmcursors.core.model.AgentAction
import io.swarmcursors.core.model.Direction
import io.swarmcursors.overlay.SwarmConfig
import io.swarmcursors.overlay.SwarmCursors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tapCount = 0
    private var cursorsVisible = true

    private lateinit var serviceStatus: TextView
    private lateinit var logView: TextView
    private lateinit var btnCounter: Button
    private lateinit var inputField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serviceStatus = findViewById(R.id.serviceStatus)
        logView = findViewById(R.id.logView)
        btnCounter = findViewById(R.id.btnCounter)
        inputField = findViewById(R.id.inputField)

        // 目标演示区：真实点击的可见反馈（证明无障碍操作是真的发生了）
        listOf(
            R.id.btnHello to "👋 Hello from Swarm!",
            R.id.btnToo to " buzz buzz 🐝",
            R.id.btnCelebrate to "🎉 蜂群任务完成！",
            R.id.btnPing to "🏓 pong",
            R.id.btnPong to "🏓 ping",
        ).forEach { (id, msg) ->
            findViewById<Button>(id).setOnClickListener {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                log("👤 人工点击：${resources.getResourceEntryName(id)}")
            }
        }
        btnCounter.setOnClickListener {
            tapCount++
            btnCounter.text = getString(R.string.tap_counter_fmt, tapCount)
        }

        findViewById<Button>(R.id.btnEnableService).setOnClickListener {
            SwarmCursors.openAccessibilitySettings(this)
        }

        findViewById<Button>(R.id.btnStartDemo).setOnClickListener { startSwarmDemo() }

        findViewById<Button>(R.id.btnVisibility).setOnClickListener {
            cursorsVisible = !cursorsVisible
            SwarmCursors.setCursorVisible(cursorsVisible)
            log("👁 光标可见性 → $cursorsVisible")
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            SwarmCursors.stopSwarm()
            log("■ 蜂群已停止，Overlay 已卸载")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshServiceStatus()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshServiceStatus() {
        val enabled = SwarmCursors.isAccessibilityServiceEnabled(this)
        serviceStatus.text = getString(
            R.string.service_status_fmt,
            getString(if (enabled) R.string.service_enabled else R.string.service_disabled),
        )
    }

    /**
     * 蜂群演示：4 只光标并行执行真实操作序列。
     *
     * 目标用**短名 viewId**（btnHello 等）—— SwarmAccessibilityService 会按
     * viewIdResourceName 后缀在无障碍树中解析，光标落点与真实点击位置一致。
     */
    private fun startSwarmDemo() {
        if (!SwarmCursors.isAccessibilityServiceEnabled(this)) {
            Toast.makeText(this, "请先启用 Swarm Cursors 无障碍服务", Toast.LENGTH_LONG).show()
            SwarmCursors.openAccessibilitySettings(this)
            return
        }

        val actions = listOf(
            AgentAction(cursorId = 0, type = ActionType.TAP, targetNodeId = "btnHello"),
            AgentAction(cursorId = 1, type = ActionType.TYPE, targetNodeId = "inputField", text = "蜂群光标注入 🐝"),
            AgentAction(cursorId = 2, type = ActionType.TAP, targetNodeId = "btnCelebrate"),
            AgentAction(cursorId = 3, type = ActionType.TAP, targetNodeId = "btnPing"),
            // 第二波：分区并行的点击（不同网格区 → 同时起飞）
            AgentAction(cursorId = 0, type = ActionType.TAP, targetNodeId = "btnCounter"),
            AgentAction(cursorId = 1, type = ActionType.TAP, targetNodeId = "btnToo"),
            AgentAction(cursorId = 2, type = ActionType.TAP, targetNodeId = "btnPong"),
            // 收尾：蜂群自己滚动页面
            AgentAction(cursorId = 3, type = ActionType.SCROLL, targetNodeId = "demoScroll", direction = Direction.DOWN),
        )

        log("▶ 注入 ${actions.size} 个操作（SAFE 分区并行 · SIGNATURE_ARC 运动）")
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
                        log(
                            if (event.success) {
                                "✓ ${event.action.type} 完成${snapshotInfo}"
                            } else {
                                "✗ ${event.action.type} 未送达：${event.errorMessage}"
                            },
                        )
                    }

                    is ActionEvent.Failed ->
                        log("✗ 失败：${event.reason}")
                }
            }
            log("── 蜂群序列完结 ──")
        }
    }

    private fun log(line: String) {
        logView.text = getString(R.string.hint_log) // 保留标题行
        logView.append("\n$line")
    }
}
