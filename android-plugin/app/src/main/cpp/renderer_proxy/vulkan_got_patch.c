// SPDX-License-Identifier: LGPL-3.0-only
// GOT patcher: binds libMobileGL.so's imported Vulkan entry points to libvulkanshim.so
// without touching the core binary. Works even when the system libvulkan.so was already
// loaded into the process (the launcher's capability probes do that on some devices).
#define _GNU_SOURCE
#include "vulkan_got_patch.h"

#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#define TAG "VulkanShim"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef struct {
    uintptr_t base;
    const ElfW(Sym)* symtab;
    const char* strtab;
    const ElfW(Rela)* jmprel;
    size_t jmprelCount;
    const ElfW(Rela)* rela;
    size_t relaCount;
} ElfView;

static int ParseElf(uintptr_t base, ElfView* view) {
    const ElfW(Ehdr)* eh = (const ElfW(Ehdr)*)base;
    if (memcmp(eh->e_ident, ELFMAG, SELFMAG) != 0) return 0;
    if (eh->e_machine != EM_AARCH64) return 0;
    const ElfW(Phdr)* ph = (const ElfW(Phdr)*)(base + eh->e_phoff);
    const ElfW(Dyn)* dyn = NULL;
    for (int i = 0; i < eh->e_phnum; ++i) {
        if (ph[i].p_type == PT_DYNAMIC) {
            dyn = (const ElfW(Dyn)*)(base + ph[i].p_vaddr);
            break;
        }
    }
    if (dyn == NULL) return 0;

    memset(view, 0, sizeof(*view));
    view->base = base;
    size_t jmprelSize = 0;
    size_t relaSize = 0;
    for (const ElfW(Dyn)* d = dyn; d->d_tag != DT_NULL; ++d) {
        switch (d->d_tag) {
        case DT_SYMTAB:
            view->symtab = (const ElfW(Sym)*)(base + d->d_un.d_ptr);
            break;
        case DT_STRTAB:
            view->strtab = (const char*)(base + d->d_un.d_ptr);
            break;
        case DT_JMPREL:
            view->jmprel = (const ElfW(Rela)*)(base + d->d_un.d_ptr);
            break;
        case DT_PLTRELSZ:
            jmprelSize = (size_t)d->d_un.d_val;
            break;
        case DT_RELA:
            view->rela = (const ElfW(Rela)*)(base + d->d_un.d_ptr);
            break;
        case DT_RELASZ:
            relaSize = (size_t)d->d_un.d_val;
            break;
        default:
            break;
        }
    }
    if (view->symtab == NULL || view->strtab == NULL) return 0;
    view->jmprelCount = jmprelSize / sizeof(ElfW(Rela));
    view->relaCount = relaSize / sizeof(ElfW(Rela));
    return 1;
}

static int PatchRelocations(ElfView* view, const ElfW(Rela)* rel, size_t count, void* shim) {
    int patched = 0;
    const long pageSize = sysconf(_SC_PAGESIZE);
    for (size_t i = 0; i < count; ++i) {
        const unsigned type = (unsigned)ELF64_R_TYPE(rel[i].r_info);
        if (type != R_AARCH64_JUMP_SLOT && type != R_AARCH64_GLOB_DAT) continue;
        const ElfW(Sym)* sym = &view->symtab[ELF64_R_SYM(rel[i].r_info)];
        const char* name = view->strtab + sym->st_name;
        if (strncmp(name, "vk", 2) != 0) continue;
        void* target = dlsym(shim, name);
        if (target == NULL) continue;
        void** slot = (void**)(view->base + rel[i].r_offset);
        void* pageStart = (void*)((uintptr_t)slot & ~(uintptr_t)(pageSize - 1));
        if (mprotect(pageStart, (size_t)pageSize, PROT_READ | PROT_WRITE) != 0) continue;
        *slot = target;
        __builtin___clear_cache((char*)slot, (char*)slot + sizeof(void*));
        ++patched;
    }
    return patched;
}

int MGPatchVulkanImports(void* core_handle, void* shim_handle) {
    if (core_handle == NULL || shim_handle == NULL) return -1;
    void* anchor = dlsym(core_handle, "eglGetProcAddress");
    if (anchor == NULL) anchor = dlsym(core_handle, "glGetString");
    if (anchor == NULL) {
        LOGE("no anchor symbol in the core");
        return -1;
    }
    Dl_info info;
    if (dladdr(anchor, &info) == 0 || info.dli_fbase == NULL) {
        LOGE("dladdr failed");
        return -1;
    }
    ElfView view;
    if (!ParseElf((uintptr_t)info.dli_fbase, &view)) {
        LOGE("cannot parse the core ELF");
        return -1;
    }
    int patched = PatchRelocations(&view, view.jmprel, view.jmprelCount, shim_handle);
    patched += PatchRelocations(&view, view.rela, view.relaCount, shim_handle);
    LOGI("patched %d Vulkan GOT slots in %s", patched,
         (info.dli_fname != NULL) ? info.dli_fname : "?");
    return patched;
}
