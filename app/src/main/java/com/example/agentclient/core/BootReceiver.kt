package com.example.agentclient.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.agentclient.network.HeartbeatService
import com.example.agentclient.ui.MainActivity

/**
 * 开机自启动接收器
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            val logger = Logger.getInstance(context)
            logger.info("BootReceiver", "Device boot completed, starting AgentClient")

            // 启动主界面（可选）
            val mainIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(mainIntent)

            // 启动心跳服务
            HeartbeatService.getInstance(context).start()
        }
    }
}