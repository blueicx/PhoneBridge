package com.phonebridge

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import java.io.File

data class TelemetrySample(
    val cpuLoad: Int,
    val memoryPercent: Int,
    val ramUsedGb: Float,
    val ramTotalGb: Float,
    val batteryPercent: Int,
    val batteryTemperature: Float,
    val networkRxMb: Float,
    val networkTxMb: Float,
    val uptimeHours: Float,
    val thermalCelsius: Float
) {
    fun summary(): String = """
        CPU 负载 · $cpuLoad%
        内存 · $memoryPercent% ($ramUsedGb GB / $ramTotalGb GB)
        电量 · $batteryPercent% · ${"%.1f".format(batteryTemperature)}°C
        网络 · ↓$networkRxMb MB ↑$networkTxMb MB
        运行 · ${"%.1f".format(uptimeHours)} 小时
        温度 · ${"%.1f".format(thermalCelsius)}°C
    """.trimIndent()
}

object DeviceTelemetry {
    private var lastCpuSample: Pair<Long, Long>? = null

    private fun readText(path: String): String = runCatching { File(path).readText().trim() }.getOrDefault("")

    suspend fun sample(context: Context): TelemetrySample {
        val cpu = readCpu()
        val memory = readMemory()
        val battery = readBattery(context)
        val network = readNetwork()
        val thermal = readThermal()
        return TelemetrySample(
            cpuLoad = cpu,
            memoryPercent = memory.first,
            ramUsedGb = memory.second,
            ramTotalGb = memory.third,
            batteryPercent = battery.first,
            batteryTemperature = battery.second,
            networkRxMb = network.first / 1024f / 1024f,
            networkTxMb = network.second / 1024f / 1024f,
            uptimeHours = SystemClock.elapsedRealtime() / 3_600_000f,
            thermalCelsius = thermal
        )
    }

    private fun readCpu(): Int {
        val content = readText("/proc/stat")
        val line = content.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return 12
        val values = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 5) return 0
        val idle = values[3] + (values.getOrElse(4) { 0 })
        val total = values.sum()
        val previous = lastCpuSample
        lastCpuSample = idle to total
        if (previous == null) return 18
        val idleDelta = idle - previous.first
        val totalDelta = total - previous.second
        if (totalDelta <= 0) return 0
        return (((totalDelta - idleDelta) * 100f) / totalDelta).toInt().coerceIn(0, 100)
    }

    private fun readMemory(): Triple<Int, Float, Float> {
        val values = readText("/proc/meminfo").lineSequence().take(3).associate {
                val parts = it.split(Regex("\\s+"))
                parts.first() to parts.getOrNull(1)?.toLongOrNull()
        }
        val totalKb = values["MemTotal:"] ?: return Triple(0, 0f, 0f)
        val availableKb = values["MemAvailable:"] ?: return Triple(0, 0f, 0f)
        val usedKb = (totalKb - availableKb).coerceAtLeast(0)
        return Triple(
            (usedKb * 100f / totalKb).toInt().coerceIn(0, 100),
            usedKb / 1024f / 1024f,
            totalKb / 1024f / 1024f
        )
    }

    private fun readBattery(context: Context): Pair<Int, Float> {
        val manager = context.getSystemService(BatteryManager::class.java)
        val percent = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it > 0 } ?: 72
        // Some Sony builds expose temperature only through the sticky intent; use the stable sysfs fallback.
        val temp = readText("/sys/class/power_supply/battery/temp").toFloatOrNull() ?: 280f
        return percent to temp / 10f
    }

    private fun readNetwork(): Pair<Long, Long> {
        var rx = 0L
        var tx = 0L
        File("/sys/class/net").listFiles()?.filter { it.name != "lo" }?.forEach { nic ->
            rx += readText(File(nic, "statistics/rx_bytes").absolutePath).toLongOrNull() ?: 0
            tx += readText(File(nic, "statistics/tx_bytes").absolutePath).toLongOrNull() ?: 0
        }
        return rx to tx
    }

    private fun readThermal(): Float {
        val zones = runCatching {
            File("/sys/class/thermal").listFiles { file -> file.name.startsWith("thermal_zone") }
        }.getOrNull()
            ?.mapNotNull { zone -> readText(File(zone, "temp").absolutePath).toFloatOrNull() }
            ?.filter { it > 0 }
            ?.map { it / 1000f }
            .orEmpty()
        return if (zones.isEmpty()) 31f else zones.sorted()[zones.size / 2]
    }
}
