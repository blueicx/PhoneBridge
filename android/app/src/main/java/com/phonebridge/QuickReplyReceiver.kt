package com.phonebridge

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput

class QuickReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(QuickReplyNotification.KEY_REPLY)?.toString()?.trim()
        if (text.isNullOrBlank()) return
        ChatOutbox.enqueue(context, text)
        showQueued(context, text)
        val service = Intent(context, BridgeService::class.java)
            .setAction(BridgeService.ACTION_SEND_REPLY)
            .putExtra(BridgeService.EXTRA_REPLY_TEXT, text)
        context.startForegroundService(service)
    }

    private fun showQueued(context: Context, text: String) {
        val notification = NotificationCompat.Builder(context, BridgeService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Mote 已收到快捷指令")
            .setContentText(text.take(120))
            .setOngoing(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            ?.notify(BridgeService.NOTIFICATION_ID + 1, notification)
    }
}
