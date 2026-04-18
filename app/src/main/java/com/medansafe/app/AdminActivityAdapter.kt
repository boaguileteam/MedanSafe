package com.medansafe.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.medansafe.app.databinding.ItemAdminActivityBinding
import java.text.SimpleDateFormat
import java.util.Locale

class AdminActivityAdapter : ListAdapter<AdminActivity, AdminActivityAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAdminActivityBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemAdminActivityBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: AdminActivity) {
            binding.tvActivityTitle.text = item.title
            binding.tvActivitySubtext.text = item.subtext
            
            // Set dot color based on category
            val dotColor = when (item.kategori.lowercase()) {
                "aman" -> "#10B981"    // Green
                "waspada" -> "#F59E0B" // Yellow
                else -> "#C0392B"      // Red (Bahaya/Lainnya)
            }
            binding.vActivityDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor(dotColor)
            )
            
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            binding.tvActivityTime.text = item.timestamp?.toDate()?.let { sdf.format(it) } ?: "-"
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<AdminActivity>() {
        override fun areItemsTheSame(oldItem: AdminActivity, newItem: AdminActivity): Boolean {
            return oldItem.timestamp == newItem.timestamp && oldItem.title == newItem.title
        }

        override fun areContentsTheSame(oldItem: AdminActivity, newItem: AdminActivity): Boolean {
            return oldItem == newItem
        }
    }
}
