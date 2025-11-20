package com.example.agentclient.network

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.example.agentclient.core.Logger

/**
 * 设备状态收集器
 * 负责收集设备和环境状态，供心跳服务使用
 */
class DeviceStatusCollector(
    private val context: Context,
    private val logger: Logger
) {

    /**
     * 设备状态数据
     */
    data class DeviceStatus(
        val appVersion: String,
        val model: String,
        val osVersion: String,
        val battery: Float,
        val charging: Boolean,
        val network: String,
        val groupTag: String?,
        val resourceStats: HeartbeatService.ResourceStats
    )

    /**
     * 收集当前设备状态
     */
    fun collectDeviceStatus(): DeviceStatus {
        return DeviceStatus(
            appVersion = getAppVersion(),
            model = Build.MODEL,
            osVersion = "Android ${Build.VERSION.RELEASE}",
            battery = getBatteryLevel(),
            charging = isCharging(),
            network = getNetworkType(),
            groupTag = getGroupTag(),
            resourceStats = getResourceStats()
        )
    }

    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            logger.warn("DeviceStatusCollector", "Failed to get app version: ${e.message}")
            "1.0.0"
        }
    }

    private fun getBatteryLevel(): Float {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            batteryLevel / 100f
        } catch (e: Exception) {
            logger.warn("DeviceStatusCollector", "Failed to get battery level: ${e.message}")
            0f
        }
    }

    private fun isCharging(): Boolean {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            batteryManager.isCharging
        } catch (e: Exception) {
            // Fallback for older APIs or if BatteryManager fails
            try {
                val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                val batteryStatus = context.registerReceiver(null, intentFilter)
                val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            } catch (e2: Exception) {
                false
            }
        }
    }

    private fun getNetworkType(): String {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val networkInfo = connectivityManager.activeNetworkInfo
            when {
                networkInfo == null || !networkInfo.isConnected -> "none"
                networkInfo.type == android.net.ConnectivityManager.TYPE_WIFI -> "wifi"
                networkInfo.type == android.net.ConnectivityManager.TYPE_MOBILE -> {
                    when (networkInfo.subtype) {
                        android.telephony.TelephonyManager.NETWORK_TYPE_LTE -> "4g"
                        android.telephony.TelephonyManager.NETWORK_TYPE_NR -> "5g"
                        else -> "mobile"
                    }
                }
                else -> "unknown"
            }
        } catch (e: Exception) {
            logger.warn("DeviceStatusCollector", "Failed to get network type: ${e.message}")
            "unknown"
        }
    }

    private fun getGroupTag(): String? {
        return try {
            val prefs = context.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)
            prefs.getString("group_tag", null)
        } catch (e: Exception) {
            null
        }
    }

    private fun getResourceStats(): HeartbeatService.ResourceStats {
        return try {
            val runtime = Runtime.getRuntime()
            val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            
            HeartbeatService.ResourceStats(
                cpuPercent = 0, // Placeholder
                memoryMb = usedMemory.toInt(),
                storageAvailableMb = context.filesDir.freeSpace / (1024 * 1024)
            )
        } catch (e: Exception) {
            HeartbeatService.ResourceStats()
        }
    }
}
