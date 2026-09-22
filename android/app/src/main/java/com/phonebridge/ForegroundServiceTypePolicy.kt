package com.phonebridge

import android.content.pm.ServiceInfo

object ForegroundServiceTypePolicy {
    fun typeFor(cameraActive: Boolean, microphoneActive: Boolean): Int {
        var type = 0
        if (cameraActive) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (microphoneActive) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return if (type == 0) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else type
    }
}
