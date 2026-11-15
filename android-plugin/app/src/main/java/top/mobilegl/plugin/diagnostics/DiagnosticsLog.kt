// SPDX-License-Identifier: LGPL-3.0-only
package top.mobilegl.plugin.diagnostics

import android.content.Context
import android.os.Build
import android.os.Environment
import top.mobilegl.plugin.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Static facts combined into the shared diagnostics report. */
data class DiagnosticsSnapshot(
    val diagnosticsEnabled: Boolean,
    val backend: String,
    val sfpewEnabled: Boolean,
    val turnipEnabled: Boolean,
    val turnipDriverPath: String?,
    val turnipDriverVersion: String?,
    val gpuLabel: String,
    val autoVariantLabel: String?,
    val runtimeConfigPath: String,
    val runtimeConfigSharedWritten: Boolean,
)

/** Current log file locations/sizes for the in-app info card. */
data class DiagnosticsStatus(
    val uiLogPath: String,
    val uiLogSizeBytes: Long,
    val usingPrivateFallback: Boolean,
    val gameLogPath: String,
    val gameLogReadable: Boolean,
)

/**
 * Diagnostics log shown in the in-app Info tab, so a user without a PC can share what
 * happened when Turnip was enabled.
 *
 * Recording is opt-in ([setEnabled] mirrors PluginConfig.diagnosticsEnabled): while disabled
 * nothing is written. The UI writes to /sdcard/MobileGL/logs/ui.log; when "all files access"
 * is not granted it falls back to filesDir/logs/ui.log. The native game log
 * (/sdcard/MobileGL/logs/game.log, written by the loader) is read-only for the UI and
 * combined into the shared report. Logs rotate to ui.log.1 once they exceed 512 KiB.
 *
 * Sharing uses a real file: [exportReport] writes the combined report (up to 1 MiB of UI log
 * and 1 MiB of game log) into cacheDir/diagnostics/<timestamp>.txt, which is exposed through
 * the FileProvider declared with the [FILE_PROVIDER_AUTHORITY_SUFFIX] authority.
 */
class DiagnosticsLog(context: Context) {
    private val appContext = context.applicationContext

    @Volatile
    private var enabled = false

    companion object {
        const val SHARED_DIR = "/sdcard/MobileGL/logs"
        const val UI_LOG_NAME = "ui.log"
        const val GAME_LOG_NAME = "game.log"

        /** FileProvider authority is "<applicationId>" + this suffix (see AndroidManifest.xml). */
        const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".diagnostics"

        private const val MAX_LOG_BYTES = 512L * 1024L
        private const val MAX_READ_BYTES = 1024 * 1024
        private const val PREVIEW_LINES = 8
        private const val MAX_EXPORT_FILES = 5
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun isEnabled(): Boolean = enabled

    fun privateDir(): File = File(appContext.filesDir, "logs")

    fun privateUiLog(): File = File(privateDir(), UI_LOG_NAME)

    fun privateGameLog(): File = File(privateDir(), GAME_LOG_NAME)

    fun sharedDir(): File = File(SHARED_DIR)

    fun sharedUiLog(): File = File(sharedDir(), UI_LOG_NAME)

    fun sharedGameLog(): File = File(sharedDir(), GAME_LOG_NAME)

    fun exportDir(): File = File(appContext.cacheDir, "diagnostics")

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    private fun sharedWritable(): Boolean = runCatching {
        hasAllFilesAccess() && (sharedDir().isDirectory || sharedDir().mkdirs())
    }.getOrDefault(false)

    /** The log currently receiving UI entries (shared when permitted, private fallback otherwise). */
    fun activeUiLog(): File = if (sharedWritable()) sharedUiLog() else privateUiLog()

    fun status(): DiagnosticsStatus {
        val active = activeUiLog()
        val game = sharedGameLog()
        return DiagnosticsStatus(
            uiLogPath = active.absolutePath,
            uiLogSizeBytes = if (active.isFile) active.length() else 0L,
            usingPrivateFallback = active.absolutePath == privateUiLog().absolutePath,
            gameLogPath = game.absolutePath,
            gameLogReadable = game.isFile && runCatching { game.canRead() }.getOrDefault(false),
        )
    }

    /**
     * Appends one timestamped line to the UI log. Does nothing while recording is disabled.
     * When the shared path is not usable the entry falls back to the private copy.
     */
    @Synchronized
    /** Writes one UI-side line. No-op unless the user enabled diagnostics. */
    fun record(category: String, message: String) {
        if (!enabled) return
        val line = timestamp() + " [" + category + "] " + message + "\n"
        val sharedOk = sharedWritable() && runCatching { append(sharedUiLog(), line); true }.getOrDefault(false)
        if (!sharedOk) {
            runCatching { append(privateUiLog(), line) }
        }
    }

    /** Last [maxLines] non-empty lines of the active UI log (private fallback included). */
    fun lastLines(maxLines: Int = PREVIEW_LINES): String {
        val candidates = listOf(activeUiLog(), sharedUiLog(), privateUiLog())
        for (candidate in candidates) {
            val text = readTail(candidate, 64 * 1024) ?: continue
            if (text.isNotBlank()) {
                return text.lineSequence().filter { it.isNotBlank() }.toList().takeLast(maxLines).joinToString("\n")
            }
        }
        return ""
    }

    /** Clears the UI log (shared and private copies plus rotated files). The native game log is untouched. */
    @Synchronized
    /** Clears only the UI log; the native game.log is read-only for the app. */
    fun clear(): Boolean {
        var cleared = false
        val files = listOf(
            activeUiLog(),
            sharedUiLog(),
            privateUiLog(),
            File(sharedUiLog().parentFile, UI_LOG_NAME + ".1"),
            File(privateUiLog().parentFile, UI_LOG_NAME + ".1"),
        ).distinctBy { it.absolutePath }
        for (file in files) {
            if (!file.isFile) continue
            if (runCatching { file.writeText(""); true }.getOrDefault(false)) cleared = true
        }
        return cleared
    }

    /**
     * Writes the combined report into cacheDir/diagnostics/<timestamp>.txt for FileProvider
     * sharing. Keeps every UI/game line (up to [MAX_READ_BYTES] each) instead of a clipboard
     * text limit, and prunes old exports.
     */
    /**
     * Builds the file shared through FileProvider. Unlike shortSummary(), this keeps up to
     * 1 MiB of UI and game logs so WeChat/QQ never truncate the actual diagnostic tail.
     */
    fun exportReport(state: DiagnosticsSnapshot): File {
        val dir = exportDir()
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "MobileGL-diagnostics-" + stamp + ".txt")
        file.writeText(snapshot(state))
        pruneExports(dir)
        return file
    }

    /** Full combined report: header + UI log + native game log (without file-export bookkeeping). */
    /** Full report (headers + UI log + native game.log) used for export/preview. */
    fun snapshot(state: DiagnosticsSnapshot): String = buildString {
        append(header(state))
        appendLine()
        appendLine("--- UI 日志 " + activeUiLog().absolutePath + " ---")
        appendLine(readTail(activeUiLog(), MAX_READ_BYTES) ?: "（暂无 UI 日志）")
        appendLine()
        appendLine("--- 游戏日志 " + sharedGameLog().absolutePath + " ---")
        val gameText = readTail(sharedGameLog(), MAX_READ_BYTES)
        if (gameText != null) {
            appendLine(gameText)
        } else {
            appendLine("（无法读取：文件不存在或未授予“所有文件访问权限”）")
            val privateGameText = readTail(privateGameLog(), MAX_READ_BYTES)
            if (privateGameText != null) {
                appendLine()
                appendLine("--- 私有回退 " + privateGameLog().absolutePath + " ---")
                appendLine(privateGameText)
            }
        }
    }

    /** Report trimmed for the in-app dialog (large tails stay in the exported file). */
    fun previewReport(state: DiagnosticsSnapshot, maxChars: Int = 12000): String {
        val full = snapshot(state)
        return if (full.length <= maxChars) {
            full
        } else {
            "……（仅显示末尾内容，完整日志请用“分享日志”导出文件）\n" + full.takeLast(maxChars)
        }
    }

    /** Short text copied to the clipboard; the exported file keeps the full tails. */
    fun shortSummary(state: DiagnosticsSnapshot): String = buildString {
        append(header(state))
        appendLine()
        appendLine("--- UI 日志末尾 ---")
        appendLine(lastLines(30).ifBlank { "（暂无）" })
        appendLine("（完整日志请使用“分享日志”导出 .txt 文件）")
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> bytes.toString() + " B"
        bytes < 1024 * 1024 -> (bytes / 1024).toString() + " KiB"
        else -> String.format(Locale.US, "%.1f MiB", bytes / 1024.0 / 1024.0)
    }

    private fun header(state: DiagnosticsSnapshot): String {
        val uiLog = activeUiLog()
        val fallback = uiLog.absolutePath == privateUiLog().absolutePath
        return buildString {
            appendLine("=== MobileGL 诊断日志 ===")
            appendLine("生成时间: " + timestampFull())
            appendLine("应用: " + appContext.packageName + " " + appVersion())
            appendLine("设备: " + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
            appendLine("后端: " + state.backend + " · SFPEW=" + if (state.sfpewEnabled) "开" else "关")
            appendLine("GPU: " + state.gpuLabel + " · 自动变体=" + (state.autoVariantLabel ?: "无（非 Adreno）"))
            appendLine(
                "Turnip: " + if (state.turnipEnabled) {
                    "已启用 · 路径=" + (state.turnipDriverPath ?: "-") + " · 版本=" + (state.turnipDriverVersion ?: "未知")
                } else {
                    "未启用（使用系统 Vulkan）"
                }
            )
            appendLine(
                "runtime.conf: " + state.runtimeConfigPath +
                    (if (state.runtimeConfigSharedWritten) {
                        " · 已写入共享路径"
                    } else {
                        " · 未写入共享路径（仅私有副本，游戏进程读不到 UI 配置）"
                    })
            )
            appendLine("诊断记录: " + if (state.diagnosticsEnabled) "开（MOBILEGL_DIAG_LOG_ENABLED=1）" else "关（MOBILEGL_DIAG_LOG_ENABLED=0）")
            appendLine(
                "UI 日志: " + uiLog.absolutePath + " (" + formatSize(if (uiLog.isFile) uiLog.length() else 0L) + ")" +
                    if (fallback) " · 共享权限不可用，已回退私有路径" else ""
            )
        }
    }

    private fun timestamp(): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun timestampFull(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun appVersion(): String = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        info.versionName ?: BuildConfig.VERSION_NAME
    }.getOrDefault(BuildConfig.VERSION_NAME)

    private fun append(file: File, line: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
        if (file.length() > MAX_LOG_BYTES) rotate(file)
    }

    /** ui.log exceeds 512 KiB: move it to ui.log.1 and continue with a fresh file. */
    private fun rotate(file: File) {
        val backup = File(file.parentFile, file.name + ".1")
        runCatching {
            if (backup.exists()) backup.delete()
            if (!file.renameTo(backup)) {
                file.copyTo(backup, overwrite = true)
                file.writeText("")
            }
        }
    }

    private fun pruneExports(dir: File) {
        val files = runCatching {
            (dir.listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".txt") }
        }.getOrDefault(emptyList())
        files.sortedByDescending { it.lastModified() }.drop(MAX_EXPORT_FILES).forEach { runCatching { it.delete() } }
    }

    private fun readTail(file: File, maxBytes: Int): String? {
        if (!file.isFile || !runCatching { file.canRead() }.getOrDefault(false)) return null
        return runCatching {
            val length = file.length()
            if (length <= maxBytes) {
                file.readText()
            } else {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(length - maxBytes)
                    val bytes = ByteArray(maxBytes)
                    val read = raf.read(bytes)
                    if (read <= 0) "" else {
                        "……（仅显示末尾 " + (maxBytes / 1024) + " KiB）\n" + String(bytes, 0, read, Charsets.UTF_8)
                    }
                }
            }
        }.getOrNull()
    }
}
