package com.twister.bridge.db

data class WhatsAppNotif(
    val id: Long,
    val sender: String,
    val message: String,
    val timestamp: Long
)
