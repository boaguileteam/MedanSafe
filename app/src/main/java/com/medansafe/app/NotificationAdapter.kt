package com.medansafe.app

import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.medansafe.app.databinding.ItemNotificationBinding

class NotificationAdapter(
    private val onItemClick: (NotificationModel) -> Unit
) : ListAdapter<NotificationModel, NotificationAdapter.NotificationViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotificationViewHolder {
        val binding = ItemNotificationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return NotificationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NotificationViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class NotificationViewHolder(private val binding: ItemNotificationBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(notification: NotificationModel) {
            binding.tvNotifTitle.text = notification.judul
            binding.tvNotifContent.text = notification.isi
            binding.tvNotifTime.text = DateUtils.getRelativeTimeSpanString(
                notification.timestamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )

            // Background based on read status
            val bgColor = if (notification.isRead) {
                ContextCompat.getColor(binding.root.context, R.color.notif_read)
            } else {
                ContextCompat.getColor(binding.root.context, R.color.notif_unread)
            }
            binding.clNotifBg.setBackgroundColor(bgColor)
            binding.viewUnreadDot.visibility = if (notification.isRead) View.GONE else View.VISIBLE

            // Icon and Color based on type
            val (iconRes, colorHex, titleColorHex) = when (notification.type) {
                "incident" -> Triple(R.drawable.ic_nav_report, "#F59E0B", "#F59E0B")
                "verified" -> Triple(R.drawable.ic_check_filled, "#D1FAE5", "#065F46")
                "rejected" -> Triple(R.drawable.ic_close, "#FEE2E2", "#991B1B")
                "danger" -> Triple(R.drawable.ic_circle, "#E74C3C", "#E74C3C")
                else -> Triple(R.drawable.ic_setting, "#3B82F6", "#3B82F6")
            }

            binding.ivNotifIcon.setImageResource(iconRes)
            binding.viewIconBg.backgroundTintList = ColorStateList.valueOf(colorHex.toColorInt())
            binding.tvNotifTitle.setTextColor(titleColorHex.toColorInt())

            binding.root.setOnClickListener { onItemClick(notification) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<NotificationModel>() {
        override fun areItemsTheSame(oldItem: NotificationModel, newItem: NotificationModel): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: NotificationModel, newItem: NotificationModel): Boolean {
            return oldItem == newItem
        }
    }
}
