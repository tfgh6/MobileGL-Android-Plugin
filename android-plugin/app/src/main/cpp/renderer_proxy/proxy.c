// SPDX-License-Identifier: LGPL-3.0-only
// Runtime renderer dispatcher: reads /sdcard/MobileGL/runtime.conf and forwards
// every EGL/GL entry point either to SimpleFPEWrapper (and its MobileGL backend)
// or directly to a user-selected libMobileGL.so. Imported libraries are mapped
// through memfd so they do not need to live in the APK's nativeLibraryDir.
#define _GNU_SOURCE
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#include "proxy_symbols.h"
#include "vulkan_got_patch.h"

#define TAG "RendererProxy"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Never abort the whole game for one unwrapped extension: return 0 instead.
// EGL init paths are always resolved; this only guards optional Desktop-GL
// entry points that the backend may not implement.
static uintptr_t proxy_missing(void) {
    return 0;
}

static char g_lib_dir[PATH_MAX];

// Some applications probe GL_MAX_TEXTURE_SIZE before the backend has filled
// in its dynamic limits. MobileGL then returns 0 and the caller falls back to
// 16384; keep the probe useful by clamping only the zero case.
static void (*g_real_glGetIntegerv)(uint32_t pname, int32_t *data);
static void proxy_glGetIntegerv(uint32_t pname, int32_t *data) {
    if (g_real_glGetIntegerv) g_real_glGetIntegerv(pname, data);
    if (data && pname == 0x0D33 /* GL_MAX_TEXTURE_SIZE */ && *data <= 0) {
        *data = 16384;
        LOGE("GL_MAX_TEXTURE_SIZE probe returned 0; clamped to 16384");
    }
}


static const char *sibling_path(const char *name, char *out, size_t out_len) {
    if (g_lib_dir[0] && name[0] != '/') {
        snprintf(out, out_len, "%s/%s", g_lib_dir, name);
        return out;
    }
    snprintf(out, out_len, "%s", name);
    return out;
}

static void *dlopen_sibling(const char *name) {
    void *handle = dlopen(name, RTLD_NOW | RTLD_LOCAL);
    if (handle) return handle;
    if (g_lib_dir[0] && name[0] != '/') {
        char path[PATH_MAX];
        snprintf(path, sizeof(path), "%s/%s", g_lib_dir, name);
        handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
        if (handle) return handle;
    }
    return NULL;
}

static void trim(char *s) {
    if (!s) return;
    size_t n = strlen(s);
    while (n > 0 && (s[n - 1] == '\n' || s[n - 1] == '\r' || s[n - 1] == ' ' || s[n - 1] == '\t')) s[--n] = 0;
    char *p = s;
    while (*p == ' ' || *p == '\t') p++;
    if (p != s) memmove(s, p, strlen(p) + 1);
}

static int truthy(const char *value) {
    if (!value || !value[0]) return 0;
    return strcmp(value, "0") != 0 && strcasecmp(value, "false") != 0;
}

// Clean-room experiment build: an earlier install may have left
// /sdcard/MobileGL/runtime.conf pointing at an older external core (RENDERER_SO)
// plus a stale version snapshot. Ignore that file completely and always load the
// libMobileGL.so bundled in this APK, so the running renderer matches the package.
static int read_config(char *mode, size_t mode_len, char *so, size_t so_len) {
    mode[0] = 0;
    so[0] = 0;
    snprintf(mode, mode_len, "direct");
    LOGI("clean-room proxy: ignoring runtime.conf; loading the bundled libMobileGL.so");
    return 0;
}

// Load a .so that lives outside the app's native library directory: copy it into
// an anonymous memfd and dlopen /proc/self/fd/N. This mirrors libadrenotools and
// keeps working where direct dlopen of /sdcard is denied (noexec / W^X).
static void *memfd_load(const char *path) {
    int in = open(path, O_RDONLY | O_CLOEXEC);
    if (in < 0) return NULL;
    int fd = (int)syscall(__NR_memfd_create, "renderer", 0x0001 /* MFD_CLOEXEC */);
    if (fd < 0) {
        close(in);
        return NULL;
    }
    char buffer[64 * 1024];
    ssize_t n;
    while ((n = read(in, buffer, sizeof(buffer))) > 0) {
        ssize_t off = 0;
        while (off < n) {
            ssize_t written = write(fd, buffer + off, (size_t)(n - off));
            if (written <= 0) {
                close(in);
                close(fd);
                return NULL;
            }
            off += written;
        }
    }
    close(in);
    if (n < 0) {
        close(fd);
        return NULL;
    }
    char proc[64];
    snprintf(proc, sizeof(proc), "/proc/self/fd/%d", fd);
    void *handle = dlopen(proc, RTLD_NOW | RTLD_LOCAL);
    if (!handle) LOGE("memfd dlopen failed for %s: %s", path, dlerror());
    // The mapped library keeps its own reference; closing the fd is fine.
    close(fd);
    return handle;
}

__attribute__((constructor)) static void init_renderer_proxy(void) {
    Dl_info self_info;
    if (dladdr((void *)&init_renderer_proxy, &self_info) && self_info.dli_fname) {
        snprintf(g_lib_dir, sizeof(g_lib_dir), "%s", self_info.dli_fname);
        char *slash = strrchr(g_lib_dir, '/');
        if (slash) *slash = 0;
    }
    if (!g_lib_dir[0]) snprintf(g_lib_dir, sizeof(g_lib_dir), "/data/app");
    LOGI("proxy library dir: %s", g_lib_dir);
    const char *launcher_renderer = getenv("POJAV_RENDERER");
    const char *vulkan_ptr = getenv("VULKAN_PTR");
    const char *driver_path = getenv("DRIVER_PATH");
    const char *backend_type = getenv("MOBILEGL_BACKEND_TYPE");
    LOGI("launcher env: POJAV_RENDERER=%s VULKAN_PTR=%s DRIVER_PATH=%s MOBILEGL_BACKEND_TYPE=%s",
         launcher_renderer ? launcher_renderer : "(unset)",
         vulkan_ptr ? vulkan_ptr : "(unset)",
         driver_path ? driver_path : "(unset)",
         backend_type ? backend_type : "(unset)");
    char mode[16];
    char so[512];
    int file_ok = read_config(mode, sizeof(mode), so, sizeof(so));
    // Drop stale routing variables from earlier builds; launcher-provided env stays.
    unsetenv("RENDERER_SO");
    unsetenv("RENDERER_MODE");
    unsetenv("SFPEW_ENABLED");

    void *target = NULL;
    void *direct = NULL;
    // A stale /sdcard/MobileGL/runtime.conf must not influence ConfigLoader either:
    // point it at a path that does not exist so only the launcher environment applies.
    setenv("MOBILEGL_CONFIG_FILE", "/data/local/tmp/mobilegl-kopper-no-config.conf", 1);
    if (strcmp(mode, "sfpew") == 0) {
        // SFPEW branch: load the wrapper by an absolute sibling path. The game
        // process does not put the plugin APK's lib dir on the default linker
        // search path, so dlopen("libSfpew_real.so") alone fails there.
        char sfpew_path[PATH_MAX];
        sibling_path("libSfpew_real.so", sfpew_path, sizeof(sfpew_path));
        target = dlopen(sfpew_path, RTLD_NOW | RTLD_LOCAL);
        if (!target) {
            target = dlopen("libSfpew_real.so", RTLD_NOW | RTLD_LOCAL);
        }
        char backend_path[PATH_MAX];
        if (so[0]) {
            sibling_path(so, backend_path, sizeof(backend_path));
        } else {
            sibling_path("libMobileGL.so", backend_path, sizeof(backend_path));
        }
        setenv("SFPEW_EGL", backend_path, 1);
        if (target) {
            LOGI("SFPEW enabled: %s loaded, backend=%s", sfpew_path, backend_path);
        } else {
            LOGE("SFPEW enabled but libSfpew_real.so failed: %s; using direct MobileGL", dlerror());
        }
        direct = dlopen_sibling("libMobileGL.so");
        if (!direct) LOGE("libMobileGL.so not loadable: %s", dlerror());
        if (!target) target = direct;
    } else {
        // Direct branch: do not touch libSfpew_real.so at all.
        LOGI("SFPEW disabled (config=%s): loading MobileGL directly", file_ok ? "file" : "default");
        if (so[0]) {
            char direct_path[PATH_MAX];
            sibling_path(so, direct_path, sizeof(direct_path));
            target = strchr(so, '/') ? memfd_load(so) : dlopen_sibling(so);
            if (target) LOGI("direct renderer loaded: %s", direct_path);
            else LOGE("cannot load direct renderer %s: %s", direct_path, dlerror());
        }
        if (!target) {
            target = dlopen_sibling("libMobileGL.so");
            if (target) LOGI("direct renderer loaded: libMobileGL.so");
            else LOGE("libMobileGL.so not loadable: %s", dlerror());
        }
        direct = target;
    }

    // External Vulkan loader bridge: libMobileGL.so is used exactly as shipped (its
    // DT_NEEDED libvulkan.so and direct vk* imports are untouched). libvulkanshim.so
    // forwards every Vulkan entry point to the loader the host launcher prepared
    // (VULKAN_PTR, e.g. Turnip through FCL/ZL2's Vulkan/Zink path), and we rebind the
    // core's GOT slots to that shim here. This also works when the system libvulkan.so
    // was already loaded by the launcher's capability probes.
    {
        void *vulkanShim = dlopen_sibling("libvulkanshim.so");
        if (vulkanShim != NULL) {
            void (*reinit)(void) = (void (*)(void))dlsym(vulkanShim, "MGVkShimReinit");
            if (reinit != NULL) reinit();
            void *coreForPatch = (direct != NULL) ? direct : target;
            const int patched = MGPatchVulkanImports(coreForPatch, vulkanShim);
            LOGI("Vulkan shim loaded: %p, patched GOT slots=%d", vulkanShim, patched);
        } else {
            LOGE("libvulkanshim.so not loadable: %s", dlerror());
        }
    }

    unsigned missing = 0;
    for (unsigned i = 0; i < g_proxy_symbol_count; ++i) {
        void *fn = target ? dlsym(target, g_proxy_symbols[i].name) : NULL;
        if (!fn && direct && direct != target) fn = dlsym(direct, g_proxy_symbols[i].name);
        if (!fn) {
            if (missing < 8) LOGE("symbol not found in backend: %s", g_proxy_symbols[i].name);
            ++missing;
            *g_proxy_symbols[i].slot = (void *)proxy_missing;
        } else if (strcmp(g_proxy_symbols[i].name, "glGetIntegerv") == 0) {
            g_real_glGetIntegerv = (void (*)(uint32_t, int32_t *))fn;
            *g_proxy_symbols[i].slot = (void *)proxy_glGetIntegerv;
        } else {
            *g_proxy_symbols[i].slot = fn;
        }
    }
    LOGI("renderer proxy ready: mode=%s target=%p missing=%u/%u", mode, target, missing, g_proxy_symbol_count);
}