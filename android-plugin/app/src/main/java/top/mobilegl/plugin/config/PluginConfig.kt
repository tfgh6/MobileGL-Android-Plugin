// SPDX-License-Identifier: LGPL-3.0-only
package top.mobilegl.plugin.config

import org.json.JSONObject

/**
 * User-visible configuration of the MobileGL renderer plugin.
 *
 * The renderer itself consumes MOBILEGL_* and LIBGL_* variables. We persist the UI state as
 * JSON (filesDir/plugin/config.json) and also emit a key=value runtime file that the native
 * ConfigLoader can read from shared storage (see RuntimeConfigWriter).
 */
data class PluginConfig(
    val backend: String = "DirectVulkan",
    val sfpewEnabled: Boolean = false,
    val autoSelfTest: Boolean = false,
    val activeDriverId: String? = null,
    val activeIcdPath: String? = null,
    val selfTestLibraryPath: String? = null,
    val activeRendererLibPath: String? = null,
    val activeRendererVersion: String? = null,
    val activeRendererCommit: String? = null,
    val env: Map<String, String> = defaultEnv("DirectVulkan"),
    val theme: String = "dark",
) {
    fun toJson(): JSONObject {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("backend", backend)
        root.put("sfpewEnabled", sfpewEnabled)
        root.put("autoSelfTest", autoSelfTest)
        root.put("activeDriverId", activeDriverId ?: JSONObject.NULL)
        root.put("activeIcdPath", activeIcdPath ?: JSONObject.NULL)
        root.put("selfTestLibraryPath", selfTestLibraryPath ?: JSONObject.NULL)
        root.put("activeRendererLibPath", activeRendererLibPath ?: JSONObject.NULL)
        root.put("activeRendererVersion", activeRendererVersion ?: JSONObject.NULL)
        root.put("activeRendererCommit", activeRendererCommit ?: JSONObject.NULL)
        val envJson = JSONObject()
        env.forEach { (key, value) -> envJson.put(key, value) }
        root.put("env", envJson)
        root.put("theme", theme)
        return root
    }

    companion object {
        const val SCHEMA_VERSION = 1

        fun defaultEnv(backend: String): Map<String, String> = linkedMapOf(
            "MOBILEGL_BACKEND_TYPE" to backend,
            "LIBGL_ES" to "3",
            "POJAV_RENDERER" to "opengles3",
        )

        fun fromJson(root: JSONObject): PluginConfig {
            val backend = root.optString("backend", "DirectVulkan")
            val envJson = root.optJSONObject("env")
            val env = LinkedHashMap<String, String>()
            if (envJson != null) {
                val keys = envJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = envJson.optString(key, "")
                    if (key.isNotBlank()) {
                        env[key] = value
                    }
                }
            }
            return PluginConfig(
                backend = backend,
                sfpewEnabled = root.optBoolean("sfpewEnabled", false),
                autoSelfTest = root.optBoolean("autoSelfTest", false),
                activeDriverId = if (root.isNull("activeDriverId")) null else root.optString("activeDriverId").ifEmpty { null },
                activeIcdPath = if (root.isNull("activeIcdPath")) null else root.optString("activeIcdPath").ifEmpty { null },
                selfTestLibraryPath = if (root.isNull("selfTestLibraryPath")) null else root.optString("selfTestLibraryPath").ifEmpty { null },
                activeRendererLibPath = if (root.isNull("activeRendererLibPath")) null else root.optString("activeRendererLibPath").ifEmpty { null },
                activeRendererVersion = if (root.isNull("activeRendererVersion")) null else root.optString("activeRendererVersion").ifEmpty { null },
                activeRendererCommit = if (root.isNull("activeRendererCommit")) null else root.optString("activeRendererCommit").ifEmpty { null },
                env = env,
                theme = root.optString("theme", "dark"),
            )
        }
    }
}