package cn.hllcloud.youji.ui.workflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.hllcloud.youji.util.Phase
import cn.hllcloud.youji.util.PhaseStatus

/**
 * 状态徽标 Pill。对应设计 2.3 节"状态徽标"。
 */
@Composable
fun StatusBadge(
    status: WorkflowStatus,
    modifier: Modifier = Modifier
) {
    val (bg, fg) = when (status) {
        WorkflowStatus.IDLE,
        WorkflowStatus.PENDING -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        WorkflowStatus.RUNNING -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        WorkflowStatus.PAUSED -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        WorkflowStatus.COMPLETED -> Color(0xFFE6F4EA) to Color(0xFF1E7E34)
        WorkflowStatus.FAILED -> Color(0xFFFDECEC) to Color(0xFFBA1A1A)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = bg
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val icon: ImageVector = when (status) {
                WorkflowStatus.RUNNING -> Icons.Default.HourglassEmpty
                WorkflowStatus.PAUSED -> Icons.Default.Pause
                WorkflowStatus.COMPLETED -> Icons.Default.CheckCircle
                WorkflowStatus.FAILED -> Icons.Default.Error
                else -> Icons.Default.RadioButtonUnchecked
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = fg
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = status.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = fg,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * 阶段进度列表：每阶段一行，前置图标 + 阶段名 + 序号。
 * 对应设计 2.2 节"阶段前缀图标表示状态：✅ 已完成 / ⏳ 进行中 / ⬜ 待执行"。
 */
@Composable
fun PhaseProgressList(
    state: WorkflowViewModel.WorkflowUiState,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Phase.ordered().forEachIndexed { index, phase ->
            val phaseStatus = state.phaseStatuses[phase] ?: PhaseStatus.PENDING
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val (icon, tint) = phaseStatusIcon(phaseStatus)
                if (phaseStatus == PhaseStatus.RUNNING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = tint
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = phase.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (phaseStatus == PhaseStatus.PENDING)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                // 序号
                Text(
                    text = "(${index + 1}/${state.totalPhases})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Geocode 阶段额外展示反查细粒度
                if (phase == Phase.GEOCODE && phaseStatus == PhaseStatus.RUNNING &&
                    state.geocodeTotal > 0
                ) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${state.geocodeDone}/${state.geocodeTotal} 张",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun phaseStatusIcon(status: PhaseStatus): Pair<ImageVector, Color> {
    return when (status) {
        PhaseStatus.COMPLETED -> Icons.Default.CheckCircle to Color(0xFF1E7E34)
        PhaseStatus.RUNNING -> Icons.Default.HourglassEmpty to MaterialTheme.colorScheme.primary
        PhaseStatus.FAILED -> Icons.Default.Error to Color(0xFFBA1A1A)
        PhaseStatus.PENDING -> Icons.Default.RadioButtonUnchecked to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/**
 * 工作流操作按钮组。根据任务状态显示不同按钮组合，对应设计 2.3 节。
 *
 * @param status 当前任务状态
 * @param isWorking 是否正在执行操作（按钮禁用避免重复点击）
 * @param onStart 点击「开始生成」/「恢复」/「重试」
 * @param onPause 点击「暂停」
 * @param onAbandon 点击「放弃」
 * @param onEditPhotos 点击「编辑照片」
 * @param onIncrementalUpdate 点击「增量更新」（Task 12 实现具体逻辑）
 */
@Composable
fun WorkflowActionButtons(
    status: WorkflowStatus,
    isWorking: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onAbandon: () -> Unit,
    onEditPhotos: () -> Unit,
    onIncrementalUpdate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (status) {
            WorkflowStatus.PENDING -> {
                ActionButton(
                    text = "开始生成",
                    icon = Icons.Default.PlayArrow,
                    primary = true,
                    enabled = !isWorking,
                    onClick = onStart,
                    modifier = Modifier.weight(1f)
                )
                ActionButton(
                    text = "编辑照片",
                    icon = Icons.Default.Edit,
                    primary = false,
                    enabled = !isWorking,
                    onClick = onEditPhotos,
                    modifier = Modifier.weight(1f)
                )
            }
            WorkflowStatus.RUNNING -> {
                ActionButton(
                    text = "暂停",
                    icon = Icons.Default.Pause,
                    primary = false,
                    enabled = !isWorking,
                    onClick = onPause,
                    modifier = Modifier.weight(1f)
                )
            }
            WorkflowStatus.PAUSED -> {
                ActionButton(
                    text = "恢复",
                    icon = Icons.Default.PlayArrow,
                    primary = true,
                    enabled = !isWorking,
                    onClick = onStart,
                    modifier = Modifier.weight(1f)
                )
                ActionButton(
                    text = "放弃",
                    icon = Icons.Default.Delete,
                    primary = false,
                    enabled = !isWorking,
                    onClick = onAbandon,
                    modifier = Modifier.weight(1f)
                )
                ActionButton(
                    text = "编辑照片",
                    icon = Icons.Default.Edit,
                    primary = false,
                    enabled = !isWorking,
                    onClick = onEditPhotos,
                    modifier = Modifier.weight(1f)
                )
            }
            WorkflowStatus.COMPLETED -> {
                ActionButton(
                    text = "增量更新",
                    icon = Icons.Default.Update,
                    primary = false,
                    enabled = !isWorking,
                    onClick = onIncrementalUpdate,
                    modifier = Modifier.weight(1f)
                )
                ActionButton(
                    text = "编辑照片",
                    icon = Icons.Default.Edit,
                    primary = false,
                    enabled = !isWorking,
                    onClick = onEditPhotos,
                    modifier = Modifier.weight(1f)
                )
            }
            WorkflowStatus.FAILED -> {
                ActionButton(
                    text = "重试",
                    icon = Icons.Default.Refresh,
                    primary = true,
                    enabled = !isWorking,
                    onClick = onStart,
                    modifier = Modifier.weight(1f)
                )
                ActionButton(
                    text = "放弃",
                    icon = Icons.Default.Delete,
                    primary = false,
                    enabled = !isWorking,
                    onClick = onAbandon,
                    modifier = Modifier.weight(1f)
                )
            }
            WorkflowStatus.IDLE -> {
                // 无按钮
            }
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: ImageVector,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (primary) {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White
            )
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(text)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(text)
        }
    }
}

/**
 * 暂停确认对话框。对应设计 2.4 节手动暂停部分。
 *
 * @param currentPhaseProgress 当前阶段文本（如 "地理反查 3/5"）
 */
@Composable
fun PauseConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    currentPhaseProgress: String
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确定暂停任务？") },
        text = {
            Column {
                Text("暂停后任务会保留当前进度，您可以随时从主页恢复继续执行。")
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "当前进度：$currentPhaseProgress",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确定暂停", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 放弃确认对话框。放弃后任务不可恢复。
 */
@Composable
fun AbandonConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确定放弃任务？") },
        text = {
            Text("放弃后任务不可恢复，已生成的中间数据将被清理。已保存的游记本体保留。")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确定放弃", color = Color(0xFFBA1A1A))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 工作流状态区卡片：状态徽标 + 进度文本 + 错误信息 + 操作按钮。
 * 适用于详情页（仅在 travelNote.workflowTaskId != null 时显示）。
 */
@Composable
fun WorkflowStatusSection(
    state: WorkflowViewModel.WorkflowUiState,
    isWorking: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onAbandon: () -> Unit,
    onEditPhotos: () -> Unit,
    onIncrementalUpdate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(status = state.status)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = state.progressText,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // 失败时显示错误原因（对应设计 2.3 FAILED 状态）
            if (state.status == WorkflowStatus.FAILED && !state.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFDECEC)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color(0xFFBA1A1A)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = state.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFBA1A1A)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            WorkflowActionButtons(
                status = state.status,
                isWorking = isWorking,
                onStart = onStart,
                onPause = onPause,
                onAbandon = onAbandon,
                onEditPhotos = onEditPhotos,
                onIncrementalUpdate = onIncrementalUpdate
            )
        }
    }
}
