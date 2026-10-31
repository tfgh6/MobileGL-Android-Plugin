// SPDX-License-Identifier: LGPL-3.0-only
package top.mobilegl.plugin.config

import android.content.Context
import org.json.JSONObject
import top.mobilegl.plugin.R
import java.io.File

/** JSON persistence for [PluginConfig]. Writes atomically and keeps a backup of the last good file. */
class ConfigStore(private val context: Context) {
    private val dir = File(context.filesDir, "plugin")
    private val file = File(dir, "config.json")
    private val backup = File(dir, "config.json.bak")

    @Synchronized
    fun load(): PluginConfig {
        dir.mkdirs()
        if (!file.isFile) {
            return PluginConfig(backend = defaultBackend(), env = PluginConfig.defaultEnv(defaultBackend()))
        }
        return try {
            PluginConfig.fromJson(JSONObject(file.readText()))
        } catch (t: Throwable) {
            if (backup.isFile) {
                runCatching { PluginConfig.fromJson(JSONObject(backup.readText())) }
                    .getOrElse { PluginConfig(backend = defaultBackend(), env = PluginConfig.defaultEnv(defaultBackend())) }
            } else {
                PluginConfig(backend = defaultBackend(), env = PluginConfig.defaultEnv(defaultBackend()))
            }
        }
    }

    @Synchronized
    fun save(config: PluginConfig) {
        dir.mkdirs()
        val json = config.toJson().toString(2)
        val tmp = File(dir, "config.json.tmp")
        tmp.writeText(json)
        if (file.isFile) {
            runCatching { file.copyTo(backup, overwrite = true) }
        }
        if (!tmp.renameTo(file)) {
            file.writeText(json)
            tmp.delete()
        }
    }

    fun configFile(): File {
        dir.mkdirs()
        return file
    }

    fun defaultBackend(): String = runCatching {
        context.getString(R.string.mobilegl_default_backend)
    }.getOrDefault("DirectVulkan")
}
