package com.phonebridge

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundServiceTypePolicyTest {
    @Test
    fun idleResidentUsesDataSyncWithoutSensorTypes() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            ForegroundServiceTypePolicy.typeFor(cameraActive = false, microphoneActive = false),
        )
    }

    @Test
    fun activeSensorsSelectOnlyTheTypesActuallyInUse() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            ForegroundServiceTypePolicy.typeFor(cameraActive = true, microphoneActive = false),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            ForegroundServiceTypePolicy.typeFor(cameraActive = false, microphoneActive = true),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            ForegroundServiceTypePolicy.typeFor(cameraActive = true, microphoneActive = true),
        )
    }
}
