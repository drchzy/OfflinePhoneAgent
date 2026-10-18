package com.drchzy.offlinephoneagent

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MovieHttpEntry(
    val id: Long,
    val label: String,
    val request: String,
    val response: String,
    val status: Int?,
    val at: String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
    val domain: String = runCatching {
        val match = Regex("""https?://([^/\s?]+)""").find(request)
        match?.groupValues?.getOrNull(1) ?: "unknown"
    }.getOrDefault("unknown"),
    val method: String = request.lineSequence().firstOrNull()?.substringBefore(' ')
        ?.takeIf { it in listOf("GET", "POST", "PUT", "DELETE", "PATCH") } ?: "GET"
)

object MovieHttpLog {
    private val counter = AtomicLong()
    private val data = MutableStateFlow<List<MovieHttpEntry>>(emptyList())
    val entries = data.asStateFlow()
    private val active = MutableStateFlow(false)
    val enabled = active.asStateFlow()

    fun initialize(context: Context) {
        active.value = context.getSharedPreferences("network_debug", Context.MODE_PRIVATE)
            .getBoolean("enabled", false)
    }

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("network_debug", Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", value).apply()
        active.value = value
    }

    fun add(label: String, request: String, response: String, status: Int?) {
        if (!active.value) return
        val item = MovieHttpEntry(counter.incrementAndGet(), label, request.take(48_000),
            response.take(64_000), status)
        data.update { old -> (listOf(item) + old).take(300) }
    }

    fun clear() { data.value = emptyList() }
}
