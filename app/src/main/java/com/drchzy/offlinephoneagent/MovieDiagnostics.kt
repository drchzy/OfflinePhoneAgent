package com.drchzy.offlinephoneagent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 文件中的备用 URL 仅作为手动请求预设；不会自动轮询或向结果地址提交数据。 */
private val movieConfigSources = listOf(
    "主配置" to "https://app-site.ecoliving168.com/domain_v5.json",
    "线路 1" to "https://beijing2.xstore.qihu.com/aiwork-feedback-meida/yyjnfnl2026-23696738-7c3c-4681-8e96-01290b6f2898.png",
    "线路 2" to "https://mt4hrewg.s3.ap-northeast-1.amazonaws.com/assets/assets/textb4.txt",
    "线路 3" to "https://img01.sogoucdn.com/net/a/04/link?appid=100520145&url=https://beijing2.xstore.qihu.com/aiwork-feedback-meida/yyjnfnl2026-23696738-7c3c-4681-8e96-01290b6f2898.png",
    "线路 4" to "https://6j3hrbb3g.s3.ap-northeast-3.amazonaws.com/files/config/config_v6.txt",
    "线路 5" to "https://bfns.zhaomi.cn/web/38c945b5efcf76b4c1b9.png",
    "线路 6" to "https://ube3bd.hcts2.com/assets/assets/textb4.txt",
    "线路 7" to "https://u83tghwe.hcts3.com/files/config/config_v6.txt"
)

@Composable
fun MovieDiagnosticsDialog(
    host: MainActivity,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var requestUrl by remember { mutableStateOf(movieConfigSources.first().second) }
    var result by remember { mutableStateOf<ProbeResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.86f),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 19.dp, vertical = 17.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("API LAB", fontSize = 11.sp, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(5.dp))
                        Text("接口调试", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭")
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("配置源", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    movieConfigSources.forEach { (label, uri) ->
                        FilterChip(
                            selected = requestUrl == uri,
                            onClick = { requestUrl = uri; result = null },
                            label = { Text(label, fontSize = 12.sp) },
                            enabled = !loading
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = requestUrl,
                    onValueChange = { requestUrl = it; result = null },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    label = { Text("请求 URL（可修改）") },
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                    maxLines = 3,
                    enabled = !loading
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (loading) return@Button
                        loading = true
                        result = null
                        val url = requestUrl.trim()
                        scope.launch {
                            val probe = try {
                                withContext(Dispatchers.IO) { MovieApi(context.applicationContext).probeUrl(url) }
                            } catch (e: Exception) {
                                ProbeResult(url, "", -1, 0, "", "", null, e.message ?: "URL 不正确")
                            }
                            result = probe
                            loading = false
                        }
                    },
                    enabled = !loading && requestUrl.trim().startsWith("http"),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Icon(Icons.Outlined.Send, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(9.dp))
                    Text(if (loading) "请求中…" else "发送 GET 请求")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        if (loading) return@OutlinedButton
                        loading = true
                        result = null
                        val url = requestUrl.trim()
                        scope.launch {
                            result = try {
                                val decoded = withContext(Dispatchers.IO) { MovieConfigDecoder.fetch(url) }
                                ProbeResult(
                                    decoded.url,
                                    decoded.finalUrl,
                                    decoded.code,
                                    decoded.elapsedMs,
                                    decoded.contentType,
                                    decoded.responseText,
                                    decoded.apiService,
                                    decoded.error
                                )
                            } catch (e: Exception) {
                                ProbeResult(url, "", -1, 0, "", "", null, e.message ?: "图片解码失败")
                            } finally {
                                loading = false
                            }
                        }
                    },
                    enabled = !loading && requestUrl.trim().startsWith("http"),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(if (loading) "正在解码…" else "解码图片配置")
                }
                Spacer(Modifier.height(15.dp))
                HorizontalDivider()
                Spacer(Modifier.height(11.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("返回结果", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(
                        enabled = result != null,
                        onClick = {
                            val r = result ?: return@TextButton
                            val formatted = formatProbeResult(r)
                            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            manager.setPrimaryClip(ClipData.newPlainText("Movie API response", formatted))
                            Toast.makeText(context, "返回结果已复制", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("复制")
                    }
                }

                val r = result
                if (r != null) {
                    Text(
                        "HTTP " + (if (r.statusCode < 0) "连接失败" else r.statusCode.toString()) +
                            "   ·   " + r.elapsedMillis + " ms",
                        fontSize = 12.sp,
                        color = if (r.statusCode in 200..299) accent else MaterialTheme.colorScheme.error
                    )
                    if (r.contentType.isNotBlank()) {
                        Text(r.contentType, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!r.apiService.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                host.saveMovieSettings(host.getMovieSettings().copy(baseUrl = r.apiService))
                                Toast.makeText(context, "已将 api_service 保存为 API BaseURL", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("使用返回的 api_service", maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(9.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    color = MaterialTheme.colorScheme.background,
                    shape = RoundedCornerShape(15.dp)
                ) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(13.dp)) {
                        SelectionContainer {
                            Text(
                                if (r == null) "选择配置源或输入 URL，点击“发送 GET 请求”。\n\n只执行手动 GET；最多显示 64 KiB。不会附带 Codex Token、Movie 授权信息或自动请求备用线路。"
                                else formatProbeResult(r),
                                fontSize = 11.sp,
                                lineHeight = 17.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatProbeResult(r: ProbeResult): String = buildString {
    append("请求: ").append(r.requestUrl)
    append("\n最终地址: ").append(r.finalUrl.ifEmpty { "未获取" })
    append("\nHTTP: ").append(if (r.statusCode < 0) "连接失败" else r.statusCode)
    append("\n耗时: ").append(r.elapsedMillis).append(" ms")
    if (r.contentType.isNotBlank()) append("\n类型: ").append(r.contentType)
    if (r.error != null) append("\n提示: ").append(r.error)
    if (r.apiService != null) append("\n解析出 api_service: ").append(r.apiService)
    append("\n\n")
    append(r.responseBody.ifBlank { "(无响应内容)" })
}
