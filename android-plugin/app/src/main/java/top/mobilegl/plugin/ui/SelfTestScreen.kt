// SPDX-License-Identifier: LGPL-3.0-only
package top.mobilegl.plugin.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import top.mobilegl.plugin.config.PluginConfig
import top.mobilegl.plugin.selftest.BackendReport
import top.mobilegl.plugin.selftest.CheckItem
import top.mobilegl.plugin.selftest.DriverBug
import top.mobilegl.plugin.selftest.FormatCapabilities
import top.mobilegl.plugin.selftest.FormatRow
import top.mobilegl.plugin.selftest.SelfTestRepository
import top.mobilegl.plugin.selftest.SelfTestResult

private val CAPABILITY_FULL = Color(0xFF2E7D32)
private val CAPABILITY_CAVEAT = Color(0xFFFBC02D)
private val CAPABILITY_NONE = Color(0xFFC62828)

@Composable
fun SelfTestScreen(
    repository: SelfTestRepository,
    config: PluginConfig,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf(repository.cached()) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard("驱动自检") {
                Text(
                    "与官方 MobileGL Action APK 使用同一套 Driver POST（DirectGLES + DirectVulkan），" +
                        "展示原生 checks、Known driver bugs 与 Format capabilities 完整报告。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                val activeLib = config.selfTestLibraryPath ?: config.activeRendererLibPath
                if (!activeLib.isNullOrBlank()) {
                    Text(
                        "使用外部库自检：" + activeLib,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (!running) {
                                scope.launch {
                                    running = true
                                    result = repository.run(config, config.selfTestLibraryPath ?: config.activeRendererLibPath)
                                    running = false
                                }
                            }
                        },
                    ) { Text(if (running) "探测中…" else "运行自检") }
                    OutlinedButton(
                        onClick = {
                            val text = result?.rawJson.orEmpty()
                            if (text.isNotBlank()) {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(Intent.createChooser(intent, "分享自检报告"))
                            }
                        },
                    ) { Text("分享原始报告") }
                }
                if (running) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }

        val report = result
        if (report == null) {
            item {
                SectionCard("还没有自检结果") {
                    Text("点上面的“运行自检”。它会加载 libMobileGL.so 并枚举当前驱动。", style = MaterialTheme.typography.bodySmall)
                }
            }
            return@LazyColumn
        }
        if (!report.ok) {
            item {
                SectionCard("自检失败") {
                    Text(report.error ?: "未知错误", style = MaterialTheme.typography.bodySmall)
                }
            }
            return@LazyColumn
        }

        item { SelfTestSummary(report) }

        items(report.backends, key = { it.backend + "|" + it.renderer }) { backend ->
            BackendCard(
                backend = backend,
                expanded = expanded,
                onToggle = { key -> expanded = if (key in expanded) expanded - key else expanded + key },
            )
        }
        item {
            RawReportCard(report = report, expanded = expanded, onToggle = { key ->
                expanded = if (key in expanded) expanded - key else expanded + key
            })
        }
    }
}

@Composable
private fun SelfTestSummary(report: SelfTestResult) {
    val checks = report.backends.flatMap { it.checks }
    val fails = checks.count { it.status.equals("FAIL", true) }
    val warns = checks.count { it.status.equals("WARN", true) }
    val passes = checks.count { it.status.equals("PASS", true) }
    val bugs = report.backends.sumOf { it.knownDriverBugs.size }
    val verdicts = report.backends.map { it.verdict.uppercase() }
    val overall = when {
        verdicts.any { it == "UNSUPPORTED" } || fails > 0 -> "存在失败项"
        verdicts.any { it == "DEGRADED" } || warns > 0 -> "可用，部分降级"
        else -> "可用"
    }
    val color = when (overall) {
        "存在失败项" -> verdictColor("FAIL")
        "可用，部分降级" -> verdictColor("WARN")
        else -> verdictColor("PASS")
    }
    SectionCard("自检总结") {
        Text("结论：" + overall, color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(6.dp))
        Text("通过 " + passes + " 项 · 替代 " + warns + " 项 · 失败 " + fails + " 项", style = MaterialTheme.typography.bodySmall)
        if (bugs > 0) {
            Text("已知驱动问题 " + bugs + " 项（见下方 Known driver bugs）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BackendCard(
    backend: BackendReport,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(sectionTitle(backend.backend), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (!backend.available) {
                Spacer(Modifier.height(6.dp))
                Text("Driver not present / not probeable", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Verdict: " + backend.verdict,
                    color = verdictColor(backend.verdict),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (backend.renderer.isNotBlank()) {
                    Text(backend.renderer, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                backend.checks.forEach { check ->
                    CheckRow(
                        check = check,
                        open = (backend.backend + "/check/" + check.name) in expanded,
                        onClick = { onToggle(backend.backend + "/check/" + check.name) },
                    )
                }
                if (backend.knownDriverBugs.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Known driver bugs", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    backend.knownDriverBugs.forEach { bug ->
                        val key = backend.backend + "/bug/" + bug.name
                        DriverBugRow(bug = bug, open = key in expanded, onClick = { onToggle(key) })
                    }
                }
                val formats = backend.formatCapabilities
                if (formats != null) {
                    Spacer(Modifier.height(12.dp))
                    Text("Format capabilities", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    formats.targets.forEach { target ->
                        val key = backend.backend + "/format/" + target.name
                        val open = key in expanded
                        Text(
                            (if (open) "▾ " else "▸ ") + target.name + " (" + target.rows.size + " formats)",
                            modifier = Modifier.fillMaxWidth().clickable { onToggle(key) }.padding(vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                        if (open) FormatTable(capabilities = formats, rows = target.rows)
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(check: CheckItem, open: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(check.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            StatusPill(check.status, verdictColor(check.status))
        }
        if (open && check.detail.isNotBlank()) {
            Text(
                check.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
}

@Composable
private fun DriverBugRow(bug: DriverBug, open: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(bug.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            StatusPill(bug.verdict, verdictColor(bug.verdict))
        }
        if (open && bug.detail.isNotBlank()) {
            Text(bug.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
}

@Composable
private fun FormatTable(capabilities: FormatCapabilities, rows: List<FormatRow>) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
        Row {
            TableCell("Format", 150.dp, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurface, true)
            capabilities.capabilities.forEach { name ->
                TableCell(name, 104.dp, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurface, true)
            }
        }
        rows.forEach { row ->
            Row {
                TableCell(row.name, 150.dp, MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), MaterialTheme.colorScheme.onSurface, false)
                capabilities.capabilities.indices.forEach { index ->
                    val bit = if (index < 64) 1L shl index else 0L
                    val full = bit != 0L && (row.fullMask and bit) != 0L
                    val caveat = !full && bit != 0L && (row.caveatMask and bit) != 0L
                    val color = if (full) CAPABILITY_FULL else if (caveat) CAPABILITY_CAVEAT else CAPABILITY_NONE
                    val text = if (full) "Full" else if (caveat) "Caveat" else "None"
                    TableCell(text, 104.dp, color, if (caveat) Color.Black else Color.White, true)
                }
            }
        }
    }
}

@Composable
private fun TableCell(text: String, width: androidx.compose.ui.unit.Dp, background: Color, foreground: Color, bold: Boolean) {
    Box(
        modifier = Modifier.width(width).height(32.dp).padding(end = 1.dp, bottom = 1.dp).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = foreground, fontSize = 10.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}

@Composable
private fun RawReportCard(report: SelfTestResult, expanded: Set<String>, onToggle: (String) -> Unit) {
    val key = "raw"
    val open = key in expanded
    SectionCard("Raw report") {
        Text(
            if (open) "▾ Raw report (tap to collapse)" else "▸ Raw report (tap to expand)",
            modifier = Modifier.fillMaxWidth().clickable { onToggle(key) }.padding(vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (open) {
            Text(report.rawJson, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun sectionTitle(name: String): String {
    val lower = name.lowercase()
    return when {
        lower.contains("gles") || lower.contains("espryt") -> "DirectGLES (Espryt)"
        lower.contains("vulkan") || lower.contains("magma") -> "DirectVulkan (Magma)"
        else -> name
    }
}