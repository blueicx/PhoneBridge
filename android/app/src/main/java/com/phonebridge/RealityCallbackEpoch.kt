package com.phonebridge

import java.util.concurrent.atomic.AtomicInteger

/** Drops callbacks queued by a closed ARCore session after a newer session starts. */
class RealityCallbackEpoch {
    private val epoch = AtomicInteger(0)

    fun advance(): Int = epoch.incrementAndGet()

    fun current(): Int = epoch.get()

    fun accepts(callbackEpoch: Int): Boolean = callbackEpoch == epoch.get()
}
