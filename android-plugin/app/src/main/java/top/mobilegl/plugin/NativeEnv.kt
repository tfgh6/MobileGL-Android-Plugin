// SPDX-License-Identifier: LGPL-3.0-only
package top.mobilegl.plugin

import android.system.Os
import top.mobilegl.plugin.config.PluginConfig

/**
 * Applies the user's configuration to the current process environment.
 *
 * MobileGL reads its settings from environment variables (see MobileGL/ConfigLoader.cpp),
 * so this must run before libMobileGL.so is loaded for the first time. In the game process
 * the launcher sets the renderer environment from the plugin manifest; here we mirror the
 * config for the in-plugin self-test process and, later, for a bundled Vulkan ICD.
 */
object NativeEnv {
    private val keyPattern = Regex("[A-Z][A-Z0-9_]{1,63}")

    @Synchronized
    fun apply(config: PluginConfig) {
        // Same base environment the launcher plugin declares in boatEnv/pojavEnv.
        set("LIBGL_ES", "3")
        set("POJAV_RENDERER", "opengles3")
        config.env.forEach { (key, value) ->
            if (key != "MOBILEGL_BACKEND_TYPE" && keyPattern.matches(key) && value.isNotBlank()) {
                set(key, value)
            }
        }
        // Apply last so a stale MOBILEGL_BACKEND_TYPE in env cannot override the UI choice.
        set("MOBILEGL_BACKEND_TYPE", config.backend)
        // Reserved for the PanVK phase: only active when a driver pack generated an ICD.
        val icd = config.activeIcdPath
        if (!icd.isNullOrBlank()) {
            set("VK_ICD_FILENAMES", icd)
            set("VK_DRIVER_FILES", icd)
            set("VK_LOADER_DEBUG", "error,warn")
        }
    }

    private fun set(key: String, value: String) {
        try {
            Os.setenv(key, value, true)
        } catch (_: Throwable) {
            // setenv is best effort: a denied variable must not take the plugin down.
        }
    }
}
