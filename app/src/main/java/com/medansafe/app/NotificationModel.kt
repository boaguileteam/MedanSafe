package com.medansafe.app

import com.google.firebase.firestore.PropertyName

data class NotificationModel(
    val id: String = "",
    val type: String = "", // incident, verified, danger, system
    val judul: String = "",
    val isi: String = "",
    val timestamp: Long = 0,
    @get:PropertyName("isRead") @set:PropertyName("isRead") var isRead: Boolean = false,
    val incidentId: String? = null
)
