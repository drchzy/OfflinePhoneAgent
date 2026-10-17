package com.drchzy.offlinephoneagent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MovieHttpEntry(
    val label: String,
    val request: String,
    val response: String,
    val status: Int?
)

object MovieHttpLog {
    private val data = MutableStateFlow<List<MovieHttpEntry>>(emptyList())
    val entries = data.asStateFlow()

    fun add(entry: MovieHttpEntry) {
        data.value = (listOf(entry) + data.value).take(12)
    }

    fun clear() {
        data.value = emptyList()
    }
}
