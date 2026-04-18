package com.medansafe.app

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class IncidentModel(
    val id: String = "",
    val category: String = "", // Same as type, using category for consistency with user prompt
    val address: String = "",  // Same as location
    val description: String = "",
    val reporterName: String = "Anonim",
    val upvoteCount: Int = 0,
    val areaScore: Int = 0,    // Same as areaRiskScore
    val photoBase64: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val createdAt: com.google.firebase.Timestamp? = null,
    val status: String = "Menunggu"
) : Parcelable

@Parcelize
data class CommentModel(
    val userId: String = "",
    val userName: String = "",
    val content: String = "",
    val timeAgo: String = ""
) : Parcelable
