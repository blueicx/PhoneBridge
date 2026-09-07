package com.phonebridge

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MemoryAdapter(
    private var onDelete: (MemoryItem) -> Unit
) : RecyclerView.Adapter<MemoryAdapter.Holder>() {
    private val items = mutableListOf<MemoryItem>()
    private val format = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

    fun submit(values: List<MemoryItem>) {
        items.clear()
        items.addAll(values)
        notifyDataSetChanged()
    }

    fun setOnDelete(listener: (MemoryItem) -> Unit) {
        onDelete = listener
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.memoryText)
        val meta: TextView = view.findViewById(R.id.memoryMeta)
        val delete: Button = view.findViewById(R.id.deleteMemory)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_memory, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.text.text = item.text
        holder.meta.text = "重要性 ${item.importance}/5 · 使用 ${item.useCount} 次 · ${
            if (item.lastUsedAtMs > 0) format.format(Date(item.lastUsedAtMs)) else "未召回"
        }"
        holder.delete.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount() = items.size
}
