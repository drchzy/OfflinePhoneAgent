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
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 仅在 Movie 页面显示的底部调试入口。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieHttpFloatingPanel() {
    val history by MovieHttpLog.entries.collectAsState()
    var opened by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var tab by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val latest = history.firstOrNull()
    val selected = history.firstOrNull { it.id == selectedId } ?: latest

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Surface(
            onClick = { opened = true },
            modifier = Modifier.padding(end = 17.dp, bottom = 16.dp),
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 8.dp
        ) {
            Row(
                Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Icon(Icons.Outlined.BugReport, null, Modifier.size(18.dp))
                Text(
                    when {
                        latest == null -> "请求日志"
                        latest.status == null -> "请求失败"
                        else -> "HTTP " + latest.status
                    },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                if (history.isNotEmpty()) {
                    Text(history.size.toString(), fontSize = 11.sp)
                }
            }
        }
    }

    if (opened) {
        ModalBottomSheet(
            onDismissRequest = { opened = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(0.76f)
                    .padding(start = 19.dp, end = 19.dp, bottom = 18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("NETWORK INSPECTOR", fontSize = 10.sp, letterSpacing = 1.8.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("请求与返回报文", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = {
                        MovieHttpLog.clear()
                        selectedId = null
                    }) { Text("清空") }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "仅本机展示最近 12 条 Movie 请求；复制的内容可能含有可重放的加密参数，请勿公开分享。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(10.dp))

                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    history.forEach { item ->
                        FilterChip(
                            selected = item.id == selected?.id,
                            onClick = { selectedId = item.id },
                            label = {
                                Text(
                                    item.label.substringAfterLast("/").take(18) + " · " +
                                        (item.status?.toString() ?: "ERR"),
                                    maxLines = 1,
                                    fontSize = 11.sp
                                )
                            }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                if (selected == null) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("暂无请求记录，先返回 Movie 首页点击“刷新”",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TabRow(
                            selectedTabIndex = tab,
                            modifier = Modifier.weight(1f),
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            Tab(selected = tab == 0, onClick = { tab = 0 },
                                text = { Text("请求") })
                            Tab(selected = tab == 1, onClick = { tab = 1 },
                                text = { Text("响应") })
                        }
                    }
                    Spacer(Modifier.height(9.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { copyMovieDebug(context, "请求报文", selected.request) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Outlined.ContentCopy, null, Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("复制请求", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { copyMovieDebug(context, "响应报文", selected.response) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Outlined.ContentCopy, null, Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("复制响应", fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            copyMovieDebug(context, "请求与响应",
                                selected.request + "\n\n---------- RESPONSE ----------\n" + selected.response)
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("复制完整记录", fontSize = 12.sp) }
                    Surface(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        shape = RoundedCornerShape(15.dp),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(13.dp)) {
                            SelectionContainer {
                                Text(
                                    if (tab == 0) selected.request else selected.response,
                                    fontSize = 11.sp,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun copyMovieDebug(context: Context, label: String, content: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText(label, content))
    Toast.makeText(context, "已复制" + label, Toast.LENGTH_SHORT).show()
}
