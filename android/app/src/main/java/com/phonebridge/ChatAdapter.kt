package com.phonebridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.Holder>() {
    private val items = mutableListOf<ChatMessage>()

    fun load(values: List<ChatMessage>) {
        items.clear()
        items.addAll(values.takeLast(120))
        notifyDataSetChanged()
    }

    fun append(message: ChatMessage) {
        items.add(message)
        if (items.size > 120) items.removeAt(0)
        notifyDataSetChanged()
    }

    fun upsertStreaming(message: ChatMessage) {
        val last = items.lastOrNull()
        if (last != null && last.role == "assistant" && last.streaming) {
            items[items.lastIndex] = message
        } else {
            items.add(message)
            if (items.size > 120) items.removeAt(0)
        }
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val row: LinearLayout = view.findViewById(R.id.chatRow)
        val bubble: TextView = view.findViewById(R.id.chatBubble)
        val meta: TextView = view.findViewById(R.id.chatMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val isUser = item.role.equals("user", true)
        holder.row.gravity = if (isUser) Gravity.END else Gravity.START
        holder.bubble.setBackgroundResource(if (isUser) R.drawable.bg_bubble_user else R.drawable.bg_bubble_mote)
        holder.bubble.text = if (item.streaming) "${item.text}▍" else item.text
        holder.meta.text = listOf(if (isUser) "你" else "Mote", item.time).joinToString(" · ")
        holder.bubble.setOnLongClickListener { view ->
            val manager = view.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("Mote", item.text))
            Toast.makeText(view.context, "已复制", Toast.LENGTH_SHORT).show()
            true
        }
    }

    override fun getItemCount() = items.size

    fun messageAt(position: Int): ChatMessage? = items.getOrNull(position)
}
