package com.example.agentclient.core

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * 设备ID管理器
 * 负责生成和管理设备唯一标识符
 */
class DeviceIdManager private constructor(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var deviceId: String? = null
    private var serverDeviceId: String? = null

    companion object {
        private const val PREFS_NAME = "agent_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_SERVER_DEVICE_ID = "server_device_id"

        @Volatile
        private var instance: DeviceIdManager? = null

        fun getInstance(context: Context): DeviceIdManager {
            return instance ?: synchronized(this) {
                instance ?: DeviceIdManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * 获取本地设备ID
     * 如果不存在则生成新的
     */
    fun getDeviceId(): String {
        // 先从内存缓存获取
        deviceId?.let { return it }

        // 从SharedPreferences读取
        var id = prefs.getString(KEY_DEVICE_ID, null)

        if (id == null) {
            // 生成新ID
            id = generateDeviceId()
            // 保存到SharedPreferences
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            Logger.getInstance(context).info("DeviceIdManager", "Generated new device ID: $id")
        }

        deviceId = id
        return id
    }

    /**
     * 获取服务器分配的设备ID
     */
    fun getServerDeviceId(): String? {
        if (serverDeviceId == null) {
            serverDeviceId = prefs.getString(KEY_SERVER_DEVICE_ID, null)
        }
        return serverDeviceId
    }

    /**
     * 设置服务器分配的设备ID
     */
    fun setServerDeviceId(id: String) {
        serverDeviceId = id
        prefs.edit().putString(KEY_SERVER_DEVICE_ID, id).apply()
        Logger.getInstance(context).info("DeviceIdManager", "Server device ID set: $id")
    }

    /**
     * 生成设备ID
     */
    private fun generateDeviceId(): String {
        // 使用UUID + 时间戳的组合
        val uuid = UUID.randomUUID().toString().replace("-", "")
        val timestamp = System.currentTimeMillis()
        return "agent_${uuid}_$timestamp"
    }

    /**
     * 清除设备ID（仅用于测试）
     */
    fun clearDeviceId() {
        deviceId = null
        serverDeviceId = null
        prefs.edit().clear().apply()
        Logger.getInstance(context).warn("DeviceIdManager", "Device IDs cleared")
    }
}