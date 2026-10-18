package com.drchzy.offlinephoneagent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 本机观看状态：不上传云端，不存播放 URL、Token 或 HTTP 头。 */
data class EpisodeWatch(
    val movieId: String,
    val movieName: String,
    val episodeId: String,
    val episodeName: String,
    val lineCode: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
    val watched: Boolean
) {
    val finished: Boolean get() = watched || (durationMs > 60_000L &&
        positionMs.toDouble() / durationMs >= 0.92)
    val resumeMs: Long get() =
        if (finished || positionMs < 5_000L ||
            (durationMs > 0L && durationMs - positionMs < 15_000L)) 0L
        else positionMs
}

/** 最多保留最近 300 个剧集进度；SharedPreferences 自动持久化。 */
object MovieWatchHistory {
    private const val FILE = "movie_watch_history_v1"
    private const val KEY = "episodes"
    private const val LIMIT = 300
    private val lock = Any()
    val revision = MutableStateFlow(0L)

    private fun parse(context: Context): MutableList<EpisodeWatch> {
        val raw = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY, "[]").orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("movieId")
                if (id.isBlank()) continue
                add(EpisodeWatch(
                    movieId = id,
                    movieName = o.optString("movieName").take(100),
                    episodeId = o.optString("episodeId"),
                    episodeName = o.optString("episodeName").take(100),
                    lineCode = o.optString("lineCode"),
                    positionMs = o.optLong("positionMs", 0L).coerceAtLeast(0L),
                    durationMs = o.optLong("durationMs", 0L).coerceAtLeast(0L),
                    updatedAtMs = o.optLong("updatedAtMs", 0L),
                    watched = o.optBoolean("watched", false)
                ))
            }
        }.toMutableList()
    }

    fun all(context: Context): List<EpisodeWatch> = synchronized(lock) {
        parse(context).sortedByDescending { it.updatedAtMs }
    }

    fun latest(context: Context, movieId: String): EpisodeWatch? =
        all(context).firstOrNull { it.movieId == movieId }

    fun find(context: Context, movieId: String, episode: MovieEpisode): EpisodeWatch? =
        all(context).firstOrNull { it.movieId == movieId &&
            it.lineCode == episode.fromCode &&
            (episode.id.isNotBlank() && it.episodeId == episode.id ||
             episode.id.isBlank() && it.episodeName == episode.name)
        }

    fun save(
        context: Context,
        movieId: String,
        movieName: String,
        episodeId: String,
        episodeName: String,
        lineCode: String,
        positionMs: Long,
        durationMs: Long,
        completed: Boolean = false
    ) {
        if (movieId.isBlank() || lineCode.isBlank() || episodeName.isBlank()) return
        synchronized(lock) {
            val items = parse(context)
            val old = items.firstOrNull {
                it.movieId == movieId && it.lineCode == lineCode &&
                    (episodeId.isNotBlank() && it.episodeId == episodeId ||
                     episodeId.isBlank() && it.episodeName == episodeName)
            }
            items.removeAll {
                it.movieId == movieId && it.lineCode == lineCode &&
                    (episodeId.isNotBlank() && it.episodeId == episodeId ||
                     episodeId.isBlank() && it.episodeName == episodeName)
            }
            val validDuration = maxOf(durationMs, old?.durationMs ?: 0L)
            val clamped = positionMs.coerceAtLeast(0L)
            val watched = completed || old?.watched == true ||
                (validDuration > 60_000L &&
                    clamped.toDouble() / validDuration >= 0.92)
            val updated = EpisodeWatch(movieId, movieName, episodeId, episodeName,
                lineCode, clamped, validDuration, System.currentTimeMillis(), watched)
            items.add(0, updated)
            val encoded = JSONArray()
            items.sortedByDescending { it.updatedAtMs }.take(LIMIT).forEach { item ->
                encoded.put(JSONObject().apply {
                    put("movieId", item.movieId)
                    put("movieName", item.movieName)
                    put("episodeId", item.episodeId)
                    put("episodeName", item.episodeName)
                    put("lineCode", item.lineCode)
                    put("positionMs", item.positionMs)
                    put("durationMs", item.durationMs)
                    put("updatedAtMs", item.updatedAtMs)
                    put("watched", item.watched)
                })
            }
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putString(KEY, encoded.toString()).apply()
            revision.value += 1L
        }
    }

    fun clearMovie(context: Context, movieId: String) {
        synchronized(lock) {
            val items = parse(context).filter { it.movieId != movieId }
            val array = JSONArray()
            items.forEach {
                array.put(JSONObject().apply {
                    put("movieId", it.movieId)
                    put("movieName", it.movieName)
                    put("episodeId", it.episodeId)
                    put("episodeName", it.episodeName)
                    put("lineCode", it.lineCode)
                    put("positionMs", it.positionMs)
                    put("durationMs", it.durationMs)
                    put("updatedAtMs", it.updatedAtMs)
                    put("watched", it.watched)
                })
            }
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putString(KEY, array.toString()).apply()
            revision.value += 1L
        }
    }
}
