package com.dafei.voicenotes.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dafei.voicenotes.VoiceNotesApp
import com.dafei.voicenotes.ui.common.diaryPreview
import com.dafei.voicenotes.ui.common.diaryTotalSize
import com.dafei.voicenotes.ui.common.formatDateTime
import com.dafei.voicenotes.ui.common.formatFileSize

@Composable
fun DiaryListScreen(app: VoiceNotesApp, onOpenDetail: (Long) -> Unit) {
    val vm: DiaryListViewModel = viewModel(factory = DiaryListViewModel.factory(app))
    val entries by vm.entries.collectAsState()
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("记事列表", style = MaterialTheme.typography.titleLarge)
            if (selectionMode) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        if (selected.isNotEmpty()) showDeleteDialog = true
                    }) {
                        Text("删除(${selected.size})")
                    }
                    TextButton(onClick = {
                        selectionMode = false
                        selected = emptySet()
                    }) {
                        Text("取消")
                    }
                }
            } else {
                IconButton(onClick = { selectionMode = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "多选删除")
                }
            }
        }

        if (entries.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("还没有记事，去录制页说点什么吧")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.id }) { entry ->
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (selectionMode) {
                                        selected = if (entry.id in selected) {
                                            selected - entry.id
                                        } else {
                                            selected + entry.id
                                        }
                                    } else {
                                        onOpenDetail(entry.id)
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selectionMode) {
                                Checkbox(
                                    checked = entry.id in selected,
                                    onCheckedChange = {
                                        selected = if (it) selected + entry.id else selected - entry.id
                                    }
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(diaryPreview(entry), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    formatDateTime(entry.createdAt),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    formatFileSize(diaryTotalSize(entry)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.fillMaxWidth(),
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除") },
            text = { Text("将永久删除选中的 ${selected.size} 篇记事，此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteEntries(selected.toList())
                    selected = emptySet()
                    selectionMode = false
                    showDeleteDialog = false
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }
}
