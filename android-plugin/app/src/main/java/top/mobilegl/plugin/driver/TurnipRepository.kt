// SPDX-License-Identifier: LGPL-3.0-only
package top.mobilegl.plugin.driver

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/** Where the Turnip library was found. */
enum class TurnipDriverSource { Bundled, External }

/**
 * One of the Turnip v27 builds packaged in jniLibs/arm64-v8a.
 *
 * [id] is stable and persisted in the UI state; [libraryName] is the actual file name that
 * ends up in the APK's nativeLibraryDir (extracted on install).
 */
data class TurnipVariant(
    val id: String,
    val label: String,
    val libraryName: String,
    val models: String,
    val experimental: Boolean,
)

/**
 * A usable Turnip (Mesa Freedreno Vulkan) driver library.
 *
 * [path] is the absolute path written to runtime.conf (MOBILEGL_TURNIP_PATH). Bundled
 * variants live in the plugin APK's nativeLibraryDir and are the reliable load path.
 * External copies live on the shared /sdcard/MobileGL/turnip directory: /sdcard is mounted
 * noexec on modern Android, so the game process cannot dlopen them in place. MobileGL copies
 * them into the game process's cache directory before loading; that path is experimental and
 * falls back to system Vulkan when the copy or load fails.
 */
data class TurnipDriver(
    val source: TurnipDriverSource,
    val variant: TurnipVariant?,
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

    val variantId: String? get() = variant?.id

    /** Variant label for bundled builds, file name for imported ones. */
    val displayName: String get() = variant?.label ?: name

    /** Short one-line description used by the settings card. */
    val versionLabel: String
        get() = buildString {
            append(version ?: "版本未知")
            buildId?.let { append(" (").append(it).append(")") }
        }
}

enum class TurnipGpuKind { Adreno, Other, Unknown }

/**
 * Result of the Adreno check: Turnip is only offered on Adreno/Qualcomm devices.
 *
 * [model] is the Adreno number parsed from the renderer string (740, 710, 830, ...).
 * [autoVariantId] is the bundled variant the auto rule selected for this GPU.
 */
data class TurnipGpu(
    val kind: TurnipGpuKind,
    val label: String,
    val detail: String,
    val model: Int? = null,
    val samsung: Boolean = false,
    val autoVariantId: String? = null,
) {
    val isAdreno: Boolean get() = kind == TurnipGpuKind.Adreno

    /** Only positively identified Adreno (or Qualcomm platform) devices may enable Turnip. */
    val canEnable: Boolean get() = kind == TurnipGpuKind.Adreno

    val modelLabel: String get() = if (model != null) "Adreno " + model else label

    val autoVariant: TurnipVariant? get() = TurnipRepository.variant(autoVariantId)

    val autoVariantLabel: String get() = autoVariant?.label ?: "无（非 Adreno）"
}

/**
 * Turnip (Mesa Freedreno Vulkan) support for Adreno devices.
 *
 * The APK bundles four arm64-v8a Turnip v27 builds (generic A6xx/A7xx, A710/A720, A8xx and
 * A740 OneUI); the matching one is selected from the detected Adreno model. The bundled
 * copy in nativeLibraryDir is the reliable load path. An external Turnip .so can also be
 * imported to /sdcard/MobileGL/turnip/ so it survives plugin updates, but it is experimental:
 * the game process cannot dlopen files from /sdcard (noexec/SELinux), so MobileGL copies them
 * into the game process's cache directory before loading and falls back to system Vulkan when
 * that fails. Every library is validated as 64-bit little-endian AArch64 ELF, and the
 * Mesa/build id is parsed out of its string table for the status card.
 */
class TurnipRepository(private val context: Context) {
    companion object {
        const val EXTERNAL_DIR = "/sdcard/MobileGL/turnip"

        const val VARIANT_GENERIC = "generic"
        const val VARIANT_710_720 = "710_720"
        const val VARIANT_A8XX = "a8xx"
        const val VARIANT_8G2_ONEUI = "8g2_oneui"
        const val DEFAULT_VARIANT_ID = VARIANT_GENERIC

        /** Registry of the Turnip builds packaged in jniLibs/arm64-v8a. */
        val VARIANTS: List<TurnipVariant> = listOf(
            TurnipVariant(
                id = VARIANT_GENERIC,
                label = "通用 A6xx/A7xx",
                libraryName = "libturnip_generic.so",
                models = "Adreno 6xx / 7xx（710/720/722 除外）",
                experimental = false,
            ),
            TurnipVariant(
                id = VARIANT_710_720,
                label = "A710/A720",
                libraryName = "libturnip_710_720.so",
                models = "Adreno 710 / 720 / 722（实验）",
                experimental = true,
            ),
            TurnipVariant(
                id = VARIANT_A8XX,
                label = "A8xx",
                libraryName = "libturnip_a8xx.so",
                models = "Adreno 750 / 8xx",
                experimental = false,
            ),
            TurnipVariant(
                id = VARIANT_8G2_ONEUI,
                label = "8G2 OneUI",
                libraryName = "libturnip_8g2_oneui.so",
                models = "Adreno 740 · OneUI / 三星",
                experimental = false,
            ),
        )

        fun variant(id: String?): TurnipVariant? = VARIANTS.firstOrNull { it.id == id }

        /**
         * Auto rule: 710/720/722 → 710_720; 750 and 8xx → a8xx; 740 on OneUI/Samsung →
         * 8g2_oneui (otherwise generic); any other Adreno / unknown model → generic.
         */
        fun autoVariantId(model: Int?, samsung: Boolean): String {
            if (model == null) return DEFAULT_VARIANT_ID
            return when {
                model == 710 || model == 720 || model == 722 -> VARIANT_710_720
                model == 750 || model in 800..899 -> VARIANT_A8XX
                model == 740 -> if (samsung) VARIANT_8G2_ONEUI else DEFAULT_VARIANT_ID
                else -> DEFAULT_VARIANT_ID
            }
        }

        private const val MIN_LIBRARY_BYTES = 1024L * 1024L
        private const val MAX_LIBRARY_BYTES = 128L * 1024L * 1024L

        // Strict dotted version: avoids matching the "Mesa 3D ..." banner text.
        private val MESA_VERSION = Regex("Mesa\\s+([0-9]+\\.[0-9]+\\.[0-9]+[0-9A-Za-z._+\\-]*)")
        private val BUILD_ID = Regex("\\(git-([0-9a-fA-F]{6,40})\\)")
        private val ADRENO_MODEL = Regex("Adreno\\s*(?:\\(TM\\)|™)?\\s*([0-9]{3,4})", RegexOption.IGNORE_CASE)

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

    fun bundledVariantFile(variant: TurnipVariant): File =
        File(context.applicationInfo.nativeLibraryDir, variant.libraryName)

    fun externalDir(): File = externalRoot

    fun canWriteExternal(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    /** Bundled variants (registry order) followed by imported external libraries. */
    fun scan(): List<TurnipDriver> = bundledDrivers() + externalDrivers()

    /** Every bundled variant that is actually present and valid, registry order. */
    fun bundledDrivers(): List<TurnipDriver> = VARIANTS.mapNotNull { variant ->
        describe(bundledVariantFile(variant), TurnipDriverSource.Bundled, variant)
    }

    /** The bundled driver the auto rule picks for [gpu], falling back to generic. */
    fun autoBundledDriver(drivers: List<TurnipDriver>, gpu: TurnipGpu): TurnipDriver? {
        val wanted = gpu.autoVariantId ?: DEFAULT_VARIANT_ID
        return drivers.firstOrNull { it.bundled && it.variantId == wanted }
            ?: drivers.firstOrNull { it.bundled && it.variantId == DEFAULT_VARIANT_ID }
            ?: drivers.firstOrNull { it.bundled }
    }

    /** Every usable Turnip .so under /sdcard/MobileGL/turnip, newest first. */
    fun externalDrivers(): List<TurnipDriver> {
        val files = runCatching {
            (externalRoot.listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".so", true) }
        }.getOrDefault(emptyList())
        return files
            .sortedByDescending { it.lastModified() }
            .mapNotNull { describe(it, TurnipDriverSource.External, null) }
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
                // Banners-Turnip ships .zip bundles (libvulkan_freedreno.so + meta.json).
                // Extract the driver so the normal ELF/marker validation below still applies.
                if (isZipArchive(temp)) {
                    val extracted = File(context.cacheDir, "turnip-unzip-" + System.currentTimeMillis() + ".so")
                    try {
                        if (!extractDriverFromZip(temp, extracted)) {
                            error("压缩包里没有找到 Turnip .so（需要 Banners-Turnip 的 .zip）")
                        }
                        if (!extracted.renameTo(temp)) {
                            extracted.copyTo(temp, overwrite = true)
                        }
                    } finally {
                        extracted.delete()
                    }
                }
                if (temp.length() > MAX_LIBRARY_BYTES) error("文件超过 128 MiB，不是 Turnip 驱动库")
                if (temp.length() < MIN_LIBRARY_BYTES) {
                    error("文件过小（" + (temp.length() / 1024) + " KiB），不是 Turnip 驱动库")
                }
                if (!isArm64Elf(temp)) error("不是 arm64 ELF（需要 AArch64 64 位小端 .so）")
                if (!hasTurnipMarker(temp)) error("不是 Turnip / Freedreno 驱动库（缺少 freedreno 标识）")
                externalRoot.mkdirs()
                if (!externalRoot.isDirectory) error("无法创建 " + EXTERNAL_DIR + "（请检查文件访问权限）")
                val base = sanitize(uri.lastPathSegment?.substringAfterLast('/') ?: "libturnip_imported.so")
                val name = base.substringBeforeLast('.', base).ifBlank { "libturnip_imported" } + ".so"
                val target = File(externalRoot, name)
                temp.copyTo(target, overwrite = true)
                describe(target, TurnipDriverSource.External, null)
                    ?: error("导入后校验失败：不是有效的 arm64 Turnip 库")
            } finally {
                temp.delete()
            }
        }
    }

    /** Deletes an imported external library; bundled variants cannot be removed. */
    fun delete(driver: TurnipDriver): Boolean {
        if (driver.bundled) return false
        val canonicalRoot = runCatching { externalRoot.canonicalPath + File.separator }.getOrNull() ?: return false
        val file = File(driver.path)
        val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return false
        if (!canonical.startsWith(canonicalRoot)) return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    /**
     * Detects whether this is an Adreno device and which model it is, based on the renderer
     * strings the plugin already collects (EGL/GLES probe and the Driver POST report) with a
     * Qualcomm platform fallback. Turnip must never be enabled on Mali/PowerVR/Xclipse.
     */
    fun detectGpu(hints: List<String>): TurnipGpu {
        val cleaned = hints.map { it.trim() }.filter { it.isNotEmpty() }
        val samsung = isSamsungDevice()
        val adrenoHint = cleaned.firstOrNull { it.contains("Adreno", ignoreCase = true) }
        if (adrenoHint != null) {
            val model = parseAdrenoModel(adrenoHint) ?: cleaned.firstNotNullOfOrNull { parseAdrenoModel(it) }
            val auto = autoVariantId(model, samsung)
            return TurnipGpu(
                kind = TurnipGpuKind.Adreno,
                label = adrenoHint,
                detail = buildString {
                    append(model?.let { "Adreno " + it } ?: "Adreno GPU")
                    if (model == 740 && samsung) append(" · OneUI / 三星")
                    append(" · 自动变体 ").append(variant(auto)?.label ?: auto)
                },
                model = model,
                samsung = samsung,
                autoVariantId = auto,
            )
        }
        val other = cleaned.firstOrNull { hint -> NON_ADRENO_GPUS.any { hint.contains(it, ignoreCase = true) } }
        if (other != null) {
            return TurnipGpu(
                kind = TurnipGpuKind.Other,
                label = other,
                detail = "检测到非 Adreno GPU，Turnip 不可用；将使用系统 Vulkan",
                samsung = samsung,
            )
        }
        val qualcomm = qualcommPlatformLabel()
        if (qualcomm != null) {
            return TurnipGpu(
                kind = TurnipGpuKind.Adreno,
                label = qualcomm,
                detail = "Qualcomm 平台（未读到 GPU 型号），自动变体 " + (variant(DEFAULT_VARIANT_ID)?.label ?: DEFAULT_VARIANT_ID),
                samsung = samsung,
                autoVariantId = DEFAULT_VARIANT_ID,
            )
        }
        return TurnipGpu(
            kind = TurnipGpuKind.Unknown,
            label = "未知 GPU",
            detail = "未检测到 Adreno，无法确认是否支持 Turnip；如为 Mali/PowerVR 请勿启用",
            samsung = samsung,
        )
    }

    private val coreCapabilityCache = HashMap<String, Pair<Long, Boolean>>()

    /**
     * True when the active libMobileGL.so was built with the built-in Turnip loader.
     *
     * Official MobileGL cores link the system libvulkan loader and do not contain the
     * MOBILEGL_TURNIP_* handling, so enabling Turnip with one is a silent no-op. The marker
     * is a stable string from TurnipLoader.cpp; scanning 11-16 MiB once per core file is
     * cheap compared to the confusion it prevents. [corePath] null means the bundled core.
     */
    fun coreSupportsTurnip(corePath: String?): Boolean {
        val file = if (corePath.isNullOrBlank()) {
            File(context.applicationInfo.nativeLibraryDir, "libMobileGL.so")
        } else {
            File(corePath)
        }
        if (!file.isFile) return false
        val stamp = file.length() xor file.lastModified()
        synchronized(coreCapabilityCache) {
            coreCapabilityCache[file.absolutePath]?.takeIf { it.first == stamp }?.let { return it.second }
        }
        val supported = runCatching { containsMarker(file, "MOBILEGL_TURNIP_ENABLED") }.getOrDefault(false)
        synchronized(coreCapabilityCache) { coreCapabilityCache[file.absolutePath] = stamp to supported }
        return supported
    }

    private fun containsMarker(file: File, marker: String): Boolean {
        val needle = marker.toByteArray(Charsets.US_ASCII)
        if (needle.isEmpty()) return true
        val buffer = ByteArray(1024 * 1024 + needle.size)
        file.inputStream().use { input ->
            var carry = 0
            while (true) {
                val read = input.read(buffer, carry, buffer.size - carry)
                if (read <= 0) break
                val length = carry + read
                if (indexOfBytes(buffer, length, needle) >= 0) return true
                carry = minOf(needle.size - 1, length)
                System.arraycopy(buffer, length - carry, buffer, 0, carry)
            }
        }
        return false
    }

    private fun indexOfBytes(haystack: ByteArray, length: Int, needle: ByteArray): Int {
        if (needle.size > length) return -1
        outer@ for (i in 0..length - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun isZipArchive(file: File): Boolean {
        val head = ByteArray(4)
        val read = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(-1)
        return read == 4 &&
            head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
    }

    /**
     * Extracts the AArch64 driver from a Banners-Turnip .zip bundle. The bundles put
     * libvulkan_freedreno.so and meta.json at the archive root; older ones use nested names,
     * so pick the largest .so mentioning turnip/freedreno and fall back to the largest .so.
     */
    private fun extractDriverFromZip(zip: File, out: File): Boolean {
        ZipFile(zip).use { archive ->
            val candidates = archive.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".so", true) }
                .sortedByDescending { it.size }
                .toList()
            if (candidates.isEmpty()) return false
            val entry = candidates.firstOrNull {
                it.name.contains("turnip", ignoreCase = true) || it.name.contains("freedreno", ignoreCase = true)
            } ?: candidates.first()
            var total = 0L
            out.outputStream().use { output ->
                archive.getInputStream(entry).use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_LIBRARY_BYTES) return false
                        output.write(buffer, 0, read)
                    }
                }
            }
            return total >= MIN_LIBRARY_BYTES
        }
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

    private fun parseAdrenoModel(text: String): Int? =
        ADRENO_MODEL.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun isSamsungDevice(): Boolean {
        val text = Build.MANUFACTURER.orEmpty() + " " + Build.BRAND.orEmpty()
        return text.contains("samsung", ignoreCase = true)
    }

    private fun hasTurnipMarker(file: File): Boolean {
        val text = runCatching { String(file.readBytes(), Charsets.ISO_8859_1) }.getOrNull() ?: return false
        return text.contains("freedreno", ignoreCase = true) || text.contains("turnip", ignoreCase = true)
    }

    private fun describe(file: File, source: TurnipDriverSource, variant: TurnipVariant?): TurnipDriver? {
        if (!file.isFile) return null
        if (file.length() < MIN_LIBRARY_BYTES) return null
        if (!isArm64Elf(file)) return null
        val info = versionInfo(file)
        return TurnipDriver(
            source = source,
            variant = variant,
            name = file.name,
            path = file.absolutePath,
            version = info.version,
            buildId = info.buildId,
            sizeBytes = file.length(),
        )
    }

    /** Cached scan of the library string table (each bundled variant is ~8 MiB). */
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
        return cleaned.ifBlank { "libturnip_imported.so" }
    }
}
