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
import androidx.compose.material3.Switch
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
import top.mobilegl.plugin.diagnostics.DiagnosticsLog
import top.mobilegl.plugin.driver.DriverPack
import top.mobilegl.plugin.driver.DriverRepository
import top.mobilegl.plugin.driver.ExternalLibrary
import top.mobilegl.plugin.driver.ExternalLibraryRepository
import top.mobilegl.plugin.driver.TurnipDriver
import top.mobilegl.plugin.driver.TurnipGpu
import top.mobilegl.plugin.driver.TurnipGpuKind
import top.mobilegl.plugin.driver.TurnipRepository
import java.io.File

@Composable
fun DriverScreen(
    repository: DriverRepository,
    externalRepository: ExternalLibraryRepository,
    turnipRepository: TurnipRepository,
    diagnostics: DiagnosticsLog,
    drivers: List<DriverPack>,
    externalLibraries: List<ExternalLibrary>,
    turnipDrivers: List<TurnipDriver>,
    turnipGpu: TurnipGpu,
    turnipLoading: Boolean,
    configFileShared: Boolean,
    config: PluginConfig,
    onRefresh: () -> Unit,
    onActivate: (DriverPack) -> Unit,
    onClearActive: () -> Unit,
    onActivateLibrary: (ExternalLibrary?) -> Unit,
    onTurnipChange: (Boolean, String?, String?) -> Unit,
    externalCoreActive: Boolean,
    coreSupportsTurnip: Boolean,
    onUseBundledCore: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DriverPack?>(null) }
    var pendingDeleteLibrary by remember { mutableStateOf<ExternalLibrary?>(null) }
    var pendingDeleteTurnip by remember { mutableStateOf<TurnipDriver?>(null) }
    var turnipMessage by remember { mutableStateOf<String?>(null) }
    var turnipImporting by remember { mutableStateOf(false) }

    val autoTurnip = remember(turnipDrivers, turnipGpu) {
        turnipRepository.autoBundledDriver(turnipDrivers, turnipGpu)
    }
    val selectedTurnip = remember(turnipDrivers, config.turnipDriverPath, autoTurnip) {
        turnipDrivers.firstOrNull { it.path == config.turnipDriverPath } ?: autoTurnip
    }

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
    val turnipImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                turnipImporting = true
                diagnostics.record("import", "开始导入外部 Turnip: " + uri)
                val outcome = turnipRepository.import(uri)
                turnipImporting = false
                outcome.fold(
                    onSuccess = { driver ->
                        turnipMessage = "已导入 " + driver.name + "（" + driver.versionLabel + "），已设为当前驱动"
                        diagnostics.record("import", "导入成功: " + driver.name + " · " + driver.versionLabel + " · " + driver.path)
                        onTurnipChange(true, driver.path, driver.version)
                        onRefresh()
                    },
                    onFailure = {
                        val reason = it.message ?: it.toString()
                        turnipMessage = "导入失败：" + reason
                        diagnostics.record("import", "导入失败: " + reason)
                    },
                )
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

        TurnipCard(
            config = config,
            gpu = turnipGpu,
            drivers = turnipDrivers,
            loading = turnipLoading,
            configFileShared = configFileShared,
            selected = selectedTurnip,
            autoDriver = autoTurnip,
            importing = turnipImporting,
            message = turnipMessage,
            externalDir = turnipRepository.externalDir().absolutePath,
            onToggle = { enabled ->
                if (enabled) {
                    val driver = selectedTurnip ?: autoTurnip
                    onTurnipChange(true, driver?.path, driver?.version)
                } else {
                    onTurnipChange(false, null, null)
                }
            },
            onSelect = { driver -> onTurnipChange(true, driver.path, driver.version) },
            onDelete = { driver -> pendingDeleteTurnip = driver },
            onImport = { turnipImporter.launch(arrayOf("application/octet-stream", "*/*")) },
            onRefresh = onRefresh,
            externalCoreActive = externalCoreActive,
            coreSupportsTurnip = coreSupportsTurnip,
            onUseBundledCore = onUseBundledCore,
        )

        SectionCard("外部 .so（更新渲染器 / SFPEW）") {
            Text(
                "可导入官方 Action 构建的 libMobileGL.so，或 libSimpleFPEWrapper.so。会校验 arm64 ELF。" +
                    "启用后：启动游戏时由 libRendererProxy.so 读取所选库；驱动自检也用它。" +
                    "注意：官方核心不含本项目的 Turnip 加载器；只有本项目增强核心（libMobileGL-turnip-*.so）才能启用 Turnip。",
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
    pendingDeleteTurnip?.let { driver ->
        AlertDialog(
            onDismissRequest = { pendingDeleteTurnip = null },
            title = { Text("删除外部 Turnip") },
            text = { Text("确认删除 " + driver.name + "？删除后可用「内置驱动」继续。") },
            confirmButton = {
                TextButton(onClick = {
                    val wasSelected = driver.path == selectedTurnip?.path
                    val deleted = turnipRepository.delete(driver)
                    diagnostics.record("import", (if (deleted) "已删除外部 Turnip: " else "删除外部 Turnip 失败: ") + driver.name)
                    turnipMessage = if (deleted) "已删除 " + driver.name else "删除失败"
                    if (wasSelected) {
                        val fallback = autoTurnip
                        if (config.turnipEnabled && fallback != null) {
                            onTurnipChange(true, fallback.path, fallback.version)
                        } else {
                            onTurnipChange(false, null, null)
                        }
                    }
                    pendingDeleteTurnip = null
                    onRefresh()
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteTurnip = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun TurnipCard(
    config: PluginConfig,
    gpu: TurnipGpu,
    drivers: List<TurnipDriver>,
    loading: Boolean,
    configFileShared: Boolean,
    selected: TurnipDriver?,
    autoDriver: TurnipDriver?,
    importing: Boolean,
    message: String?,
    externalDir: String,
    onToggle: (Boolean) -> Unit,
    onSelect: (TurnipDriver) -> Unit,
    onDelete: (TurnipDriver) -> Unit,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
    externalCoreActive: Boolean,
    coreSupportsTurnip: Boolean,
    onUseBundledCore: () -> Unit,
) {
    SectionCard("骁龙 Turnip（Adreno · 内置驱动）") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (config.turnipEnabled) "Turnip（Mesa Freedreno）" else "系统 Vulkan",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (config.turnipEnabled) {
                        "MobileGL 启动时加载所选 Turnip；关闭后回退系统 Vulkan"
                    } else {
                        "未启用 Turnip，回退系统 Vulkan（libvulkan.so）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (config.turnipEnabled) {
                StatusPill("已启用", verdictColor("PASS"))
            } else {
                StatusPill("未启用", MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(6.dp))
        InfoRow(
            "GPU 型号",
            gpu.modelLabel + when (gpu.kind) {
                TurnipGpuKind.Adreno -> if (gpu.model == 740 && gpu.samsung) " · OneUI/三星" else ""
                TurnipGpuKind.Other -> " · 非 Adreno"
                TurnipGpuKind.Unknown -> " · 未确认"
            },
        )
        InfoRow("自动变体", gpu.autoVariantLabel)
        if (config.turnipEnabled) {
            InfoRow("当前驱动", selected?.displayName ?: if (loading) "扫描中…" else "未找到可用 Turnip 库")
            InfoRow("驱动版本", selected?.versionLabel ?: "—")
            InfoRow("驱动来源", selected?.let { it.sourceLabel + " · " + it.name } ?: "—")
            InfoRow("驱动路径", selected?.path ?: "—")
        } else {
            InfoRow("当前驱动", "未启用 · 系统 Vulkan")
            InfoRow("内置版本", selected?.versionLabel ?: if (loading) "扫描中…" else "未找到")
            InfoRow("启用后", "自动选择 " + gpu.autoVariantLabel + "（可手动切换）")
        }
        if (!configFileShared) {
            Text(
                "注意：/sdcard/MobileGL/runtime.conf 未能写入（需授予“所有文件访问权限”），" +
                    "游戏进程读不到本页开关；请先授权，否则 Turnip 不会生效。",
                style = MaterialTheme.typography.labelSmall,
                color = verdictColor("FAIL"),
            )
        }
        if (!coreSupportsTurnip) {
            Text(
                "注意：当前渲染核心是官方 26.11（内置或外部），不含本项目的 Turnip 加载器，Turnip 开关不会生效。" +
                    "要测试 Turnip：先导入本项目的增强核心 libMobileGL-turnip-*.so，" +
                    "在下方“外部 .so”里选中它，再回到本页启用 Turnip。",
                style = MaterialTheme.typography.labelSmall,
                color = verdictColor("FAIL"),
            )
        } else if (externalCoreActive) {
            Text(
                "当前使用外部增强核心（含 Turnip 加载器），Turnip 开关可以生效。",
                style = MaterialTheme.typography.labelSmall,
                color = verdictColor("PASS"),
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onUseBundledCore) { Text("切回内置核心（官方 26.11）") }
        }
        if (selected?.experimental == true) {
            Text(
                "外部库为实验性：/sdcard 不能直接 dlopen，MobileGL 会在游戏进程内复制到应用缓存后加载；" +
                    "复制或加载失败会自动回退系统 Vulkan，建议优先使用内置驱动。",
                style = MaterialTheme.typography.labelSmall,
                color = verdictColor("WARN"),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("启用骁龙 Turnip", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    "按 GPU 型号自动选择内置变体；写入 runtime.conf 的 MOBILEGL_TURNIP_ENABLED / MOBILEGL_TURNIP_PATH，重启游戏生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = config.turnipEnabled,
                enabled = !loading && drivers.isNotEmpty() && (gpu.canEnable || config.turnipEnabled) && coreSupportsTurnip,
                onCheckedChange = onToggle,
            )
        }
        if (gpu.kind != TurnipGpuKind.Adreno) {
            Text(
                gpu.detail,
                style = MaterialTheme.typography.labelSmall,
                color = if (gpu.kind == TurnipGpuKind.Other) verdictColor("FAIL") else verdictColor("WARN"),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text("驱动来源", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        if (loading) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (!loading && drivers.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "APK 内未找到内置 Turnip 变体库，也未导入外部库；Turnip 不可用，会继续使用系统 Vulkan。",
                style = MaterialTheme.typography.labelSmall,
                color = verdictColor("WARN"),
            )
        }
        drivers.forEach { driver ->
            TurnipDriverRow(
                driver = driver,
                selected = driver.path == selected?.path,
                recommended = autoDriver != null && driver.path == autoDriver.path,
                onSelect = { onSelect(driver) },
                onDelete = { onDelete(driver) },
            )
        }
        if (drivers.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "自动规则：Adreno 710/720/722 → A710/A720；750/8xx → A8xx；740 三星/OneUI → 8G2 OneUI；其他 Adreno → 通用。可手动切换内置变体。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !importing, onClick = onImport) {
                Text(if (importing) "导入中…" else "导入外部 Turnip（.so / .zip）")
            }
            OutlinedButton(onClick = onRefresh) { Text("刷新") }
        }
        Text(
            "支持 Banners-Turnip 发布的 zip（自动取其中的 libvulkan_freedreno.so），也支持单个 .so。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "开关直接写入 /sdcard/MobileGL/runtime.conf，由 MobileGL 启动时读取；" +
                "不需要在 FCL / Zalith 的版本设置里选择任何驱动，也不再依赖启动器的 driver 选项。" +
                "本 APK 内置 4 个 Turnip v27 arm64 变体（通用 / A710-A720 / A8xx / 8G2 OneUI），" +
                "启用时写入所选变体的绝对路径（nativeLibraryDir），是可靠的加载路径。" +
                "外部库放在 " + externalDir + "（会校验 arm64 ELF），为实验性功能：/sdcard 为 noexec 挂载，" +
                "MobileGL 会在游戏进程内复制到应用缓存后再加载，失败时自动回退系统 Vulkan。" +
                "检测到非 Adreno（Mali / PowerVR / Xclipse）时开关自动禁用。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TurnipDriverRow(
    driver: TurnipDriver,
    selected: Boolean,
    recommended: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(driver.displayName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                Text(
                    driver.sourceLabel + " · " + driver.versionLabel + " · " + driver.sizeMiB + " MiB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                driver.variant?.models?.let { models ->
                    Text(
                        models,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    driver.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (selected) {
                    StatusPill("使用中", verdictColor("PASS"))
                }
                if (recommended) {
                    if (selected) Spacer(Modifier.height(4.dp))
                    StatusPill("自动", MaterialTheme.colorScheme.primary)
                } else if (!selected && driver.experimental) {
                    StatusPill("实验", verdictColor("WARN"))
                }
            }
        }
        if (driver.experimental) {
            Text(
                "实验：由 MobileGL 复制到应用缓存后加载（/sdcard 不能直接 dlopen），失败回退系统 Vulkan",
                style = MaterialTheme.typography.labelSmall,
                color = verdictColor("WARN"),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSelect, enabled = !selected) {
                Text(if (selected) "已选择" else "使用")
            }
            if (!driver.bundled) {
                OutlinedButton(onClick = onDelete) { Text("删除") }
            }
        }
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
