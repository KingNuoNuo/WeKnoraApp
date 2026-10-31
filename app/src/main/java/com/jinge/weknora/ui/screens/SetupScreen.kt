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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.jinge.weknora.ui.AppViewModel
import com.jinge.weknora.ui.theme.Brand
import com.jinge.weknora.ui.theme.brandMarkShape
import com.jinge.weknora.ui.theme.brandTopBarColor
import com.jinge.weknora.ui.theme.onSuccessContainer
import com.jinge.weknora.ui.theme.successContainer

/**
 * 首次启动的连接配置页 —— 用户看到的第一个界面，第一印象就在这里。
 *
 * 这一版的结构：**墨色头图 → 一张装三个输入框的白卡 → 一个主按钮 → 一条提示**。
 * 上一版是「一段四行说明 + 三个裸输入框 + 整行按钮」平铺到底，既没有层次，
 * 也把最该先做的事（填地址）压在了半屏文字之后。
 *
 * 地址要注意：局域网直连填 http://10.0.0.4:8080 这种（App 已在 manifest 里放行
 * 明文 http）；走公网必须是 https，否则 Android 会拦。
 */
@Composable
fun SetupScreen(vm: AppViewModel) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            // 墨色头图：和登录后的顶栏是同一块颜色，翻页时不会觉得换了 App
            Surface(color = brandTopBarColor(), contentColor = Brand.OnTopBar) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 24.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(brandMarkShape(48.dp))
                                .background(Brand.Accent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = null,
                                Modifier.size(26.dp),
                                tint = Color.White
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                "WeKnora",
                                style = MaterialTheme.typography.titleLarge,
                                color = Brand.OnTopBar
                            )
                            Text(
                                "私有部署知识库 · 安卓客户端",
                                style = MaterialTheme.typography.labelMedium,
                                color = Brand.OnTopBar.copy(alpha = Brand.ON_TOP_BAR_SECONDARY_ALPHA)
                            )
                        }
                    }
                    Text(
                        "连接你的知识库",
                        Modifier.padding(top = 22.dp),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Brand.OnTopBar
                    )
                    Text(
                        "用 WeKnora 账号登录，空间与权限跟随账号。",
                        Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Brand.OnTopBar.copy(alpha = Brand.ON_TOP_BAR_SECONDARY_ALPHA)
                    )
                }
            }

            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Field(
                            value = vm.baseUrlInput,
                            onValueChange = { vm.baseUrlInput = it },
                            label = "服务地址",
                            placeholder = "http://10.0.0.4:8080"
                        )
                        Field(
                            value = vm.emailInput,
                            onValueChange = { vm.emailInput = it },
                            label = "账号",
                            placeholder = "WeKnora 登录邮箱"
                        )
                        Field(
                            value = vm.passwordInput,
                            onValueChange = { vm.passwordInput = it },
                            label = "密码",
                            placeholder = "登录密码",
                            isPassword = true
                        )
                    }
                }

                Button(
                    onClick = { vm.saveAndTest() },
                    enabled = !vm.busy,
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    if (vm.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Text("  " + vm.busyLabel)
                    } else {
                        Text("登录", fontWeight = FontWeight.SemiBold)
                    }
                }

                // 结果卡片：成功绿、失败红。颜色本身就是最快的反馈
                vm.snackText?.let { msg ->
                    val ok = vm.connectOk == true
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (ok) successContainer()
                        else MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(13.dp),
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

                // 提示压到最后、字号收到最小：它是「出问题再来看」的内容，
                // 不该和输入框抢开屏时的注意力
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(13.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "连不上时先看这三条",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "局域网直接用 http 内网地址\n" +
                            "外网需端口映射或反代，并配 HTTPS\n" +
                            "仍失败：确认手机能访问该地址、端口未被防火墙拦",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * 表单字段。
 *
 * 全部走同一套：开始输入后标签浮到边框上，未输入时只显示占位示例。
 * 上一版标签和占位各写一遍（「空间 ID（可选）」+「平台级 API Key 才需要填」），
 * 两句话说的是同一件事，白占一行高度。
 */
@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    supporting: String? = null,
    isPassword: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        supportingText = supporting?.let {
            {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        textStyle = MaterialTheme.typography.bodyMedium,
        visualTransformation = if (isPassword) PasswordVisualTransformation()
        else VisualTransformation.None,
        keyboardOptions = if (isPassword) KeyboardOptions(keyboardType = KeyboardType.Password)
        else KeyboardOptions.Default,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
