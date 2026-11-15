# 骁龙 Turnip 驱动（内置，实验）

## 内置版本

APK 内置 **Banners-Turnip v27.0.0-20261114-r2** 的 4 个 Android arm64-v8a 变体：

| 变体 | 库文件 | 适用 GPU |
|---|---|---|
| 通用 A6xx/A7xx | `libturnip_generic.so` | Adreno 6xx / 7xx（710/720/722 除外） |
| A710/A720 | `libturnip_710_720.so` | Adreno 710 / 720 / 722（实验） |
| A8xx | `libturnip_a8xx.so` | Adreno 750 / 8xx（810/812/825/829/830/840 等） |
| 8G2 OneUI | `libturnip_8g2_oneui.so` | Adreno 740 · 三星 OneUI |

全部为 64 位 AArch64，Mesa 27.0.0-devel，Vulkan 1.4.368，minApi 28，16KB page 对齐。

## 自动选择

- 从 GlInfoProbe / Driver POST / GL renderer 解析 Adreno 数字型号；
- 710 / 720 / 722 → `710_720`；
- 750 / 8xx → `a8xx`；
- 740 且三星 OneUI 设备 → `8g2_oneui`，其他 740 → `generic`（代码按三星厂商判定；目前 740 的三星机型均为 OneUI）；
- 其他 Adreno → `generic`；
- 非 Adreno 禁用 Turnip。

UI 可手动切换内置变体；外部导入仍标实验。

## 设计

- MobileGL 不再链接系统 `libvulkan.so`：Android 构建使用 volk 运行时加载；
- 启用 Turnip 时通过静态链接的 libadrenotools 打开选中变体，再 `volkInitializeCustom()`；
- 未启用、非 Adreno、API < 28 或加载失败时回退系统 Vulkan，不崩溃；
- 入口在 MobileGL UI：设置 → 驱动管理 → 骁龙 Turnip；不依赖 FCL/ZL2 的驱动下拉框。
- runtime.conf：`MOBILEGL_TURNIP_ENABLED=0|1` 总是写；启用时写 `MOBILEGL_TURNIP_PATH=<绝对路径>`。
- 外置 .so 导入到 `/sdcard/MobileGL/turnip/`，由游戏进程复制到自己的 cache 后加载；实验性，失败回退。

## 测试前提

- Qualcomm Adreno 6xx/7xx/8xx；Turnip 不支持 Mali。
- Android 9+（API 28+）。
- 安装 debug APK，并授予“所有文件访问权限”（否则共享 runtime.conf 写入失败，UI 会警告）。

## 测试步骤

1. 打开 MobileGL UI → 设置 → 驱动管理 → 骁龙 Turnip。
2. 确认 GPU 型号与自动变体；需要时可手动切换变体。
3. 打开“启用 Turnip”。
4. FCL/ZL2 中**不需要**选择 Turnip 驱动，直接启动 MobileGL（Vulkan）。
5. 抓日志：
   ```
   adb logcat -c
   adb logcat -s MobileGL RendererProxy hook_impl vulkan
   ```
   期望 `Turnip: using driver ... through ...`，以及 Vulkan device/driver 含 Turnip。
6. 游戏内检查画面/光影/皮肤/区块加载；退出再启动一次。

## 回退测试

- 关闭开关 → 系统 Vulkan。
- 删除 `MOBILEGL_TURNIP_PATH`（保留 `MOBILEGL_TURNIP_ENABLED=1`）→ 回退到内置 `libturnip_generic.so`；路径存在但无效/加载失败才回退系统 Vulkan。
- 非 Adreno 设备开关应禁用。
- 手动切换每个内置变体，确认不崩溃并回退。

## 驱动自检

- 驱动自检仍使用官方 Driver POST 的检查项和判定，不额外增加 Turnip 专用行。
- 只切换 Vulkan loader：Turnip 开启时自检通过 Turnip，关闭时使用系统 libvulkan。
- 自检开始前会重新读取 `MOBILEGL_TURNIP_ENABLED`；切换开关后直接重跑自检即可，无需重启 App（游戏渲染进程仍需重启游戏才能换驱动）。
- 自检的 “Vulkan loader initialized with Turnip / with system libvulkan” 一行显示本次实际使用的驱动来源。

## 诊断日志（无需电脑）

- 入口：信息 → 诊断日志。
- 默认关闭；打开“记录诊断日志”后才写日志。
- UI：`/sdcard/MobileGL/logs/ui.log`（无存储权限时回退 App 私有目录）。
- 游戏进程：`/sdcard/MobileGL/logs/game.log`，记录选择变体、hook 目录、adrenotools 成功/失败和回退原因。
- 分享日志通过 FileProvider 发送一个 `.txt` 文件，不受聊天软件文本长度限制，内容包含设备/GPU/变体配置与两份日志尾部。
- 日志 512 KiB 轮转；runtime.conf 写入 `MOBILEGL_DIAG_LOG_ENABLED=0|1`。

## 当前限制

- 尚未在真实 Adreno 上端到端验证；构建/静态校验已通过。
- A710/A720 变体上游标记为实验，可能需 sysmem 模式。
- 8G2 OneUI 变体只适合 Adreno 740 + 较新 OneUI 固件，其他 740 用通用。
- 外置 /sdcard Turnip 依赖游戏进程存储读取权限；失败回退。
- 测试通过前不发布正式版 APK。