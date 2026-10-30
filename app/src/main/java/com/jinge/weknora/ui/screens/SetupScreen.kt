package com.jinge.weknora.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jinge.weknora.ui.AppViewModel
import com.jinge.weknora.ui.theme.Brand
import com.jinge.weknora.ui.theme.brandMarkShape
import com.jinge.weknora.ui.theme.onSuccessContainer
import com.jinge.weknora.ui.theme.successContainer

/**
 * 首次启动的连接配置页 —— 用户看到的第一个界面，第一印象就在这里。
 *
 * 地址要注意：局域网直连填 http://10.0.0.4:8080 这种（App 已在 manifest 里放行
 * 明文 http）；走公网必须是 https，否则 Android 会拦。
 */
@Composable
fun SetupScreen(vm: AppViewModel) {
    Scaffold { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 20.dp, vertical = 20.dp)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 品牌头部：跟桌面图标同色的小方块 + 标题，第一眼就建立识别
            Row(
                Modifier.padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(brandMarkShape(44.dp))
                        .background(Brand.Accent)
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        "WeKnora",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "连接你的知识库",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                "填写服务地址与 API Key。Key 在 WeKnora 网页端「设置 → API 密钥」生成。" +
                "空间 ID 只有平台级 API Key 才需要（空间级 Key 已绑定空间，留空即可）。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = vm.baseUrlInput,
                onValueChange = { vm.baseUrlInput = it },
                label = { Text("服务地址") },
                placeholder = { Text("http://10.0.0.4:8080") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = vm.apiKeyInput,
                onValueChange = { vm.apiKeyInput = it },
                label = { Text("API Key") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = vm.tenantIdInput,
                onValueChange = { vm.tenantIdInput = it },
                label = { Text("空间 ID（可选）") },
                placeholder = { Text("平台级 API Key 才需要填") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { vm.saveAndTest() },
                enabled = !vm.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                if (vm.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Text("  " + vm.busyLabel)
                } else {
                    Text("测试并保存")
                }
            }

            // 结果卡片：成功绿、失败红。颜色本身就是最快的反馈
            vm.snackText?.let { msg ->
                val ok = vm.connectOk == true
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = if (ok) successContainer()
                    else MaterialTheme.colorScheme.errorContainer,
                    border = BorderStroke(
                        0.8.dp,
                        if (ok) onSuccessContainer().copy(alpha = 0.3f)
                        else MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            Modifier.size(18.dp),
                            tint = if (ok) onSuccessContainer()
                            else MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            msg,
                            Modifier.padding(start = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (ok) onSuccessContainer()
                            else MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "连接提示",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "局域网：直接用 http 内网地址，无需 https\n" +
                        "外网：需在 NAS 侧做端口映射或反向代理，并配 HTTPS\n" +
                        "连不上：先确认手机能访问该地址、端口没被防火墙拦",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
