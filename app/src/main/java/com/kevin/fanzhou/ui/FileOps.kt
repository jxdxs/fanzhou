package com.kevin.fanzhou.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.navigation.NavHostController

/** Back to the tab the user started browsing from (媒体源, or 首页 for Jellyfin), skipping every folder level. */
fun goHome(nav: NavHostController) {
    if (!nav.popBackStack("sources", false)) if (!nav.popBackStack("home", false)) nav.navigate("sources")
}

/** Top-bar navigation: back one level, plus a home button that leaves the folder tree at once. */
@Composable
fun BackAndHome(nav: NavHostController, showHome: Boolean = true) {
    Row {
        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        if (showHome) IconButton(onClick = { goHome(nav) }) { Icon(Icons.Filled.Home, "回到媒体源") }
    }
}

@Composable
fun RenameDialog(oldName: String, isDir: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    // Select the name without its extension, the way file managers do.
    val stem = if (isDir) oldName.length else oldName.lastIndexOf('.').takeIf { it > 0 } ?: oldName.length
    var value by remember { mutableStateOf(TextFieldValue(oldName, TextRange(0, stem))) }
    val name = value.text.trim()
    val bad = name.isEmpty() || name.any { it in "/\\:*?\"<>|" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value, { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = bad && name.isNotEmpty(),
                supportingText = { if (bad && name.isNotEmpty()) Text("名称不能包含 / \\ : * ? \" < > |") },
            )
        },
        confirmButton = { TextButton(enabled = !bad && name != oldName, onClick = { onConfirm(name) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun DeleteDialog(name: String, isDir: Boolean, toTrash: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isDir) "删除文件夹？" else "删除文件？") },
        text = {
            Text(
                "「$name」" + (if (isDir) "及其中所有内容" else "") +
                    if (toTrash) "将移到网盘回收站。" else "将被永久删除，无法恢复。"
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
