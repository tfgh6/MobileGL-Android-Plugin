// SPDX-License-Identifier: LGPL-3.0-only
package top.mobilegl.plugin.selftest

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.mobilegl.plugin.NativeEnv
import top.mobilegl.plugin.PostActivity
import top.mobilegl.plugin.config.PluginConfig
import java.io.File

data class CheckItem(
    val name: String,
    val status: String,
    val detail: String,
)

data class DriverBug(
    val name: String,
    val verdict: String,
    val detail: String,
)

data class FormatRow(
    val name: String,
    val fullMask: Long,
    val caveatMask: Long,
)

data class FormatTarget(
    val name: String,
    val rows: List<FormatRow>,
)

data class FormatCapabilities(
    val capabilities: List<String>,
    val targets: List<FormatTarget>,
)

data class BackendReport(
    val backend: String,
    val available: Boolean,
    val verdict: String,
    val renderer: String,
    val checks: List<CheckItem>,
    val knownDriverBugs: List<DriverBug>,
    val formatCapabilities: FormatCapabilities?,
)

data class SelfTestResult(
    val ok: Boolean,
    val error: String?,
    val backends: List<BackendReport>,
    val rawJson: String,
    val finishedAt: Long,
)

/**
 * Runs MobileGL's official Driver POST (same libMobileGL JNI entry the upstream
 * Action APK uses) and parses the full report: checks, known driver bugs and the
 * format capability matrix. This is the same JSON PostActivity renders upstream.
 */
class SelfTestRepository(private val context: Context) {
    private val dir = File(context.filesDir, "plugin")
    private val lastReport = File(dir, "last-post.json")

    @Volatile
    private var cached: SelfTestResult? = null

    fun cached(): SelfTestResult? = cached ?: loadLast()

    suspend fun run(config: PluginConfig, libraryPath: String? = null): SelfTestResult = withContext(Dispatchers.IO) {
        NativeEnv.apply(config)
        val raw = try {
            if (libraryPath.isNullOrBlank()) {
                PostActivity.runDriverPostJson()
            } else {
                PostActivity.runDriverPostJsonFrom(libraryPath)
            }
        } catch (t: Throwable) {
            return@withContext SelfTestResult(
                ok = false,
                error = t.message ?: t.toString(),
                backends = emptyList(),
                rawJson = "",
                finishedAt = System.currentTimeMillis(),
            )
        }
        val parsed = parse(raw)
        val result = parsed.copy(finishedAt = System.currentTimeMillis())
        cached = result
        save(raw)
        result
    }

    fun rawShareText(): String = cached()?.rawJson ?: ""

    private fun loadLast(): SelfTestResult? {
        if (!lastReport.isFile) return null
        return runCatching { parse(lastReport.readText()).copy(finishedAt = lastReport.lastModified()) }.getOrNull()
    }

    private fun save(raw: String) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "last-post.json.tmp")
            tmp.writeText(raw)
            if (!tmp.renameTo(lastReport)) {
                lastReport.writeText(raw)
                tmp.delete()
            }
        }
    }

    private fun parse(raw: String): SelfTestResult {
        val root = runCatching { JSONObject(raw) }.getOrElse {
            return SelfTestResult(false, "自检返回的不是合法 JSON", emptyList(), raw, System.currentTimeMillis())
        }
        val error = root.optString("error", "")
        if (error.isNotEmpty()) {
            return SelfTestResult(false, error, emptyList(), raw, System.currentTimeMillis())
        }

        val reports = mutableListOf<BackendReport>()
        val array = root.optJSONArray("backends")
        if (array != null) {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { reports.add(parseBackend(it, "backend")) }
            }
        } else {
            // Upstream extractBackends() accepts these four section names.
            val keys = listOf(
                "gles" to "DirectGLES",
                "DirectGLES" to "DirectGLES",
                "vulkan" to "DirectVulkan",
                "DirectVulkan" to "DirectVulkan",
            )
            val seen = mutableSetOf<String>()
            keys.forEach { (key, title) ->
                root.optJSONObject(key)?.let { backend ->
                    if (!backend.has("backend") && !backend.has("name")) {
                        runCatching { backend.put("backend", key) }
                    }
                    val parsed = parseBackend(backend, title)
                    if (seen.add(parsed.backend + "|" + parsed.renderer)) reports.add(parsed)
                }
            }
        }
        if (reports.isEmpty()) {
            return SelfTestResult(false, "自检报告中没有后端段落", emptyList(), raw, System.currentTimeMillis())
        }
        return SelfTestResult(true, null, reports, raw, System.currentTimeMillis())
    }

    private fun parseBackend(json: JSONObject, nameHint: String): BackendReport {
        val name = json.optString("backend").ifEmpty {
            json.optString("name").ifEmpty { nameHint }
        }
        val checks = mutableListOf<CheckItem>()
        json.optJSONArray("checks")?.let { array ->
            for (i in 0 until array.length()) {
                val check = array.optJSONObject(i) ?: continue
                checks.add(
                    CheckItem(
                        name = check.optString("name", "check"),
                        status = check.optString("status", "INFO"),
                        detail = check.optString("detail", check.optString("message", "")),
                    )
                )
            }
        }
        val bugs = mutableListOf<DriverBug>()
        json.optJSONArray("knownDriverBugs")?.let { array ->
            for (i in 0 until array.length()) {
                val bug = array.optJSONObject(i) ?: continue
                bugs.add(
                    DriverBug(
                        name = bug.optString("name", "unnamed bug"),
                        verdict = bug.optString("verdict", "UNFIXABLE"),
                        detail = bug.optString("detail", ""),
                    )
                )
            }
        }
        return BackendReport(
            backend = name,
            available = json.optBoolean("available", true),
            verdict = json.optString("verdict", "UNKNOWN"),
            renderer = json.optString("renderer", ""),
            checks = checks,
            knownDriverBugs = bugs,
            formatCapabilities = parseFormatCapabilities(json.optJSONObject("formatCapabilities")),
        )
    }

    private fun parseFormatCapabilities(json: JSONObject?): FormatCapabilities? {
        if (json == null) return null
        val capabilities = mutableListOf<String>()
        json.optJSONArray("capabilities")?.let { array ->
            for (i in 0 until array.length()) capabilities.add(array.optString(i, "capability $i"))
        }
        val targets = mutableListOf<FormatTarget>()
        json.optJSONArray("targets")?.let { array ->
            for (i in 0 until array.length()) {
                val target = array.optJSONObject(i) ?: continue
                val rows = mutableListOf<FormatRow>()
                target.optJSONArray("rows")?.let { rowsArray ->
                    for (j in 0 until rowsArray.length()) {
                        val row = rowsArray.optJSONArray(j) ?: continue
                        if (row.length() < 3) continue
                        rows.add(
                            FormatRow(
                                name = row.optString(0, "Unknown"),
                                fullMask = row.optLong(1, 0L),
                                caveatMask = row.optLong(2, 0L),
                            )
                        )
                    }
                }
                targets.add(FormatTarget(target.optString("name", "Unknown target"), rows))
            }
        }
        if (capabilities.isEmpty() || targets.isEmpty()) return null
        return FormatCapabilities(capabilities, targets)
    }
}
