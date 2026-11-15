# 配置项同步审计（官方 26.11 核心）

审计基准：`MobileGL-Dev/MobileGL@8db74d69`（26.11）的
`userguide/configuration.md` 与 `MobileGL/ConfigLoader.cpp`。
插件侧：`android-plugin/app/src/main/java/.../ui/ConfigScreen.kt`、`SettingsTab.kt`、
`app/build.gradle`（RendererPlugin-v2 启动器配置）。

## 已修复（插件写了但官方核心不读 → 无效开关）

| 旧键 | 位置 | 处理 |
|---|---|---|
| `MOBILEGL_DISABLE_SUBGROUP` | 启动配置 → 通用 | 改为 `MOBILEGL_SUBGROUP_EMULATION` 三选一（Auto / ForceOn / ForceOff） |
| `MOBILEGL_DISABLE_TEXTURE_VIEW` | 启动配置 → 通用 | 删除；官方只有 `MOBILEGL_ESPRYT_ENABLE_TEXTURE_VIEW`（已在 Espryt 区） |
| `MOBILEGL_MAGMA_FRAMESINFLIGHT` | 启动配置 → Magma | 删除；官方已废弃该别名，统一用 `MOBILEGL_FRAMES_IN_FLIGHT` |

## 本轮补齐的官方选项

- 通用：`ADVERTISE_FP64`、`ES_CONTEXT_IDENTITY`、`DISABLE_SHADER_PREWARM`、
  `ITRP_FIX`（Auto/ForceOn/ForceOff）、`PIPE_STATS_FILE`、`VK_ICD_FILENAMES`
- Magma：`MAGMA_MAX_DRAWS_PER_COMMAND_BUFFER`、`MAGMA_DESCRIPTOR_TRIM_FRAMES`、
  `MAGMA_DISABLE_ROBUST_BUFFER_ACCESS`、`MAGMA_INPASS_CLEAR`、`MAGMA_PRIMGEN_QUERY_REROUTE`、
  `MAGMA_VERTEX_CONVERSION_CACHE`、`MAGMA_WRITE_RENAME`、`MAGMA_ALLOW_INVALID_SPIRV`
- Espryt：`FORCE_DS_READBACK_EMULATION`、`DISABLE_INVALIDATE_FLUSH`、`DISABLE_UBO_RING`、
  `DISABLE_UNPACK_RING`、`ATOMIC_SUBGROUP_SCRATCH`、`AVOID_EXPLICIT_LOD_BIAS`、
  `FORCE_VIEWPORT_ARRAY_EMULATION`、`INVALID_IMAGE_PLACEHOLDER`、`UNLOCATED_IO_BLOCKS`、
  `WIDEN_PACKED16_STORAGE`、`YUV_DRIVER_CONVERSION`
- 启动器 v2 JSON：补 `MOBILEGL_ESPRYT_AVOID_SAMPLER_MIPMAP_MIN_FILTER`（官方 v2 规范包含）

## 仍未在 UI 暴露（开发/调试类，按官方文档故意不列）

`PIPE_PUSH`、`PIPE_VERIFY(_FATAL)`、`PIPE_FRAMEBUFFER_STATE_MEMO`、
`PIPE_PERSISTENT_MPROTECT`、`PIPE_VERTEX_ELEMENTS_DEDUP`、`POINT_SIZE_DEMOTION`、
`CLIP_DISTANCE_EMULATION`、`ASYNC_OPTIMISTIC_SHADER_STATUS`、`MAGMA_INPASS_CLEAR_DEFAULT`、
`ESPRYT_MULTIDRAW_MODE`、`MAGMA_MULTIDRAW_MODE`、`TRACE_ANGLE_VARIANT(S)`、
`TRANSPORT` / `IPC_*`（split 构建）、`LOG_FILE_PATH`、`BACKEND_FILE`。

这些可以随时手动写进 `runtime.conf`（经 proxy 注入环境变量），不需要改 UI。

## 关于 VK_ICD_FILENAMES（Vulkan ICD 选择）

- 官方核心通过 `dlopen("libvulkan.so.1" / "libvulkan.so")` 使用**当前进程的 Vulkan loader**；
  `VK_ICD_FILENAMES` 是 loader 读取的标准变量，因此是否生效取决于 loader：
  - 桌面 Khronos loader：生效（官方 README 的 macOS MoltenVK 用法即此）；
  - Android 系统 `libvulkan.so`：不读取该变量（安全限制），单独设置无效；
  - 启动器（FCL / ZalithLauncher2）自带的 Vulkan loader（Turnip 驱动选择）：生效——loader
    先加载后，官方核心的 `dlopen("libvulkan.so")` 会复用它。
- 插件已把 `VK_ICD_FILENAMES` 暴露到“启动配置 → 通用”，由 proxy 写入游戏进程环境，
  配合启动器/自带 loader 使用；若要让插件自身独立选择 ICD，需要额外打包一个可在 Android
  运行的 Khronos Vulkan-Loader（独立工程，尚未接入）。
