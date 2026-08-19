package cn.hllcloud.youji.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 首页"待恢复任务"区块。对应设计 V3 第 2.4 节。
 *
 * 仅在 [tasks] 非空时由调用方渲染；本组件本身不判空。
 *
 * 列表合并 status=paused（待恢复）与 status=failed（失败）两类任务，
 * failed 任务排在前面以优先提示用户。两类任务的差异：
 * - paused：标题"待恢复任务 (N)"，按钮文案「恢复」+「放弃」
 * - failed：标题追加"+ M 个失败"，错误信息始终展示且配色加深，
 *   按钮文案「重试」+「放弃」（点击「重试」由调用方走与 resume 相同路径，
 *   引擎从失败阶段续传）
 *
 * 「恢复全部」仅触发 paused 任务（避免静默重试已知失败的任务）；
 * failed 任务需用户主动点击「重试」。
 *
 * @param tasks 待处理任务 UI 模型列表（paused + failed 合并）
 * @param onResumeAll 点击"恢复全部"，调用方负责跳转进度页观察第一个任务
 * @param onResume 单个任务恢复 / 重试（failed 任务也走此回调，引擎会从失败阶段续传）
 * @param onAbandon 单个任务放弃
 */
@Composable
fun PendingTasksSection(
    tasks: List<HomeViewModel.PendingTaskUiModel>,
    onResumeAll: () -> Unit,
    onResume: (Long) -> Unit,
    onAbandon: (Long) -> Unit
) {
    val pausedCount = tasks.count { it.status == "paused" }
    val failedCount = tasks.count { it.status == "failed" }
    val title = buildString {
        append("待恢复任务 ($pausedCount)")
        if (failedCount > 0) append(" · 失败 ($failedCount)")
    }
    // 有 failed 任务时整体卡片切到错误色容器，提示用户优先处理
    val containerColor = if (failedCount > 0)
        MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.secondaryContainer
    val onContainerColor = if (failedCount > 0)
        MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSecondaryContainer

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (failedCount > 0)
                            Icons.Default.ErrorOutline else Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = onContainerColor
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = onContainerColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                // 列表仅含 failed 时隐藏"恢复全部"（无可自动恢复的任务）
                if (pausedCount > 0) {
                    TextButton(onClick = onResumeAll) {
                        Text("恢复全部")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            tasks.forEachIndexed { index, task ->
                if (index > 0) {
                    Divider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
                PendingTaskItem(
                    task = task,
                    onContainerColor = onContainerColor,
                    onResume = { onResume(task.taskId) },
                    onAbandon = { onAbandon(task.taskId) }
                )
            }
        }
    }
}

@Composable
private fun PendingTaskItem(
    task: HomeViewModel.PendingTaskUiModel,
    onContainerColor: Color,
    onResume: () -> Unit,
    onAbandon: () -> Unit
) {
    val isFailed = task.status == "failed"
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = onContainerColor,
                modifier = Modifier.weight(1f)
            )
            // 阶段进度 (N+1/M)：1-based 展示更直观
            Text(
                text = "(${task.currentIndex + 1}/${task.totalPhases}) ${task.currentPhaseText}",
                style = MaterialTheme.typography.labelMedium,
                color = onContainerColor
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "共 ${task.photoCount} 张照片 · 创建于 ${task.createdAtText}",
            style = MaterialTheme.typography.labelSmall,
            color = onContainerColor.copy(alpha = 0.7f)
        )

        // 错误信息：failed 任务始终展示错误原因摘要（满足"主页 failed 任务错误摘要"要求），
        // paused 任务一般无 errorMessage，跳过
        task.errorMessage?.takeIf { it.isNotBlank() }?.let { msg ->
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                color = if (isFailed) Color(0x22BA1A1A) else Color(0x14BA1A1A),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFBA1A1A),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onAbandon,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("放弃")
            }
            Spacer(modifier = Modifier.size(8.dp))
            TextButton(
                onClick = onResume,
                shape = RoundedCornerShape(8.dp)
            ) {
                // failed 任务按钮文案「重试」，paused 任务文案「恢复」
                Text(if (isFailed) "重试" else "恢复")
            }
        }
    }
}
