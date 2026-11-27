# Jami → Jami:Freeisland 品牌定制（About 页）改动说明

**实施时间**：2026-11-20
**范围**：仅 About 页（用户 22:36 拍板：两端都改 / 仅 About 页）
**原则**：追加式、不覆盖上游全局字符串、集中到最少文件、便于 Jami 升级 rebase（呼应 F-904 §10 兼容纪律）
**功能代码**：这是 UI 品牌定制，与 F-904/F-905 功能无关，不改动 daemon / 协议 / P2P 逻辑。

## 1. 需求

| 项 | 要求 |
|---|---|
| 名称 | About 页显示 `Jami:Freeisland`（替代原 "Jami"/"Euclid"/"About Jami" 位） |
| 版本号 | 现有 Jami 版本号后面**分行**显示 `Freeisland V0.01` |
| 版权 | 现有版权信息后面**增加一行** `Freeisland by popeye using AI` |

## 2. 改动清单（基础 4 文件；i18n 订正后再 +2，见 §4）

### 2.1 Qt 桌面 —— `jami-client-qt-master/src/app/mainview/components/AboutPopUp.qml`（单文件 3 处）

| # | 位置 | 改动 | 追加式说明 |
|---|---|---|---|
| 1 | `textMetricsjamiSlogansText.text`（约 :117） | `JamiStrings.slogan` → `"Jami:Freeisland"` | 只改本页 TextMetrics，全局 `JamiStrings.qml` 未动 |
| 2 | `versionAndBuildInfo` 的 text 函数（约 :143） | `return` 前加 `contentStr += "\nFreeisland V0.01";` | 保留原 Build ID + Version 两行 |
| 3 | `textMetricsYears.text`（约 :270） | 末尾拼 `"Freeisland by popeye using AI"` | 保留原 declarationYear + Savoir-faire Linux Inc. 链接 |

### 2.2 Android —— `jami-client-android-master/jami-android/`

| 文件 | 改动 |
|---|---|
| `app/src/main/res/values/strings.xml` | 新增 3 个独立字符串 `freeisland_brand` / `freeisland_version` / `freeisland_copyright`；**不改** `app_name`/`copyright`/`app_release`/`description`/`menu_item_about` |
| `app/src/main/java/cx/ring/about/AboutFragment.kt` | `onCreateView` 的 binding apply 块内追加 3 行：`toolbar.title = freeisland_brand`、`release.text += "\n" + freeisland_version`、`copyright.text += "\n" + freeisland_copyright` |

> 设计要点：`copyright` 与 `app_release` 被 TV 版 About（`TVAboutFragment.kt`）复用，故**不改共享字符串**，改在 `AboutFragment.kt` 运行时追加，只影响手机 About 页，零全局污染。

## 3. 自测结果（2026-11-20 实测）

| 项 | 结果 |
|---|---|
| Qt QML 括号配平 | `{`30/`}`30、`(`18/`)`18 平衡 ✅ |
| Qt 全局 `JamiStrings.qml` 无 Freeisland 污染 | ✅ 0 处 |
| Android XML 配平 | `<string>`675/`</string>`675、resources 1:1 ✅ |
| Android `app_name` 仍为 "Jami"、共享版权/版本串未改 | ✅ |
| **Android APK 编译** | **BUILD SUCCESSFUL in 2m 59s（15 executed）**，脚本 exit=0 ✅ |
| **APK 内容验证** | `resources.arsc` 内实含三条字符串 `Jami:Freeisland` / `Freeisland V0.01` / `Freeisland by popeye using AI` ✅ |
| APK 产物 | `jami-android/app/build/outputs/apk/noPush/debug/app-noPush-debug.apk`（65,563,598 B，22:44:25 生成，晚于改动文件 22:40） |

## 4. 国际化（i18n）合规 —— 2026-11-20 用户明确要求后订正

用户 22:45 明确「界面要做好国际化，支持多种语言」（已写入用户级长期记忆为跨项目通用规则）。按此复查并订正上一轮实现（原做法把三条都写了 `translatable="false"` / Qt 硬编码，不符合 i18n）：

| 文案 | 性质 | 处理方式（订正后） |
|---|---|---|
| `Jami:Freeisland` | 纯品牌标识 | 例外，不翻译；保留资源集中管理（`translatable="false"`） |
| `Freeisland V0.01` | 版本号 | 例外，不翻译；保留资源集中管理（`translatable="false"`） |
| `Freeisland by popeye using AI` | 面向用户的署名文案 | **必须可翻译**：Android 去掉 `translatable="false"` 并在 `values-zh-rCN/strings.xml` 补中文；Qt 用 `qsTr()` 包裹并在 `translations/jami_client_qt_zh_CN.ts` 补译文 |

订正涉及文件（在 §2 的 4 文件基础上 +2）：
- Android：`values/strings.xml`（署名去 `translatable="false"`）+ 新增 `values-zh-rCN/strings.xml` 条目。
- Qt：`AboutPopUp.qml` 署名改 `qsTr("...")` + `translations/jami_client_qt_zh_CN.ts` 新增 AboutPopUp context 译文。
- 代码无需改：`getString()`（Android）/ `qsTr()`（Qt）自动按系统语言取值。

i18n 自测：三处定义与中文配对齐全、署名无 `translatable="false"`、XML `<string>`675:675 / zh-rCN 640:640 / TS `<context>`27:27 配平，均 ✅。
**Android 重编译（i18n 后）**：`BUILD SUCCESSFUL in 56s`，APK `resources.arsc` 实含英文三条 + 中文署名「Freeisland，作者 popeye，由 AI 协助开发」，APK 65,563,670 B（22:48:42）✅。

## 5. 真机验证（Android）—— 2026-11-20 22:53 已完成

设备：Redmi DEVICE-B-MODEL（小米机，序列号 DEVICE-B-SERIAL，Android 16）。安装：`adb install -r` **Success**（MIUI 未拦截）。启动后进入 About 页截图，三处显示逐字确认：

| 要求 | 真机截图实际显示 | 结论 |
|---|---|---|
| 名称 → `Jami:Freeisland` | toolbar 标题栏 **「Jami:Freeisland」** | ✅ |
| 版本号后分行 `Freeisland V0.01` | `Tim Berners-Lee - 20261106-01` 下一行 **「Freeisland V0.01」** | ✅ |
| 版权后加一行署名 | `2004-2025 Savoir-faire Linux Inc.` 下一行 **「Freeisland，作者 popeye，由 AI 协助开发」** | ✅（中文系统下显示中文译文，i18n 生效铁证） |

- 版本号 `versionName=20261106-01` 未变，符合设计（Freeisland 行是 About 页追加显示，不改 BuildConfig）。
- 截图存档：`output/brand_verify/android_about_freeisland_zh.png`（另存 `screen_overflow.png` 原始帧）。
- 英文系统下版权行应显示 `Freeisland by popeye using AI`（APK `resources.arsc` 已含该串，仅当前设备为中文系统故显示译文）。

## 6. 未验证 / 边界

- **Qt 桌面未编译**：本机无 qmake/qmllint in PATH，Qt 客户端历史上从未完整编译（需 MSVC+vcpkg+Qt 6.12 全链，耗时数小时）。QML 改动是字符串字面量与属性赋值，已做括号配平与污染检查，**语法级通过、未做运行级截图验证**。如需截图确认，须先完成 Qt 客户端构建。
- **Android 英文系统截图未做**：中文系统下已验证界面且 i18n 译文正常显示；英文文案已由 `resources.arsc` 内容验证，无需额外切系统语言。

## 7. Jami 升级 rebase 提示

- 改动全部集中在 4 个文件、且为**追加式**（不删上游行），上游升级时冲突面小。
- Qt：若上游重构 `AboutPopUp.qml` 的 `textMetricsjamiSlogansText`/`versionAndBuildInfo`/`textMetricsYears`，按符号名重新定位三处追加点。
- Android：若上游改 `AboutFragment.kt` 的 `onCreateView`，重新插入 3 行追加；新增字符串保留在 strings.xml 末尾即可。
- 若上游新增同名 `freeisland_*` 资源（极不可能），改名规避。

---

> 状态：Qt 语法级通过（未编译）、Android 编译通过且 APK 内含品牌字符串（未真机截图）。未改动 daemon / 功能代码。
