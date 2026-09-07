package com.phonebridge

import android.content.Context
import org.json.JSONArray

object ChatOutbox {
    private const val PREFS = "mote_outbox"
    private const val KEY_ITEMS = "items"

    fun enqueue(context: Context, text: String): MutableList<String> {
        val clean = text.trim()
        val items = load(context)
        if (clean.isNotEmpty()) items.add(clean)
        replace(context, items)
        return items
    }

    fun load(context: Context): MutableList<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            val items = mutableListOf<String>()
            for (i in 0 until array.length()) {
                val value = array.optString(i).trim()
                if (value.isNotEmpty()) items.add(value)
            }
            items
        }.getOrDefault(mutableListOf())
    }

    fun replace(context: Context, items: List<String>) {
        val array = JSONArray()
        items.forEach { array.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ITEMS, array.toString())
            .apply()
    }

    fun drain(context: Context): List<String> {
        val items = load(context)
        replace(context, emptyList())
        return items
    }

    fun remove(context: Context, text: String): Boolean {
        val clean = text.trim()
        val items = load(context)
        val removed = items.removeAll { it == clean }
        if (removed) replace(context, items)
        return removed
    }
}
