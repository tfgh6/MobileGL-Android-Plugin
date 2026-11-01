# Code review / cleanup notes

## Current shape

- `PluginApp.kt` (~757 lines) owns theme, navigation, home tab, info tab and shared UI.
- `ConfigScreen.kt` (~280 lines) hardcodes every option row.
- `SettingsTab.kt` (~190 lines) duplicates row/toggle patterns.
- `build.gradle` embeds the complete RendererPlugin-v2 JSON builder.
- `ExternalLibraryRepository` validates, hashes and scans the whole .so in app memory.
- Driver POST/self-test runs in the app process; the imported library is loaded after the
  bundled one, so JNI symbol resolution can keep the bundled version.

## Recommended next refactors (priority order)

1. Split `PluginApp.kt` into `AppNavigation.kt`, `HomeTab.kt`, `InfoTab.kt`,
   `Theme.kt` and `UiComponents.kt`. Navigation and page bodies should not share a file.
2. Make config options data-driven: one `OptionSpec` list for 通用 / 按后端 / SFPEW,
   then render once. This removes the repeated `OptionToggle` / `SwitchRow` calls.
3. Move the v2 renderer JSON to a dedicated Gradle file/template so
   `app/build.gradle` only wires prebuilt libs, signing and flavors.
4. Run Driver POST in a dedicated `:selftest` process. This guarantees the
   selected external `.so` is the first MobileGL loaded and its GL_VERSION/commit
   are reported correctly.
5. Validate imported .so files with a streaming scan instead of `readBytes()`
   (currently both version scan and MobileGL signature scan read the whole file).
6. Move UI strings to `strings.xml`; keep only technical logs in code.
7. Add unit tests for `PluginConfig` JSON round-trip, runtime.conf key/value
   parsing and the version/commit scanner.

## Done in this cleanup

- Runtime config no longer writes duplicate `MOBILEGL_BACKEND_TYPE` lines.
- Removed the diagnostic `mobilegl.rendererLib` build switch and temporary APK.
- Proxy clamps a zero `GL_MAX_TEXTURE_SIZE` probe to 16384.
- Version scanner now pairs `MobileGL <version>` with the nearest `GIT@<commit>`.
