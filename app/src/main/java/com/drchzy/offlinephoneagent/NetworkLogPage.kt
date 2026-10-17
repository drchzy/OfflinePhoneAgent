package com.drchzy.offlinephoneagent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun NetworkLogPage() {
    val context = LocalContext.current
    val enabled by MovieHttpLog.enabled.collectAsState()
    val entries by MovieHttpLog.entries.collectAsState()
    var keyword by remember { mutableStateOf("") }
    var opened by remember { mutableStateOf<Long?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    val filtered = remember(entries, keyword) {
        entries.filter { entry ->
            keyword.isBlank() || listOf(entry.domain, entry.method, entry.label,
                entry.request, entry.response, entry.status?.toString().orEmpty())
                .any { it.contains(keyword.trim(), ignoreCase = true) }
        }
    }
    Column(Modifier.fillMaxSize().padding(start = 17.dp, end = 17.dp, top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NETWORK CONSOLE", fontSize = 10.sp, letterSpacing = 1.7.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("请求日志", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { MovieHttpLog.clear(); opened = null }) {
                Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text("清空")
            }
        }
        Surface(shape = RoundedCornerShape(17.dp),
            color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (enabled) "正在采集" else "采集已关闭",
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("仅本机内存，最近 300 条 · 公开分享前检查敏感参数",
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = {
                    MovieHttpLog.setEnabled(context, it)
                })
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = keyword, onValueChange = { keyword = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("搜索域名 / API / 状态码 / 内容") },
            leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(15.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text("共 " + filtered.size + " 条 · 默认倒序", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
            items(filtered, key = { it.id }) { entry ->
                Surface(
                    shape = RoundedCornerShape(15.dp),
                    color = MaterialTheme.colorScheme.surface,
                    onClick = { opened = if (opened == entry.id) null else entry.id }
                ) {
                    Column(Modifier.fillMaxWidth().padding(13.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.method, fontSize = 11.sp,
                                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(entry.domain, Modifier.weight(1f), fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(entry.status?.toString() ?: "ERR", fontSize = 11.sp,
                                color = if (entry.status in 200..299)
                                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            Icon(if (opened == entry.id) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                null, Modifier.size(18.dp))
                        }
                        Text(entry.at + " · " + entry.label, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (opened == entry.id) {
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider()
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                TextButton(onClick = { logCopy(context, "请求", entry.request) }) {
                                    Text("复制请求", fontSize = 11.sp)
                                }
                                TextButton(onClick = { logCopy(context, "返回", entry.response) }) {
                                    Text("复制返回", fontSize = 11.sp)
                                }
                                TextButton(onClick = { logCopy(context, "完整报文",
                                    entry.request + "\n--- RESPONSE ---\n" + entry.response) }) {
                                    Text("复制完整", fontSize = 11.sp)
                                }
                            }
                            TabRow(selectedTabIndex = tab) {
                                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("请求") })
                                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("返回") })
                            }
                            Surface(shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.background) {
                                SelectionContainer {
                                    Text(
                                        if (tab == 0) entry.request else entry.response,
                                        Modifier.fillMaxWidth().padding(12.dp),
                                        fontSize = 11.sp, lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
private fun logCopy(context: Context, title: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(title, value))
    Toast.makeText(context, "已复制" + title, Toast.LENGTH_SHORT).show()
}
