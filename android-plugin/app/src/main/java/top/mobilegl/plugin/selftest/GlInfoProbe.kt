// SPDX-License-Identifier: LGPL-3.0-only
package top.mobilegl.plugin.selftest

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20

/**
 * Same detection idea as MobileGlues 2.0: create an EGL pbuffer context and ask
 * the driver through glGetString, instead of guessing from the Vulkan report.
 * Runs fine from a plain Kotlin thread; no native helper required.
 */
object GlInfoProbe {
    data class Info(
        val renderer: String,
        val vendor: String,
        val version: String,
    ) {
        val glesShort: String
            get() = Regex("OpenGL ES [0-9.]+").find(version)?.value ?: version
    }

    fun query(): Info? {
        var display: EGLDisplay? = null
        var context: EGLContext? = null
        var surface: EGLSurface? = null
        var config: EGLConfig? = null
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return null
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null

            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] <= 0) return null
            config = configs[0] ?: return null

            val surfaceAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE,
            )
            surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) return null

            for (clientVersion in intArrayOf(3, 2)) {
                val contextAttribs = intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, clientVersion,
                    EGL14.EGL_NONE,
                )
                context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
                if (context != EGL14.EGL_NO_CONTEXT) break
            }
            if (context == null || context == EGL14.EGL_NO_CONTEXT) return null
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null

            val renderer = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty()
            val vendor = GLES20.glGetString(GLES20.GL_VENDOR).orEmpty()
            val glVersion = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
            if (renderer.isBlank() && glVersion.isBlank()) return null
            return Info(renderer, vendor, glVersion)
        } catch (_: Throwable) {
            return null
        } finally {
            runCatching {
                display?.let { dpy ->
                    EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    context?.let { EGL14.eglDestroyContext(dpy, it) }
                    surface?.let { EGL14.eglDestroySurface(dpy, it) }
                    EGL14.eglTerminate(dpy)
                }
            }
        }
    }
}
