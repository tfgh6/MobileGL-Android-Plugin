# MobileGL Android Plugin

FCL / ZalithLauncher2 renderer plugin for MobileGL, with the MobileGlues-style UI,
runtime renderer proxy, configurable SimpleFPEWrapper loading and the upstream
Driver POST self-test.

## Build (GitHub Actions)

The workflow `Build MobileGL Plugin APK` builds the plugin with prebuilt native libs
in `prebuilt/arm64-v8a`:

- `libMobileGL.so` — latest MobileGL dev CI build (d6fd101d)
- `libSfpew_real.so` — SimpleFPEWrapper (bundled as libSfpew_real.so)
- `libRendererProxy.so` — runtime dispatcher

Download the APK from the workflow run artifact.

## Runtime configuration

The plugin writes `/sdcard/MobileGL/runtime.conf`. The proxy reads it before
loading the renderer:

- `RENDERER_MODE=direct` / `SFPEW_ENABLED=0`: load MobileGL directly; SFPEW is never touched.
- `RENDERER_MODE=sfpew` / `SFPEW_ENABLED=1`: load SimpleFPEWrapper and point it at MobileGL.
- No config file: keep the previous default (load SFPEW).
- A present config file without either key: stay direct.

`MOBILEGL_*` / `LIBGL_*` keys are also exported before loading, and
`MOBILEGL_CONFIG_FILE` points the latest MobileGL at the same file.

## Signing

Debug APKs are signed with the machine/runner debug keystore. Uninstall an APK
signed by a different debug key before installing a new one.
