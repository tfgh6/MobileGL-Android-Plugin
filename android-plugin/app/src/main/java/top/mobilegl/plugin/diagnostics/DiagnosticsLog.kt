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
 * The UI writes to /sdcard/MobileGL/logs/ui.log; when "all files access" is not granted it
 * falls back to filesDir/logs/ui.log. The native game log (/sdcard/MobileGL/logs/game.log,
 * written by the loader) is read-only for the UI and combined into the shared report. Logs
 * rotate to ui.log.1 once they exceed 512 KiB.
 */
class DiagnosticsLog(context: Context) {
    private val appContext = context.applicationContext

    companion object {
        const val SHARED_DIR = "/sdcard/MobileGL/logs"
        const val UI_LOG_NAME = "ui.log"
        const val GAME_LOG_NAME = "game.log"

        private const val MAX_LOG_BYTES = 512L * 1024L
        private const val MAX_READ_BYTES = 256 * 1024
        private const val PREVIEW_LINES = 8
    }

    fun privateDir(): File = File(appContext.filesDir, "logs")

    fun privateUiLog(): File = File(privateDir(), UI_LOG_NAME)

    fun privateGameLog(): File = File(privateDir(), GAME_LOG_NAME)

    fun sharedDir(): File = File(SHARED_DIR)

    fun sharedUiLog(): File = File(sharedDir(), UI_LOG_NAME)

    fun sharedGameLog(): File = File(sharedDir(), GAME_LOG_NAME)

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
     * Appends one timestamped line to the UI log. When the shared path is not usable the
     * entry falls back to the private copy so diagnostics still exist.
     */
    @Synchronized
    fun record(category: String, message: String) {
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

    /** Full combined report: app/device/Turnip/runtime.conf header + UI log + native game log. */
    fun snapshot(state: DiagnosticsSnapshot): String {
        val uiLog = activeUiLog()
        val gameLog = sharedGameLog()
        val gameText = readTail(gameLog, MAX_READ_BYTES)
        val privateGameText = readTail(privateGameLog(), MAX_READ_BYTES)
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
            appendLine(
                "UI 日志: " + uiLog.absolutePath + " (" + formatSize(if (uiLog.isFile) uiLog.length() else 0L) + ")" +
                    if (fallback) " · 共享权限不可用，已回退私有路径" else ""
            )
            appendLine()
            appendLine("--- UI 日志 " + uiLog.absolutePath + " ---")
            appendLine(readTail(uiLog, MAX_READ_BYTES) ?: "（暂无 UI 日志）")
            appendLine()
            appendLine("--- 游戏日志 " + gameLog.absolutePath + " ---")
            if (gameText != null) {
                appendLine(gameText)
            } else {
                appendLine("（无法读取：文件不存在或未授予“所有文件访问权限”）")
                if (privateGameText != null) {
                    appendLine()
                    appendLine("--- 私有回退 " + privateGameLog().absolutePath + " ---")
                    appendLine(privateGameText)
                }
            }
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> bytes.toString() + " B"
        bytes < 1024 * 1024 -> (bytes / 1024).toString() + " KiB"
        else -> String.format(Locale.US, "%.1f MiB", bytes / 1024.0 / 1024.0)
    }

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

    private fun timestamp(): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun timestampFull(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun appVersion(): String = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        info.versionName ?: BuildConfig.VERSION_NAME
    }.getOrDefault(BuildConfig.VERSION_NAME)
}
