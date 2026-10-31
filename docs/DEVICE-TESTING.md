# Device testing notes (2026-11-01)

Test device: PLC110 (Android 15), Mali-G925-Immortalis MC12.

## Driver POST / probe results

- Vulkan instance API 1.3.278.
- `VK_EXT_vertex_attribute_divisor` is not exposed; only
  `VK_KHR_vertex_attribute_divisor` is exposed. Minecraft 26.3's native Vulkan
  backend reports `Device missing capabilities` because of this.
- A standalone probe (`tools/device_probe/mglprobe`) calls MobileGL's
  `eglGetDisplay` / `eglInitialize` directly: both succeed with
  `EGL_VENDOR=MobileGL`.

## The actual game failure

The game process loaded `libRendererProxy.so` successfully but the proxy's
`dlopen("libSfpew_real.so")` and `dlopen("libMobileGL.so")` returned
"library not found". The plugin APK's native library directory is not part of
the default linker search path in the launcher process.

Fix: resolve the proxy's own directory with `dladdr()`, then dlopen siblings by
absolute path; set `SFPEW_EGL` to the absolute MobileGL path as well.

## Runtime config

`/sdcard/MobileGL/runtime.conf` is read by the proxy; keys are exported before
loading. `MOBILEGL_CONFIG_FILE` is pointed at the same file so MobileGL reads
it natively.
## 2026-11-01 26.3 Vulkan skin observation (no repro after reboot)

During the three-run comparison, Minecraft 26.3 with the DirectVulkan backend
showed the default skin once, while DirectGLES showed the custom skin. After
rebooting the phone, the same 26.3 + DirectVulkan run loaded the custom skin
correctly and the issue did not reproduce.

Post-reboot log confirms the intended path:

```
RendererProxy: mode=direct, libMobileGL.so loaded by absolute path
MobileGL: Active backend type set to DirectVulkan
MobileGL: Renderer Name: Magma / Backend Name: Direct (Vulkan)
```

Conclusion: transient process/driver cache state, not a code regression. No
proxy or backend change was made for this observation; leave the shader/texture
cache untouched unless it reproduces consistently.
