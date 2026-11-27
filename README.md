# Jami Freeisland 二开改动仓库

开源 P2P 聊天软件 Jami 的增强功能版。

本仓库保存基于 Jami（GPLv3+）的 Freeisland 定制项目的**二开改动与方案文档**，不包含 Jami 上游完整源码与构建产物。

## 仓库内容

| 目录 | 说明 |
|---|---|
| `docs/` | 全部方案/设计/调研文档（F-901 公共聊天室、F-903 导入节点入网、F-904 名片加好友、DHT 节点机制等） |
| `patches/qt/` | Qt 客户端二开改动文件（按上游源码原始相对路径存放） |
| `patches/android/` | Android 客户端二开改动文件（按上游源码原始相对路径存放） |
| `build/` | MinGW 构建补丁、构建脚本、辅助工具 |

## 版本基线

- **当前版本**：Freeisland V0.0.2（2026-11-27，IPv6 私有 DHT 双向消息打通版）
- **PC 客户端**：About 页显示 `Freeisland V0.0.2.<构建序号>`（构建序号由 cmake 每次 configure 自动 +1）
- **Android 客户端**：About 页显示 `Freeisland V0.0.2.<构建序号>`（构建序号起始 1，每次成功编译 +1）
- **daemon**：16.0.0 + P6 IPv6 私有 DHT 改动，作为功能补丁存于 `build/patches/daemon-freeisland-features.patch`（基线 P2 快照 2026-11-23，诊断日志已剔除）；另有 MinGW 构建补丁
- 历史版本标记（`V0.01.x` / `A1~A6`）保留在 `docs/` 与 `CHANGES.md` 中作为历史记录，不再沿用

## 核心改动

### F-904 带外加好友（节点名片交换）
- 名片编解码：`NodeCardCodec.js` / `NodeCardCodec.kt`（`JAMICARD1:` + base64url + SHA256 指纹）
- 导入/导出/批量导入对话框（Qt QML + Android Kotlin）
- **信任请求闭环**：`addContact` + 确定性选边发 `sendTrustRequest`，两端进同一 1:1 会话实现通讯
- 剪贴板自动导入、self 守卫、跨端中文兼容

### Freeisland 品牌定制
- About 页构建号显示与自动递增机制
- 中文文案与资源（JamiStrings.qml / strings.xml）

## 如何应用改动

本仓库的 `patches/` 目录按**上游源码的相对路径**组织。将本仓库对应文件覆盖到 Jami 上游源码的同名路径即可。详细改动清单与合并步骤见 `docs/Jami上游版本对比与合并方案_2026-11-22.md`。

> 注意：Jami 上游源码（daemon / client-qt / client-android）不在本仓库中，请从 https://github.com/savoirfairelinux 获取。

## 许可

本项目基于 Jami（GPLv3+）二次开发，改动部分遵循 GPLv3+ 许可。完整许可证见 [LICENSE](LICENSE)（GNU GPL v3）。
