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
import com.example.agentclient.core.UiDriver
import com.example.agentclient.scripts.engine.ScriptEngine
import kotlinx.coroutines.*

/**
 * 主界面Activity
 * 提供脚本启动/停止按钮和状态显示
 */
class MainActivity : AppCompatActivity() {

    private lateinit var logger: Logger
    private lateinit var uiDriver: UiDriver

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var updateJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logger = Logger.getInstance(this)
        uiDriver = UiDriver.getInstance(this)
        
        // 初始化 ScriptEngine
        ScriptEngine.initialize(applicationContext)

        initViews()
        checkAccessibilityService()
        startStatusUpdate()
    }

    private fun initViews() {
        statusText = findViewById(R.id.status_text)
        startButton = findViewById(R.id.start_button)
        stopButton = findViewById(R.id.stop_button)

        // 开始测试脚本按钮
        startButton.setOnClickListener {
            if (!AgentAccessibilityService.isEnabled()) {
                openAccessibilitySettings()
            } else {
                uiDriver.startTestScript()
                updateButtons()
                logger.info("MainActivity", "Start button clicked")
            }
        }

        // 停止脚本按钮
        stopButton.setOnClickListener {
            uiDriver.stopScript()
            updateButtons()
            logger.info("MainActivity", "Stop button clicked")
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
        val isRunning = uiDriver.isScriptRunning()
        
        startButton.isEnabled = !isRunning && AgentAccessibilityService.isEnabled()
        stopButton.isEnabled = isRunning

        startButton.text = if (AgentAccessibilityService.isEnabled()) "开始测试脚本" else "开启无障碍"
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
        val accessibilityEnabled = AgentAccessibilityService.isEnabled()
        val scriptRunning = uiDriver.isScriptRunning()
        val currentScriptName = uiDriver.getCurrentScriptName()

        val status = buildString {
            appendLine("=== 系统状态 ===")
            appendLine("无障碍服务: ${if (accessibilityEnabled) "已启用" else "未启用"}")
            appendLine()

            appendLine("=== 脚本状态 ===")
            appendLine("运行中: ${if (scriptRunning) "是" else "否"}")
            if (scriptRunning && currentScriptName != null) {
                appendLine("当前脚本: $currentScriptName")
            }
            appendLine()
            
            appendLine("说明:")
            appendLine("1. 先开启无障碍服务")
            appendLine("2. 点击「开始测试脚本」")
            appendLine("3. 脚本将执行5次滚动操作")
            appendLine("4. 完成后自动停止")
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
        // 停止脚本
        uiDriver.stopScript()
        super.onDestroy()
    }
}