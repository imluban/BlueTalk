package com.bluetalk.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {
    private val items = mutableListOf<ChatMessage>()

    fun submit(message: ChatMessage) {
        items += message
        notifyItemInserted(items.lastIndex)
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txt: TextView = itemView.findViewById(R.id.txtMessage)
        fun bind(item: ChatMessage) {
            // Simple terminal line: prefix incoming/outgoing with symbols
            val prefix = if (item.isIncoming) "&lt;" else "&gt;"
            txt.text = "$prefix ${item.text}"
        }
    }
}
