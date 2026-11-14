// SPDX-License-Identifier: LGPL-3.0-only
package top.mobilegl.plugin.driver

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Where the Turnip library was found. */
enum class TurnipDriverSource { Bundled, External }

/**
 * A usable Turnip (Mesa Freedreno Vulkan) driver library.
 *
 * [path] is the absolute path written to runtime.conf (MOBILEGL_TURNIP_PATH). The bundled
 * copy lives in the plugin APK's nativeLibraryDir and is the reliable load path. External
 * copies live on the shared /sdcard/MobileGL/turnip directory: /sdcard is mounted noexec on
 * modern Android, so the game process cannot dlopen them in place. MobileGL copies them into
 * the game process's cache directory before loading; that path is experimental and falls
 * back to system Vulkan when the copy or load fails.
 */
data class TurnipDriver(
    val source: TurnipDriverSource,
    val name: String,
    val path: String,
    val version: String?,
    val buildId: String?,
    val sizeBytes: Long,
) {
    val bundled: Boolean get() = source == TurnipDriverSource.Bundled

    /**
     * Bundled libraries are extracted native libraries and can be dlopen'ed by the game
     * process directly. External /sdcard copies cannot be dlopen'ed in place (noexec mount +
     * SELinux); MobileGL copies them into the game process's cache directory before loading.
     * That path is experimental and falls back to system Vulkan on failure.
     */
    val experimental: Boolean get() = !bundled
    val sourceLabel: String get() = if (bundled) "内置" else "外部（实验）"
    val sizeMiB: Long get() = (sizeBytes / (1024 * 1024)).coerceAtLeast(1)

    /** Short one-line description used by the settings card. */
    val versionLabel: String
        get() = buildString {
            append(version ?: "版本未知")
            buildId?.let { append(" (").append(it).append(")") }
        }
}

enum class TurnipGpuKind { Adreno, Other, Unknown }

/** Result of the Adreno check: Turnip is only offered on Adreno/Qualcomm devices. */
data class TurnipGpu(
    val kind: TurnipGpuKind,
    val label: String,
    val detail: String,
) {
    val isAdreno: Boolean get() = kind == TurnipGpuKind.Adreno

    /** Only positively identified Adreno (or Qualcomm platform) devices may enable Turnip. */
    val canEnable: Boolean get() = kind == TurnipGpuKind.Adreno
}

/**
 * Turnip (Mesa Freedreno Vulkan) support for Adreno devices.
 *
 * The APK bundles libvulkan_freedreno.so in jniLibs (extracted to nativeLibraryDir on
 * install); that is the reliable load path. An external Turnip .so can also be imported to
 * /sdcard/MobileGL/turnip/ so it survives plugin updates, but it is experimental: the game
 * process cannot dlopen files from /sdcard (noexec/SELinux), so MobileGL copies them into
 * the game process's cache directory before loading and falls back to system Vulkan when
 * that fails. Both are validated as 64-bit little-endian AArch64 ELF, and the Mesa/build id
 * is parsed out of the library's string table for the status card.
 */
class TurnipRepository(private val context: Context) {
    companion object {
        const val BUNDLED_LIBRARY = "libvulkan_freedreno.so"
        const val EXTERNAL_DIR = "/sdcard/MobileGL/turnip"

        private const val MIN_LIBRARY_BYTES = 1024L * 1024L
        private const val MAX_LIBRARY_BYTES = 128L * 1024L * 1024L

        // Parsed from the library's string table: "Mesa 26.3.0-devel (git-9c7e022677)".
        private val MESA_VERSION = Regex("Mesa\\s+([0-9][0-9A-Za-z._+\\-]{1,24})")
        private val BUILD_ID = Regex("\\(git-([0-9a-fA-F]{6,40})\\)")

        private val NON_ADRENO_GPUS = listOf(
            "Mali",
            "Immortalis",
            "PowerVR",
            "Xclipse",
            "Imagination",
            "SwiftShader",
            "llvmpipe",
            "VideoCore",
            "Vivante",
            "Tegra",
            "NVIDIA",
            "Intel",
            "AMD",
            "Apple",
        )
    }

    val externalRoot: File = File(EXTERNAL_DIR)

    private data class VersionInfo(val version: String?, val buildId: String?)
    private val versionCache = HashMap<String, Pair<Long, VersionInfo>>()

    fun bundledFile(): File = File(context.applicationInfo.nativeLibraryDir, BUNDLED_LIBRARY)

    fun bundledPath(): String = bundledFile().absolutePath

    fun externalDir(): File = externalRoot

    fun canWriteExternal(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    /** Bundled and external Turnip libraries, bundled first. Call from an IO dispatcher. */
    fun scan(): List<TurnipDriver> {
        val bundled = describe(bundledFile(), TurnipDriverSource.Bundled)
        val external = externalDrivers()
        return listOfNotNull(bundled) + external
    }

    fun bundledDriver(): TurnipDriver? = describe(bundledFile(), TurnipDriverSource.Bundled)

    /** Every usable Turnip .so under /sdcard/MobileGL/turnip, newest first. */
    fun externalDrivers(): List<TurnipDriver> {
        val files = runCatching {
            (externalRoot.listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".so", true) }
        }.getOrDefault(emptyList())
        return files
            .sortedByDescending { it.lastModified() }
            .mapNotNull { describe(it, TurnipDriverSource.External) }
    }

    /**
     * Copies a user-picked .so into /sdcard/MobileGL/turnip after validating it. The shared
     * path is required because the launcher starts the game in another process/UID.
     */
    suspend fun import(uri: Uri): Result<TurnipDriver> = withContext(Dispatchers.IO) {
        runCatching {
            if (!canWriteExternal()) {
                error("请先授予“所有文件访问权限”，否则无法写入 " + EXTERNAL_DIR)
            }
            val temp = File(context.cacheDir, "turnip-import-" + System.currentTimeMillis() + ".so")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
                } ?: error("无法读取所选文件")
                if (temp.length() > MAX_LIBRARY_BYTES) error("文件超过 128 MiB，不是 Turnip 驱动库")
                if (temp.length() < MIN_LIBRARY_BYTES) {
                    error("文件过小（" + (temp.length() / 1024) + " KiB），不是 Turnip 驱动库")
                }
                if (!isArm64Elf(temp)) error("不是 arm64 ELF（需要 AArch64 64 位小端 .so）")
                if (!hasTurnipMarker(temp)) error("不是 Turnip / Freedreno 驱动库（缺少 freedreno 标识）")
                externalRoot.mkdirs()
                if (!externalRoot.isDirectory) error("无法创建 " + EXTERNAL_DIR + "（请检查文件访问权限）")
                val name = sanitize(uri.lastPathSegment?.substringAfterLast('/') ?: BUNDLED_LIBRARY)
                    .let { if (it.endsWith(".so", true)) it else it + ".so" }
                val target = File(externalRoot, name)
                temp.copyTo(target, overwrite = true)
                describe(target, TurnipDriverSource.External)
                    ?: error("导入后校验失败：不是有效的 arm64 Turnip 库")
            } finally {
                temp.delete()
            }
        }
    }

    /** Deletes an imported external library; the bundled copy cannot be removed. */
    fun delete(driver: TurnipDriver): Boolean {
        if (driver.bundled) return false
        val canonicalRoot = runCatching { externalRoot.canonicalPath + File.separator }.getOrNull() ?: return false
        val file = File(driver.path)
        val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return false
        if (!canonical.startsWith(canonicalRoot)) return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    /**
     * Detects whether this is an Adreno device, based on the renderer strings the plugin
     * already collects (EGL/GLES probe and the Driver POST report) with a Qualcomm platform
     * fallback. Turnip must never be enabled on Mali/PowerVR/Xclipse hardware.
     */
    fun detectGpu(hints: List<String>): TurnipGpu {
        val cleaned = hints.map { it.trim() }.filter { it.isNotEmpty() }
        val adreno = cleaned.firstOrNull { it.contains("Adreno", ignoreCase = true) }
        if (adreno != null) {
            return TurnipGpu(TurnipGpuKind.Adreno, adreno, "Adreno GPU，可启用骁龙 Turnip")
        }
        val other = cleaned.firstOrNull { hint -> NON_ADRENO_GPUS.any { hint.contains(it, ignoreCase = true) } }
        if (other != null) {
            return TurnipGpu(
                TurnipGpuKind.Other,
                other,
                "检测到非 Adreno GPU，Turnip 不可用；将使用系统 Vulkan",
            )
        }
        val qualcomm = qualcommPlatformLabel()
        if (qualcomm != null) {
            return TurnipGpu(
                TurnipGpuKind.Adreno,
                qualcomm,
                "Qualcomm 平台（未读到 GPU 名称），按 Adreno 处理",
            )
        }
        return TurnipGpu(
            TurnipGpuKind.Unknown,
            "未知 GPU",
            "未检测到 Adreno，无法确认是否支持 Turnip；如为 Mali/PowerVR 请勿启用",
        )
    }

    fun isArm64Elf(file: File): Boolean {
        val header = ByteArray(20)
        val read = runCatching { file.inputStream().use { it.read(header) } }.getOrDefault(-1)
        if (read < 20) return false
        if (header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
        ) return false
        val is64 = header[4].toInt() == 2
        val le = header[5].toInt() == 1
        val machine = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
        return is64 && le && machine == 0xB7
    }

    private fun hasTurnipMarker(file: File): Boolean {
        val text = runCatching { String(file.readBytes(), Charsets.ISO_8859_1) }.getOrNull() ?: return false
        return text.contains("freedreno", ignoreCase = true) || text.contains("turnip", ignoreCase = true)
    }

    private fun describe(file: File, source: TurnipDriverSource): TurnipDriver? {
        if (!file.isFile) return null
        if (file.length() < MIN_LIBRARY_BYTES) return null
        if (!isArm64Elf(file)) return null
        val info = versionInfo(file)
        return TurnipDriver(
            source = source,
            name = file.name,
            path = file.absolutePath,
            version = info.version,
            buildId = info.buildId,
            sizeBytes = file.length(),
        )
    }

    /** Cached scan of the library string table (the bundled .so is ~14 MiB). */
    private fun versionInfo(file: File): VersionInfo {
        val key = file.absolutePath
        val stamp = file.length() xor file.lastModified()
        synchronized(versionCache) {
            versionCache[key]?.takeIf { it.first == stamp }?.let { return it.second }
        }
        val text = runCatching { String(file.readBytes(), Charsets.ISO_8859_1) }.getOrNull()
        val info = if (text == null) {
            VersionInfo(null, null)
        } else {
            VersionInfo(
                version = MESA_VERSION.find(text)?.groupValues?.getOrNull(1),
                buildId = BUILD_ID.find(text)?.groupValues?.getOrNull(1)?.take(8),
            )
        }
        synchronized(versionCache) { versionCache[key] = stamp to info }
        return info
    }

    private fun qualcommPlatformLabel(): String? {
        val parts = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER?.takeIf { it.isNotBlank() }?.let { parts += it }
            Build.SOC_MODEL?.takeIf { it.isNotBlank() }?.let { parts += it }
        }
        Build.HARDWARE?.takeIf { it.isNotBlank() }?.let { parts += it }
        if (parts.isEmpty()) return null
        val text = parts.joinToString(" ")
        val qualcomm = text.contains("Qualcomm", ignoreCase = true) ||
            text.contains("qcom", ignoreCase = true) ||
            text.contains("msm", ignoreCase = true) ||
            Regex("\\bSM[0-9]{3,4}\\b").containsMatchIn(text)
        if (!qualcomm) return null
        val model = parts.firstOrNull { it.matches(Regex("SM[0-9]{3,4}[A-Za-z-]*")) }
        return if (model != null) "Qualcomm " + model else "Qualcomm 平台"
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
        return cleaned.ifBlank { BUNDLED_LIBRARY }
    }
}
