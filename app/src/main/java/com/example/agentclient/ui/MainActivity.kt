package com.example.agentclient.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.agentclient.R
import com.example.agentclient.accessibility.AgentAccessibilityService
import com.example.agentclient.core.Logger
import com.example.agentclient.network.HeartbeatService
import com.example.agentclient.core.BehaviorProfile   // ★ 修正：BehaviorProfile は core 配下
import com.example.agentclient.scripts.engine.ScriptEngine   // ★ 新規：ScriptEngine を利用
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var logger: Logger
    private lateinit var heartbeatService: HeartbeatService
    private lateinit var scriptEngine: ScriptEngine      // ★ RealTestScript の代わりに ScriptEngine

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var profileButton: Button

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var updateJob: Job? = null

    private var currentProfileIndex = 0
    private val profiles = BehaviorProfile.getAllProfiles()

    // ① 统一获取无障碍服务实例
    private val accessibilityService: AgentAccessibilityService?
        get() = AgentAccessibilityService.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logger = Logger.getInstance(this)
        heartbeatService = HeartbeatService.getInstance(this)
        scriptEngine = ScriptEngine.getInstance(applicationContext) // ★ ScriptEngine 初期化

        // 一次普通点击（非挂起函数）
        AgentAccessibilityService.getInstance()?.tap(500f, 500f)

        // ② 挂起版点击：在协程中执行 + 等待服务就绪
        scope.launch {
            // 给 AccessibilityService 一点时间完成 onServiceConnected
            delay(800)

            // ③ 调用前确认无障碍服务已启用
            if (!AgentAccessibilityService.isEnabled()) {
                logger.warn("Script", "Accessibility service not enabled, skip tapSuspend")
                return@launch
            }

            // ④ 使用统一的 accessibilityService 属性
            val ok = accessibilityService?.tapSuspend(450f, 450f) ?: false
            logger.info("Script", "tapSuspend result = $ok")
        }

        initViews()
        checkAccessibilityService()
        startStatusUpdate()
    }

    private fun initViews() {
        statusText = findViewById(R.id.status_text)
        startButton = findViewById(R.id.start_button)
        stopButton = findViewById(R.id.stop_button)
        profileButton = findViewById(R.id.profile_button)

        // 启动心跳 + スクリプトエンジン
        startButton.setOnClickListener {
            if (!AgentAccessibilityService.isEnabled()) {
                openAccessibilitySettings()
            } else {
                heartbeatService.start()
                // ★ ScriptEngine経由で test_script を開始（TestScript が登録されている前提）
                scope.launch {
                    scriptEngine.startScript("test_script")
                }
                updateButtons()
            }
        }

        // 停止
        stopButton.setOnClickListener {
            heartbeatService.stop()
            // ★ 現在実行中のスクリプトを停止
            scriptEngine.stopScript()
            updateButtons()
        }

        // 切换Profile（現時点では表示のみ。将来は Config/Task と連携させる想定）
        profileButton.setOnClickListener {
            currentProfileIndex = (currentProfileIndex + 1) % profiles.size
            val profile = profiles[currentProfileIndex]
            profileButton.text = "Profile: ${profile.name}"
            logger.info("MainActivity", "Selected profile: ${profile.name}")
        }

        updateButtons()
    }

    private fun checkAccessibilityService() {
        if (!AgentAccessibilityService.isEnabled()) {
            statusText.text = "请先开启无障碍服务"
            startButton.text = "开启无障碍"
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun updateButtons() {
        val heartbeatStatus = heartbeatService.getStatus()
        val engineStatus = scriptEngine.getStatus()  // ★ ScriptEngine の状態を使用

        startButton.isEnabled = !heartbeatStatus.isRunning && !engineStatus.isRunning
        stopButton.isEnabled = heartbeatStatus.isRunning || engineStatus.isRunning

        startButton.text = if (AgentAccessibilityService.isEnabled()) "启动" else "开启无障碍"
        profileButton.text = "Profile: ${profiles[currentProfileIndex].name}"
    }

    private fun startStatusUpdate() {
        updateJob = scope.launch {
            while (isActive) {
                updateStatus()
                delay(1000)
            }
        }
    }

    private fun updateStatus() {
        val heartbeatStatus = heartbeatService.getStatus()
        val engineStatus = scriptEngine.getStatus()
        val accessibilityEnabled = AgentAccessibilityService.isEnabled()

        val status = buildString {
            appendLine("=== 系统状态 ===")
            appendLine("无障碍服务: ${if (accessibilityEnabled) "已启用" else "未启用"}")
            appendLine()

            appendLine("=== 心跳服务 ===")
            appendLine("状态: ${if (heartbeatStatus.isRunning) "运行中" else "已停止"}")
            appendLine("连续失败: ${heartbeatStatus.consecutiveFailures}")
            if (heartbeatStatus.lastSuccessTime > 0) {
                val secondsAgo =
                    (System.currentTimeMillis() - heartbeatStatus.lastSuccessTime) / 1000
                appendLine("上次成功: ${secondsAgo}秒前")
            }
            appendLine()

            // ★ ここからは ScriptEngine の状態を表示
            appendLine("=== 脚本引擎 ===")
            appendLine("运行中: ${engineStatus.isRunning}")
            appendLine("暂停中: ${engineStatus.isPaused}")
            appendLine("当前脚本: ${engineStatus.currentScriptKey ?: "无"}")
            appendLine("已注册脚本: ${engineStatus.registeredScripts.joinToString()}")
        }

        statusText.text = status
        updateButtons()
    }

    override fun onResume() {
        super.onResume()
        checkAccessibilityService()
        updateButtons()
    }

    override fun onDestroy() {
        updateJob?.cancel()
        // ★ アプリ終了時にスクリプトも停止
        scriptEngine.stopScript()
        super.onDestroy()
    }
}