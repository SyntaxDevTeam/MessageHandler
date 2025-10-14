package com.github.messagehandler

import java.time.Instant
import java.util.Locale

internal data class MessageEntry(
    val raw: String,
    val format: MessageFormat
)

internal data class MessageBundle(
    val locale: Locale,
    private val entries: Map<String, MessageEntry>,
    val loadedAt: Instant = Instant.now()
) {
    fun entry(path: List<String>): MessageEntry? = entries[path.joinToString(".")]
}
