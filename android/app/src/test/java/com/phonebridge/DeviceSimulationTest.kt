package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSimulationTest {
    @Test
    fun coarseRegionAndSeedAreDeterministic() {
        assertEquals("cell:1561:6073", DeviceSimulation.coarseRegion(31.2304, 121.4737))
        val a = DeviceSimulation("same")
        val b = DeviceSimulation("same")
        assertEquals(a.realitySeed(10), b.realitySeed(10))
    }

    @Test
    fun commandsKeepStateBoundedAndOfflineIsExplicit() {
        val sim = DeviceSimulation()
        sim.apply(SimulationCommand.Network(false))
        sim.apply(SimulationCommand.Sensor(camera = true, microphone = false))
        val state = sim.apply(SimulationCommand.Telemetry(battery = -10f, temperature = 99f, fps = 18))
        assertTrue(!state.networkOnline)
        assertTrue(state.camera)
        assertEquals(-10f, state.battery, 0f)
        assertEquals(99f, state.temperature, 0f)
        assertEquals(18, state.fps)
    }
}
