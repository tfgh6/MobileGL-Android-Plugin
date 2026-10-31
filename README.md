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

## Launcher-facing renderer metadata

- App label (the launcher's "来自 ..." summary): `MobileGL_UI`.
- Renderer display name: `MobileGL`.
- No MS/MC version bounds are advertised.
- Default SFPEW state is **off**; the APK still bundles the wrapper so it can be
  enabled explicitly.
- MobileGL options are split in the app into 通用配置, 按后端配置 and
  SFPEW 后端.

## Runtime configuration

The plugin writes `/sdcard/MobileGL/runtime.conf`. The proxy reads it before
loading the renderer:

- `RENDERER_MODE=direct` / `SFPEW_ENABLED=0`: load MobileGL directly; SFPEW is never touched.
- `RENDERER_MODE=sfpew` / `SFPEW_ENABLED=1`: load SimpleFPEWrapper and point it at MobileGL.
- No config file: keep the previous default (load SFPEW).
- A present config file without either key: stay direct.

`MOBILEGL_*` / `LIBGL_*` keys are also exported before loading, and
`MOBILEGL_CONFIG_FILE` points the latest MobileGL at the same file.

## 2026-11-01 device fix

The game process loads the plugin library by absolute path, but its linker
namespace does not search the plugin APK's `nativeLibraryDir` for bare
`dlopen("libMobileGL.so")` / `dlopen("libSfpew_real.so")` calls. The proxy now
resolves its own directory with `dladdr()` and loads both sibling libraries by
absolute path; `SFPEW_EGL` is also set to the absolute MobileGL path.

Symptom before the fix (game logcat):

```
RendererProxy: SFPEW enabled but libSfpew_real.so failed:
  dlopen failed: library "libSfpew_real.so" not found
RendererProxy: libMobileGL.so not loadable:
  dlopen failed: library "libMobileGL.so" not found
renderer proxy ready: mode=sfpew target=0x0 missing=2900/2900
```

After the fix the app process log is:

```
RendererProxy: SFPEW enabled: .../libSfpew_real.so loaded, backend=.../libMobileGL.so
RendererProxy: renderer proxy ready: mode=sfpew ... missing=0/2900
```

## Signing

- `android-plugin/debug.keystore` (alias `mobilegl`, password `android`) is
  committed so local builds and GitHub Actions produce the same signature.
- APKs signed with an older, different debug key require one uninstall before
  the first install of this build.


Debug APKs are signed with the machine/runner debug keystore. Uninstall an APK
signed by a different debug key before installing a new one.