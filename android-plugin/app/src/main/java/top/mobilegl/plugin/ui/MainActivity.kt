// SPDX-License-Identifier: LGPL-3.0-only
package top.mobilegl.plugin.ui

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import top.mobilegl.plugin.BuildConfig
import top.mobilegl.plugin.NativeEnv
import top.mobilegl.plugin.config.ConfigStore

/**
 * Configuration UI of the MobileGL renderer plugin. The renderer itself is loaded by the
 * Minecraft launcher; this activity only edits MobileGL's configuration and runs the existing
 * Driver POST for diagnostics.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // System UI stays visible (status/navigation bars), like MobileGlues 2.0.
        // Draw edge to edge so the app background reaches behind the transparent bars.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        val store = ConfigStore(this)
        val config = store.load()
        // Apply before libMobileGL.so can be loaded by the self-test screen.
        NativeEnv.apply(config)
        // Debug build: load the renderer proxy once so logcat shows that the
        // dispatcher and its backend resolve; harmless for the self-test process.
        if (BuildConfig.DEBUG) {
            runCatching { System.loadLibrary("RendererProxy") }
        }
        setContent { MobileGlPluginApp() }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("mobilegl_plugin", MODE_PRIVATE)
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else true
        if (granted && prefs.getBoolean("awaiting_restart", false)) {
            prefs.edit().putBoolean("awaiting_restart", false).apply()
            recreate()
        }
    }
}