// SPDX-License-Identifier: LGPL-3.0-only
package top.mobilegl.plugin.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.mobilegl.plugin.config.PluginConfig
import top.mobilegl.plugin.driver.DriverPack
import top.mobilegl.plugin.driver.DriverRepository
import top.mobilegl.plugin.driver.ExternalLibrary
import top.mobilegl.plugin.driver.ExternalLibraryRepository
import java.io.File

@Composable
fun DriverScreen(
    repository: DriverRepository,
    externalRepository: ExternalLibraryRepository,
    drivers: List<DriverPack>,
    externalLibraries: List<ExternalLibrary>,
    config: PluginConfig,
    onRefresh: () -> Unit,
    onActivate: (DriverPack) -> Unit,
    onClearActive: () -> Unit,
    onActivateLibrary: (ExternalLibrary?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DriverPack?>(null) }
    var pendingDeleteLibrary by remember { mutableStateOf<ExternalLibrary?>(null) }

    val driverImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                importing = true
                val outcome = repository.install(uri)
                importing = false
                message = outcome.fold(
                    onSuccess = { "已导入驱动包 " + it.name + "（" + it.version + "）" },
                    onFailure = { "导入失败：" + (it.message ?: it.toString()) },
                )
                onRefresh()
            }
        }
    }
    val libraryImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                importing = true
                val outcome = externalRepository.import(uri)
                importing = false
                message = outcome.fold(
                    onSuccess = { "已导入外部库 " + it.name + "（" + (it.size / 1024 / 1024) + " MiB）" },
                    onFailure = { "导入失败：" + (it.message ?: it.toString()) },
                )
                onRefresh()
            }
        }
    }

    val sfpewBundled = remember {
        runCatching {
            File(context.applicationInfo.nativeLibraryDir, "libSfpew_real.so").isFile ||
                File(context.applicationInfo.nativeLibraryDir, "libSimpleFPEWrapper.so").isFile
        }.getOrDefault(false)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("渲染器组件") {
            Text(
                "本 APK 的渲染链：启动器 → libRendererProxy.so →（SFPEW 包装器 或 直连）→ libMobileGL.so。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            InfoRow("SFPEW 包装器", if (sfpewBundled) "已内置" else "未内置（可用外部库页导入自检）")
            InfoRow("后端库", "libMobileGL.so（APK 内置）")
            Text(
                "说明：启用外部库后，libRendererProxy.so 会在启动游戏时按配置加载（memfd），驱动自检也用它。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("外部 .so（更新渲染器 / SFPEW）") {
            Text(
                "可导入官方 Action 构建的 libMobileGL.so，或 libSimpleFPEWrapper.so。会校验 arm64 ELF。" +
                    "启用后：启动游戏时由 libRendererProxy.so 读取所选库；驱动自检也用它。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !importing,
                    onClick = { libraryImporter.launch(arrayOf("application/octet-stream", "*/*")) },
                ) { Text(if (importing) "导入中…" else "导入 .so") }
                OutlinedButton(onClick = onRefresh) { Text("刷新") }
            }
            if (importing) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            if (externalLibraries.isEmpty()) {
                Text("还没有导入外部库。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                externalLibraries.forEach { library ->
                    ExternalLibraryRow(
                        library = library,
                        selected = (library.sharedFile?.absolutePath ?: library.file.absolutePath) == config.activeRendererLibPath,
                        onActivate = {
                            onActivateLibrary(
                                if ((library.sharedFile?.absolutePath ?: library.file.absolutePath) == config.activeRendererLibPath) null else library
                            )
                        },
                        onDelete = { pendingDeleteLibrary = library },
                    )
                }
            }
        }

        SectionCard("驱动包（PanVK / Vulkan ICD · 未完善）") {
            Text(
                "带 driver.json 的 zip：安装、校验、生成 ICD。功能尚未完善，PanVK loader 接入前不会影响游戏。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { driverImporter.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                    Text("导入驱动包")
                }
                OutlinedButton(onClick = onRefresh) { Text("刷新") }
            }
        }
        drivers.forEach { pack ->
            DriverCard(
                pack = pack,
                active = pack.id == config.activeDriverId,
                repository = repository,
                onActivate = { onActivate(pack) },
                onClearActive = onClearActive,
                onDelete = { pendingDelete = pack },
                onMessage = { message = it },
                onRefresh = onRefresh,
            )
        }
    }

    pendingDelete?.let { pack ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除驱动") },
            text = { Text("确认删除 " + pack.name + "？") },
            confirmButton = {
                TextButton(onClick = {
                    message = if (repository.delete(pack)) "已删除 " + pack.name else "删除失败"
                    pendingDelete = null
                    onRefresh()
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
    pendingDeleteLibrary?.let { library ->
        AlertDialog(
            onDismissRequest = { pendingDeleteLibrary = null },
            title = { Text("删除外部库") },
            text = { Text("确认删除 " + library.name + "？") },
            confirmButton = {
                TextButton(onClick = {
                    if ((library.sharedFile?.absolutePath ?: library.file.absolutePath) == config.activeRendererLibPath) onActivateLibrary(null)
                    message = if (externalRepository.delete(library)) "已删除 " + library.name else "删除失败"
                    pendingDeleteLibrary = null
                    onRefresh()
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteLibrary = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ExternalLibraryRow(
    library: ExternalLibrary,
    selected: Boolean,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
) {
    val kind = when {
        library.isSfpew() -> "SFPEW 包装器"
        library.isRenderer() -> "MobileGL 渲染器"
        else -> "其他 .so"
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(library.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                Text(
                    kind + " · " + (library.size / 1024 / 1024) + " MiB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "导入于 " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(library.importedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) StatusPill("已启用", verdictColor("PASS"))
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onActivate) { Text(if (selected) "取消启用" else "启用") }
            OutlinedButton(onClick = onDelete) { Text("删除") }
        }
    }
}

@Composable
private fun DriverCard(
    pack: DriverPack,
    active: Boolean,
    repository: DriverRepository,
    onActivate: () -> Unit,
    onClearActive: () -> Unit,
    onDelete: () -> Unit,
    onMessage: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    SectionCard(pack.name) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(pack.version, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (active) StatusPill("已激活", verdictColor("PASS")) else StatusPill("未激活", MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        InfoRow("ABI", pack.abi)
        InfoRow("类型", pack.kind)
        if (pack.mesaVersion.isNotBlank()) InfoRow("Mesa", pack.mesaVersion)
        InfoRow("路径", pack.dir.absolutePath)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (active) OutlinedButton(onClick = onClearActive) { Text("取消激活") } else Button(onClick = onActivate) { Text("激活") }
            if (pack.isVulkanIcd()) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val file = withContext(Dispatchers.IO) { runCatching { repository.generateIcd(pack) }.getOrNull() }
                        onMessage(if (file != null) "ICD 已生成：" + file.absolutePath else "生成 ICD 失败（缺少模板或库）")
                    }
                }) { Text("生成 ICD") }
            }
            OutlinedButton(onClick = onDelete) { Text("删除") }
        }
    }
}