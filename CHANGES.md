# F-904 / Freeisland 改动文件清单

> 用途：合并到 Jami 上游新版本时，逐文件对照。状态标注"新增"=上游无此文件，直接复制；"修改"=上游有同名文件，需对比合并。

## Qt 客户端（patches/qt/，22 个文件）

| 文件（相对上游源码根） | 状态 | 改动内容 |
|---|---|---|
| `src/app/net/jami/Constants/NodeCardCodec.js` | 新增 | 名片编解码（纯 JS base64url，绕开 Qt6 btoa/atob 高位字节问题） |
| `src/app/settingsview/components/NodeCardExportDialog.qml` | 新增 | 名片导出对话框 |
| `src/app/settingsview/components/NodeCardImportDialog.qml` | 新增 | 名片导入对话框（含 self 守卫、预览） |
| `src/app/settingsview/components/NodeCardBatchImportDialog.qml` | 新增 | 批量导入对话框 |
| `src/app/MainApplicationWindow.qml` | 修改 | 剪贴板自动导入（onActiveChanged 读剪贴板） |
| `src/app/contactadapter.cpp` / `.h` | 修改 | 新增 `addContactFromCard` Q_INVOKABLE（确定性选边发信任请求） |
| `src/app/commoncomponents/ListSelectionView.qml` | 修改 | 批量导入列表支持 |
| `src/app/net/jami/Constants/JamiStrings.qml` | 修改 | nodeCard* 文案（中英） |
| `src/app/net/jami/Constants/JamiResources.qml` | 修改 | 名片相关资源 |
| `src/app/qml.qrc` / `resources.qrc` | 修改 | 注册新增 QML/JS 文件 |
| `src/app/settingsview/SettingsView.qml` / `SettingsSidePanel.qml` | 修改 | 「节点名片」菜单入口 |
| `src/app/settingsview/components/ManageAccountPage.qml` | 修改 | 名片菜单挂载 |
| `src/app/mainview/components/AboutPopUp.qml` | 修改 | About 页显示 Freeisland 构建号 |
| `src/app/utilsadapter.cpp` / `.h` | 修改 | stringToFile / clipboardText / getFreeislandBuildNumber |
| `src/version_info/version_info.cpp.in` / `.h` | 修改 | 构建号注入 |
| `extras/build/cmake/generate_version_info.cmake` | 修改 | 构建号自动递增 |
| `translations/jami_client_qt_zh_CN.ts` | 修改 | 中文翻译 |

## Android 客户端（patches/android/，11 个文件）

| 文件（相对 jami-android/ 根） | 状态 | 改动内容 |
|---|---|---|
| `app/src/main/java/cx/ring/utils/NodeCardCodec.kt` | 新增 | 名片编解码（canonicalStd 双规则兼容） |
| `app/src/main/java/cx/ring/account/NodeCardExportDialog.kt` | 新增 | 导出对话框 |
| `app/src/main/java/cx/ring/account/NodeCardImportDialog.kt` | 新增 | 导入对话框（预填解析、按钮启用时序） |
| `app/src/main/java/cx/ring/account/NodeCardBatchImportDialog.kt` | 新增 | 批量导入 |
| `app/src/main/java/cx/ring/client/HomeActivity.kt` | 修改 | 剪贴板自动导入（窗口焦点回调）、3 秒防重 |
| `app/src/main/java/cx/ring/account/JamiAccountSummaryFragment.kt` | 修改 | 名片入口、self 跳过 |
| `app/src/main/java/cx/ring/about/AboutFragment.kt` | 修改 | About 页 Freeisland 版本标记 |
| `app/src/main/res/layout/frag_acc_summary.xml` | 修改 | 名片入口布局 |
| `app/src/main/res/values/strings.xml` | 修改 | nodeCard* 文案 + `freeisland_version`（V0.0.2.<构建序号>） |
| `app/src/main/res/values-zh-rCN/strings.xml` | 修改 | 中文文案 |
| `libjamiclient/src/main/kotlin/net/jami/services/AccountService.kt` | 修改 | 新增 `addContactFromCard`（确定性选边） |

## 构建（build/）

| 文件 | 说明 |
|---|---|
| `patches/daemon-mingw-*.patch`（4 个） | daemon Windows MinGW/MSVC 构建修复 |
| `patches/dhtnet-mingw-fix.patch` | dhtnet Windows 构建修复 |
| `patches/libgit2-mingw-regex.patch` | libgit2 Windows 构建修复 |
| `patches/SetupAPI.h` / `poll.h` | Windows 编译头文件替补 |
| `scripts/` | 构建脚本（V0.0.2，构建序号按端自增） |
| `tools/parse_dhtstate.py` | DHT 节点缓存解析工具 |
