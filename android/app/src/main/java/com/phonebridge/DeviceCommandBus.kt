package com.phonebridge

object DeviceCommandBus {
    @Volatile private var receiver: ((String) -> Unit)? = null

    fun setReceiver(receiver: ((String) -> Unit)?) {
        this.receiver = receiver
    }

    fun dispatch(action: String): Boolean {
        val current = receiver ?: return false
        current(action)
        return true
    }
}
