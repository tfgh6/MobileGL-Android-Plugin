# 配置项同步审计（官方 26.11 核心）

审计基准：`MobileGL-Dev/MobileGL@8db74d69`（26.11）的源码，而非只读二进制：
`userguide/configuration.md`、`MobileGL/ConfigLoader.cpp`，以及 env-only 键所在的实现文件
（`DirectGLESSharedImage.cpp`、`GL_Getter.cpp`、`MG_Util/Debug/Log.cpp`、`OpenGL/Loader.cpp`）。
参考源码快照保存在 `docs/upstream-26.11/src/`（gitignore）。

## 一、无效键清理（插件写了但官方核不认）

| 键 | 位置 | 处理 |
|---|---|---|
| `MOBILEGL_DISABLE_SUBGROUP` | 启动配置 → 通用 | 官方无此键；改为 `MOBILEGL_SUBGROUP_EMULATION`（Auto / ForceOn / ForceOff） |

> 复核修正：下面两个**不是**无效键，先前判断有误，已恢复/保留：
> - `MOBILEGL_DISABLE_TEXTURE_VIEW`：官方 `MG_Util/BackendLoaders/OpenGL/Loader.cpp:1287` 仍读取
>   （对驱动隐藏 EXT/OES_texture_view 能力），已恢复开关。
> - `MOBILEGL_MAGMA_FRAMESINFLIGHT`：官方作为 `FRAMES_IN_FLIGHT` 的 deprecated alias 仍读取
>   （`ConfigLoader.cpp:390-392`）；删除 UI 里的重复项是对的，但原因只是“重复暴露”，不是无效。

## 二、本轮补齐的官方选项

用户级（官方配置表列出）：`ADVERTISE_FP64`、`FRAMES_IN_FLIGHT`（已有）、`ITRP_FIX`、
`MAGMA_MAX_DRAWS_PER_COMMAND_BUFFER`、`MAGMA_DESCRIPTOR_TRIM_FRAMES`、`VK_ICD_FILENAMES`。

开发/quirk 级（官方指南故意不列，但在 `ConfigLoader.cpp` 中真实读取）：`DISABLE_SHADER_PREWARM`、
`MAGMA_DISABLE_ROBUST_BUFFER_ACCESS`、`MAGMA_INPASS_CLEAR`、`MAGMA_PRIMGEN_QUERY_REROUTE`、
`MAGMA_VERTEX_CONVERSION_CACHE`、`MAGMA_WRITE_RENAME`、`MAGMA_ALLOW_INVALID_SPIRV`、
`ESPRYT_FORCE_DS_READBACK_EMULATION`、`ESPRYT_DISABLE_INVALIDATE_FLUSH`、`ESPRYT_DISABLE_UBO_RING`、
`ESPRYT_DISABLE_UNPACK_RING`、`ESPRYT_ATOMIC_SUBGROUP_SCRATCH`、`ESPRYT_AVOID_EXPLICIT_LOD_BIAS`、
`ESPRYT_FORCE_VIEWPORT_ARRAY_EMULATION`、`ESPRYT_INVALID_IMAGE_PLACEHOLDER`、
`ESPRYT_UNLOCATED_IO_BLOCKS`、`ESPRYT_WIDEN_PACKED16_STORAGE`、`YUV_DRIVER_CONVERSION`（env-only）、
`ES_CONTEXT_IDENTITY`（env-only）、`PIPE_STATS_FILE`。

## 三、语义修正（同一键，控件类型/取值错误）

1. **三态键不能只用开关**：官方 `QueryEnvQuirkOverride`（`ConfigLoader.cpp:252-268`）规定
   - 未设置 / 空 / `auto` → Auto；
   - `forceon` / `on` → ForceOn；
   - `forceoff` / `off` / `no` → ForceOff；
   - 其它按 flag：`1/true` → ForceOn，`0/false` → ForceOff。
   普通开关的“关”写入 `0` = ForceOff，会覆盖设备自动探测。以下键已改用 Auto/ForceOn/ForceOff
   三选一：`ASYNC_SHADER_COMPILE`、`SHADER_CACHE`、`MAGMA_INPASS_CLEAR`、`MAGMA_PASS_CACHE`、
   `MAGMA_VERTEX_CONVERSION_CACHE`、`MAGMA_WRITE_RENAME`、`MAGMA_PRIMGEN_QUERY_REROUTE`、
   `MAGMA_E5_RAW_READS`、`MAGMA_SPLIT_SLICE_COPIES`、`MAGMA_REEMIT_STATE_AFTER_DISCARD`、
   `MAGMA_DISABLE_BLENDED_DEPTH_WRITE`、`ESPRYT_VAO_SHADOW`、`ESPRYT_DEFER_GEOMETRY_EMITS`、
   `ESPRYT_ATOMIC_SUBGROUP_SCRATCH`、`ESPRYT_INVALID_IMAGE_PLACEHOLDER`、`ESPRYT_UNLOCATED_IO_BLOCKS`、
   `ESPRYT_WIDEN_PACKED16_STORAGE`、`ESPRYT_FORCE_VIEWPORT_ARRAY_EMULATION`。
2. `MOBILEGL_MAGMA_EXTRA_SWAPCHAIN_IMAGES` 是 0-16 的整数，改为数字输入（原是 0/1 开关）。
3. `MOBILEGL_PIPE_INDEX_MIRROR_MB` 标签改为 index mirror 语义（原写“上传环大小”）。

## 四、配置持久化修复

原先“启动配置”页保存的 raw 文本会在以下时机被 `RuntimeConfigWriter.write(config)` 覆盖：
应用启动时、以及任何设置项保存时；而 `ConfigScreen` 的修改没有写回 `PluginConfig.env`。
现在保存时会解析 raw 文本中的 `KEY=value` 并合并进 `config.env`（排除由类型字段管理的
`RENDERER_MODE` / `RENDERER_SO` / `SFPEW_ENABLED`），因此新增/手写的键在重启和再保存后仍然保留。

## 五、启动器 RendererPlugin-v2 JSON

官方 v2 选项已逐项对应；补上 `MOBILEGL_ESPRYT_AVOID_SAMPLER_MIPMAP_MIN_FILTER`，并为
`FRAMES_IN_FLIGHT` / `ESPRYT_USE_ANGLE` / `COHERENT_AS_FLUSH` / `RELAXED_SEMANTICS` /
`AVOID_SAMPLER_MIPMAP_MIN_FILTER` 补齐 title 字符串与 `AndroidManifest.xml` 的 meta-data。

## 六、构建模式差异（重要）

- **prebuilt 模式**（发布仓库 CI）：核心是官方 26.11 二进制。它只从进程环境 / `MOBILEGL_CONFIG_FILE`
  指向的文件读配置，**不会**主动读 `/sdcard/MobileGL/runtime.conf`；插件依赖 `libRendererProxy.so`
  把该文件 setenv 进游戏进程后才生效。
- **源码模式**（fork CI 不带 `-Pmobilegl.prebuiltLibsDir`）：编译本仓库旧核，旧核直接读
  `/sdcard/MobileGL/runtime.conf`，且只认 26.7 时代的少量键 + 别名；本轮新增的大部分官方键在
  源码模式 APK 里是 no-op。发布以 prebuilt 官方核为准。

## 七、VK_ICD_FILENAMES（ICD 选择）

官方核心通过 `dlopen("libvulkan.so.1"/"libvulkan.so")` 使用当前进程的 Vulkan loader；
`VK_ICD_FILENAMES` 由 loader 读取：
- 桌面 Khronos loader：生效（README 的 macOS MoltenVK 用法）；
- Android 系统 `libvulkan.so`：不读取（安全限制）；
- 启动器（FCL / ZalithLauncher2）自带 loader 时：生效。
因此插件只把它作为可写的环境变量暴露；要让插件自身独立选择 ICD，需要内置可在 Android 运行的
Khronos Vulkan-Loader（独立工程，尚未接入）。
