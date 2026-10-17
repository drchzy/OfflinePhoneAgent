package com.drchzy.offlinephoneagent

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MovieHttpEntry(
    val id: Long,
    val label: String,
    val request: String,
    val response: String,
    val status: Int?
)

object MovieHttpLog {
    private val counter = AtomicLong()
    private val data = MutableStateFlow<List<MovieHttpEntry>>(emptyList())
    val entries = data.asStateFlow()

    fun add(label: String, request: String, response: String, status: Int?) {
        val item = MovieHttpEntry(
            counter.incrementAndGet(), label, request.take(30000), response.take(30000), status
        )
        data.value = (listOf(item) + data.value).take(12)
    }

    fun clear() {
        data.value = emptyList()
    }
}
