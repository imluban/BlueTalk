package com.bluetalk.app

data class ChatMessage(
    val text: String,
    val isIncoming: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)
