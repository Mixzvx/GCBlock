# Changelog

## v2.8
- 搜索框：拦截 `View.setVisibility`，从源头阻止其被重新显示，消除开场闪烁。

## v2.7
- 搜索框改为只按 `app:id/search_box` 精确隐藏，删除会误伤背景板/开始按钮的启发式；
- 提前到 `LayoutInflater.inflate` 返回时隐藏，首帧前完成；
- 移除调试用的 UI dump 写文件逻辑。

## v2.6
- 搜索框改为按资源 id `search_box` 查找（不再依赖 binding 字段）。

## v2.5
- 增加 UI 树导出（已移除）。

## v2.4 / v2.3 / v2.2
- 遍历视图树、binding 字段、父容器等多轮尝试隐藏搜索框（均已被 v2.7 取代）。

## v2.0
- 新增：隐藏游戏空间顶部搜索框。

## v1.9
- 游戏库卡片：同时 hook `submitList` 的两个重载。
- 兜底：`MainAppAdapter.onBindViewHolder` 隐藏残留条目。

## v1.8
- 修正 `submitList(List, Runnable)` 两参重载未被 hook 的问题。

## v1.7
- 新增：过滤游戏空间顶部「游戏库」标签页（`TabPagerAdapter`）。

## v1.6
- 新增：移除「游戏库」卡片（`MainAppAdapter` 数据层过滤）。

## v1.5
- 新增：移除悬浮窗「福利」标签（`WelfareSelectTabManager` + `AssistantView`）。

## v1.4
- 新增：隐藏悬浮窗「福利」页（`WelfareView`）。

## v1.3
- 迁移到 libxposed API 102（新版框架），保留 legacy API 82 变体。

## v1.2
- 增加桌面图标与自检页。

## v1.1
- 修正 manifest meta-data 类型（boolean/int）。

## v1.0
- 初始版本：阻断与游戏中心的 IPC。
