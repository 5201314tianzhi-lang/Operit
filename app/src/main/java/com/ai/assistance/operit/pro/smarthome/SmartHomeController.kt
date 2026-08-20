package com.ai.assistance.operit.pro.smarthome

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * 智能家居控制器
 *
 * 统一管理 IoT 设备，支持 Matter 协议、场景模式、
 * 自动化规则和语音控制。
 *
 * 核心能力：
 * - 多协议设备管理（Matter, WiFi, Bluetooth, Zigbee）
 * - 场景模式一键切换
 * - 自动化规则引擎
 * - 设备分组管理
 * - 能耗监控
 * - 语音控制集成
 */
class SmartHomeController {

    companion object {
        private val _devices = MutableStateFlow<List<SmartDevice>>(emptyList())
        val devices: StateFlow<List<SmartDevice>> = _devices.asStateFlow()

        private val _scenes = MutableStateFlow<List<SceneMode>>(emptyList())
        val scenes: StateFlow<List<SceneMode>> = _scenes.asStateFlow()

        private val _automations = MutableStateFlow<List<HomeAutomation>>(emptyList())
        val automations: StateFlow<List<HomeAutomation>> = _automations.asStateFlow()

        private val _energyUsage = MutableStateFlow(EnergyReport(0.0, 0.0, emptyMap()))
        val energyUsage: StateFlow<EnergyReport> = _energyUsage.asStateFlow()

        private val deviceMap = mutableMapOf<String, SmartDevice>()
        private val sceneMap = mutableMapOf<String, SceneMode>()
        private val automationList = mutableListOf<HomeAutomation>()
        private val deviceGroups = mutableMapOf<String, MutableList<String>>()
    }

    /** 添加设备 */
    fun addDevice(device: SmartDevice): Boolean {
        if (deviceMap.containsKey(device.id)) return false
        deviceMap[device.id] = device
        updateDevices()
        return true
    }

    /** 删除设备 */
    fun removeDevice(deviceId: String): Boolean {
        val removed = deviceMap.remove(deviceId) != null
        if (removed) updateDevices()
        return removed
    }

    /** 更新设备状态 */
    fun updateDeviceState(deviceId: String, state: DeviceState): Boolean {
        val device = deviceMap[deviceId] ?: return false
        deviceMap[deviceId] = device.copy(
            state = state,
            lastUpdated = System.currentTimeMillis(),
        )
        updateDevices()
        checkAutomations(deviceId, state)
        updateEnergyReport()
        return true
    }

    /** 控制设备 */
    fun controlDevice(deviceId: String, command: DeviceCommand): DeviceControlResult {
        val device = deviceMap[deviceId] ?: return DeviceControlResult.Error("Device not found")

        val newState = when (command) {
            is DeviceCommand.TurnOn -> device.state.copy(isOn = true)
            is DeviceCommand.TurnOff -> device.state.copy(isOn = false)
            is DeviceCommand.SetBrightness -> device.state.copy(
                isOn = command.level > 0,
                brightness = command.level,
            )
            is DeviceCommand.SetColor -> device.state.copy(color = command.color)
            is DeviceCommand.SetTemperature -> device.state.copy(temperature = command.temp)
            is DeviceCommand.SetMode -> device.state.copy(mode = command.mode)
            is DeviceCommand.SetFanSpeed -> device.state.copy(fanSpeed = command.speed)
        }

        updateDeviceState(deviceId, newState)
        return DeviceControlResult.Success(newState)
    }

    /** 创建场景模式 */
    fun createScene(name: String, description: String, actions: List<SceneAction>): SceneMode {
        val scene = SceneMode(
            id = UUID.randomUUID().toString(),
            name = name,
            description = description,
            actions = actions,
        )
        sceneMap[scene.id] = scene
        _scenes.value = sceneMap.values.toList()
        return scene
    }

    /** 激活场景 */
    fun activateScene(sceneId: String): Boolean {
        val scene = sceneMap[sceneId] ?: return false
        scene.actions.forEach { action ->
            controlDevice(action.deviceId, action.command)
        }
        return true
    }

    /** 添加自动化规则 */
    fun addAutomation(automation: HomeAutomation) {
        automationList.add(automation)
        _automations.value = automationList.toList()
    }

    /** 创建设备分组 */
    fun createGroup(groupName: String, deviceIds: List<String>) {
        deviceGroups[groupName] = deviceIds.toMutableList()
    }

    /** 控制分组 */
    fun controlGroup(groupName: String, command: DeviceCommand) {
        deviceGroups[groupName]?.forEach { deviceId ->
            controlDevice(deviceId, command)
        }
    }

    /** 语音控制 */
    fun processVoiceCommand(command: String): VoiceControlResult {
        val cmd = command.lowercase()

        // 解析设备名
        val targetDevice = deviceMap.values.find { dev ->
            cmd.contains(dev.name.lowercase())
        }

        // 解析动作
        return when {
            cmd.contains("开") || cmd.contains("打开") || cmd.contains("turn on") || cmd.contains("open") -> {
                if (targetDevice != null) {
                    controlDevice(targetDevice.id, DeviceCommand.TurnOn)
                    VoiceControlResult.Success("已为您打开${targetDevice.name}")
                } else {
                    VoiceControlResult.Error("未找到匹配的设备")
                }
            }
            cmd.contains("关") || cmd.contains("关闭") || cmd.contains("turn off") || cmd.contains("close") -> {
                if (targetDevice != null) {
                    controlDevice(targetDevice.id, DeviceCommand.TurnOff)
                    VoiceControlResult.Success("已为您关闭${targetDevice.name}")
                } else {
                    VoiceControlResult.Error("未找到匹配的设备")
                }
            }
            cmd.contains("调亮") || cmd.contains("变亮") || cmd.contains("brighter") -> {
                if (targetDevice != null) {
                    val newLevel = minOf(100, (targetDevice.state.brightness ?: 50) + 20)
                    controlDevice(targetDevice.id, DeviceCommand.SetBrightness(newLevel))
                    VoiceControlResult.Success("已将${targetDevice.name}亮度调至$newLevel%")
                } else {
                    VoiceControlResult.Error("未找到匹配的设备")
                }
            }
            cmd.contains("调暗") || cmd.contains("变暗") || cmd.contains("dimmer") -> {
                if (targetDevice != null) {
                    val newLevel = maxOf(0, (targetDevice.state.brightness ?: 50) - 20)
                    controlDevice(targetDevice.id, DeviceCommand.SetBrightness(newLevel))
                    VoiceControlResult.Success("已将${targetDevice.name}亮度调至$newLevel%")
                } else {
                    VoiceControlResult.Error("未找到匹配的设备")
                }
            }
            else -> VoiceControlResult.Error("无法理解指令: $command")
        }
    }

    private fun checkAutomations(deviceId: String, state: DeviceState) {
        automationList.forEach { automation ->
            if (automation.triggerDeviceId == deviceId) {
                val shouldTrigger = when (automation.condition) {
                    AutomationCondition.TURNED_ON -> state.isOn
                    AutomationCondition.TURNED_OFF -> !state.isOn
                    AutomationCondition.TEMPERATURE_ABOVE -> {
                        (state.temperature ?: 0.0) > automation.threshold
                    }
                    AutomationCondition.TEMPERATURE_BELOW -> {
                        (state.temperature ?: 100.0) < automation.threshold
                    }
                }
                if (shouldTrigger) {
                    controlDevice(automation.actionDeviceId, automation.action)
                }
            }
        }
    }

    private fun updateDevices() {
        _devices.value = deviceMap.values.toList()
    }

    private fun updateEnergyReport() {
        var totalPower = 0.0
        var dailyUsage = 0.0
        val byType = mutableMapOf<String, Double>()

        deviceMap.values.forEach { device ->
            if (device.state.isOn) {
                val power = device.powerUsage ?: 0.0
                totalPower += power
                dailyUsage += power * 24 / 1000 // kWh per day
                byType[device.type.displayName] = (byType[device.type.displayName] ?: 0.0) + power
            }
        }

        _energyUsage.value = EnergyReport(totalPower, dailyUsage, byType)
    }
}

/** 智能设备 */
data class SmartDevice(
    val id: String,
    val name: String,
    val type: DeviceType,
    val protocol: DeviceProtocol,
    val state: DeviceState,
    val powerUsage: Double? = null,
    val room: String = "未分类",
    val lastUpdated: Long = System.currentTimeMillis(),
)

/** 设备状态 */
data class DeviceState(
    val isOn: Boolean = false,
    val brightness: Int? = null,
    val color: String? = null,
    val temperature: Double? = null,
    val mode: String? = null,
    val fanSpeed: Int? = null,
    val battery: Int? = null,
)

/** 设备类型 */
enum class DeviceType(val displayName: String, val icon: String) {
    LIGHT("灯具", "lightbulb"),
    THERMOSTAT("温控器", "thermostat"),
    SWITCH("开关", "power"),
    CAMERA("摄像头", "camera"),
    LOCK("门锁", "lock"),
    SPEAKER("音箱", "speaker"),
    TV("电视", "tv"),
    FAN("风扇", "fan"),
    CURTAIN("窗帘", "curtains"),
    SENSOR("传感器", "sensors"),
    ROBOT_VACUUM("扫地机器人", "cleaning"),
    OTHER("其他", "device"),
}

/** 设备协议 */
enum class DeviceProtocol(val displayName: String) {
    MATTER("Matter"),
    WIFI("WiFi"),
    BLUETOOTH("蓝牙"),
    ZIGBEE("Zigbee"),
    Z_WAVE("Z-Wave"),
}

/** 设备命令 */
sealed class DeviceCommand {
    object TurnOn : DeviceCommand()
    object TurnOff : DeviceCommand()
    data class SetBrightness(val level: Int) : DeviceCommand()
    data class SetColor(val color: String) : DeviceCommand()
    data class SetTemperature(val temp: Double) : DeviceCommand()
    data class SetMode(val mode: String) : DeviceCommand()
    data class SetFanSpeed(val speed: Int) : DeviceCommand()
}

/** 控制结果 */
sealed class DeviceControlResult {
    data class Success(val newState: DeviceState) : DeviceControlResult()
    data class Error(val message: String) : DeviceControlResult()
}

/** 场景模式 */
data class SceneMode(
    val id: String,
    val name: String,
    val description: String,
    val actions: List<SceneAction>,
)

/** 场景动作 */
data class SceneAction(
    val deviceId: String,
    val command: DeviceCommand,
)

/** 自动化规则 */
data class HomeAutomation(
    val id: String,
    val name: String,
    val triggerDeviceId: String,
    val condition: AutomationCondition,
    val threshold: Double = 0.0,
    val actionDeviceId: String,
    val action: DeviceCommand,
)

/** 自动化条件 */
enum class AutomationCondition {
    TURNED_ON, TURNED_OFF, TEMPERATURE_ABOVE, TEMPERATURE_BELOW
}

/** 能耗报告 */
data class EnergyReport(
    val totalPowerWatts: Double,
    val estimatedDailyKwh: Double,
    val byType: Map<String, Double>,
)

/** 语音控制结果 */
sealed class VoiceControlResult {
    data class Success(val message: String) : VoiceControlResult()
    data class Error(val message: String) : VoiceControlResult()
}
