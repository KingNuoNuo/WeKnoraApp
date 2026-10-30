package com.jinge.weknora.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jinge.weknora.data.KbStats
import com.jinge.weknora.data.KnowledgeBase
import com.jinge.weknora.ui.AppViewModel

/**
 * 三列统计的列宽。固定宽度是「跨行对齐」的前提：
 * 数字右对齐到同一条竖线，一眼就能比较「哪个库文档多」。
 */
private val STAT_CELL_W = 42.dp

/** 对勾槽位也固定宽度，否则选中行会把三列整体左推，各行错开 */
private val STAT_ICON_W = 22.dp

/**
 * 设置页：切换/新建知识库、看当前连接、改连接参数。
 *
 * 知识库列表之前用 RadioButton 表示「当前选中」—— 语义是错的：
 * RadioButton 表示「从一组里选一个」，而这里是导航入口，
 * 点它等于切库。改成整行可点 + 选中行高亮 + 右侧对勾。
 *
 * 每个库右侧再挂三列文档统计（总数 / 排队 / 处理），
 * 不用点进去、也不用切库就能看出「哪个库还压着一堆没解析完」。
 */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    var newKbName by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        KbSectionHeader(vm)

        vm.knowledgeBases.forEach { kb ->
            KbRow(
                kb = kb,
                stats = vm.kbStats[kb.id],
                selected = vm.currentKb?.id == kb.id,
                onClick = { vm.selectKb(kb) }
            )
        }

        SectionTitle("新建知识库", Modifier.padding(top = 8.dp))

        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = newKbName,
                    onValueChange = { newKbName = it },
                    label = { Text("名称") },
                    placeholder = { Text("例如：交换机手册") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
                Button(
                    onClick = {
                        vm.createKb(newKbName)
                        newKbName = ""
                    },
                    enabled = newKbName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("创建并切换") }
            }
        }

        SectionTitle("连接信息", Modifier.padding(top = 8.dp))

        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InfoLine("服务地址", vm.baseUrlInput)
                InfoLine("API Key", "••••••（已保存，不可见）")
                if (vm.tenantIdInput.isNotBlank()) {
                    InfoLine("空间 ID", vm.tenantIdInput)
                }
                InfoLine("当前文档", "${vm.docs.size} 篇")
                InfoLine("会话", "${vm.sessions.size} 个")
            }
        }

        OutlinedButton(
            onClick = { vm.editConfig() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) { Text("修改连接设置") }
    }
}

/**
 * 「知识库」区块标题 + 统计刷新入口。
 *
 * 统计要按库拉一遍文档，属于重活，不做定时轮询：只在知识库列表加载完、
 * 以及上传/删除/重建解析之后自动跑一次。服务端解析是异步的，状态过一会儿
 * 才会翻，所以再给一个手动刷新的入口（统计期间标题右侧转圈）。
 */
@Composable
private fun KbSectionHeader(vm: AppViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "知识库",
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (vm.kbStatsLoading) {
            CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp)
        }
        // 用 Box + clickable 而不是 IconButton：M3 的 IconButton 有 48dp 的最小
        // 触控尺寸，会把这一行撑得比标题高出一大截
        Box(
            Modifier
                .padding(start = 8.dp)
                .size(26.dp)
                .clip(CircleShape)
                .clickable { vm.loadKbStats() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "刷新文档统计",
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(start = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * 知识库行：左「名称 + 说明」，右「总数 / 排队 / 处理」三列，最右对勾槽位。
 *
 * 对齐做法：三列都是固定宽度 + 内部右对齐，对勾槽位恒占 [STAT_ICON_W]（未选中留空）。
 * 这样无论选中与否、数字是几位，三个数字都各自落在一条竖直线上 ——
 * 之前对勾是「选中才出现」，会让选中行整体左移，各行的数字根本对不齐。
 */
@Composable
private fun KbRow(
    kb: KnowledgeBase,
    stats: KbStats?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            0.8.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    kb.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    kb.description.ifBlank { "ID ${kb.id.take(8)}" },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected)
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            // 总数：中性色，纯信息
            StatCell("总数", stats?.total, MaterialTheme.colorScheme.onSurface)
            // 排队：比总数淡一档的灰 —— 「等着」是惰性状态，不该抢「正在跑」的注意力
            StatCell("排队", stats?.queued, MaterialTheme.colorScheme.onSurfaceVariant)
            // 处理中：蓝，与文档页「解析中」同一套语义色
            StatCell("处理", stats?.processing, MaterialTheme.colorScheme.tertiary)

            Box(
                Modifier.size(width = STAT_ICON_W, height = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "当前知识库",
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * 一列统计：数字在上、说明在下，整列右对齐。
 *
 * 还没统计出来（或正在统计）时显示破折号而不是 0 ——
 * 「确实是 0 篇」和「还不知道」是两件事，用同一个 0 表示会让人以为文档丢了。
 * 同理，值为 0 时数字压成灰色：0 是常态，不该跟「有 361 篇」抢同一个亮度。
 */
@Composable
private fun StatCell(label: String, value: Int?, accent: Color) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    Column(
        Modifier.width(STAT_CELL_W),
        horizontalAlignment = Alignment.End
    ) {
        Text(
            text = value?.toString() ?: "—",
            style = MaterialTheme.typography.titleSmall,
            color = if (value == null || value == 0) muted else accent,
            maxLines = 1,
            softWrap = false
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            Modifier.padding(end = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
