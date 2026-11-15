// SPDX-License-Identifier: LGPL-3.0-only
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

// Redirects the Vulkan imports (PLT/GOT slots) of an already-loaded libMobileGL.so to a
// forwarding shim. The core binary and its NEEDED libvulkan.so stay untouched.
// Returns the number of patched slots, or -1 on failure.
int MGPatchVulkanImports(void* core_handle, void* shim_handle);

#ifdef __cplusplus
}
#endif
