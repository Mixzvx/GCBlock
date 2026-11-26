# GCBlock

一个 **LSPosed 模块**：把「荣耀游戏管家」(com.hihonor.gameassistant) 里那些由**游戏中心**提供 / 推广性质的功能去掉，只留本地工具。

> 面向已 root、装了 LSPosed / Vector 的荣耀设备。仅对 `com.hihonor.gameassistant` 生效。

[![Platform](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)](#兼容性)
[![Framework](https://img.shields.io/badge/LSPosed-API%2082%20%7C%20libxposed%20102-blue)](#兼容性)
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)

---

## 功能

| # | 功能 | 说明 |
|---|---|---|
| 1 | **阻断与游戏中心的通信** | Provider Binder、`gamecenter://` 深链、服务绑定、包查询全部拦截，让管家认为游戏中心不存在 |
| 2 | **隐藏悬浮窗「福利」页** | 悬浮窗里的福利页面整页不渲染 |
| 3 | **移除悬浮窗「福利」标签** | 顶部三标签（性能/福利/工具）只剩两个，服务端也加不回来 |
| 4 | **移除游戏空间「游戏库」卡片** | 左侧游戏列表里的大卡片（推荐安装入口） |
| 5 | **过滤「游戏库」标签页** | 游戏空间顶部标签栏里的游戏库页 |
| 6 | **移除顶部搜索框** | 游戏空间首页右上角的搜索胶囊，含框内滚动字幕，且不闪 |

---

## 原理

全部是运行时 hook（不改 APK、不改资源）：

- **跨应用调用**：`ContentResolver.call` / `acquire*ContentProviderClient` / `ContentProviderClient.call` / `ContextImpl.bindService*` / `startActivity*` / `ApplicationPackageManager.getPackageInfo*`，命中即返回 null / false / `NameNotFoundException`。
- **UI 移除**：按视图类、`app:id/search_box`、`AssistantLayoutBinding` 字段、`ListAdapter.submitList` 数据层过滤等方式隐藏。
- **防复现**：对 `WelfareView`、福利标签的服务端回调、搜索框的 `setVisibility` 都做了"再显示就吞掉"的处理。

详细 hook 点见 [docs/HOOKS.md](docs/HOOKS.md)。

---

## 安装

1. 设备已 root（Magisk / KernelSU），并装好 **LSPosed** 或 **Vector**（Zygisk 已启用）。
2. 安装 APK（Release 里下载；或自己构建）。
3. 打开 LSPosed/Vector 管理器 → 模块 → 启用 **GCBlock**。
4. 点「作用域」→ **只勾「游戏管家」**（`com.hihonor.gameassistant`）。
5. 强制停止游戏管家，再打开。hook 只在进程启动时注入。

```sh
adb install -r GCBlock.apk
adb shell am force-stop com.hihonor.gameassistant
adb logcat -s GCBlock    # 看 BLOCK 日志确认生效
```

## 两个变体

仓库会产出两个包（包名/签名相同，可互相覆盖）：

| 文件 | 适用框架 |
|---|---|
| `GCBlock.apk` | libxposed API 101/102（Vector 2.x、新版 LSPosed，Android 14+ 常见） |
| `GCBlock-legacy.apk` | 旧 XposedBridge API 82（LSPosed 1.9.x 等） |

判断方法：模块管理器里看不到模块 → 换另一个变体试试。

---

## 构建

只需要 JDK 17 + 几个 jar，无需 Android Studio / Gradle：

```text
$GCBLOCK_SDK/
├── android-api16.jar      # android.jar（API 16 就够编译，运行在设备上）
├── api-82.jar             # Xposed API 82（legacy 变体用）
└── bt/
    ├── d8.jar             # 来自 build-tools
    └── apksigner.jar      # 来自 build-tools
```

```sh
export GCBLOCK_SDK=/path/to/sdk
python3 tools/build.py              # 产出 out/GCBlock.apk（libxposed 版）
VARIANT=legacy python3 tools/build.py   # 产出 out/GCBlock-legacy.apk
```

首次构建会在 `out/` 生成自签名密钥（`gcblock.pk8` / `gcblock.pem`），**不要提交**（已写进 `.gitignore`）。

`tools/axml.py` 是一个极简的二进制 AndroidManifest 编码器 —— 本模块没有资源，manifest 是手写 AXML，因此不需要 aapt2。

---

## 兼容性

- 逆向与测试针对：**游戏管家 18.0.36.321**（`versionCode 180036321`），Android 17 / API 37，arm64。
- 其他版本可能类名、字段、布局 id 有变化；日志里会打印实际的类名/id，方便按需调整。
- 依赖若干隐藏 API（`ContextImpl`、`ApplicationPackageManager`），系统大版本升级后可能失效。

## 已知限制

- hook 是"运行时改"，不是从布局里删控件；搜索框已在 inflate 阶段隐藏，视觉上没有闪烁，但严格说控件仍存在于视图树。
- 只覆盖静态分析确认过的路径；应用若新增推广入口，需要再加 hook。
- 不处理 native 层（15 个 `.so` 里没有相关逻辑）。

---

## 免责声明

- 本项目仅用于**自己在自己设备上的学习与研究**，不修改、不重新分发任何第三方代码。
- 与 Honor / 荣耀无任何关联，未获其授权或认可。
- 使用需要 root 与 Xposed 框架，可能影响设备稳定性、OTA、应用完整性校验；**风险自负**。
- 请勿用于商业分发或绕过任何付费/授权机制。

## License

[MIT](LICENSE)
