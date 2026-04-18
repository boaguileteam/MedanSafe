package com.medansafe.app

import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.RecyclerView

class IncidentAdapter(
    private val incidents: List<IncidentModel>,
    private val isHistory: Boolean = false,
    private val onItemClick: (IncidentModel) -> Unit
) : RecyclerView.Adapter<IncidentAdapter.IncidentViewHolder>() {

    class IncidentViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvEmoji: TextView = view.findViewById(R.id.tv_item_emoji)
        val tvType: TextView = view.findViewById(R.id.tv_item_type)
        val tvLocation: TextView = view.findViewById(R.id.tv_item_location)
        val tvTime: TextView = view.findViewById(R.id.tv_item_time)
        val tvUpvotes: TextView = view.findViewById(R.id.tv_item_upvotes)
        val tvScoreLabel: TextView = view.findViewById(R.id.tv_item_score_label)
        val cvCategoryIcon: CardView = view.findViewById(R.id.cv_category_icon)
        val ivStatusBadge: ImageView = view.findViewById(R.id.iv_status_badge)
        val llStatusContainer: View = view.findViewById(R.id.ll_status_container)
        val tvActionText: TextView = view.findViewById(R.id.tv_item_action_text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IncidentViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_incident, parent, false)
        return IncidentViewHolder(view)
    }

    override fun onBindViewHolder(holder: IncidentViewHolder, position: Int) {
        val incident = incidents[position]
        val context = holder.itemView.context
        
        holder.tvType.text = incident.category
        holder.tvLocation.text = incident.address
        holder.tvUpvotes.text = context.getString(R.string.incident_upvote_format, incident.upvoteCount)
        
        incident.createdAt?.let {
            val timeAgo = DateUtils.getRelativeTimeSpanString(
                it.seconds * 1000,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )
            holder.tvTime.text = timeAgo
        } ?: run {
            holder.tvTime.text = context.getString(R.string.incident_time_default)
        }
        
        val scoreColor: Int
        val scoreBgColor: Int

        when (incident.areaScore) {
            in 71..100 -> {
                scoreColor = "#10B981".toColorInt()
                scoreBgColor = "#DCFCE7".toColorInt()
            }
            in 41..70 -> {
                scoreColor = "#F59E0B".toColorInt()
                scoreBgColor = "#FEF3C7".toColorInt()
            }
            else -> {
                scoreColor = "#E74C3C".toColorInt()
                scoreBgColor = "#FEE2E2".toColorInt()
            }
        }

        holder.tvScoreLabel.text = context.getString(R.string.incident_score_format, incident.areaScore)
        holder.tvScoreLabel.setTextColor(scoreColor)
        holder.tvScoreLabel.backgroundTintList = ColorStateList.valueOf(scoreBgColor)
        holder.tvActionText.setTextColor(scoreColor)

        if (isHistory) {
            holder.llStatusContainer.visibility = View.GONE
            holder.ivStatusBadge.visibility = View.VISIBLE
            
            when (incident.status.lowercase()) {
                "terverifikasi" -> {
                    holder.ivStatusBadge.setImageResource(R.drawable.bg_badge_verified)
                    holder.ivStatusBadge.imageTintList = null
                }
                "ditolak" -> {
                    holder.ivStatusBadge.setImageResource(R.drawable.ic_close)
                    holder.ivStatusBadge.imageTintList = ColorStateList.valueOf("#E74C3C".toColorInt())
                }
                else -> {
                    holder.ivStatusBadge.setImageResource(R.drawable.bg_badge_pending)
                    holder.ivStatusBadge.imageTintList = null
                }
            }
        } else {
            holder.llStatusContainer.visibility = View.VISIBLE
            holder.ivStatusBadge.visibility = View.GONE
        }

        when (incident.category.uppercase()) {
            "BEGAL" -> {
                holder.tvEmoji.text = "⚠️"
                holder.tvType.setTextColor("#E74C3C".toColorInt())
                holder.cvCategoryIcon.setCardBackgroundColor("#FEE2E2".toColorInt())
            }
            "JALAN GELAP" -> {
                holder.tvEmoji.text = "🌙"
                holder.tvType.setTextColor("#F59E0B".toColorInt())
                holder.cvCategoryIcon.setCardBackgroundColor("#FEF3C7".toColorInt())
            }
            "KECELAKAAN" -> {
                holder.tvEmoji.text = "🛡️"
                holder.tvType.setTextColor("#3B82F6".toColorInt())
                holder.cvCategoryIcon.setCardBackgroundColor("#DBEAFE".toColorInt())
            }
            "JALAN RUSAK" -> {
                holder.tvEmoji.text = "🚧"
                holder.tvType.setTextColor("#8B5E3C".toColorInt())
                holder.cvCategoryIcon.setCardBackgroundColor("#EDE0D4".toColorInt())
            }
            else -> {
                holder.tvEmoji.text = "📍"
                holder.tvType.setTextColor("#64748B".toColorInt())
                holder.cvCategoryIcon.setCardBackgroundColor("#F1F5F9".toColorInt())
            }
        }

        holder.itemView.setOnClickListener { onItemClick(incident) }
    }

    override fun getItemCount() = incidents.size
}
