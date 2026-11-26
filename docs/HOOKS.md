# GCBlock Hook 点说明

只对 `com.hihonor.gameassistant` 生效。以下类名/字段名来自对游戏管家 18.0.36.321 的反编译，
升级后可能变化；改动时优先看 `logcat -s GCBlock` 打印的实际类名。

## 1. 阻断与游戏中心（com.hihonor.gamecenter / com.honor.quickgamecenter）的通信

| 目标 | Hook | 行为 |
|---|---|---|
| `ContentResolver.call / acquireUnstableContentProviderClient / acquireContentProviderClient / query` | authority 含 `gamecenter` / `game_space.provider` | 返回 `null` |
| `ContentProviderClient.call/query` | 方法名 `get_gcdownloadinstallservice_binder`、`shortcutCreated` | 返回 `null` |
| `ContextImpl.bindService / bindServiceAsUser / bindServiceInstance` | Intent 指向游戏中心 | 返回 `false` |
| `ContextImpl.startActivity*`、`Activity.startActivity*` | `gamecenter://` / `quickgamecenter://` 深链 | 丢弃 |
| `ApplicationPackageManager.getPackageInfo / getApplicationInfo / getPackageUid` | 包名是游戏中心 | 抛 `NameNotFoundException` |
| `ApplicationPackageManager.resolveActivity / queryIntentActivities / getLaunchIntentForPackage` | 目标是游戏中心 | `null` / 空列表 |
| `File.exists` | 路径含 `/priv-app/GameCenter` | `false` |

目的：让管家以为游戏中心未安装，从而走它自带的降级分支（反编译里本来就有
`Game center package not found`、`contentProviderClient is null` 等日志）。

## 2. 悬浮窗「福利」页

| 目标 | Hook | 行为 |
|---|---|---|
| `buoywindow.view.WelfareView` 构造器 | after | `setVisibility(GONE)` |
| `WelfareView.onAttachedToWindow` | 替换 | 只置 GONE，不执行原逻辑 |
| `WelfareView.setViewIsVisible(Z)` | 替换 | 吞掉 |
| `WelfareView.getViewIsVisible()` | 替换 | 恒 `false` |
| `WelfareView.setWelfarePageShowing(Z)` | 替换 | 吞掉 |

> 福利页是 App 自己的原生 View + 自有 HTTPS 接口（`GetWelfareReq` / `WelfareResp`，
> 后端 `gamemanager-api-*.hispace.hihonorcloud.com`），不是对游戏中心 App 的 IPC，
> 所以第 1 节拦不住它，必须单独隐藏。

## 3. 悬浮窗「福利」标签

| 目标 | Hook | 行为 |
|---|---|---|
| `router.util.WelfareSelectTabManager.e()` | 恒 `false` | 初始就不显示福利标签 |
| `buoywindow.view.AssistantView` 构造器 | after | 字段 `s`（isShowWelfareTab）置 false，`binding.r` 与红点 `t` 置 GONE |
| `AssistantView.Z(AssistantView, Boolean)` | 吞掉 | 服务端 `isSupportBenefit` 回调无法把标签加回来 |
| `AssistantView.v0(int, boolean)` | index 1 → 0 | 防止落到空的福利页 |

## 4. 游戏空间「游戏库」卡片

| 目标 | Hook | 行为 |
|---|---|---|
| `androidx.recyclerview.widget.ListAdapter.submitList`（**1 参和 2 参两个重载**） | `this` 是 `MainAppAdapter` 时 | 过滤 `MainAppBean.isGameLibrary()==true` 的条目 |
| `MainAppAdapter.onBindViewHolder` | 兜底 | 若条目仍是游戏库，`itemView` 置 GONE + 高度 0 |

> `MainFragment.g1()` 调用的是 `submitList(List, Runnable)` 两参重载，
> 只 hook 单参版本会"看起来装了但没生效"。

## 5. 游戏空间「游戏库」标签页

| 目标 | Hook | 行为 |
|---|---|---|
| `gamespace.adapter.TabPagerAdapter.h(ArrayList)` | after 前过滤 | 剔除标题含「游戏库 / 遊戲庫 / game library」的项，并打印 `tabs=[...]` |
| `TabPagerAdapter.a(item)` | 单条追加 | 同类项跳过 |

## 6. 游戏空间顶部搜索框

| 目标 | Hook | 行为 |
|---|---|---|
| `LayoutInflater.inflate(...)`（所有返回 View 的重载） | 返回后 | `findViewById(app:id/search_box)` → `GONE`，第一帧前完成 |
| `GameSpaceActivity.onCreate / onResume` | after | 同上，并在 300ms / 1200ms 兜底 |
| `View.setVisibility(int)` | `id == search_box` 且参数非 GONE | 吞掉该调用（从源头防复显） |

搜索框实测信息：`android.widget.LinearLayout`，`app:id/search_box`（0x7f0909fa），
bounds `1759,49-2172,147`；框内滚动字幕是 `HnMarqueeView` + `TextSwitcher`。

## 7. 旧版 / 新版两套实现

- `GcModule`：libxposed API 102，拦截链模型（`hook(executable).intercept(chain -> ...)`），
  入口由 `META-INF/xposed/module.prop` + `java_init.list` 声明。
- `MainHook`：XposedBridge API 82（`IXposedHookLoadPackage`），入口靠 manifest 的
  `xposedmodule` meta-data。
- 两者逻辑一一对应，改功能时**两边都要改**。
