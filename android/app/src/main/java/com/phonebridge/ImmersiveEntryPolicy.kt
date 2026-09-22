package com.phonebridge

enum class ImmersiveSurface(val persistedValue: String) {
    COMPANION("companion"),
    REALITY("reality");

    companion object {
        fun fromPersisted(value: String?): ImmersiveSurface = values().firstOrNull {
            it.persistedValue == value?.trim()?.lowercase()
        } ?: COMPANION
    }
}

data class ImmersiveEntryState(
    val lastSurface: ImmersiveSurface = ImmersiveSurface.COMPANION,
    val cameraPermissionGranted: Boolean = false,
)

object ImmersiveEntryPolicy {
    fun surfaceFor(state: ImmersiveEntryState): ImmersiveSurface =
        if (state.lastSurface == ImmersiveSurface.REALITY && state.cameraPermissionGranted) {
            ImmersiveSurface.REALITY
        } else {
            ImmersiveSurface.COMPANION
        }

    fun shouldRequestPermissionsOnLaunch(): Boolean = false
}
