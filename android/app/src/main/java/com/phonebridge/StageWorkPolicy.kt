package com.phonebridge

/** Keeps host lifecycle decisions out of the Canvas and telemetry implementation. */
object StageWorkPolicy {
    fun renderStage(foreground: Boolean, shown: Boolean): Boolean = foreground && shown
    fun telemetryInterval(foreground: Boolean, activeSensors: Boolean): Long =
        if (foreground || activeSensors) 2500L else 15000L
}
