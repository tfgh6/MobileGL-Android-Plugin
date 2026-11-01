// SPDX-License-Identifier: LGPL-3.0-only
package top.mobilegl.plugin.driver

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Imported .so files. Two kinds are understood:
 *  - libMobileGL.so: an updated renderer library (used for the in-plugin self-test;
 *    the game process still loads the copy packaged in the APK, because that is what
 *    the launcher resolves from the plugin);
 *  - libSimpleFPEWrapper.so: SimpleFPEWrapper, a fixed-function front end that can sit
 *    in front of MobileGL or the system GLES driver.
 *
 * Files are validated as 64-bit little-endian AArch64 ELF before they are stored.
 */
data class ExternalLibrary(
    val name: String,
    val file: File,
    val sharedFile: File?,
    val size: Long,
    val sha256: String,
    val importedAt: Long,
) {
    fun isRenderer(): Boolean = name.contains("MobileGL", ignoreCase = true) && !name.contains("FPE")
    fun isSfpew(): Boolean = name.contains("SimpleFPE", ignoreCase = true) || name.contains("FPEWrapper", ignoreCase = true)
}

class ExternalLibraryRepository(private val context: Context) {
    private val root = File(context.filesDir, "libraries")
    private val sharedRoot = File("/sdcard/MobileGL/libs")

    fun sharedDir(): File = sharedRoot

    fun list(): List<ExternalLibrary> {
        root.mkdirs()
        return (root.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".so") }
            .sortedByDescending { it.lastModified() }
            .mapNotNull { file ->
                runCatching {
                    ExternalLibrary(
                        name = file.name,
                        file = file,
                        sharedFile = File(sharedRoot, file.name).takeIf { it.isFile },
                        size = file.length(),
                        sha256 = sha256(file),
                        importedAt = file.lastModified(),
                    )
                }.getOrNull()
            }
    }

    suspend fun import(uri: Uri): Result<ExternalLibrary> = withContext(Dispatchers.IO) {
        runCatching {
            val temp = File(context.cacheDir, "import-" + System.currentTimeMillis() + ".so")
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    input.copyTo(output, 1024 * 1024)
                }
            } ?: error("无法读取所选文件")
            if (temp.length() > 128L * 1024 * 1024) error("文件超过 128 MiB")
            if (!isArm64Elf(temp)) error("不是 arm64 ELF（需要 AArch64 64 位小端 .so）")
            if (!isMobileGlLibrary(temp)) error("不是 MobileGL 的 .so（需要包含 MobileGL Driver POST / EGL 符号）")
            val baseName = sanitize(uri.lastPathSegment?.substringAfterLast('/') ?: "imported.so")
            val name = if (baseName.endsWith(".so")) baseName else baseName + ".so"
            root.mkdirs()
            val target = File(root, name)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            // Shared copy: the game process (launcher UID) cannot read this app's
            // filesDir, but libRendererProxy.so can memfd-load a .so from /sdcard.
            val shared = runCatching {
                sharedRoot.mkdirs()
                val out = File(sharedRoot, name)
                target.copyTo(out, overwrite = true)
                out
            }.getOrNull()
            ExternalLibrary(name, target, shared, target.length(), sha256(target), System.currentTimeMillis())
        }
    }

    fun delete(library: ExternalLibrary): Boolean {
        val canonicalRoot = root.canonicalPath + File.separator
        if (!library.file.canonicalPath.startsWith(canonicalRoot)) return false
        library.sharedFile?.delete()
        return library.file.delete()
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)

    private fun isMobileGlLibrary(file: File): Boolean {
        if (file.length() > 64L * 1024 * 1024) return false
        val text = runCatching { String(file.readBytes(), Charsets.ISO_8859_1) }.getOrNull() ?: return false
        if (!text.contains("MobileGL")) return false
        val hasPost = text.contains("nativeRunDriverPost") || text.contains("MobileGL Driver POST")
        val hasEgl = text.contains("eglGetDisplay") && text.contains("glGetString")
        return hasPost || hasEgl
    }

    private fun isArm64Elf(file: File): Boolean {
        val header = ByteArray(20)
        val read = file.inputStream().use { it.read(header) }
        if (read < 20) return false
        if (header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
        ) return false
        val is64 = header[4].toInt() == 2
        val le = header[5].toInt() == 1
        val machine = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
        return is64 && le && machine == 0xB7
    }

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
}

/** Best-effort read of the MobileGL version/GIT revision from an imported .so. */
fun scanMobileGlVersion(file: File): Pair<String?, String?> {
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null to null
    val text = String(bytes, Charsets.ISO_8859_1)
    val versions = Regex("MobileGL\\s+([0-9][0-9A-Za-z._+\\-]{0,40})").findAll(text).toList()
    val commits = Regex("GIT@([0-9a-fA-F]{6,40})").findAll(text).toList()
    if (versions.isEmpty()) return null to commits.firstOrNull()?.groupValues?.getOrNull(1)
    // Pick the version string closest to a GIT@ marker: large binaries contain
    // unrelated "MobileGL <version>" strings in embedded docs/constants.
    var bestVersion = versions.last()
    var bestCommit: MatchResult? = commits.firstOrNull()
    var bestDistance = Int.MAX_VALUE
    for (version in versions) {
        for (commit in commits) {
            val distance = kotlin.math.abs(version.range.first - commit.range.first)
            if (distance < bestDistance) {
                bestDistance = distance
                bestVersion = version
                bestCommit = commit
            }
        }
    }
    return bestVersion.groupValues.getOrNull(1) to bestCommit?.groupValues?.getOrNull(1)
}