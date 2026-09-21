package com.phonebridge

import kotlin.math.floor

data class SimulatedDeviceState(
    val revision: Long = 0L,
    val region: String = "cell:0:0",
    val bearing: Int = 0,
    val distanceBand: String = "near",
    val clueType: String = "location",
    val networkOnline: Boolean = true,
    val battery: Float = 82f,
    val temperature: Float = 28f,
    val fps: Int = 30,
    val camera: Boolean = false,
    val microphone: Boolean = false,
)

class DeviceSimulation(private val seed: String = "phonebridge-sim") {
    var state: SimulatedDeviceState = SimulatedDeviceState()
        private set

    fun apply(command: SimulationCommand): SimulatedDeviceState {
        val next = when (command) {
            is SimulationCommand.Reset -> SimulatedDeviceState(region = command.region)
            is SimulationCommand.Region -> state.copy(
                region = command.region ?: state.region,
                bearing = normalizeBearing(command.bearing ?: state.bearing),
                distanceBand = command.distanceBand ?: state.distanceBand,
                clueType = command.clueType ?: state.clueType,
            )
            is SimulationCommand.Network -> state.copy(networkOnline = command.online)
            is SimulationCommand.Sensor -> state.copy(camera = command.camera, microphone = command.microphone)
            is SimulationCommand.Telemetry -> state.copy(
                battery = command.battery ?: state.battery,
                temperature = command.temperature ?: state.temperature,
                fps = command.fps ?: state.fps,
            )
            SimulationCommand.Tick -> state.copy(
                bearing = normalizeBearing(state.bearing + deterministicDelta(state.revision + 1)),
                battery = (state.battery - if (state.camera) .03f else .005f).coerceIn(0f, 100f),
                temperature = (state.temperature + if (state.camera) .2f else -.05f).coerceIn(18f, 48f),
            )
        }
        state = next.copy(revision = state.revision + 1)
        return state
    }

    fun realitySeed(timeBucket: Long): String = "$seed:${state.region}:$timeBucket"

    companion object {
        fun coarseRegion(latitude: Double, longitude: Double, cellSize: Double = .02): String {
            require(latitude.isFinite() && longitude.isFinite() && cellSize > 0)
            return "cell:${floor(latitude / cellSize).toInt()}:${floor(longitude / cellSize).toInt()}"
        }

        private fun normalizeBearing(value: Int): Int = ((value % 360) + 360) % 360
        private fun deterministicDelta(revision: Long): Int = ((revision * 1103515245L + 12345L) ushr 28).toInt() % 18 - 9
    }
}

sealed interface SimulationCommand {
    data class Reset(val region: String = "cell:0:0") : SimulationCommand
    data class Region(val region: String? = null, val bearing: Int? = null, val distanceBand: String? = null, val clueType: String? = null) : SimulationCommand
    data class Network(val online: Boolean) : SimulationCommand
    data class Sensor(val camera: Boolean, val microphone: Boolean) : SimulationCommand
    data class Telemetry(val battery: Float? = null, val temperature: Float? = null, val fps: Int? = null) : SimulationCommand
    data object Tick : SimulationCommand
}
