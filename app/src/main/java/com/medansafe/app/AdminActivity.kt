package com.medansafe.app

import com.google.firebase.Timestamp

data class AdminActivity(
    val title: String,
    val subtext: String,
    val timestamp: Timestamp?,
    val kategori: String = "Aman"
)
