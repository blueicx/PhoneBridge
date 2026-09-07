package com.phonebridge

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogAdapter : RecyclerView.Adapter<LogAdapter.Holder>() {
    private val items = mutableListOf<LogItem>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun add(level: String, message: String) {
        items.add(LogItem(timeFormat.format(Date()), level, message))
        if (items.size > 180) items.removeAt(0)
        notifyDataSetChanged()
    }

    fun submit(values: List<LogItem>) {
        items.clear()
        items.addAll(values.takeLast(180))
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.logText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.text.text = "${item.time}  ${item.message}"
        holder.text.setTextColor(
            when (item.level) {
                "error" -> 0xFFFF8A80.toInt()
                "warn" -> 0xFFFFD9A3.toInt()
                "success" -> 0xFFA5F3CB.toInt()
                else -> 0xFFCDE8DA.toInt()
            }
        )
    }

    override fun getItemCount() = items.size
}

class TaskAdapter : RecyclerView.Adapter<TaskAdapter.Holder>() {
    private val items = mutableListOf<TaskItem>()

    fun submit(values: List<TaskItem>) {
        items.clear()
        items.addAll(values)
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.taskTitle)
        val status: TextView = view.findViewById(R.id.taskStatus)
        val detail: TextView = view.findViewById(R.id.taskDetail)
        val progress: ProgressBar = view.findViewById(R.id.taskProgress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_task, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.status.text = "${item.status} ${item.progress}%"
        holder.detail.text = item.detail.ifBlank { " " }
        holder.progress.progress = item.progress.coerceIn(0, 100)
    }

    override fun getItemCount() = items.size
}
