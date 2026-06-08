package com.twister.bridge

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.twister.bridge.db.WhatsAppNotif
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val onCopyClick: (WhatsAppNotif) -> Unit,
    private val onShareClick: (WhatsAppNotif) -> Unit,
    private val onDeleteClick: (WhatsAppNotif) -> Unit
) : ListAdapter<WhatsAppNotif, HistoryAdapter.ViewHolder>(DiffCallback()) {

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val senderText: TextView = itemView.findViewById(R.id.senderTextView)
        private val timeText: TextView = itemView.findViewById(R.id.timeTextView)
        private val messageText: TextView = itemView.findViewById(R.id.messageTextView)
        private val copyBtn: MaterialButton = itemView.findViewById(R.id.copyBtn)
        private val shareBtn: MaterialButton = itemView.findViewById(R.id.shareBtn)
        private val deleteBtn: MaterialButton = itemView.findViewById(R.id.deleteBtn)

        fun bind(item: WhatsAppNotif) {
            senderText.text = item.sender
            timeText.text = timeFormat.format(Date(item.timestamp))
            messageText.text = item.message

            copyBtn.setOnClickListener { onCopyClick(item) }
            shareBtn.setOnClickListener { onShareClick(item) }
            deleteBtn.setOnClickListener { onDeleteClick(item) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<WhatsAppNotif>() {
        override fun areItemsTheSame(oldItem: WhatsAppNotif, newItem: WhatsAppNotif): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: WhatsAppNotif, newItem: WhatsAppNotif): Boolean {
            return oldItem == newItem
        }
    }
}
