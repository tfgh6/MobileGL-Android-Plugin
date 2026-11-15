// SPDX-License-Identifier: LGPL-3.0-only
package top.mobilegl.plugin.ui

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.core.content.FileProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.mobilegl.plugin.BuildConfig
import top.mobilegl.plugin.config.ConfigStore
import top.mobilegl.plugin.config.PluginConfig
import top.mobilegl.plugin.config.RuntimeConfigWriter
import top.mobilegl.plugin.diagnostics.DiagnosticsLog
import top.mobilegl.plugin.diagnostics.DiagnosticsSnapshot
import top.mobilegl.plugin.driver.DriverPack
import top.mobilegl.plugin.driver.DriverRepository
import top.mobilegl.plugin.driver.ExternalLibrary
import top.mobilegl.plugin.driver.ExternalLibraryRepository
import top.mobilegl.plugin.driver.TurnipDriver
import top.mobilegl.plugin.driver.TurnipGpuKind
import top.mobilegl.plugin.driver.TurnipRepository
import top.mobilegl.plugin.driver.scanMobileGlVersion
import top.mobilegl.plugin.selftest.BackendReport
import top.mobilegl.plugin.selftest.GlInfoProbe
import top.mobilegl.plugin.selftest.SelfTestRepository
import top.mobilegl.plugin.selftest.SelfTestResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BLUE = Color(0xFF2F6BFF)
private val ORANGE = Color(0xFFF57C00)
private val PASS = Color(0xFF1A9C57)
private val WARN = Color(0xFFB26A00)
private val FAIL = Color(0xFFD1343C)
private val LIGHT_BLUE_CARD = Color(0xFFEEF2FF)

private sealed interface Dest {
    data object Home : Dest
    data object Settings : Dest
    data object Info : Dest
    data object Drivers : Dest
    data object ConfigFile : Dest
    data object SelfTest : Dest
}

private data class Tab(val label: String, val icon: ImageVector, val dest: Dest)

private val TABS = listOf(
    Tab("首页", Icons.Filled.Home, Dest.Home),
    Tab("设置", Icons.Filled.Settings, Dest.Settings),
    Tab("信息", Icons.Filled.Info, Dest.Info),
)

/** Same transition as MobileGlues 2.0 (MaterialApp.pageTransition). */
private fun pageTransition(from: Dest, to: Dest): ContentTransform {
    val fadeInSpec = tween<Float>(durationMillis = 260, easing = FastOutSlowInEasing)
    val fadeOutSpec = tween<Float>(durationMillis = 180, easing = FastOutSlowInEasing)
    val slide = tween<IntOffset>(durationMillis = 320, easing = FastOutSlowInEasing)

    val tabOrdinal = { destination: Dest ->
        when (destination) {
            Dest.Home -> 0
            Dest.Settings -> 1
            Dest.Info -> 2
            else -> -1
        }
    }
    val fromOrdinal = tabOrdinal(from)
    val toOrdinal = tabOrdinal(to)
    val (enterFraction, exitFraction) = when {
        toOrdinal < 0 -> 3 to -10
        fromOrdinal < 0 -> -10 to 3
        fromOrdinal >= 0 && toOrdinal > fromOrdinal -> 6 to -6
        else -> -6 to 6
    }

    return ContentTransform(
        targetContentEnter = slideInHorizontally(slide) { it / enterFraction } + fadeIn(fadeInSpec),
        initialContentExit = slideOutHorizontally(slide) { it / exitFraction } + fadeOut(fadeOutSpec),
        sizeTransform = SizeTransform(clip = false),
    )
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MobileGlPluginApp() {
    MobileGlTheme {
        val context = LocalContext.current
        val store = remember { ConfigStore(context) }
        val driverRepository = remember { DriverRepository(context) }
        val externalRepository = remember { ExternalLibraryRepository(context) }
        val selfTestRepository = remember { SelfTestRepository(context) }
        val runtimeWriter = remember { RuntimeConfigWriter(context) }
        val turnipRepository = remember { TurnipRepository(context) }
        val scope = rememberCoroutineScope()

        var config by remember { mutableStateOf(store.load()) }
        val diagnostics = remember { DiagnosticsLog(context).apply { setEnabled(config.diagnosticsEnabled) } }
        var drivers by remember { mutableStateOf(driverRepository.list()) }
        var externalLibraries by remember { mutableStateOf(externalRepository.list()) }
        var runtimeStatus by remember { mutableStateOf(runtimeWriter.write(config)) }
        var dest by remember { mutableStateOf<Dest>(Dest.Home) }
        var lastTab by remember { mutableIntStateOf(0) }
        var report by remember { mutableStateOf(selfTestRepository.cached()) }
        var probing by remember { mutableStateOf(report == null) }
        var glInfo by remember { mutableStateOf<GlInfoProbe.Info?>(null) }
        var turnipDrivers by remember { mutableStateOf<List<TurnipDriver>>(emptyList()) }
        var turnipScanning by remember { mutableStateOf(true) }

        LaunchedEffect(config.diagnosticsEnabled) {
            diagnostics.setEnabled(config.diagnosticsEnabled)
        }

        LaunchedEffect(Unit) {
            glInfo = withContext(Dispatchers.IO) { GlInfoProbe.query() }
            turnipDrivers = withContext(Dispatchers.IO) {
                runCatching { turnipRepository.scan() }.getOrDefault(emptyList())
            }
            turnipScanning = false
            diagnostics.record(
                "app",
                "启动 · 内置 Turnip 变体=" + turnipDrivers.count { it.bundled } +
                    " · runtime.conf 共享写入=" + runtimeStatus.sharedWritten,
            )
            if (report == null) {
                probing = true
                report = selfTestRepository.run(config, config.selfTestLibraryPath ?: config.activeRendererLibPath)
                probing = false
            }
        }

        fun persist(updated: PluginConfig) {
            config = updated
            top.mobilegl.plugin.NativeEnv.apply(updated)
            scope.launch {
                val status = withContext(Dispatchers.IO) {
                    store.save(updated)
                    runtimeWriter.write(updated)
                }
                if (!status.sharedWritten) {
                    diagnostics.record("runtime", "共享 runtime.conf 写入失败: " + (status.reason ?: "未知原因"))
                }
                runtimeStatus = status
            }
        }

        // --- Turnip (Mesa Freedreno) -------------------------------------------------
        // The bundled core is now the official 26.11 build (system libvulkan), which has no
        // Turnip loader. Only a core built from this project - bundled previously or imported
        // as an external .so - honors MOBILEGL_TURNIP_*. Keep the switch honest about that.
        val activeCorePath = config.activeRendererLibPath
        val coreSupportsTurnip = remember(activeCorePath) {
            runCatching { turnipRepository.coreSupportsTurnip(activeCorePath) }.getOrDefault(false)
        }
        LaunchedEffect(coreSupportsTurnip, config.turnipEnabled) {
            if (!coreSupportsTurnip && config.turnipEnabled) {
                diagnostics.record(
                    "turnip",
                    "自动关闭 · 当前核心不含 Turnip 加载器（" +
                        (config.activeRendererLibPath ?: "内置官方核心") + "）",
                )
                persist(config.copy(turnipEnabled = false))
            }
        }
        // Renderer strings the plugin already collects (EGL/GLES probe + Driver POST).
        // Turnip may only be enabled on Adreno, with a Qualcomm platform fallback when the
        // GPU name could not be read.
        val gpuHints = remember(glInfo, report) {
            buildList {
                glInfo?.renderer?.takeIf { it.isNotBlank() }?.let { add(it) }
                glInfo?.vendor?.takeIf { it.isNotBlank() }?.let { add(it) }
                report?.backends?.forEach { backend ->
                    backend.renderer.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }
        val turnipGpu = remember(gpuHints) { turnipRepository.detectGpu(gpuHints) }

        LaunchedEffect(turnipScanning, turnipDrivers, turnipGpu, config.turnipEnabled, config.turnipDriverPath, config.turnipDriverVersion) {
            if (turnipScanning) return@LaunchedEffect
            if (!config.turnipEnabled) {
                // Disabled: path/version are cleared so the next enable re-selects the auto
                // variant. This also migrates configs from the single-library version.
                if (config.turnipDriverPath != null || config.turnipDriverVersion != null) {
                    diagnostics.record("turnip", "未启用 · 清除残留驱动路径: " + config.turnipDriverPath)
                    persist(config.copy(turnipDriverPath = null, turnipDriverVersion = null))
                }
                return@LaunchedEffect
            }
            // A positively detected non-Adreno GPU must never keep Turnip enabled.
            if (turnipGpu.kind == TurnipGpuKind.Other) {
                diagnostics.record("turnip", "自动关闭 · 检测到非 Adreno GPU: " + turnipGpu.label)
                persist(config.copy(turnipEnabled = false, turnipDriverPath = null, turnipDriverVersion = null))
                return@LaunchedEffect
            }
            // Repair a stale selection: fall back to the auto variant, otherwise disable.
            val auto = turnipRepository.autoBundledDriver(turnipDrivers, turnipGpu)
            val selected = turnipDrivers.firstOrNull { it.path == config.turnipDriverPath } ?: auto
            if (selected == null) {
                diagnostics.record("turnip", "自动关闭 · 没有可用的内置/外部 Turnip 库")
                persist(config.copy(turnipEnabled = false, turnipDriverPath = null, turnipDriverVersion = null))
            } else if (selected.path != config.turnipDriverPath || selected.version != config.turnipDriverVersion) {
                diagnostics.record(
                    "turnip",
                    "路径失效或变体变更 · 自动回退: " + selected.displayName + " · " + selected.path,
                )
                persist(config.copy(turnipDriverPath = selected.path, turnipDriverVersion = selected.version))
            }
        }

        val turnipSummary = remember(turnipDrivers, turnipGpu, config.turnipEnabled, config.turnipDriverPath) {
            val auto = turnipRepository.autoBundledDriver(turnipDrivers, turnipGpu)
            val selected = turnipDrivers.firstOrNull { it.path == config.turnipDriverPath } ?: auto
            buildString {
                if (config.turnipEnabled) {
                    append("已启用 · ").append(selected?.displayName ?: "Turnip")
                } else {
                    append("未启用 · 系统 Vulkan")
                }
                if (selected?.experimental == true) append(" · 外部实验")
                selected?.version?.let { append(" · Mesa ").append(it) }
                append(" · ").append(turnipGpu.modelLabel)
                turnipGpu.autoVariant?.let { append(" → ").append(it.label) }
            }
        }

        // Facts embedded into the diagnostics report shared from the Info tab.
        val diagnosticsState = remember(
            config.diagnosticsEnabled,
            config.backend,
            config.sfpewEnabled,
            config.turnipEnabled,
            config.turnipDriverPath,
            config.turnipDriverVersion,
            config.activeRendererLibPath,
            coreSupportsTurnip,
            turnipGpu,
            runtimeStatus.sharedWritten,
            report,
        ) {
            DiagnosticsSnapshot(
                diagnosticsEnabled = config.diagnosticsEnabled,
                backend = config.backend,
                sfpewEnabled = config.sfpewEnabled,
                turnipEnabled = config.turnipEnabled,
                turnipDriverPath = config.turnipDriverPath,
                turnipDriverVersion = config.turnipDriverVersion,
                gpuLabel = turnipGpu.modelLabel,
                autoVariantLabel = turnipGpu.autoVariant?.label,
                runtimeConfigPath = runtimeStatus.sharedPath,
                runtimeConfigSharedWritten = runtimeStatus.sharedWritten,
                activeRendererLibPath = config.activeRendererLibPath,
                coreSupportsTurnip = coreSupportsTurnip,
                selfTestLines = selfTestSummaryLines(report),
            )
        }

        val prefs = remember { context.getSharedPreferences("mobilegl_plugin", Context.MODE_PRIVATE) }
        var showPermissionDialog by remember {
            mutableStateOf(!hasAllFilesAccess() && !prefs.getBoolean("permission_prompted", false))
        }
        if (showPermissionDialog) {
            AlertDialog(
                onDismissRequest = { showPermissionDialog = false },
                title = { Text("需要文件访问权限") },
                text = {
                    Text(
                        "MobileGL 需要“所有文件访问权限”来写入 /sdcard/MobileGL/runtime.conf，" +
                            "启动器和游戏进程才能读取配置。授权后应用会自动重启。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        prefs.edit().putBoolean("permission_prompted", true).putBoolean("awaiting_restart", true).apply()
                        showPermissionDialog = false
                        val packageUri = Uri.parse("package:" + context.packageName)
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri))
                        }.onFailure {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    }) { Text("去授权") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        prefs.edit().putBoolean("permission_prompted", true).apply()
                        showPermissionDialog = false
                    }) { Text("稍后") }
                },
            )
        }

        val tabIndex = TABS.indexOfFirst { it.dest == dest }
        val isSubPage = tabIndex < 0

        BackHandler(enabled = isSubPage) { dest = TABS[lastTab].dest }
        BackHandler(enabled = !isSubPage && dest != Dest.Home) { dest = Dest.Home }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                AnimatedVisibility(
                    visible = isSubPage,
                    enter = slideInVertically { -it } + fadeIn(),
                    exit = slideOutVertically { -it } + fadeOut(),
                ) {
                    MobileGlTopBar(
                        title = when (dest) {
                            Dest.Drivers -> "驱动管理"
                            Dest.ConfigFile -> "启动配置"
                            Dest.SelfTest -> "驱动自检"
                            else -> ""
                        },
                        onBack = { dest = TABS[lastTab].dest },
                    )
                }
            },
            bottomBar = {
                AnimatedVisibility(
                    visible = !isSubPage,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        TABS.forEachIndexed { index, tab ->
                            NavigationBarItem(
                                selected = tabIndex == index,
                                onClick = {
                                    lastTab = index
                                    dest = tab.dest
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = BLUE,
                                    selectedTextColor = BLUE,
                                    indicatorColor = BLUE.copy(alpha = 0.12f),
                                ),
                            )
                        }
                    }
                }
                AnimatedVisibility(
                    visible = isSubPage,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    BottomBackCard(onClick = { dest = TABS[lastTab].dest })
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                AnimatedContent(
                    targetState = dest,
                    transitionSpec = { pageTransition(initialState, targetState) },
                    label = "screen",
                ) { target ->
                    when (target) {
                        Dest.Home -> HomeTab(
                            config = config,
                            report = report,
                            probing = probing,
                            drivers = drivers,
                            externalCount = externalLibraries.size,
                            onOpenSettings = { lastTab = 1; dest = Dest.Settings },
                            glInfo = glInfo,
                        )
                        Dest.Settings -> SettingsTab(
                            config = config,
                            runtime = runtimeStatus,
                            drivers = drivers,
                            externalLibraries = externalLibraries,
                            turnipSummary = turnipSummary,
                            onSave = { persist(it) },
                            onOpenConfigFile = { lastTab = 1; dest = Dest.ConfigFile },
                            onOpenDrivers = { lastTab = 1; dest = Dest.Drivers },
                            onOpenSelfTest = { lastTab = 1; dest = Dest.SelfTest },
                        )
                        Dest.Info -> InfoTab(
                            config = config,
                            lastReport = report,
                            diagnostics = diagnostics,
                            diagnosticsState = diagnosticsState,
                            onToggleDiagnostics = { enabled ->
                                if (enabled) {
                                    diagnostics.setEnabled(true)
                                    diagnostics.record("ui", "诊断日志记录已开启")
                                } else {
                                    diagnostics.record("ui", "诊断日志记录已关闭")
                                    diagnostics.setEnabled(false)
                                }
                                persist(config.copy(diagnosticsEnabled = enabled))
                            },
                            onOpenSelfTest = { lastTab = 2; dest = Dest.SelfTest },
                            onShareReport = { shareReport(context, selfTestRepository) },
                        )
                        Dest.Drivers -> DriverScreen(
                            repository = driverRepository,
                            externalRepository = externalRepository,
                            turnipRepository = turnipRepository,
                            diagnostics = diagnostics,
                            drivers = drivers,
                            externalLibraries = externalLibraries,
                            turnipDrivers = turnipDrivers,
                            turnipGpu = turnipGpu,
                            turnipLoading = turnipScanning,
                            configFileShared = runtimeStatus.sharedWritten,
                            config = config,
                            onRefresh = {
                                drivers = driverRepository.list()
                                externalLibraries = externalRepository.list()
                                scope.launch {
                                    turnipDrivers = withContext(Dispatchers.IO) {
                                        runCatching { turnipRepository.scan() }.getOrDefault(emptyList())
                                    }
                                }
                            },
                            onActivate = { pack ->
                                val icd = runCatching { driverRepository.generateIcd(pack) }.getOrNull()
                                persist(config.copy(activeDriverId = pack.id, activeIcdPath = icd?.absolutePath))
                            },
                            onClearActive = { persist(config.copy(activeDriverId = null, activeIcdPath = null)) },
                            onActivateLibrary = { lib ->
                                val shared = lib?.sharedFile?.absolutePath ?: lib?.file?.absolutePath
                                val (version, commit) = lib?.file?.let { scanMobileGlVersion(it) } ?: (null to null)
                                val updated = config.copy(
                                    activeRendererLibPath = shared,
                                    selfTestLibraryPath = lib?.file?.absolutePath,
                                    activeRendererVersion = version,
                                    activeRendererCommit = commit,
                                )
                                persist(updated)
                                scope.launch {
                                    probing = true
                                    report = selfTestRepository.run(updated, lib?.file?.absolutePath)
                                    probing = false
                                }
                            },
                            onTurnipChange = { enabled, path, version ->
                                val action = when {
                                    enabled && !config.turnipEnabled -> "启用"
                                    !enabled && config.turnipEnabled -> "关闭"
                                    enabled && path != config.turnipDriverPath -> "切换变体"
                                    else -> "更新选择"
                                }
                                diagnostics.record(
                                    "turnip",
                                    action + " · 路径=" + (path ?: "-") + " · 版本=" + (version ?: "-"),
                                )
                                persist(
                                    config.copy(
                                        turnipEnabled = enabled,
                                        turnipDriverPath = path,
                                        turnipDriverVersion = version,
                                    )
                                )
                            },
                            externalCoreActive = config.activeRendererLibPath != null,
                            coreSupportsTurnip = coreSupportsTurnip,
                            onUseBundledCore = {
                                val updated = config.copy(
                                    activeRendererLibPath = null,
                                    selfTestLibraryPath = null,
                                    activeRendererVersion = null,
                                    activeRendererCommit = null,
                                )
                                persist(updated)
                                scope.launch {
                                    probing = true
                                    report = selfTestRepository.run(updated, null)
                                    probing = false
                                }
                            },
                        )
                        Dest.ConfigFile -> ConfigScreen(
                            writer = runtimeWriter,
                            status = runtimeStatus,
                            onStatusChanged = { runtimeStatus = it },
                        )
                        Dest.SelfTest -> SelfTestScreen(repository = selfTestRepository, config = config)
                    }
                }
            }
        }
    }
}

private fun shareReport(context: Context, repository: SelfTestRepository) {
    val text = repository.rawShareText()
    if (text.isNotBlank()) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "分享自检报告"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileGlTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun MobileGlTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = BLUE,
            onPrimary = Color.White,
            background = Color(0xFFF7F8FD),
            surface = Color.White,
            surfaceVariant = Color(0xFFF2F4FA),
            onSurface = Color(0xFF191C24),
            onSurfaceVariant = Color(0xFF6B7280),
            outline = Color(0xFFE3E7F0),
        ),
        content = content,
    )
}

private data class DeviceInfo(
    val gpu: String,
    val gles: String,
    val vulkan: String,
    val memory: String,
    val coreVersion: String,
    val coreCommit: String?,
)

private fun selfTestSummaryLines(report: SelfTestResult?): String? {
    report ?: return null
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(report.finishedAt))
    return buildString {
        appendLine(
            "时间: " + time + " · " + if (report.ok) "报告解析成功" else "报告解析失败: " + (report.error ?: "未知"),
        )
        report.backends.forEach { backend ->
            val loader = backend.checks.firstOrNull { it.name == "Vulkan instance" }?.detail
                ?.split(";")?.firstOrNull { it.contains("Vulkan loader", ignoreCase = true) }?.trim()
            appendLine(
                "• " + backend.backend + " · " + backend.renderer.ifBlank { "—" } + " · " + backend.verdict +
                    (if (loader.isNullOrBlank()) "" else " · " + loader),
            )
        }
    }.trimEnd()
}

private fun deviceInfo(context: Context, report: SelfTestResult?, glInfo: GlInfoProbe.Info?, config: PluginConfig): DeviceInfo {
    val glesReport = report?.backends?.firstOrNull { it.backend.contains("gles", true) }
    val vulkanReport = report?.backends?.firstOrNull { it.backend.contains("vulkan", true) }
    val glesRenderer = glesReport?.renderer.orEmpty()
    val vulkanRenderer = vulkanReport?.renderer.orEmpty()

    val gpu = vulkanRenderer.substringBefore(" (").trim().ifEmpty {
        glesRenderer.substringBefore(" (").trim().ifEmpty {
            glInfo?.renderer.orEmpty().ifBlank { "未知 GPU" }
        }
    }
    val gles = glesRenderer.ifBlank { glInfo?.version.orEmpty().ifBlank { "未检测到" } }
    val vulkan = when {
        vulkanReport == null -> "未检测到"
        !vulkanReport.available -> "不可用"
        vulkanRenderer.isBlank() -> "Vulkan"
        else -> vulkanRenderer
    }
    val versionDetail = (vulkanReport?.checks.orEmpty() + glesReport?.checks.orEmpty())
        .firstOrNull { it.name.contains("reported GL_VERSION", true) }?.detail.orEmpty()
    val core = Regex("MobileGL\\s+([0-9][^,\\s]*)").find(versionDetail)?.groupValues?.getOrNull(1)
    val coreVersion = config.activeRendererVersion
        ?: core?.removePrefix("v")?.ifBlank { null }
        ?: BuildConfig.VERSION_NAME
    val coreCommit = config.activeRendererCommit
        ?: Regex("GIT@([0-9a-fA-F]+)").find(versionDetail)?.groupValues?.getOrNull(1)
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mi = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mi)
    val memory = String.format(Locale.US, "%.1f GB", mi.totalMem / 1024.0 / 1024.0 / 1024.0)
    return DeviceInfo(gpu, gles, vulkan, memory, coreVersion, coreCommit)
}

@Composable
private fun HomeTab(
    config: PluginConfig,
    report: SelfTestResult?,
    probing: Boolean,
    drivers: List<DriverPack>,
    externalCount: Int,
    onOpenSettings: () -> Unit,
    glInfo: GlInfoProbe.Info?,
) {
    val context = LocalContext.current
    val info = remember(report, glInfo, config.activeRendererVersion, config.activeRendererCommit) { deviceInfo(context, report, glInfo, config) }
    val authorized = hasAllFilesAccess()
    val sfpewActive = config.sfpewEnabled && BuildConfig.SFPEW_BUNDLED
    val summary = when (config.backend) {
        "DirectVulkan" -> if (sfpewActive) "MobileGL-Magma-SFPEW" else "MobileGL-Magma"
        "DirectGLES" -> buildString {
            append("MobileGL-Espryt")
            if (config.env["MOBILEGL_ESPRYT_USE_ANGLE"] == "1") append("-angle")
            if (sfpewActive) append("-SFPEW")
        }
        else -> "MobileGL"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(44.dp))
        Row {
            Text("Mobile", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text("GL", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = ORANGE)
        }
        Text(
            "v" + info.coreVersion + (info.coreCommit?.let { " (" + it + ")" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        PermissionPill(authorized) {
            val packageUri = Uri.parse("package:" + context.packageName)
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri))
            }.onFailure {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
        Spacer(Modifier.height(28.dp))

        DeviceRow("GPU", if (probing) "检测中…" else info.gpu)
        DeviceRow("OpenGL ES", if (probing) "检测中…" else info.gles)
        DeviceRow("Vulkan", if (probing) "检测中…" else info.vulkan)
        DeviceRow("内存", info.memory)

        Spacer(Modifier.height(20.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = LIGHT_BLUE_CARD),
            onClick = onOpenSettings,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (probing) "正在检测驱动信息…" else summary,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "点击调整渲染器设置",
                    style = MaterialTheme.typography.bodySmall,
                    color = BLUE,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "设备：" + Build.MODEL + " · Android " + Build.VERSION.RELEASE + " · 驱动包 " + drivers.size + " 个 · 外部库 " + externalCount + " 个",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PermissionPill(authorized: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (authorized) BLUE.copy(alpha = 0.12f) else Color(0xFFFFE9E9),
        shape = RoundedCornerShape(50),
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (authorized) BLUE else FAIL)
            )
            Spacer(Modifier.width(8.dp))
            Text(if (authorized) "已授权" else "未授权 · 点击授权", color = if (authorized) BLUE else FAIL, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun DeviceRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(2.2f),
        )
    }
}

@Composable
private fun InfoTab(
    config: PluginConfig,
    lastReport: SelfTestResult?,
    diagnostics: DiagnosticsLog,
    diagnosticsState: DiagnosticsSnapshot,
    onToggleDiagnostics: (Boolean) -> Unit,
    onOpenSelfTest: () -> Unit,
    onShareReport: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        BigTitle("信息")
        SectionLabel("应用")
        InfoCard {
            InfoRow("版本", BuildConfig.VERSION_NAME)
            LinkRow("GitHub 仓库", "github.com/tfgh6/MobileGL-Android-Plugin") {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/tfgh6/MobileGL-Android-Plugin")))
            }
            LinkRow("上游项目", "MobileGL / SimpleFPEWrapper") {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/MobileGL-Dev")))
            }
            LinkRow("驱动自检", "查看完整报告与说明", onOpenSelfTest)
            val verdict = lastReport?.backends?.joinToString(" / ") { it.verdict }
            InfoRow("最近自检", verdict?.ifBlank { "未运行" } ?: "未运行")
        }
        SectionLabel("诊断日志")
        InfoCard {
            DiagnosticsPanel(
                diagnostics = diagnostics,
                state = diagnosticsState,
                enabled = config.diagnosticsEnabled,
                onToggleEnabled = onToggleDiagnostics,
            )
        }
        SectionLabel("关于")
        InfoCard {
            InfoRow("开发者", "MobileGL-Dev")
            InfoRow("开源许可证", "GNU LGPL-3.0")
            InfoRow("兼容启动器", "FCL · Zalith · Pojav")
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onShareReport) { Text("分享自检报告") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DiagnosticsPanel(
    diagnostics: DiagnosticsLog,
    state: DiagnosticsSnapshot,
    enabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(diagnostics.status()) }
    var preview by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var fullText by remember { mutableStateOf<String?>(null) }
    var showFull by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmShareDisabled by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            busy = true
            val loaded = withContext(Dispatchers.IO) { diagnostics.status() to diagnostics.lastLines(8) }
            status = loaded.first
            preview = loaded.second
            busy = false
        }
    }

    fun previewText(onReady: (String) -> Unit) {
        scope.launch {
            busy = true
            val text = withContext(Dispatchers.IO) { diagnostics.previewReport(state) }
            busy = false
            onReady(text)
        }
    }

    fun copySummary() {
        scope.launch {
            busy = true
            val text = withContext(Dispatchers.IO) { diagnostics.shortSummary(state) }
            busy = false
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard == null) {
                message = "无法访问剪贴板"
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("MobileGL 诊断摘要", text))
                diagnostics.record("share", "复制诊断摘要到剪贴板（" + text.length + " 字符）")
                message = "已复制诊断摘要（" + text.length + " 字符）；完整日志请用“分享日志”导出文件"
            }
        }
    }

    fun exportAndShare() {
        scope.launch {
            busy = true
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val file = diagnostics.exportReport(state)
                    diagnostics.record(
                        "share",
                        "导出诊断日志: " + file.name + " (" + diagnostics.formatSize(file.length()) + ")",
                    )
                    FileProvider.getUriForFile(
                        context,
                        context.packageName + DiagnosticsLog.FILE_PROVIDER_AUTHORITY_SUFFIX,
                        file,
                    )
                }
            }
            busy = false
            outcome.fold(
                onSuccess = { uri ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "MobileGL 诊断日志")
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("MobileGL 诊断日志", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(
                        Intent.createChooser(intent, "分享诊断日志")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                },
                onFailure = { message = "导出诊断日志失败：" + (it.message ?: it.toString()) },
            )
        }
    }

    fun shareFile() {
        if (enabled) exportAndShare() else confirmShareDisabled = true
    }

    LaunchedEffect(Unit) { reload() }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("记录诊断日志", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                if (enabled) {
                    "记录 Turnip 操作、导入结果与 runtime.conf 写入失败到 ui.log"
                } else {
                    "已关闭：不写新的 UI 日志（仍可分享已有日志与 game.log）"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggleEnabled)
    }

    InfoRow("UI 日志", status.uiLogPath)
    InfoRow("大小", diagnostics.formatSize(status.uiLogSizeBytes))
    InfoRow(
        "游戏日志",
        if (status.gameLogReadable) "可读取 · " + status.gameLogPath else "不可读（未授权或不存在）· " + status.gameLogPath,
    )
    if (status.usingPrivateFallback) {
        Text(
            "共享路径不可用，UI 日志已回退私有路径；未授予“所有文件访问权限”时游戏日志也无法读取。",
            style = MaterialTheme.typography.labelSmall,
            color = verdictColor("WARN"),
        )
    }
    Spacer(Modifier.height(8.dp))
    Text("最近日志", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    Text(
        preview.ifBlank { if (busy) "读取中…" else "（暂无日志；启用/切换 Turnip、导入外部库或写配置失败时会记录）" },
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy, onClick = { shareFile() }) { Text("分享日志") }
        OutlinedButton(enabled = !busy, onClick = { copySummary() }) { Text("复制摘要") }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy, onClick = {
            previewText { text ->
                fullText = text
                showFull = true
            }
        }) { Text("查看完整") }
        OutlinedButton(enabled = !busy, onClick = { reload() }) { Text("刷新") }
        OutlinedButton(enabled = !busy, onClick = { confirmClear = true }) { Text("清空") }
    }
    message?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, style = MaterialTheme.typography.labelSmall, color = verdictColor("PASS"))
    }

    if (confirmShareDisabled) {
        AlertDialog(
            onDismissRequest = { confirmShareDisabled = false },
            title = { Text("诊断记录未开启") },
            text = { Text("当前已关闭“记录诊断日志”，日志中不会包含新的 Turnip 操作记录。仍要导出并分享已有日志吗？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmShareDisabled = false
                    exportAndShare()
                }) { Text("仍要分享") }
            },
            dismissButton = { TextButton(onClick = { confirmShareDisabled = false }) { Text("取消") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空诊断日志") },
            text = { Text("只清空 UI 日志（" + status.uiLogPath + "）；native 游戏日志 game.log 不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        busy = true
                        val cleared = withContext(Dispatchers.IO) { diagnostics.clear() }
                        busy = false
                        reload()
                        message = if (cleared) "已清空 UI 日志" else "没有可清空的 UI 日志"
                    }
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
    if (showFull) {
        AlertDialog(
            onDismissRequest = { showFull = false },
            title = { Text("诊断日志") },
            text = {
                Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        fullText.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val text = fullText.orEmpty()
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("MobileGL 诊断日志", text))
                        message = "已复制日志预览（完整日志请用“分享日志”导出文件）"
                    }
                }) { Text("复制") }
            },
            dismissButton = { TextButton(onClick = { showFull = false }) { Text("关闭") } },
        )
    }
}

@Composable
private fun BottomBackCard(onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = BLUE),
            onClick = onClick,
        ) {
            Text(
                "← 返回",
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                textAlign = TextAlign.Center,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
fun BigTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        fontSize = 34.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = BLUE,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { content() }
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        onClick = onClick,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, modifier = Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
fun StatusPill(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(50)) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun verdictColor(verdict: String): Color = when (verdict.uppercase()) {
    "OK", "PASS" -> PASS
    "DEGRADED", "WARN" -> WARN
    "UNSUPPORTED", "FAIL" -> FAIL
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun hasAllFilesAccess(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true