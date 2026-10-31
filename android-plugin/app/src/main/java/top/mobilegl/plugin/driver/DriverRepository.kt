// SPDX-License-Identifier: LGPL-3.0-only
package top.mobilegl.plugin.driver

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * A driver pack is a zip with a driver.json manifest at its root:
 *
 * {
 *   "id": "panvk-g925-<sha8>",
 *   "name": "Mesa PanVK (Mali-G925)",
 *   "version": "26.3.0-devel",
 *   "mesaVersion": "26.3.0-devel",
 *   "abi": "arm64-v8a",
 *   "kind": "vulkan-icd",         // or "gl" / "unknown"
 *   "library": "lib/libvulkan_panfrost.so",
 *   "icd": "panvk_icd.json"       // optional; may contain @LIBRARY@ placeholder
 * }
 *
 * This is deliberately the same shape the PanVK phase will ship. In this phase the pack is
 * stored and its ICD (if any) can be materialized, but MobileGL still links the system Vulkan
 * loader, so activation alone does not change the renderer yet.
 */
data class DriverPack(
    val id: String,
    val name: String,
    val version: String,
    val mesaVersion: String,
    val abi: String,
    val kind: String,
    val library: String,
    val icdTemplate: String?,
    val sha256: String,
    val installedAt: Long,
    val dir: File,
) {
    fun libraryFile(): File = File(dir, library)
    fun isVulkanIcd(): Boolean = kind == "vulkan-icd"
    fun generatedIcdFile(): File = File(dir, "icd.generated.json")
}

class DriverRepository(private val context: Context) {
    private val root = File(context.filesDir, "drivers")

    fun rootDir(): File {
        root.mkdirs()
        return root
    }

    fun list(): List<DriverPack> {
        val dirs = rootDir().listFiles() ?: return emptyList()
        return dirs.filter { it.isDirectory }
            .mapNotNull { parsePack(it) }
            .sortedBy { it.name.lowercase() }
    }

    fun generateIcd(pack: DriverPack): File? {
        if (!pack.isVulkanIcd()) return null
        val templateName = pack.icdTemplate ?: return null
        val template = File(pack.dir, templateName)
        if (!template.isFile) return null
        val libraryPath = pack.libraryFile().absolutePath
        val rendered = template.readText()
            .replace("@LIBRARY@", libraryPath)
            .replace("\${LIBRARY}", libraryPath)
        val out = pack.generatedIcdFile()
        out.writeText(rendered)
        return out
    }

    fun delete(pack: DriverPack): Boolean {
        val canonicalRoot = rootDir().canonicalPath + File.separator
        if (!pack.dir.canonicalPath.startsWith(canonicalRoot)) return false
        return pack.dir.deleteRecursively()
    }

    suspend fun install(uri: Uri): Result<DriverPack> = withContext(Dispatchers.IO) {
        runCatching { installBlocking(uri) }
    }

    private fun installBlocking(uri: Uri): DriverPack {
        val work = File(context.cacheDir, "driver-import-" + System.currentTimeMillis())
        work.mkdirs()
        try {
            val zip = File(work, "driver.zip")
            context.contentResolver.openInputStream(uri)?.use { input ->
                zip.outputStream().use { output -> copyLimited(input, output, MAX_ZIP_BYTES) }
            } ?: error("无法读取所选文件")

            if (zip.length() <= 0L) error("驱动包为空")
            val extractDir = File(work, "extract")
            extractDir.mkdirs()
            extractZip(zip, extractDir)

            val manifestFile = File(extractDir, "driver.json")
            if (!manifestFile.isFile) error("驱动包根目录缺少 driver.json")
            val manifest = JSONObject(manifestFile.readText())

            val id = manifest.optString("id").also {
                if (!it.matches(Regex("[A-Za-z0-9._-]{1,64}"))) error("driver.json 的 id 非法")
            }
            val name = manifest.optString("name", id)
            val version = manifest.optString("version", "unknown")
            val mesaVersion = manifest.optString("mesaVersion", "")
            val abi = manifest.optString("abi", "arm64-v8a")
            if (!abi.startsWith("arm64")) error("仅支持 arm64 驱动，当前声明为 " + abi)
            val kind = manifest.optString("kind", "unknown")
            val library = manifest.optString("library")
            if (library.isBlank()) error("driver.json 缺少 library")
            val libraryFile = File(extractDir, library)
            if (!libraryFile.isFile) error("driver.json 指向的库不存在: " + library)
            if (!libraryFile.canonicalPath.startsWith(extractDir.canonicalPath + File.separator)) {
                error("library 路径非法")
            }
            if (!isArm64Elf(libraryFile)) error("library 不是 arm64 ELF: " + library)
            val icd = manifest.optString("icd").ifEmpty { null }
            if (icd != null && !File(extractDir, icd).isFile) error("driver.json 指向的 ICD 模板不存在: " + icd)

            val target = File(rootDir(), id)
            if (target.exists()) error("已安装同 id 的驱动: " + id)

            extractDir.copyRecursively(target, overwrite = false)
            if (parsePack(target) == null) {
                target.deleteRecursively()
                error("驱动包解析失败")
            }
            return parsePack(target) ?: error("驱动包解析失败")
        } finally {
            work.deleteRecursively()
        }
    }

    private fun parsePack(dir: File): DriverPack? {
        val manifest = File(dir, "driver.json")
        if (!manifest.isFile) return null
        return runCatching {
            val json = JSONObject(manifest.readText())
            val library = json.optString("library")
            if (library.isBlank()) return@runCatching null
            DriverPack(
                id = json.optString("id", dir.name),
                name = json.optString("name", dir.name),
                version = json.optString("version", "unknown"),
                mesaVersion = json.optString("mesaVersion", ""),
                abi = json.optString("abi", "arm64-v8a"),
                kind = json.optString("kind", "unknown"),
                library = library,
                icdTemplate = json.optString("icd").ifEmpty { null },
                sha256 = json.optString("sha256", ""),
                installedAt = dir.lastModified(),
                dir = dir,
            )
        }.getOrNull()
    }

    private fun extractZip(zip: File, destination: File) {
        val destinationCanonical = destination.canonicalPath + File.separator
        var total = 0L
        var entries = 0
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entries += 1
                if (entries > MAX_ENTRIES) error("驱动包条目过多")
                val normalized = entry.name.replace('\\', '/')
                if (normalized.startsWith("/") || normalized.split('/').contains("..")) {
                    error("驱动包包含非法路径: " + entry.name)
                }
                val outFile = File(destination, normalized)
                if (!outFile.canonicalPath.startsWith(destinationCanonical) && outFile.canonicalPath != destination.canonicalPath) {
                    error("驱动包路径越界: " + entry.name)
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_UNCOMPRESSED_BYTES) error("驱动包解压后过大")
                            output.write(buffer, 0, read)
                        }
                    }
                }
                input.closeEntry()
            }
        }
    }

    private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) error("驱动包超过大小上限")
            output.write(buffer, 0, read)
        }
    }

    private fun isArm64Elf(file: File): Boolean {
        val header = ByteArray(20)
        val read = file.inputStream().use { it.read(header) }
        if (read < 20) return false
        if (header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
        ) return false
        val is64 = header[4].toInt() == 2
        val isLittleEndian = header[5].toInt() == 1
        val machine = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
        return is64 && isLittleEndian && machine == 0xB7
    }

    @Suppress("unused")
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_ZIP_BYTES = 512L * 1024 * 1024
        private const val MAX_UNCOMPRESSED_BYTES = 1024L * 1024 * 1024
        private const val MAX_ENTRIES = 10_000
    }
}
