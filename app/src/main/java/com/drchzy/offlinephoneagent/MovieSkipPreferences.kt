package com.drchzy.offlinephoneagent

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow

/** 单部影片独立保存片头和片尾时间，单位为秒；与播放线路、剧集无关。 */
data class MovieSkipTimes(val introSeconds: Int = 0, val outroSeconds: Int = 0)

object MovieSkipPreferences {
    private const val PREFS = "movie_skip_times_v1"
    private const val MAX_SECONDS = 1800
    val revision = MutableStateFlow(0L)

    fun get(context: Context, movieId: String): MovieSkipTimes {
        if (movieId.isBlank()) return MovieSkipTimes()
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return MovieSkipTimes(
            prefs.getInt("intro_$movieId", 0).coerceIn(0, MAX_SECONDS),
            prefs.getInt("outro_$movieId", 0).coerceIn(0, MAX_SECONDS)
        )
    }

    fun save(context: Context, movieId: String, introSeconds: Int, outroSeconds: Int) {
        if (movieId.isBlank()) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt("intro_$movieId", introSeconds.coerceIn(0, MAX_SECONDS))
            .putInt("outro_$movieId", outroSeconds.coerceIn(0, MAX_SECONDS))
            .apply()
        revision.value += 1L
    }
}

/** 在影片详情页和播放器复用相同的设置弹窗。 */
@Composable
fun MovieSkipSettingsDialog(movieId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val stored = remember(movieId) { MovieSkipPreferences.get(context, movieId) }
    var intro by remember(movieId) { mutableStateOf(stored.introSeconds.toString()) }
    var outro by remember(movieId) { mutableStateOf(stored.outroSeconds.toString()) }
    val introValue = intro.toIntOrNull()
    val outroValue = outro.toIntOrNull()
    val valid = introValue != null && introValue in 0..1800 &&
        outroValue != null && outroValue in 0..1800

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("片头 / 片尾跳过设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "仅作用于当前影片的所有剧集，并保存在本机。设置为 0 秒即不跳过。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp, lineHeight = 18.sp
                )
                OutlinedTextField(
                    value = intro,
                    onValueChange = { value ->
                        if (value.length <= 4 && value.all(Char::isDigit)) intro = value
                    },
                    label = { Text("片头跳过时间（秒）") },
                    singleLine = true,
                    isError = introValue == null || introValue !in 0..1800,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(0, 30, 60, 90).forEach { seconds ->
                        SuggestionChip(
                            onClick = { intro = seconds.toString() },
                            label = { Text(if (seconds == 0) "关闭" else "${seconds}秒", fontSize = 11.sp) }
                        )
                    }
                }
                OutlinedTextField(
                    value = outro,
                    onValueChange = { value ->
                        if (value.length <= 4 && value.all(Char::isDigit)) outro = value
                    },
                    label = { Text("片尾提前跳过时间（秒）") },
                    singleLine = true,
                    isError = outroValue == null || outroValue !in 0..1800,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(0, 30, 60, 90).forEach { seconds ->
                        SuggestionChip(
                            onClick = { outro = seconds.toString() },
                            label = { Text(if (seconds == 0) "关闭" else "${seconds}秒", fontSize = 11.sp) }
                        )
                    }
                }
                Text(
                    "片尾切换仅在视频已识别总时长、且确实存在下一集时触发。手动快进到片尾区间也会触发。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp, lineHeight = 16.sp
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    MovieSkipPreferences.save(context, movieId, introValue!!, outroValue!!)
                    onDismiss()
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
