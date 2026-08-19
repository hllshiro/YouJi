package cn.hllcloud.youji.ui.style

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.hllcloud.youji.YouJiApplication
import cn.hllcloud.youji.data.entity.WritingStyleEntity

/**
 * 写作风格管理页。对应设计文档 V3 第 2.1 节"风格选择行 +管理"链接。
 *
 * 列表展示所有风格（内置优先 + 自定义）：
 * - 内置风格（纪实 / 美化）：可编辑 promptGuideline/openingTone/closingTone，不可删除
 * - 自定义风格：可编辑全部字段，可删除
 *
 * 顶部 FAB "+新建自定义风格"打开编辑对话框；列表项右侧按钮触发编辑或删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyleManagerScreen(
    onNavigateBack: () -> Unit,
    viewModel: StyleManagerViewModel = viewModel(
        factory = StyleManagerViewModel.Factory(
            LocalContext.current.applicationContext as YouJiApplication
        )
    )
) {
    val styles by viewModel.styles.collectAsStateWithLifecycle()
    // 当前正在编辑的风格：null=未打开对话框；非 null 时打开 StyleEditorDialog
    var editingStyle by remember { mutableStateOf<WritingStyleEntity?>(null) }
    // 是否处于"新建"模式：true 时打开 StyleEditorDialog 但 editingStyle=null
    var creating by remember { mutableStateOf(false) }
    // 待删除确认的风格
    var pendingDelete by remember { mutableStateOf<WritingStyleEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("写作风格管理") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    creating = true
                    editingStyle = null
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("新建自定义风格")
            }
        }
    ) { paddingValues ->
        if (styles.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "还没有风格，点击右下角新建",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(styles, key = { it.id }) { style ->
                    StyleCard(
                        style = style,
                        onEdit = {
                            editingStyle = style
                            creating = false
                        },
                        onDelete = { pendingDelete = style }
                    )
                }
            }
        }
    }

    // 新建 / 编辑对话框
    if (creating || editingStyle != null) {
        val editing = editingStyle
        StyleEditorDialog(
            initial = editing,
            onDismiss = {
                creating = false
                editingStyle = null
            },
            onSave = { name, promptGuideline, openingTone, closingTone ->
                if (editing != null) {
                    // 编辑模式：保留原 isBuiltin 与 id
                    viewModel.updateStyle(
                        editing.copy(
                            name = name,
                            promptGuideline = promptGuideline,
                            openingTone = openingTone,
                            closingTone = closingTone
                        )
                    )
                } else {
                    // 新建模式：强制 isBuiltin=0
                    viewModel.createStyle(name, promptGuideline, openingTone, closingTone)
                }
                creating = false
                editingStyle = null
            }
        )
    }

    // 删除确认
    pendingDelete?.let { style ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除风格") },
            text = { Text("确认删除「${style.name}」？该操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteStyle(style)
                    pendingDelete = null
                }) {
                    Text("删除", color = Color(0xFFBA1A1A))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun StyleCard(
    style: WritingStyleEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (style.isBuiltin == 1) {
                        Icons.Default.Bookmark
                    } else {
                        Icons.Default.AutoAwesome
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = style.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                // 内置/自定义徽标
                val (chipColor, chipText) = if (style.isBuiltin == 1) {
                    MaterialTheme.colorScheme.tertiaryContainer to "内置"
                } else {
                    MaterialTheme.colorScheme.secondaryContainer to "自定义"
                }
                Surface(
                    color = chipColor,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = chipText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (style.isBuiltin == 1) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 提示词指导（截断显示）
            Text(
                text = "提示词：",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = style.promptGuideline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            // 开篇 / 结尾语气（如有）
            style.openingTone?.takeIf { it.isNotBlank() }?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "开篇：$it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            style.closingTone?.takeIf { it.isNotBlank() }?.let {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "结尾：$it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onEdit,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("编辑")
                }
                if (style.isBuiltin != 1) {
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onDelete,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFBA1A1A)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("删除")
                    }
                }
            }
        }
    }
}

/**
 * 风格编辑/新建对话框。新建时 [initial] 为 null；编辑时为待编辑的 entity。
 */
@Composable
private fun StyleEditorDialog(
    initial: WritingStyleEntity?,
    onDismiss: () -> Unit,
    onSave: (name: String, promptGuideline: String, openingTone: String?, closingTone: String?) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var promptGuideline by remember { mutableStateOf(initial?.promptGuideline ?: "") }
    var openingTone by remember { mutableStateOf(initial?.openingTone ?: "") }
    var closingTone by remember { mutableStateOf(initial?.closingTone ?: "") }

    val isBuiltin = initial?.isBuiltin == 1
    val canSave = name.isNotBlank() && promptGuideline.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initial == null) "新建自定义风格" else "编辑风格${if (isBuiltin) "（内置）" else ""}")
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("风格名称") },
                    enabled = !isBuiltin, // 内置风格名称不可改，避免用户混乱
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = promptGuideline,
                    onValueChange = { promptGuideline = it },
                    label = { Text("提示词指导") },
                    placeholder = { Text("描述风格要求，如：如实记录行程，不做美化") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = openingTone,
                    onValueChange = { openingTone = it },
                    label = { Text("开篇语气（可选）") },
                    placeholder = { Text("如：阳光明媚的早晨...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = closingTone,
                    onValueChange = { closingTone = it },
                    label = { Text("结尾语气（可选）") },
                    placeholder = { Text("如：期待下次再出发。") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (canSave) {
                        onSave(
                            name.trim(),
                            promptGuideline.trim(),
                            openingTone.trim().takeIf { it.isNotBlank() },
                            closingTone.trim().takeIf { it.isNotBlank() }
                        )
                    }
                },
                enabled = canSave
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
