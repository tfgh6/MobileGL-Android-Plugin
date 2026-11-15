# Kopper 伪装 + 外置 Vulkan loader 垫片（官方核心零改动）

## 思路

官方 `libMobileGL.so` 保持原样（DT_NEEDED `libvulkan.so`、直接调用 `vk*`）。新增两个库，不改核心：

1. `libvulkanshim.so`：导出官方核导入的 **128 个 `vk*` 符号**，每个符号做 AArch64 裸跳转，
   目标指针在 constructor 里从宿主启动器准备好的 loader（`VULKAN_PTR`）解析；
   没有 `VULKAN_PTR` 时退回 `/system/lib64/libvulkan.so`。
2. `libRendererProxy.so`（原有代理，增加 `vulkan_got_patch.c`）：加载核心后解析其内存 ELF，
   把核心的 128 个 `vk*` GOT/PLT 槽改写到 `libvulkanshim.so`。这样**即使系统 libvulkan
   已经被启动器提前加载**，核心的 Vulkan 调用也会走启动器的 loader。

插件仍把 RendererPlugin-v2 的 `rendererId` 设为 `opengles3_desktopgl_zink_kopper`，
让 FCL/ZL2 走它们现成的 "GL/EGL 桥 + 预先 load_vulkan()" 分支：Turnip 以 loader 形式准备好，
句柄写入 `VULKAN_PTR`，而实际 GL 渲染仍由 MobileGL 提供。

## 改动

- `android-plugin/app/build.gradle`：v1 env / v2 NormalEnv / v2 rendererId → kopper id。
- `android-plugin/app/src/main/cpp/renderer_proxy/proxy.c`：加载 `libvulkanshim.so`、
  调用 `MGVkShimReinit()`、执行 GOT patch。
- `android-plugin/app/src/main/cpp/renderer_proxy/vulkan_got_patch.{c,h}`：ELF 解析与槽位改写。
- `prebuilt/arm64-v8a/libMobileGL.so`：官方原版，未修改（SHA-256 与用户提供文件一致）。
- `prebuilt/arm64-v8a/libvulkanshim.so`：转发垫片（16KB LOAD 对齐）。
- `prebuilt/arm64-v8a/libRendererProxy.so`：带 GOT patch 的代理（16KB LOAD 对齐）。

## 验证

```bash
adb logcat -c
adb logcat | findstr /i "VulkanShim RendererProxy AdrenoSupp MobileGL"
```

期望：

- 启动器：`AdrenoSupp: Loaded Turnip, loader address: 0x...`；
- 垫片：`VulkanShim: loader=VULKAN_PTR resolved=128 missing=0`；
- 代理：`VulkanShim: patched 128 Vulkan GOT slots in ...` 与 `RendererProxy: Vulkan shim loaded ...`；
- MobileGL：device 名含 Turnip，画面正常。

失败时：`libvulkanshim.so not loadable`、`patched ... 0`、`loader=/system/...` 会明确指出卡点。

## 限制

- 依赖启动器写 `VULKAN_PTR`（ZL2/FCL 的 kopper 分支）；没有时退回系统 Vulkan。
- GOT patch 依赖 `eglGetProcAddress` 作为基址锚点与 ELF 动态段；官方核已确认存在。
- Android linker 版本差异、核心被 strip/混淆都可能影响锚点，失败会记录日志并保持系统 Vulkan。
