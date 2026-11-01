# MobileGL Android Plugin

MobileGL renderer plugin for FCL / ZalithLauncher2 / Pojav-style launchers.

## Features

- MobileGlues-style light UI with three tabs: 首页 / 设置 / 信息.
- Runtime renderer dispatcher (`libRendererProxy.so`) that loads MobileGL from the plugin APK without relying on the launcher's linker search path.
- Configurable backend: DirectVulkan (`Magma`), DirectGLES (`Espryt`), optional ANGLE for DirectGLES.
- Optional SimpleFPEWrapper (SFPEW) for Minecraft 1.16.5 and older; off by default.
- Runtime configuration file at `/sdcard/MobileGL/runtime.conf`, shared by the plugin, proxy and MobileGL.
- Import and activate external `libMobileGL.so` builds; imports are validated as AArch64 MobileGL libraries.
- Driver POST self-test with checks, known driver bugs, format capability matrix and a summary.
- Full device information from EGL and Vulkan (GPU name, GL version, Vulkan API, driver version).
- First-launch all-files permission prompt with automatic restart after granting.

## Launcher-facing metadata

- App label: `MobileGL_UI` (the launcher displays the plugin as `来自 MobileGL_UI`).
- Renderer display name: `MobileGL`.
- No Minecraft version bounds are declared.

## Build

The GitHub Actions workflow `.github/workflows/build-plugin.yml` builds the APK with prebuilt native libraries from `prebuilt/arm64-v8a`: `libMobileGL.so`, `libSfpew_real.so`, `libRendererProxy.so`.

Download the APK from the workflow run artifacts.

## Configuration

`/sdcard/MobileGL/runtime.conf` is read by the proxy before loading the renderer:

- `RENDERER_MODE=direct` / `SFPEW_ENABLED=0`: load MobileGL directly; SFPEW is not touched.
- `RENDERER_MODE=sfpew` / `SFPEW_ENABLED=1`: load SimpleFPEWrapper backed by MobileGL.
- Default is direct / SFPEW off.

The plugin also exports `MOBILEGL_*` / `LIBGL_*` keys from the file and points `MOBILEGL_CONFIG_FILE` at it.

## License

GNU Lesser General Public License v3.0. See [LICENSE](LICENSE).

Upstream projects:

- https://github.com/MobileGL-Dev/MobileGL
- https://github.com/MobileGL-Dev/SimpleFPEWrapper
