// SPDX-License-Identifier: LGPL-3.0-only
package top.mobilegl.plugin.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.mobilegl.plugin.BuildConfig
import top.mobilegl.plugin.config.PluginConfig
import top.mobilegl.plugin.config.RuntimeConfigWriter
import top.mobilegl.plugin.driver.DriverPack
import top.mobilegl.plugin.driver.ExternalLibrary

@Composable
fun SettingsTab(
    config: PluginConfig,
    runtime: RuntimeConfigWriter.Status,
    drivers: List<DriverPack>,
    externalLibraries: List<ExternalLibrary>,
    onSave: (PluginConfig) -> Unit,
    onOpenConfigFile: () -> Unit,
    onOpenDrivers: () -> Unit,
    onOpenSelfTest: () -> Unit,
) {
    val context = LocalContext.current
    var backend by remember(config.backend) { mutableStateOf(config.backend) }
    var sfpewBackend by remember(config.env["SFPEW_EGL"]) { mutableStateOf(config.env["SFPEW_EGL"] ?: "libMobileGL.so") }

    fun env(key: String, fallback: String = "0"): String = config.env[key] ?: fallback
    fun setEnv(key: String, value: String) {
        val updated = LinkedHashMap(config.env)
        updated[key] = value
        onSave(config.copy(env = updated))
    }
    fun setBackend(choice: String) {
        backend = choice
        val updated = LinkedHashMap(config.env)
        updated["MOBILEGL_BACKEND_TYPE"] = choice
        onSave(config.copy(backend = choice, env = updated))
    }
    fun setSfpewBackend(choice: String) {
        sfpewBackend = choice
        setEnv("SFPEW_EGL", choice)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        BigTitle("设置")

        SectionLabel("渲染")
        InfoCard {
            ValueChoiceRow(
                title = "渲染后端",
                options = listOf("DirectVulkan", "DirectGLES"),
                selected = backend,
                onSelect = { setBackend(it) },
            )
            DividerLine()
            SwitchRow("使用 ANGLE", "Espryt 后端用 ANGLE 作为 GLES 实现", isOn(env("MOBILEGL_ESPRYT_USE_ANGLE"))) {
                setEnv("MOBILEGL_ESPRYT_USE_ANGLE", onOff(it))
            }
            DividerLine()
            if (BuildConfig.SFPEW_BUNDLED) {
                if (config.sfpewEnabled) {
                    ValueChoiceRow(
                        title = "SFPEW 后端",
                        options = listOf("libMobileGL.so", "libEGL.so"),
                        selected = sfpewBackend,
                        onSelect = { setSfpewBackend(it) },
                    )
                    DividerLine()
                }
                SwitchRow(
                    title = "启用 SFPEW 包装器",
                    subtitle = if (config.sfpewEnabled)
                        "此选项可使 MobileGL 运行在 1.16.5 及以下版本；打开后不建议启动高版本。\n" +
                            "libRendererProxy 会按配置选择 SFPEW，切换后重启游戏生效"
                    else
                        "当前配置按直连 MobileGL 使用；打开后可使 MobileGL 运行在 1.16.5 及以下版本，不建议启动高版本",
                    checked = config.sfpewEnabled,
                ) {
                    val updatedEnv = LinkedHashMap(config.env)
                    updatedEnv["SFPEW_ENABLED"] = if (it) "1" else "0"
                    onSave(config.copy(sfpewEnabled = it, env = updatedEnv))
                }
            } else {
                SwitchRow(
                    title = "SFPEW 包装器",
                    subtitle = "当前安装包是直连版，不含 libSimpleFPEWrapper.so",
                    checked = false,
                ) { }
            }
        }

        SectionLabel("配置")
        InfoCard {
            LinkRow("全部配置", "通用 / 按后端 / SFPEW 选项（原高级设置）", onOpenConfigFile)
            DividerLine()
            InfoRow("运行时配置", if (runtime.sharedWritten) "已写入共享路径" else "仅私有副本（共享路径不可写）")
            if (!hasAllFilesAccess()) {
                DividerLine()
                LinkRow("授予“所有文件访问权限”", "写共享配置、让启动器读取", {
                    val packageUri = Uri.parse("package:" + context.packageName)
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri))
                    }.onFailure {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                })
            }
        }

        SectionLabel("组件")
        InfoCard {
            LinkRow("驱动包", drivers.size.toString() + " 个 · 管理 PanVK / Vulkan ICD", onOpenDrivers)
            DividerLine()
            LinkRow("外部库", externalLibraries.size.toString() + " 个 · 导入 .so 用于自检/更新", onOpenDrivers)
            DividerLine()
            LinkRow("驱动自检", "查看完整报告与失败项说明", onOpenSelfTest)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ValueChoiceRow(title: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(option) })
            }
        }
    }
}

@Composable
private fun DividerLine() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
}

private fun isOn(value: String): Boolean = value == "1" || value.equals("true", true)
private fun onOff(value: Boolean): String = if (value) "1" else "0"

private fun hasAllFilesAccess(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true