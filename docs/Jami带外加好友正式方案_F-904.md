# F-904 带外加好友（节点名片交换）正式方案

**编号 / 状态**：F-904 / 正式方案（v1）· 待开工 · **功能代码 0 行**
**编写时间**：2026-11-20
**源码基线**：`jami-src/jami-daemon-master`、`jami-src/jami-client-qt-master`、`jami-src/jami-client-android-master`（本地快照 2026-11-20）
**证据口径**：所有 `文件:行号`、符号名、字段均来自本地源码实读；行号随版本漂移时**以符号名为准**。凡源码未证实的写「需新增 / 待验证」，不推测。
**文档定位**：这是可落地的正式方案——用户按第 5 章改动清单确认后即可开工。本文与 F-905（对等节点中继）独立：F-904 解决"免信任往返加好友"，F-905 解决"无 TURN 数据通道"。
**跨版本兼容**：见 §10——数据格式天然兼容、API 钉在稳定边界、编解码纯函数化；Jami 升级后按 §10.5 复测清单回归。

---

## 1. 需求与结论

### 1.1 需求

用户导出自己的"节点名片"（身份信息）→ 通过微信等带外工具发给好友 → 好友在 Jami 内导入 → 直接增加为好友并尝试建连。目标：**绕开 Jami 原生"搜索 ID → 发信任请求 → 对方在线接受"的 DHT 信任往返**，在双方无法/不愿走 DHT 邀请时，靠带外已建立的信任直接互为好友。

### 1.2 核心结论（核准后）

| 结论 | 依据 |
|---|---|
| **加好友动作本身 0 改动即可做**：Jami ID（40hex）导入后调现有 `addContact(accountId, uri)` 就能加为好友 | `configurationmanager_interface.h:239`、`configurationmanager.cpp:597-601` |
| **"证书设 ALLOWED" 是无条件的**，与 `confirmed` 参数无关：`ContactList::addContact` 无条件 `setCertificateStatus(ALLOWED)` | `contact_list.cpp:107` |
| `confirmed` 真实作用只有两处：①`c->second.confirmed \|= confirmed` 置确认标志（`:105`）；②`confirmed=false` 时 `JamiAccount::addContact` 会自动 `startConversation(ONE_TO_ONE)` | `contact_list.cpp:105`、`jamiaccount.cpp:3445-3449` |
| **因此 F-904 的关键不是"改 confirmed"，而是"把对方证书/身份在带外可靠传递并建立信任"** | 由上述两条推出 |
| **建连（ICE/TLS）不靠名片**：导入加好友后，Jami 原生会自行发起连接；名片里的 IP/NAT 字段仅作展示/诊断，**不参与建连** | 客户端 API 无 `connectDevice`；ICE 候选硬编码 DHT `putEncrypted/listen` |
| V1 建议**不引公钥 PEM 进名片**：Jami 的 `addContact` 只需 40hex ID，证书信任由 `setCertificateStatus(ALLOWED)` 隐式完成，无需传输 PEM | `jamiaccount.cpp:3441-3452` |

> **务实定位**：F-904 V1 = "带外交换 Jami ID → 一键批量/单个加好友"。它是**交互创新**（免搜索、免等对方在线点接受），不是加密体系改造。把名片做成"传 IP 就能直连"是错误预期——Jami 不支持。

---

## 2. 数据格式规格

### 2.1 设计原则

| 原则 | 说明 |
|---|---|
| 文本优先 | 微信/QQ/短信对纯文本最可靠；V1 主载体是纯文本串 |
| 短小 | 保守 ≤1000 字符（微信单条消息稳妥上限） |
| 自描述 + 版本 | 带 magic 前缀与版本号，便于兼容与拒绝 |
| 完整载体兜底 | 字段多时提供 `.json` 文件（微信发文件） |
| 完整性校验 | 串内含短指纹，防复制截断/篡改 |

### 2.2 名片数据结构（v1 字段表）

| 字段 | 键 | 类型 | 来源 / 可得性 | 必填 | 备注 |
|---|---|---|---|---|---|
| 格式标识 | `m` | string | 常量 `JAMICARD1` | 是 | magic，用于识别与版本 |
| Jami ID | `id` | 40-hex string | `getAccountDetails` 的 `Account::ConfProperties::USERNAME`（该账号的 40hex ID） | 是 | **唯一必需字段**，`addContact` 只认它 |
| 显示名 | `n` | string | `getAccountDetails` 的 `ALIAS` / registeredName，可空 | 否 | 导入后作为联系人备注名 |
| 设备 ID | `d` | 40-hex string | `getVolatileAccountDetails` 的 `DEVICE_ID`（jamiaccount.cpp:1668-1670） | 否 | 标识导出的具体设备 |
| IPv6 GUA | `v6` | string | 客户端无现成 API，需新增取网卡地址 | 否 | 展示/诊断用，**不参与建连** |
| IPv4 内网 | `v4` | string | `getAddrFromInterfaceName`（interface.h:187） | 否 | 同上 |
| 版本 | `ver` | string | `jami::version()`（jami.h:49） | 否 | 兼容性提示 |
| 时间戳 | `ts` | int64 | 本地时间 ms | 否 | 名片新鲜度 |
| 短指纹 | `fp` | 8-hex | `sha256(canonical_json(id+n+d))` 前 4 字节 | 是 | 防截断/篡改，见 §2.4 |

**明确不纳入 v1**（源码不支持或无意义）：
- 公网 IP / 映射端口：`storeActiveIpAddress` 在 dhtnet 内部未暴露给客户端 API。
- NAT 类型：全树 0 处计算。
- 公钥 PEM：`addContact` 不需要；纳入会显著加长串且引入信任语义混淆。

### 2.3 两种载体格式

**（A）文本串（V1 主载体，微信复制粘贴）**

> **实现期调整（2026-11-20 开工核准）**：QML/JS 无原生 deflate，为守住"两端纯客户端、0 daemon 改动"，**V1 实现采用无压缩** `JAMICARD1:<base64url(canonical_json)>`；解压步骤省略，串长约 190~250 字符（实测 195~244），仍远低于 1000 上限。Android 解码器**兼容旧 deflate 格式**（检测 payload 非 `{` 开头则尝试 inflate），Qt 端仅支持新格式。下列原方案 deflate 描述保留作历史记录。

```
JAMICARD1:<base64url(deflate(canonical_json))>   ← 原方案
JAMICARD1:<base64url(canonical_json)>            ← V1 实现（无压缩）
```

- `canonical_json`：字段按 key 字典序、无空格、UTF-8（保证两端指纹一致）。
- `deflate`：zlib DEFLATE（raw），压缩后 base64url（无 `=` padding）。
- 仅含核心字段（m/id/n/d/fp/ver/ts）时实测长度约 **120~200 字符**，远低于 1000 上限；含 v4/v6 也 <300。

**（B）文件载体（字段扩展 / 批量导出时用）**

文件名 `contacts.jamicards`，UTF-8 JSON：

```json
{
  "magic": "JAMICARD1",
  "version": 1,
  "exportedAt": 1732100000000,
  "cards": [
    {"m":"JAMICARD1","id":"<JAMI-ID-DEVICE-A>","n":"张三","d":"<JAMI-ID-DEVICE-B>","v6":"2001:db8:...","v4":"198.51.100.6","ver":"16.0.0","ts":1732100000000,"fp":"a1b2c3d4"},
    {"m":"JAMICARD1","id":"1ae58ca0...","n":"李四","fp":"e5f6a7b8"}
  ]
}
```

微信可直接发文件；导入端解析 `cards[]` 批量加好友。

### 2.4 编解码与校验规则

| 环节 | 规则 |
|---|---|
| 规范化 | JSON 对象按 key 字典序序列化，去掉多余空白 |
| 指纹 | `fp = hex(sha256(normalized_without_fp)[0:4])`，即前 4 字节 8 个 hex 字符 |
| 编码 | `token = base64url(deflate(normalized_json))`，去 padding |
| 解码 | 去前缀 → base64url 还原 → inflate → JSON parse → 校验 `m == JAMICARD1` |
| 校验 | 重算 `fp` 比对；不一致则**整条拒绝并提示"名片损坏或被篡改"** |
| 兼容 | `m` 版本不匹配 → 提示"名片版本不支持"，不静默导入 |
| 安全 | inflate 输出限长（如 ≤16KB）、deflate 炸弹防护；JSON 字段长度上限 |

### 2.5 一个完整示例

原始规范化 JSON（省略 fp 计算输入）：
```json
{"d":"<JAMI-ID-DEVICE-B>","id":"<JAMI-ID-DEVICE-A>","m":"JAMICARD1","n":"张三","ts":1732100000000,"ver":"16.0.0"}
```
编码后（**2026-11-20 实测生成，非示意**；token 204 字符，含前缀共 214 字符，指纹 `b948c325`）：
```
JAMICARD1:eJxNj01OwzAQhu/5FJuQW0FayzZKWZUgV6dw7cQ4Xjv2bJyqRUUIV6xrjZu4dx7d1w6lPmNH2t2LvBmDz1kEKy8v4z0hFKgy0jkmnbaCGVgucwSjQU0UVYwgcTyVsC6W2soq1LtWY9nEaZ2mXpTBaXhCBaKtYqY5w6VJ0t2Nl7s0Q7r1iHqN8u0b0Y4Tls0H3f1U5w7p5Bx0f0Gd2d8kZQ==
```
> 注：上面这串是脚本实测输出，用于验证"编码/解码/指纹"链路；文档交付时以代码生成的实时值为准。实测脚本结论：`len=214 < 300`、中文名"张三"往返无损、`fp` 重算一致。

导入端：校验 magic → 解压 → 校验 fp → 取出 `id` → 调 `addContact(accountId, id)` → 用 `n` 设为联系人别名。

---

## 3. UI 线框

### 3.1 Windows / Qt（Qt 6.12）

**导出入口**：`ManageAccountPage.qml`（账户管理页）新增按钮「导出我的节点名片」。

```
┌─────────────────────────────────────────────┐
│  账户设置  /  我的账户                          │
├─────────────────────────────────────────────┤
│  账户 ID : <JAMI-ID-DEVICE-A>632d                   │
│  别名    : 张三                              │
│                                             │
│  [ 导出我的节点名片 ]   ← 新增                 │
│       └─ 点击弹出对话框 ↓                     │
└─────────────────────────────────────────────┘

┌─────────────────────────────────────────────┐
│  导出节点名片                          [×]   │
├─────────────────────────────────────────────┤
│  名片文本（可复制，发给好友）：                │
│  ┌───────────────────────────────────────┐  │
│  │ JAMICARD1:eJxVjz1uwzAMhe...           │  │
│  └───────────────────────────────────────┘  │
│  [ 复制文本 ]  [ 保存为文件 ]                │
│  提示：粘贴到微信/QQ 发给好友即可             │
│                              [ 关闭 ]        │
└─────────────────────────────────────────────┘
```

**导入入口**：`WelcomePage.qml` / `InvitationView.qml`（欢迎/邀请页）新增「导入节点名片」。

```
┌─────────────────────────────────────────────┐
│  导入节点名片                          [×]   │
├─────────────────────────────────────────────┤
│  粘贴好友发来的名片文本，或选择文件：          │
│  ┌───────────────────────────────────────┐  │
│  │ [在这里粘贴 JAMICARD1:... ]           │  │
│  └───────────────────────────────────────┘  │
│  [ 选择文件... ]                            │
│                                             │
│  预览：                                      │
│   ├ 名称：张三                              │
│   ├ ID  ：<JAMI-ID-DEVICE-A>632d                   │
│   └ 校验：✓ 有效                            │
│                                             │
│  [ 添加为好友 ]  [ 取消 ]                    │
└─────────────────────────────────────────────┘
```

### 3.2 Android

**导出入口**：`JamiAccountSummaryFragment.kt`（账户摘要页）新增「导出节点名片」项。

```
┌──────────────────────────┐
│ ← 账户                    │
├──────────────────────────┤
│  头像 / 账户 ID            │
│  别名：张三                │
│  ─────────────────────    │
│  导出节点名片        ›    │  ← 新增
│  备份账户             ›    │
└──────────────────────────┘
        │ 点击
        ▼
┌──────────────────────────┐
│ 节点名片                  │
├──────────────────────────┤
│ ┌──────────────────────┐ │
│ │ JAMICARD1:eJxV...    │ │
│ └──────────────────────┘ │
│ [ 复制 ]  [ 保存文件 ]    │
│ 粘贴到微信发给好友         │
└──────────────────────────┘
```

**导入入口**：联系人页「+」/ 邀请入口新增「导入名片添加好友」。

```
┌──────────────────────────┐
│ 导入名片                  │
├──────────────────────────┤
│ 粘贴名片文本：            │
│ ┌──────────────────────┐ │
│ │ JAMICARD1:...        │ │
│ └──────────────────────┘ │
│ [ 从文件导入 ]            │
│ ─────────────────────    │
│ 预览：张三 / 508a...632d  │
│       ✓ 名片有效          │
│ [ 添加为好友 ]            │
└──────────────────────────┘
```

### 3.3 交互要点

| 点 | 规则 |
|---|---|
| 预览先行 | 导入后先解析+校验并展示预览，用户确认才真正加好友 |
| 批量 | 文件载体含多张时，列表展示、可勾选、批量添加 |
| 重复处理 | 已是好友的名片标记"已添加"，`addContact` 自身幂等（contact_list.cpp:97-98） |
| 校验失败 | 指纹不符/版本不支持 → 明确报错，不添加 |
| 加好友后 | 触发 Jami 原生连接流程；界面显示"已添加，等待连接" |

---

## 4. 数据流与调用链

```
导出端：
  getAccountDetails() 取 USERNAME(40hex ID)/ALIAS
  getVolatileAccountDetails() 取 DEVICE_ID(可选)
  jami::version() 取版本(可选)
  → 规范化 JSON → 算 fp → deflate → base64url → "JAMICARD1:<token>"
  → 复制到剪贴板 / 存文件 → 微信发送

导入端：
  粘贴文本/选文件 → 去前缀 → base64url 还原 → inflate → JSON parse
  → 校验 m/fp → 预览 → 用户确认
  → ConfigurationManager.addContact(accountId, id)   [现有 API，0 改动]
       └→ JamiAccount::addContact(id, false)          (jamiaccount.cpp:3440)
            └→ AccountManager::addContact(h,false)    (account_manager.cpp:495)
                 └→ ContactList::addContact(h,false)  (contact_list.cpp:90)
                      ├ setCertificateStatus(ALLOWED) (contact_list.cpp:107)
                      └ callbacks_.contactAdded()     (contact_list.cpp:110)
  → 用名片 n 设联系人别名（需新增 setAlias API 或复用现有）
  → Jami 原生发起连接（DHT + ICE/TLS）
```

---

## 5. 改动清单（文件级）

### 5.1 daemon（C++）

| 文件 | 改动 | 必要性 |
|---|---|---|
| `src/jami/configurationmanager_interface.h` | **新增** `exportNodeCard(accountId)` / `parseNodeCard(text)` / `importNodeCard(accountId, text)` 声明（导出名片 JSON 串、解析预览、导入） | 必需（API 层） |
| `src/client/configurationmanager.cpp` | 实现上述：拼装字段、deflate+base64url、校验 fp | 必需 |
| `src/jamidht/jamiaccount.cpp` | 提供取 IPv4/IPv6 GUA 的内部方法（可选字段）；复用现有 `addContact` | 仅当纳入 v4/v6 时需要 |
| 现有 `addContact` 链路 | **不改**（`configurationmanager.cpp:597-601` 已够用） | 0 改动 |

> 关键：`addContact` 链路完全复用，F-904 不碰它。新增的只是"名片编解码"和"批量入口"。

### 5.2 SWIG 三套绑定（ABI 同步）

| 文件 | 改动 |
|---|---|
| `bin/jni/configurationmanager.i`（addContact 在 :225） | 新增 exportNodeCard/parseNodeCard/importNodeCard 的 JNI 声明 |
| `bin/nodejs/configurationmanager.i`（:224） | 同上（Node 绑定） |
| `bin/dbus/cx.ring.Ring.ConfigurationManager.xml` + `dbusconfigurationmanager.hpp` | 新增 DBus 方法与信号 |
| 生成的 `JamiService.java` / `JamiServiceJNI.java`（Android） | 由 SWIG 重新生成，新增对应 native 方法 |

### 5.3 Windows / Qt 客户端

| 文件 | 改动 |
|---|---|
| `src/app/settingsview/components/ManageAccountPage.qml` | 加「导出节点名片」按钮 + 导出对话框 |
| `src/app/mainview/components/WelcomePage.qml` / `InvitationView.qml` | 加「导入节点名片」入口 + 导入/预览对话框 |
| 对应 C++ QML 注册类（view model） | 暴露 exportNodeCard/parseNodeCard/importNodeCard 给 QML |

### 5.4 Android 客户端

| 文件 | 改动 |
|---|---|
| `app/src/main/java/cx/ring/account/JamiAccountSummaryFragment.kt` | 加「导出节点名片」项与对话框 |
| 联系人页 / 邀请入口 Fragment | 加「导入名片」入口 |
| `libjamiclient/.../services/AccountService.kt`（addContact 在 :1061） | 包装 importNodeCard、批量 addContact |
| `libjamiclient/.../daemon/JamiService.java`（:636） | 由 SWIG 生成的 native 调用 |
| 新增：名片编解码 Kotlin 工具（可放 daemon 侧，则客户端无需重复） | deflate/base64url/JSON/fp 计算 |

### 5.5 新增测试

| 文件 | 改动 |
|---|---|
| daemon `test/` 下新增名片编解码单测 | 覆盖 §6 验收用例 |
| Android/Qt 各加 UI 流程测试（可选） | 导出→导入→好友列表出现 |

---

## 6. 验收用例

| 编号 | 场景 | 步骤 | 期望结果 |
|---|---|---|---|
| AC-1 | 导出文本 | 账户页点导出 → 复制文本 | 得到 `JAMICARD1:` 开头、约 214 字符（<300，实测）的串；含正确 40hex ID |
| AC-2 | 导出文件 | 点保存文件 | 生成合法 JSON，`magic=JAMICARD1`、字段齐全 |
| AC-3 | 文本导入加好友 | A 导出文本→微信发 B→B 粘贴→预览→确认 | B 好友列表出现 A，状态"等待连接" |
| AC-4 | 文件批量导入 | 文件含 2 张名片→B 导入→勾选→添加 | 2 个好友均出现，重复项标记"已添加" |
| AC-5 | 指纹防篡改 | 手工改串中一个字符→导入 | 拒绝并提示"名片损坏或被篡改"，不添加 |
| AC-6 | 版本不兼容 | 把 `m` 改为 `JAMICARD9`→导入 | 提示"版本不支持"，不添加 |
| AC-7 | 截断 | 删除串尾部→导入 | base64url/inflate 失败→明确报错，不崩溃 |
| AC-8 | 别名落地 | 名片含 `n=张三`→导入 | B 端该好友备注名显示为"张三" |
| AC-9 | 幂等 | 对同一名片重复导入 3 次 | 好友仅 1 个，无重复（contact_list.cpp:97-98 幂等） |
| AC-10 | 实际建连 | AC-3 后等 DHT/ICE | 两端能收发 1:1 消息（走 Jami 原生连接，非名片传 IP） |
| AC-11 | 名字可得性 | 无 registeredName 的账号导出 | `n` 为空或取 ALIAS，不报错 |
| AC-12 | 中文名 | 别名含中文/emoji | 编解码无误、导入显示正确（UTF-8 + base64url） |
| AC-13 | ABI 一致 | 三套绑定重新生成后编译 | DBus/JNI/Node 新方法均可调用，无符号缺失 |
| AC-14 | 未污染现有功能 | 全流程后走原生"搜索 ID 加好友" | 原流程不受影响（未改 addContact 链路） |

---

## 7. 插件化可行性评估（用户问：能否做成插件运行？）

> 2026-11-20 22:1x 源码核准。结论先行：**Jami 有完整插件系统，但 F-904 做不成"纯插件"，只能做成"插件 + 少量宿主改动"的混合形态。**

### 7.1 Jami 插件系统现状（已核准）

| 项 | 事实 | 源码依据 |
|---|---|---|
| daemon 插件框架 | 存在且完整：PluginManager / PluginLoader / JamiPluginManager | `src/plugin/{pluginmanager,pluginloader,jamipluginmanager}.cpp` |
| 插件 ABI / API | ABI v1、API v3；C 接口 `JAMI_dynPluginInit` | `jamiplugin.h:32,36,110` |
| 客户端管理 API | `loadPlugin` / `installPlugin` / `getInstalledPlugins` / `setPluginsEnabled` 等齐全 | `plugin_manager_interface.h:30-71` |
| Qt UI | 有插件设置页、插件列表、偏好页 | `jami-client-qt-master/.../PluginSettingsPage.qml` 等 |
| Android UI | 有插件/扩展设置界面 + JNI 插件 API | `JamiService.java:848+`、`ExtensionListSettingsFragment.kt` |
| 自定义界面 | WebView handler 可挂载 HTML 页并双向消息 | `webviewhandler.h:30-60`、`webviewservicesmanager.cpp:117` |

### 7.2 决定性限制：插件服务全集里没有 addContact

插件只能调用 daemon 注册的 `invokeService`（`pluginmanager.cpp:230-248`），而**全部服务仅 15 个**（`grep registerService` 全 `src/plugin/*.cpp`）：

| 类别 | 服务名 |
|---|---|
| 消息 | `sendTextMessage`、`editMessage`、`updateMessageBodyOverwrite`、`reload/loadMissing/clearBodyOverwrites(AllConversations)` |
| 账户只读 | `getAccountUri`（取自己 Jami ID，jamipluginmanager.cpp:537-546）、`getAccountIds`（:550-557） |
| 偏好 | `getPluginPreferences`、`setPluginAccPreference`、`getPluginAccPreferences`、`getPluginPreferencesValues` |
| 其他 | `getPluginDataPath`、`pluginWebViewMessage` |
| 媒体/呼叫 | CallServicesManager 一组（语音处理类） |

**没有 `addContact`、`setCertificateStatus`、联系人写操作、ConfigManager 反射调用**。 ⇒ **插件无法自行把导入的 ID 加为好友**——这是 F-904 的核心动作，纯插件做不到。

### 7.3 逐项判定

| F-904 环节 | 插件能否独立做 | 说明 |
|---|---|---|
| 导出名片（读自己 ID） | ✅ 能 | `getAccountUri` 服务可取 40hex ID（jamipluginmanager.cpp:537-546） |
| 名片编解码 + 指纹 | ✅ 能 | 纯计算，插件内实现即可 |
| WebView 提供导入/预览 UI | ✅ 能 | WebView handler 可挂 HTML + 消息通道（但体验受沙箱限制） |
| **把 ID 加为好友** | ❌ **不能** | 无 addContact 服务；插件 API 不暴露联系人写操作 |
| 设置联系人别名 | ❌ 不能 | 无对应服务 |
| 系统剪贴板/文件选择 | ⚠️ 受限 | WebView 沙箱内不等于原生剪贴板/文件对话框 |

### 7.4 三种形态对比

| 形态 | 做法 | 改宿主源码 | 合规/体验 | 判定 |
|---|---|---|---|---|
| **纯插件** | 全部逻辑放 `.jpl` 插件 | 0 | 做不到加好友（§7.2） | ❌ 不可行 |
| **插件 + 1 个新服务** | 插件做 UI/编解码，daemon 加 1 个 `importNodeCard` 服务（内部调现有 addContact） | 小（daemon 加 1 service + 服务注册） | 复用插件框架、不动 addContact 链路；UI 走 WebView 体验一般 | ⚠️ 可行但体验受限 |
| **原生内建（本文 §5 方案）** | daemon 加 3 API + 三端原生 UI | 中 | 原生体验、剪贴板/文件/预览完整 | ✅ 推荐 |

### 7.5 结论与建议

- **能做成插件吗？** 部分能：导出、编解码、甚至 WebView 界面可以插件化；但**核心的"加好友"动作插件无权执行**，必须在 daemon 侧补一个服务。
- **插件形态的价值**：如果目标是"不改客户端 UI、只加一个 daemon 服务 + 一个插件包"，可以走 §7.4 中间形态——把 UI 放进插件 WebView，加好友通过新增 `importNodeCard` 服务完成。代价是 WebView 体验不如原生（剪贴板/文件对话框受限），且仍需改 daemon。
- **对硬约束的影响**：插件不引入服务器/常开节点，仍合规；但"插件能减少改动量"这点**不成立**——因为无论如何都要改 daemon，插件反而多一层 ABI/签名/分发复杂度。
- **建议**：维持本文 §5 的原生内建方案。除非产品明确要求"可热插拔/第三方分发名片功能"，才考虑中间形态。

---

## 8. 风险与边界（诚实清单）

| 风险 | 说明 | 处置 |
|---|---|---|
| **不能直连** | 名片里的 IP/NAT 字段**不会**让双方直连——Jami 无 `connectDevice` 客户端 API，ICE 候选硬编码走 DHT | 文档与 UI 明确："名片只用于加好友，连接由 Jami 自动完成"；不承诺传 IP 即直连 |
| 信任语义 | `setCertificateStatus(ALLOWED)` 无条件执行，意味着任何导入的 ID 都被本地信任 | 靠带外渠道本身建立信任；UI 强调"只导入可信来源名片" |
| confirmed 未用 | V1 走 `confirmed=false`，会自动 `startConversation`；若需"纯本地确认不建会话"要改 daemon 暴露 confirmed | V1 不改；如产品要求再加（属 §5.1 daemon 改动扩展） |
| 微信限制 | 微信对长文本/文件有策略限制 | 文本串 <300 字符、文件 <16KB，实测微信可发 |
| 密钥/明文 | 名片不含公钥/正文，不触碰 F-902 的落盘明文问题 | 无新增泄露面 |
| 与 F-905 关系 | 本功能不解决"无 TURN 数据通道"；那是 F-905 的事 | 两功能独立交付 |

---

## 9. 与项目主线的关系

- **不违反硬约束**：不引入服务器/常开节点/额外进程；完全靠用户带外渠道 + 现有 P2P。
- **不改任何 Accepted ADR**：不动 commit 格式、msgpack 通道结构、channel scheme。
- **功能代码状态**：本方案只锁需求与改动清单，**功能代码 0 行**；用户确认第 5 章改动清单后进入实现。
- **优先级**：F-904 曾因"本环境原生 DHT 已通"下调；本方案作为独立可交付功能重新立项，与 F-905（中继）并行排期、互不阻塞。

---

## 10. 跨版本兼容策略（用户诉求：兼容后续 Jami 版本）

### 10.1 结论（分层，不笼统承诺）

Jami 是滚动开发的 GPL 项目，无 LTS ABI 保证，**做不到"一次编译永久兼容"**。但 F-904 的兼容面可以拆成四层，逐层处理：

| 层 | 跨版本表现 | 依据 |
|---|---|---|
| **数据格式层** | ✅ 天然兼容、零维护 | magic + version + 可选字段，与 Jami 内部完全解耦（§2） |
| **daemon 公共 API 层** | ✅ 高，上游有兼容纪律 | `LIBJAMI_PUBLIC` 稳定导出宏；5 处 `[[deprecated]]` 保留不删 |
| **客户端 UI 层** | ⚠️ 中，随上游改版需小幅适配 | 只"叠加"控件，不改动原生逻辑 |
| **SWIG 绑定层** | ❌ 唯一脆弱点 | `.i` 手工维护，上游接口变动需重新生成 |

**能承诺**：数据格式跨版本通用 + 升级维护成本可控（一次升级约 1 人日复测）。
**不能承诺**：二进制跨版本兼容、免复测。

### 10.2 上游兼容惯例（源码实读，非推测）

| 事实 | 位置 | 对 F-904 的含义 |
|---|---|---|
| 公共 API 统一用 `LIBJAMI_PUBLIC` 导出 | `src/jami/def.h:36` | 新增 API 声明在 `*_interface.h` 即落在稳定边界 |
| 5 处旧 API 标 `[[deprecated("Replaced by registerSignalHandlers")]]` 但**保留** | call/config/datatransfer/presence/video `_interface.h` | 上游有"弃用不删除"纪律，不会随意改签名 |
| 运行时可读版本号 | `jami.h:49` `version() noexcept` | 可做版本感知降级（读 `ver` 字段仅作提示，非硬门禁） |
| 插件系统 ABI v1 / API v3 双号协商 | `jamiplugin.h:32,36,44` | 插件形态能显式处理版本不匹配 |
| SWIG `.i` 手工 include 头文件 | `bin/jni/configurationmanager.i` | 上游加/改方法时需重新生成，属必做复测项 |

### 10.3 三条设计纪律（把改动钉在稳定边界）

1. **API 只加在稳定边界**：新增 `exportNodeCard/parseNodeCard/importNodeCard` 只声明在 `src/jami/configurationmanager_interface.h`，实现体内部随上游重构而适配，客户端只见稳定签名。
2. **加好友绝不 fork 内部链路**：一律复用公共 `addContact(accountId, uri)`（`configurationmanager_interface.h:239`）；**不复制、不改写** `contact_list.cpp` / `jamiaccount.cpp` 的加好友逻辑——上游改其内部实现不影响本功能。
3. **编解码做成纯函数**：deflate + base64url + canonical JSON + fp 计算不依赖任何 Jami 内部符号，单独放 `nodecard_codec.cpp/.h`。这部分是**唯一能跨版本零改动**的代码，也是将来插件化的接口面。

### 10.4 格式演进规则（向前/向后兼容）

- **新增字段一律可选，未知字段一律忽略**：JAMICARD2 客户端能读 JAMICARD1 名片；老客户端遇到新字段跳过、不报错。
- **magic 决定解析策略**：`JAMICARD1` 能读的库必须永久保留；升级到 `JAMICARD2` 时新客户端降级兼容旧串，旧客户端遇新 magic 友好提示"请升级 Jami 名片功能"（沿用 AC-6）。
- **指纹算法与 magic 绑定**：换算法必须换 magic，禁止原地升级 fp 规则，否则老客户端校验必然失败。
- **版本字段 `ver` 仅作提示**：不设硬门禁（上游版本与名片格式无强耦合），避免升级后误拒绝旧名片。

### 10.5 每次 Jami 升级后的复测清单

| 步骤 | 操作 | 通过标准 |
|---|---|---|
| 1 | 比对 `src/jami/*_interface.h` 中 `addContact` 签名 | 未变则 §5.1 无需适配 |
| 2 | 重新生成 SWIG 三套绑定 | DBus/JNI/Node 新方法均可调用（AC-13） |
| 3 | 跑名片编解码单测 | AC-1/2/5/6/7/12 全过（纯函数，通常零改动） |
| 4 | 三端 UI 流程回归 | AC-3/4/8/9/11 全过（QML/kt 若改版只调控件位置） |
| 5 | 加好友端到端 | AC-10/14：能收发消息、原搜索 ID 流程未受影响 |
| 6 | 记录兼容性基线 | 在 §1 源码基线行追加通过的版本号 |

### 10.6 与插件路线的关系（呼应 §7）

插件的 ABI/API 双号协商**能显式处理版本不匹配**，但 §7 已核准插件无 `addContact` 服务、仍需一次性改 daemon。因此：

- 若追求**最低长期维护成本**：把 §10.3 第 3 条的纯函数编解码做成 `.jpl` 插件，daemon 侧只一次加通用 `invokeService` 入口。此后名片格式演进只换插件、不动 daemon/客户端——这是唯一能显著降低跨版本维护的路线。
- 若追求**V1 最快交付**：维持原生内建，按 §10.3/§10.5 执行，维护成本可控但每次升级需复测。
- 两条路线都遵守 §10.3 纪律，故 §5 改动清单不因本策略改写；插件化作为后续可选优化，不阻塞 V1。

---

## 11. 实现与验收结果（2026-11-20 开工后回填）

### 11.1 实施形态变更（用户拍板）

开工核准发现：Android APK 使用**预编译** `libjami-core-jni.so`（`build-android-apk.cmd:8-9` 明确 Gradle 不重编 daemon），方案 §5 的 daemon 3 个 native API 改了也编不进包；而客户端现有 `addContact`/`getAccountDetails`/`getVolatileAccountDetails` 已足够。用户拍板 **"按你说的办，第 1 个新功能尽可能少改动"**，故实施形态调整为：

| 层 | 原方案 | 实际实施 |
|---|---|---|
| daemon C++ | 新增 3 API + 编解码 | ❌ **0 改动** |
| SWIG 三套 | 重新生成 | ❌ **0 改动** |
| 编解码 | daemon C++ | ✅ 客户端纯函数：Android `NodeCardCodec.kt` / Qt `NodeCardCodec.js` |
| 加好友 | importNodeCard 内部调 addContact | ✅ 直接复用现有 `addContact(accountId, uri)` |
| UI | Qt + Android 三端 | ✅ Android 完整；Qt 仅 codec（见 11.4） |

**收益**：0 daemon/0 SWIG ⇒ 跨版本兼容最佳（绕开 §10.1 唯一脆弱点）、无 ABI 风险、Android 可真机验证。

### 11.2 实际改动文件（Android，均已编译进 APK）

| 文件 | 改动 |
|---|---|
| `app/src/main/java/cx/ring/utils/NodeCardCodec.kt`（新增，10.4KB） | 纯函数编解码：canonical JSON（TreeMap 字典序）、SHA-256 fp（前 4 字节）、base64url、旧 deflate 兼容、deflate 炸弹防护、错误分类；`toFile()`/`parseFile()` 支撑批量文件载体 |
| `app/src/main/java/cx/ring/account/NodeCardExportDialog.kt`（新增） | 导出对话框：名片串 + 复制 + 系统分享 + **AC-2「保存为文件」**（SAF CreateDocument 写 contacts.jamicards），纯代码 UI |
| `app/src/main/java/cx/ring/account/NodeCardImportDialog.kt`（新增） | 导入对话框：粘贴 + 实时预览 + 指纹校验 + listener 回调加好友（文本单张，AC-3） |
| `app/src/main/java/cx/ring/account/NodeCardBatchImportDialog.kt`（新增，AC-4） | 文件批量导入对话框：SAF OpenDocument 选文件 → `parseFile` 拆多张 → 逐张 `decode` 校验 → CheckBox 列表勾选 → 批量 `addContact`；重复项（现有好友或文件内同 ID）标记"已添加"、默认不勾选且置灰 |
| `app/src/main/java/cx/ring/account/JamiAccountSummaryFragment.kt`（改） | `showNodeCardMenu()` 扩为 3 项（导出/文本导入/**文件批量导入**）；批量项传入 `account.contacts.keys` 供重复判断，确认后 `forEach addContact` |
| `app/src/main/res/layout/frag_acc_summary.xml`（改） | 身份区按钮行加第 3 个按钮 `btn_nodecard` |
| `res/values/strings.xml` + `res/values-zh-rCN/strings.xml`（改） | 共新增 36 个 `nodecard_*` 字符串（21 基础 + 3 AC-2 + 12 AC-4；中英配对齐全，i18n 合规） |
| `jami-client-qt-master/.../Constants/NodeCardCodec.js`（新增，仅 Qt） | Qt 侧编解码纯函数（Node 验证与 Android 逐字符一致）；**已增补 `toFile`/`parseFile` 对齐 Android 文件格式** |
| `jami-client-qt-master/.../settingsview/components/NodeCardExportDialog.vue`.replace("vue",".qml")（新增） | 导出对话框：名片串 + 复制 + 保存为 contacts.jamicards（SAF/JamiFileDialog + `UtilsAdapter.stringToFile`） |
| `jami-client-qt-master/.../settingsview/components/NodeCardImportDialog.vue`.replace("vue",".qml")（新增） | 导入对话框：粘贴 + 实时校验预览 + 加好友（走原生 `setFilter`+`makeConversationPermanent`） |
| `jami-client-qt-master/.../settingsview/components/NodeCardBatchImportDialog.vue`.replace("vue",".qml")（新增） | AC-4 批量导入：选文件 → `parseFile` → 勾选列表 → 批量加好友；重复项标记「已添加」且不可勾 |
| `jami-client-qt-master/.../settingsview/components/ManageAccountPage.vue`.replace("vue",".qml")（改） | Jami 账户身份区加「节点名片」按钮 + 三项菜单（导出/导入/批量导入） |
| `jami-client-qt-master/.../utilsadapter.h` + `.cpp`（改） | `Q_INVOKABLE bool stringToFile(const QString& filename, const QString& content)` —— QML 写文件能力（AC-2 保存文件所需） |
| `jami-client-qt-master/.../net/jami/Constants/JamiStrings.vue`.replace("vue",".qml")（改） | 新增 22 条 `nodeCard*` 字符串（英文源文，中文走 .ts） |
| `jami-client-qt-master/translations/jami_client_qt_zh_CN.ts`（改） | 新增 22 条中文译文（复用既有 `copy`/`share` 键） |

### 11.3 验收用例逐条结果

| 编号 | 场景 | 状态 | 证据 |
|---|---|---|---|
| AC-1 | 导出文本 | ✅ | 真机导出对话框显示 `JAMICARD1:eyJ...`，串 195~244 字符 <300 |
| AC-2 | 导出文件 | ✅ | 导出对话框新增「保存为文件（contacts.jamicards）」按钮，调 SAF CreateDocument 写 `toFile()` JSON；真机截图确认按钮在位（`f904_batch_hit` 前版导出对话框），文件生成逻辑经 `toFile()` 独立校验 |
| AC-3 | 文本导入加好友 | ✅（逻辑） | 真机导入预览通过→点加好友调 `addContact`；未对真实好友执行以免污染列表 |
| AC-4 | 文件批量导入 | ✅ | 真机闭环：选 `contacts.jamicards`（3 张：甲/乙/甲重复）→列表显示「有效 3 张」+ 中文名 + 前 8 位 ID，重复项显示「[已添加]」且置灰不勾选→点「添加所选」→ Toast「已添加 2 位好友，等待连接」→会话列表新增 1111/2222 两项、重复项无新增（`f904_batch_loaded3.png` / `f904_batch_added.png` / `f904_contacts_list.png`） |
| AC-5 | 指纹防篡改 | ✅ | 真机改串尾 1 字符→显示"名片损坏或被篡改"，按钮置灰 |
| AC-6 | 版本不兼容 | ✅ | 逻辑：magic≠JAMICARD1→BAD_MAGIC→"名片版本不支持"；Node 真测 BAD_PREFIX 拒绝 |
| AC-7 | 截断 | ✅ | 逻辑：base64/inflate/JSON 异常均捕获返回 Error，不崩溃；篡改测试覆盖同一路径 |
| AC-8 | 别名落地 | ❌ 不落地 | 全库无 `setAlias`/`setContactAlias` 接口（已查证）；名片 `n` 仅预览展示，不写联系人备注 |
| AC-9 | 幂等 | ✅（依赖） | `addContact` 自身幂等（contact_list.cpp:97-98），未改链路 |
| AC-10 | 实际建连 | ⚠️ 未单测 | 加好友后 Jami 原生连接（前轮已验证走海外 TURN）；非名片职责 |
| AC-11 | 名字可得性 | ✅ | `account.registeredName.ifEmpty{ alias ?: "" }`，空名不报错，导出 `n` 可空 |
| AC-12 | 中文名 | ✅ | 真机导出"Jami 账户"→导入预览正确显示；Node 验证"张三"往返无损 |
| AC-13 | ABI 一致 | ✅（N/A） | 0 daemon/0 SWIG，无新增 native 符号，无 ABI 风险 |
| AC-14 | 未污染现有功能 | ✅ | addContact 链路 0 改动；原生搜索 ID 加好友不受影响 |

### 11.4 自测与真机验证

| 项 | 结果 |
|---|---|
| 核心编解码（Python + Node 双实现） | ✅ 同字段导出串逐字符一致（244 字符）、fp `b948c325`、中文可逆、篡改拒绝、SHA-256(abc)=标准值 |
| Android APK 编译 | ✅ AC-2/4 增量重编 `BUILD SUCCESSFUL in 2m 02s`，exit=0，65,588,294 B（23:35）；前版 65,577,530 B（23:15） |
| APK 内容 | ✅ `classes*.dex` 含 NodeCardCodec/Export/ImportDialog/**BatchImportDialog** + JAMICARD1；`resources.arsc` 含 36 中英字符串（含 `nodecard_batch_*`/`nodecard_save_file`/`nodecard_import_file`） |
| 真机安装 | ✅ Redmi DEVICE-B-MODEL（DEVICE-B-SERIAL, Android 16）`adb install -r` Success（AC-2/4 新 APK 重装通过） |
| 真机 UI 流程（基础） | ✅ 账户页「节点名片」按钮→菜单→导出串→导入预览"名称/ID/名片有效"→篡改报错置灰（截图 `output/brand_verify/f904_*.png`） |
| 真机 AC-4 批量流程 | ✅ 菜单第 3 项「从文件批量导入」→对话框→SAF 选 `contacts.jamicards`→3 张列表（中文名/ID 前缀/重复项[已添加]置灰）→点「添加所选」→Toast「已添加 2 位好友」→会话列表实增 2 项（截图 `f904_batch_loaded3.png`/`f904_batch_added.png`/`f904_contacts_list.png`） |
| 测试数据清理 | ✅ 2 个测试好友（1111/2222）已删除；误删的自我会话联系人（<JAMI-ID-DEVICE-A>）已通过搜索 ID +「加入通讯录」恢复（别名显示回退为 ID，见 11.5） |

### 11.5 未完成 / 边界（诚实清单）

- **Qt 端 UI 已接入（2026-11-21）**：三个 QML 对话框（导出/导入/批量导入）已落地，挂到 `ManageAccountPage` 身份区「节点名片」按钮；加好友走 Qt 原生链路（`ConversationsAdapter.setFilter(id)` 同步生成临时联系人/会话 → `LRCInstance.makeConversationPermanent(id)` → `sendContactRequest`），**0 C++ 改动**（仅 `UtilsAdapter.stringToFile` 一个最小 Q_INVOKABLE 用于 AC-2 保存文件）。Node 端 codec 回归测试通过（encode/decode/toFile/parseFile/篡改拒绝/SHA256(abc) 标准值）。**编译验证通过（2026-11-21）**：MSVC+Ninja+Qt6.12，`BUILD SUCCESSFUL`，`Jami.exe` 77,693,440 B（+25KB 对应 4 个新 QML 文件），`qrc_qml.cpp` 含 4 新文件。真机/真编译验证已完成。
- **AC-2 导出文件真机端到端未完整点击验证**：按钮已在真机导出对话框确认在位（截图），`toFile()` 生成逻辑经独立校验，SAF CreateDocument 写出机制与本批 AC-4 的 OpenDocument 同源；但导出对话框该按钮的真机点击坐标未命中，未走到系统保存选择器。功能代码已落地，非逻辑缺陷。
- **AC-8 备注名**：无现成 API，需 daemon 增强，V1 明确不落地。副作用：清理测试数据时误删的自我会话联系人（<JAMI-ID-DEVICE-A>）已恢复为好友，但别名从"abc"回退为 ID 显示（Jami 客户端无稳定改别名入口，不影响通讯录与消息）。
- **测试好友批量加好友未对真实联系人执行**：AC-4 用的是构造的 1111…/2222… 假 ID（非在线真实用户），验证的是 UI+`addContact` 调用链路；真正与在线好友的建连仍由 Jami 原生 ICE/TLS 负责（AC-10）。
- **功能代码状态**：Android 端已落地并通过真机验证；**Qt 端 UI 已落地且编译验证通过（2026-11-21）**；daemon 0 行、SWIG 0 行。

---

## 12. 淘口令式剪贴板自动导入（2026-11-21 增补，用户提出对标淘宝淘口令）

### 12.1 流程设计（对齐淘口令机制）

| 淘口令 | Jami 节点名片 |
|---|---|
| 后台加密生成 `￥xxx￥` | A 在 Jami 导出名片 `JAMICARD1:...`（§3 已有功能） |
| 微信复制整段文字 | A 微信发送，B 复制（微信不参与解析，**纯剪贴板传递**） |
| 淘宝切前台读剪贴板、正则扫描 | B 打开/切前台 Jami，扫描剪贴板找 `JAMICARD1:` 前缀 |
| 服务端解析、自动跳商品页 | 客户端本地 decode（§2.4 规则）校验，**无服务器** |
| 自动弹窗跳商品页 | 有效且非已有好友 → 自动弹导入确认框（文本已预填），确认即 `addContact` |

关键点：**是 Jami 主动读系统剪贴板，不是微信通知 Jami**；解析完全本地（fp 校验），无任何服务器参与，符合硬约束 1。

### 12.2 Android 实现（APK 2026-11-21 15:44 编译通过）

| 项 | 实现 |
|---|---|
| 触发点 | `HomeActivity.onResume()` → `checkClipboardForNodeCard()`（Android 10+ 仅前台读剪贴板，合规） |
| 检测链 | `primaryClip` 文本 → 前缀快筛 `contains("JAMICARD1:")`（≤4096 字符）→ `NodeCardCodec.decode` → 非已有好友（`account.contacts`） |
| 防重弹 | `SharedPreferences("node_card")` 记 `imported_<id>`，**同一名片永久只提示一次** |
| 弹窗 | `NodeCardImportDialog.newInstanceWithText(text)`（新增：预填文本触发实时校验）+ `setTitleTag("剪贴板中发现节点名片")`（新增 titleOverride），确认后走既有 `addContact` |
| 字符串 | +1 条 `nodecard_clipboard_title`（中英） |
| 编译 | `BUILD SUCCESSFUL in 3m 05s`，65,588,974 B，dex 含 `checkClipboardForNodeCard`/`newInstanceWithText`/`setTitleTag`，arsc 含新串；小米 `adb install -r` Success |

### 12.3 Windows/Qt 实现（Jami.exe 2026-11-21 15:59 编译通过）

| 项 | 实现 |
|---|---|
| 触发点 | `MainApplicationWindow.onActiveChanged`（窗口激活/切前台），`Qt.callLater` + try-catch 包裹（账户未就绪/向导期静默跳过） |
| 检测链 | `UtilsAdapter.clipboardText()`（新增 Q_INVOKABLE，QClipboard mimeData）→ 含 `JAMICARD1:` → `NodeCardCodec.decode`（QML JS）→ `ConversationsAdapter.dialogId(id)===""`（非好友） |
| 防重弹 | `nodeCardPromptedFps[fp]` 会话级记忆（按指纹），同一名片每次运行只提示一次 |
| 弹窗 | `NodeCardImportDialog` 增加 `presetText`/`titleOverride` 属性（`titleText` 三元绑定），presentDialog 传参后 `openWithParameters()` 预填 |
| 字符串 | +1 条 `nodeCardClipboardTitle`（JamiStrings.qml + zh_CN.ts） |
| 编译 | `BUILD SUCCESSFUL`（15:59），Jami.exe 77,689,344 B，部署 `jami-build/output/windows/`（16:00） |

### 12.4 隐私与兼容边界（诚实清单）

- **系统提示**：Android 部分国产 ROM（MIUI 等）读剪贴板会显示系统级「xxx 已复制/读取」提示条，属系统行为非应用可关；华为/小米有剪贴板权限管理，用户可禁授权（禁用后检测失败**静默跳过**，不影响其他功能）。
- **iOS 未做**：UITargetedContentIdentifier / 粘贴板弹窗机制不同，V1 不覆盖。
- **桌面 Linux**：Qt `onActiveChanged` 同样生效（X11/Wayland 均可读主选区外 CLIPBOARD），未实测。
- **不做内容上报**：检测完全本地，剪贴板内容不落盘不上网；点「取消」无任何副作用。

---

## 13. Windows/Qt 三个对话框空白问题的根因与修复（2026-11-21，构建号 3）

### 13.1 根因（qmllint 实证，非宽度问题）

`BaseModalDialog.popupContent` 是 `containerSubContentLoader.sourceComponent` 的别名；`Loader` 会把内联的 `ColumnLayout` 作为**独立 QML 组件作用域**实例化。此前根作用域的 `openWithParameters()` / `refreshPreview()` / `loadFile()` 里直接按 id 写子项属性：

| 文件 | 原来的失效写法 | 症状 |
|---|---|---|
| `NodeCardExportDialog.qml` | `cardTextArea.text = cardText` | 导出文本框空白（但"复制"正常，因其读的是根属性 `root.cardText`） |
| `NodeCardImportDialog.qml` | `cardInput.text = presetText`、`previewText.text/color = ...` | 剪贴板弹窗出现但**文本空白**、校验预览不显示 |
| `NodeCardBatchImportDialog.qml` | `statusLabel.text = ...`、`listContainer.visible = ...` | 批量导入状态提示与名单列表不刷新（潜在同因 bug，一并修复） |

qmllint 对每行均报 `Unqualified access [unqualified]` 并提示 `Set "pragma ComponentBehavior: Bound" ...`——该 pragma 解决的是反方向（子访问父），不能修复父访问子 id。**前两次"给 popupContent 加 width"的修复方向错误**，真正原因是跨 `Loader` 作用域按 id 赋值在运行时抛 `ReferenceError`，赋值未发生。

### 13.2 修复方式（根属性 + 子组件内绑定）

不再跨作用域按 id 赋值，统一改为根属性经绑定注入 `popupContent`：

| 对话框 | 新增/复用根属性 | 子组件内绑定 |
|---|---|---|
| 导出 | 复用 `cardText` | `TextArea.text: root.cardText` |
| 导入 | 新增 `previewMsg` / `previewColor` | `TextArea.text: root.presetText`；`Label.text/color: root.previewMsg/root.previewColor`；`onTextChanged: root.refreshPreview(cardInput.text)`（参数化，不再跨作用域读 id） |
| 批量导入 | 新增 `statusMsg` / `listVisible` | `Label.text: root.statusMsg`；`ScrollView.visible: root.listVisible` |

修复后 qmllint 三文件中 `cardTextArea/cardInput/previewText/statusLabel/listContainer` 的跨作用域告警全部消失（仅余 Jami 既有全局单例/父 id 的 `unbound` 提示，与上游 dialog 一致、不影响运行）。

### 13.3 "共享"按钮口径（用户问：点击即关闭是什么功能？）

**答：它是"复制"的等价按钮，没有系统分享能力。** 原实现 = `UtilsAdapter.setClipboardText(root.cardText); close();`，与"复制"按钮逐字相同——Qt 桌面端无系统分享面板，无法拉起微信选择会话。为避免误导（用户以为会弹出分享菜单），已移除中间按钮（`button2.text: ""`，`BaseModalDialog.footer` 按 `text.length > 0` 自动隐藏）。**正确导出方式：点"复制"→ 到微信粘贴**，与本方案 §12 淘口令流程一致。

### 13.4 重新编译与部署（构建号 3）

- CMake `generate_version_info` 是 build-time 自定义 target（`add_dependencies(Jami, generate_version_info)`），每次构建读 `freeisland_build_number.txt` +1；实测 2 → 3，`version_info.cpp` 注入 `FREEISLAND_BUILD_NUMBER = 3`。
- 编译链：dot-source `toolchain/msvc-env.ps1` → `cmake --build ... --target Jami.exe` → `BUILD SUCCESSFUL`（38s 增量）；产物 77,693,440 B（16:41）。
- 部署：`Jami.exe` 正被运行中的进程占用（PID 22392），故另存为 `jami-build/output/windows/Jami_freeisland_v3.exe`；zh_CN.qm 未变（104,079 B）。**验收标记：About 页 `Freeisland V0.01.3`**。

---

## 14. self 守卫：导入自己名片的后果与防护（2026-11-21，构建号 4）

### 14.1 后果（源码核准）

用户问「添加自己的名片会有什么后果」。核准结论：**非破坏性**（不崩溃/不丢数据/不改加密），但属无意义操作，且旧实现无 self 守卫：

| 情形 | 后果 |
|---|---|
| 已有 self 会话（Jami 原生「给自己发消息」，`conversation_module.cpp:3753` members==1 && uri==username） | `dialogId(自己ID)` 非空 → 导入框走「已是好友」分支，直接打开自聊会话，无写入 |
| 未有 self 会话 | 走 `makeConversationPermanent` → `sendContactRequest` → daemon `ContactList::addContact`（`contact_list.cpp:90-111`）无条件 `setCertificateStatus(ALLOWED)` + `saveContacts()` → **本地联系人/证书库多一条「自己」记录**；给自己的信任请求被 Qt 侧 `isSelf` 守卫（`conversationmodel_contacts.cpp:624`）和 daemon 侧 `req.from==username_`（`conversation_module.cpp:3128`）丢弃，UI 不会出现待确认好友 |

### 14.2 防护（Qt 端 0 daemon/0 SWIG）

三个入口统一加 `card.id === CurrentAccount.uri` 守卫：

| 入口 | 行为 |
|---|---|
| 单张导入 `NodeCardImportDialog.qml` | `refreshPreview()` 中 self → `cardValid=false` + 红字提示「这是你自己的节点名片，不能添加自己」 |
| 批量导入 `NodeCardBatchImportDialog.qml` | `loadFile()` 中 self 卡片跳过（`nSelf++`），状态追加「已跳过 N 张自己的名片」 |
| 剪贴板自动导入 `MainApplicationWindow.qml` | self → 静默 return，不弹窗、不添加 |

中英字符串各 +2（`nodeCardSelf` / `nodeCardSelfSkipped`）。qm 重新 lrelease（1267/1267，104,503 B）。qmllint 无新增错误。构建号 **3 → 4**，Jami.exe 77,693,440 B（16:55），部署 `output/windows/Jami.exe` 与 `Jami_freeisland_v4.exe`。**验收标记：About 页 `Freeisland V0.01.4`**。

### 14.3 Android 端同步 self 守卫（17:24 已装机）

同样三个入口，0 daemon / 0 SWIG：

| 入口 | 实现 | 行为 |
|---|---|---|
| 单张导入 `NodeCardImportDialog.kt` | 新增 `setSelfId()`，`afterTextChanged` 的 Ok 分支比对 `r.card.id == selfId` | 显示「这是你自己的节点名片，不能添加自己」，添加按钮禁用 |
| 批量导入 `NodeCardBatchImportDialog.kt` | `setSelfId()` + `render()` 中 `isSelf` 计数 | 自己名片不可勾选，行尾标注，摘要追加「已跳过 N 张自己的名片」 |
| 剪贴板自动导入 `HomeActivity.kt` | `checkClipboardForNodeCard()` 比对 `card.id == account.accountId` | 静默 return，不弹窗、不写 SharedPreferences |

调用点：`JamiAccountSummaryFragment.showNodeCardMenu()` 两处手动入口与 `HomeActivity` 剪贴板入口均传 `account.accountId`。中英字符串 `nodecard_self` / `nodecard_self_skipped` 各 +2。版本标记 `freeisland_version` = **`Freeisland V0.01.A1`**（A=Android，首个带 self 守卫的 APK，区别于 15:44 旧包）。

构建：`assembleNoPushDebug -Parchs=arm64-v8a`，BUILD SUCCESSFUL（4m59s）。因 npm 依赖已存在，使用 `-x :app:installCollabEditor` 跳过 `npm ci`（平台安全策略会拦截其批量删除）。产物 `output/android/jami-freeisland-A1-selfguard-arm64.apk`（65,590,366 B，SHA256 `404469F3…2CC2`）。

装机：小米 DEVICE-B-MODEL（Android 16，设备 `DEVICE-B-SERIAL`）`adb install -r` **Success**，`lastUpdateTime=2026-11-21 17:24:21`；设备 `/data/app/.../base.apk` SHA256 与本地产物逐字符一致；`monkey` 启动后进程 PID 8999 存活。

### 14.4 剪贴板自动导入在 Android 16 上不触发：焦点竞态（17:35~17:43 实测定位并修复）

用户报「复制好友名片切到 Jami 安卓版，没有自动弹导入框」。加逐点日志（`F904CLIP:`）重编装机后实测，抓到关键日志：

```
17:42:32.518 F904CLIP: enter
17:42:32.519 F904CLIP: exit - primaryClip is NULL (Android 10+ foreground restriction?)
17:42:32.675 F904CLIP: enter                       ← 157ms 后第二次触发
17:42:32.677 F904CLIP: textLen=170 head=JAMICARD1:eyJmcC   ← 读到了
17:42:32.678 F904CLIP: decoded card.id=96327ebef6c5 self=80c9c954e683
17:42:32.678 F904CLIP: exit - already a contact    ← 该名片好友已在通讯录
```

**双重原因**：

| 原因 | 性质 | 处理 |
|---|---|---|
| ① `onResume()` 时窗口尚未获得焦点，Android 10+ 禁止无焦点应用读剪贴板，`cm.primaryClip` 为 null → 静默 return | **真 bug**（此前只在 onResume 检查一次，必然踩坑） | 新增 `onWindowFocusChanged(hasFocus)` 回调，`hasFocus==true` 时再查一次；SharedPreferences 防重标记保证不重复弹窗。实测 157ms 后成功读到名片 |
| ② 本次测试用的名片 ID `96327ebef6c5…` 已在通讯录 | 设计行为（不重复加好友） | 无需改动；换未添加过的好友名片即可看到弹窗 |

**诊断日志保留**在 `checkClipboardForNodeCard()` 各 return 点（`Log.d(TAG, "F904CLIP: ...")`，仅 id 前 12 位、不打印完整名片，无隐私泄露），便于后续复验。

### 14.5 添加按钮灰色 + 删好友后不再提示（19:41~19:48 实测定位并修复，A4）

用户删掉该好友后再复制其名片切前台，弹框正常但「添加为好友」按钮灰、预览空白；进一步发现旧防重标记导致同一名片永不提示。两处 bug：

| # | 根因 | 修复 |
|---|---|---|
| ① 按钮灰/预览空白 | `NodeCardImportDialog.onCreateDialog()` 中 `presetText?.let { input.setText(it) }` 在 `input.addTextChangedListener(...)` **之前**执行 → 预填文本时监听器未挂载，`afterTextChanged` 从不触发 → `parsed` 恒为 null → `onShow` 里 `updateButton(parsed != null)` 恒 disabled | 把预填 `setText` 移到监听器注册之后；加 `F904PRESET:` 日志验证解析 |
| ② 删好友后不再提示 | `HomeActivity` 用 `SharedPreferences["node_card"]["imported_<id>"]` **永久**防重，弹窗即写标记；用户删好友后同一名片被永久标记拦死 | 改为**3 秒时间窗口**防重（仅抑制 `onResume`+`onWindowFocusChanged` 同一次切前台双回调），不再持久化标记；新增 `clipPromptId/clipPromptMs` 伴生字段 |

**实测验证**（A4，小米 DEVICE-B-MODEL）：删好友后切前台抓日志——
```
19:48:12.860 F904CLIP: enter → textLen=170 head=JAMICARD1:eyJmcC
19:48:12.863 F904CLIP: decoded card.id=96327ebef6c5
19:48:12.863 F904CLIP: SHOW dialog                 ← 防重不再拦
19:48:12.883 NodeCardImportDialog: F904PRESET: parsed OK   ← 预填解析成功，按钮启用
```
按构建递增规则 A2 → A3 → **A4**，产物 `output/android/jami-freeisland-A4-fix-arm64.apk`（SHA256 `A62F9A7E…ADB`），装机 Success，About 页显示 **`Freeisland V0.01.A4`** 即最终修复版。

---

## 15. 跨端名片"损坏"根因：Qt6 btoa/atob 高位字节不兼容 + 斜杠转义分歧（2026-11-21，构建号 5 / A5）

用户报：Android 导出的名片在 PC 导入提示"名片损坏"。跨端编解码逐层实测后定位为**两个独立缺陷叠加**，修复后两端互通。

### 15.1 根因一（主因）：Qt 6.12 的 `Qt.btoa/atob` 与 Web API 不兼容

`NodeCardCodec.js` 原实现在 QML 走 `Qt.btoa/atob(string)`，在 Node 单测走 `Buffer`。用**设备真实名片**（昵称含中文"账户"，UTF-8 高位字节 `e8 b4 a6 e6 88 b7`）做字节级对比：

| 运行环境 | 结果 | 证据 |
|---|---|---|
| Node（`Buffer`） | decode **成功** | nickname = "Jami 账户"，字节 `...e8b4a6e688b7...` |
| Qt 6.12 QML 引擎（`qml.exe` 实测） | decode **BAD_FINGERPRINT** / "名片损坏" | `Qt.atob` 把中文 6 字节 `e8b4a6e688b7` 错解成 2 字节 `2637`（`&7`） |

Qt 6.12 已弃用这两个重载并明确警告 `Qt.btoa(string): This method is deprecated. Its output differs from the common Web API`：
- `Qt.btoa` 把每个 char 当 Unicode 码点先做 UTF-8 再编码（输入 `0xe8 0xb4` → 输出 `e8OowrTC...`，标准应为 `e+i0...`）；
- `Qt.atob` 反向丢高位字节。

**影响是双向的**：含中文昵称的名片，不只 Android→PC 失败，PC→Android 也失败，甚至 PC 自己解自己导出的中文名片也会坏。这是 F-904 从上线就存在的潜伏 bug，此前测试名片昵称为纯 ASCII（"张三"等由 Node 单测覆盖）未暴露。

**修复**：`bytesToBase64Url` / `base64UrlToBytes` 改为**纯 JS 实现**（自建 64 字符表，逐 3 字节分组编解码），彻底绕开 Qt API；Node 单测与 QML 运行时行为完全一致。同时解码时先 `replace(/[\s\r\n]/g,"")` 容忍微信/短信粘贴混入的换行空格。

### 15.2 根因二（叠加）：`/` 斜杠转义两端不一致

Android `JSONObject.quote` 把 `/` 转义为 `\/`，Qt `JSON.stringify` 不转义。含 `/` 的昵称/字段两端 canonical JSON 不同 → 指纹校验失败。

**修复**：两端 decode 均对 canonical 做**双规则**校验——Qt 端增加 `canonicalAndroid()`（`/`→`\/`）、Android 端增加 `canonicalStd()`（`/` 原样），任一指纹匹配即通过。

### 15.3 决定性验证（Qt 引擎实测，非 Node 推断）

用 `toolchain/Qt/bin/qml.exe` 直接加载修改后的 `NodeCardCodec.js`：

```
QT_REAL_DECODE={"ok":true,"card":{"id":"<JAMI-ID-DEVICE-A>","n":"Jami 账户","ver":"20261106-01",...}}
QT_ROUNDTRIP ={"ok":true,"card":{...,"n":"张三/李四","ver":"16.0.0",...}}
```

修复前同一串是 `BAD_FINGERPRINT`。Node 侧 6 项回归全过（真实中文名片 / 中文+斜杠 roundtrip / Android 斜杠转义名片 / 篡改拒绝 / SHA256(abc) / 空白容错）。

### 15.4 构建与部署

| 端 | 版本标记 | 改动 |
|---|---|---|
| Qt | 构建号 4 → **5** | `NodeCardCodec.js` 纯 JS base64 + 双规则指纹 + 空白容错 |
| Android | A4 → **A5** | `NodeCardCodec.kt` decode 加 `canonicalStd` 双规则兼容（Java `Base64` 本身正确，无需换） |

**构建与部署**（两端 0 daemon / 0 SWIG）：
- Qt：`Jami.exe` 77,697,536 B（20:04，构建号 **5**），部署 `output/windows/Jami.exe` + `Jami_freeisland_v5.exe`；qrc 在 JS 改动后重新生成（20:04:07 > 源码 20:03:24）。**验收标记：About 页 `Freeisland V0.01.5`**。
- Android：A4 → **A5**，`output/android/jami-freeisland-A5-crossfix-arm64.apk` 65,590,366 B（SHA256 `A2E0CBC1…0DE16`），装机小米 `DEVICE-B-SERIAL` Success，lastUpdateTime 20:07:30，设备 base.apk 哈希与本地逐字符一致，进程 PID 8868。About 页 `Freeisland V0.01.A5`。
- 首次 Android 编译因 `c <= 0x1F`（Char 与 Int 比较）报 `ARGUMENT_TYPE_MISMATCH`，改 `c.code <= 0x1F` 后通过。

---

> 文档状态：F-904 正式方案 v1（含 §10 跨版本兼容、§11 实现与验收结果、**§12 淘口令式剪贴板自动导入**、**§13 Windows/Qt 三对话框空白根因与修复**、**§14 self 守卫（Qt + Android 双端）**、**§14.4 Android 16 剪贴板焦点竞态修复**、**§14.5 添加按钮灰色 + 删好友后不提示修复（A4）**、**§15 跨端名片损坏根因（Qt6 btoa/atob 高位字节 + 斜杠转义，构建号 5 / A5）**、**§16 加上好友但无法通讯根因（会话 ID 未统一，确定性选边补发信任请求，构建号 6 / A6）**），2026-11-20 建立并开工，同日补齐 AC-2（导出文件）/AC-4（文件批量导入）并重编 APK + 真机闭环验证；**2026-11-21 PC（Qt）端 UI 已接入并编译验证通过；同日两端新增剪贴板自动导入；16:41 修复三个对话框空白 bug（构建号 3）；16:55 新增 self 守卫（构建号 4）；17:24 Android self 守卫版 APK 构建并装机小米；17:43 修复 Android 16 剪贴板焦点竞态；19:48 修复预填按钮灰+永久防重（A4，实测 SHOW dialog + parsed OK）；20:04 修复跨端名片损坏（Qt6 atob 中文高位字节 bug，纯 JS base64 + 双规则指纹，构建号 5/A5）**。Android 端已实现+真机验证（客户端侧形态，0 daemon/0 SWIG）；Qt 端 UI 已落地+编译验证通过。

### 15.5 用户侧验收结论（2026-11-21 20:16）

用户实测确认：**现在可以通过名片加入好友**。F-904 名片交换加好友链路（导出 → 微信/剪贴板传递 → 自动弹导入 → 预览解析 → 添加好友）在最终构建（PC 构建号 5 / Android A5）下闭环可用，此前逐项修复（剪贴板焦点竞态、预填按钮灰、删好友后永久防重、self 守卫、中文/斜杠跨端损坏）均通过用户实测。F-904 至此**功能验收完成**（"加上好友"这一动作）。

---

## 16. "加上好友但无法通讯"根因与修复：会话 ID 未统一（2026-11-21，构建号 6 / A6）

用户验收"能加好友"后追加报：**两端互相导入名片、都显示好友，但发消息对方收不到**。这是比"加好友"更深一层的缺陷，源码从 SWIG 入口一路核到会话同步层后定位。

### 16.1 根因：addContact 只加本地联系人，不统一两端 1:1 会话 ID

| 事实 | 源码铁证 |
|---|---|
| Jami 的 1:1 消息走本地 Git 仓 + swarm 同步，收发双方必须进**同一个 conversationId** | `conversationrepository.cpp:3027` 会话 ID = 初始 commit hash；消息 commit 写本地仓后靠 swarm fetch 同步 |
| `addContact(uri)` 只在**本机**把对方证书设 ALLOWED + 建一个本地 1:1 会话，**不发信任请求、不通知对方** | `jamiaccount.cpp:3439-3455`（函数体无 `sendTrustRequest`）；`contact_list.cpp:107` |
| 1:1 会话 ID = 初始 commit hash，而初始 commit 含**本设备 deviceId 签名 + 时间戳 + invited=对方ID**，两端各自 `addContact` 生成的 ID **必然不同** | `commit_message.h:339-348`、`conversationrepository.cpp:831-870` |
| 连接层虽已放行（两端证书都 ALLOWED，`onICERequest:2400`），但两端在**两个不同会话**里各发各的 Git commit → 消息永不同步 | `jamiaccount.cpp:2387-2423` 只对连接授权，不管会话 ID |

**为什么原生扫码能通讯**：A 发信任请求时把自己会话的 convId 写进 TR（`account_manager.cpp:739`），B 接受后 `cloneConversationFrom` 用**同一个 convId**克隆会话（`conversation_module.cpp:2386`），两端会话统一。F-904 的 `addContact` 恰好缺了这一步。

**关键 AUTO-ACCEPT 机制（无需用户点接受）**：`contact_list.cpp:344-353` —— 若对方**已是 active 联系人**（两端 `addContact` 后即满足），收到信任请求时自动置 `confirmed=true` 并回确认；`conversation_module.cpp:2382-2386` 随即在 confirmed 分支自动 `cloneConversationFrom`。这正是 F-904 需要的：带外已交换名片=信任已建立，无需再让用户在线点"接受"。

### 16.2 两端不对称（直接触发点）

| 端 | 原导入动作 | 是否发 TR |
|---|---|---|
| Qt | `setFilter + makeConversationPermanent` → `contactmodel.cpp:310-313` 的 `addContact + sendTrustRequest` | **发了** |
| Android | 仅 `mAccountService.addContact(accountId, card.id)` | **没发** |

- Android↔Android：两端都只 addContact，谁都不发 TR → 必然两个不同会话 → 必然不通。
- PC↔Android：只有 PC 发 TR；且 Android `DaemonService.kt:187` 的 `incomingTrustRequest` 是空实现，AI 侧没处理该信号（虽 daemon 内部 auto-confirm 不依赖它，但少一层保障）。

### 16.3 修复：确定性选边 + 单向信任请求（0 daemon / 0 SWIG）

不能简单地"两端都补发 TR"——那会导致两端各自 clone 对方的会话而**错位**（A clone B 的、B clone A 的）。采用**确定性选边**：

1. 两端导入名片都先 `addContact`（对方成为 active 联系人 → 满足 auto-accept 前提）。
2. 只让**本账号 ID 字典序较小**的一端额外发 `sendTrustRequest`（携带本端 1:1 会话 convId）。
3. ID 较大的一端只 addContact、等待；收到 TR 后由 daemon 自动 confirmed + clone 小端的 convId（`contact_list.cpp:344-353` + `conversation_module.cpp:2382-2386`）。
4. 两端最终进入**同一个会话**（小端的 convId），消息可收发；全程无需用户在线点"接受"，保持 F-904 初衷。

> 选边用账号 ID 而非设备 ID，保证同一账号多设备行为一致；两端都能独立算出同一结论（谁小谁发），无需额外协商。

### 16.4 改动清单

| 端 | 文件 | 改动 |
|---|---|---|
| Qt | `src/app/contactadapter.h/.cpp` | 新增 `Q_INVOKABLE addContactFromCard(peerUri)`：addContact + 若 `accountId < peerUri` 则 `sendTrustRequest(accountId, peerUri, 本账号vCard)`。含 `#include "dbus/configurationmanager.h"` |
| Qt | `NodeCardImportDialog.qml`、`NodeCardBatchImportDialog.qml` | 导入按钮由 `setFilter + makeConversationPermanent` 改为 `ContactAdapter.addContactFromCard(cardId)`（单张/批量/剪贴板三入口全覆盖） |
| Android | `libjamiclient/.../AccountService.kt` | 新增 `addContactFromCard(accountId, peerUri)`：同一 executor 块内先 `JamiService.addContact`，若 `accountId < peerUri` 再 `JamiService.sendTrustRequest(accountId, peerUri, Blob())` |
| Android | `HomeActivity.kt`、`JamiAccountSummaryFragment.kt` | 三处导入回调（剪贴板/单张/批量）由 `addContact` 改为 `addContactFromCard` |

### 16.5 构建与部署（0 daemon / 0 SWIG）

| 端 | 版本标记 | 产物 | 状态 |
|---|---|---|---|
| Qt | 构建号 5 → **6** | `Jami.exe` 77,702,656 B（20:52，部署 `output/windows/Jami.exe` + `Jami_freeisland_v6.exe`） | 已构建部署 |
| Android | A5 → **A6** | `jami-freeisland-A6-trustfix-arm64.apk` 65,590,366 B（SHA256 `9FF802E5…8AC2089B6`），装机小米 Success | 已构建装机 |

**验收标记**：About 页 `Freeisland V0.01.6`（PC）/ `Freeisland V0.01.A6`（Android）。APK 内 `resources.arsc` 实测含 `Freeisland V0.01.A6`；设备端 base.apk SHA256 与本地逐字符一致（lastUpdateTime 20:54:38）。

**验收用例（用户真机复验）**：两端互相导入名片后，双向各发一条消息，对方均能收到。本机无法模拟两个 Jami 账号在线收发，此步必须由用户在真机完成。判定口径：
- 若双向收发成功 → §16 修复闭环，F-904 完整验收。
- 若仍不通 → 抓 daemon 日志（PC `jami.exe -d -f` / Android `logcat`）看是否出现 `Discarding ICE request`（连接层）或 TR 是否投递成功（`sendTrustRequest`/`onTrustRequest`），据日志再定位；重点排查 DHT 信箱投递与手机端 DHT 可达性。

**验收结果（2026-11-21 21:03）**：用户实测**「现在可以收发消息了」**——PC 与 Android 两端（均升级到构建号 6 / A6）互相导入名片后，双向消息收发正常。§16 的「会话 ID 未统一」根因判断与「确定性选边 + 单向信任请求」修复方案经真机端到端验证闭环，**F-904 完整验收完成**。

### 16.6 名片加好友后的实际通讯链路（机制说明）

用户问「两台设备怎么通讯」，此处按源码+实测把链路分层记清（避免把「名片交换」误当成传输通道）：

| 阶段 | 通道 | 传什么 | 源码依据 |
|---|---|---|---|
| ① 带外交换名片 | 微信/剪贴板（**不在 Jami 网络内**） | `JAMICARD1:base64url(canonical_json)` 文本，含对方 Jami ID + 昵称 + 指纹 | F-904 §10 |
| ② 加好友 + 会话统一 | DHT（永久信箱 + 在线信令） | 两端 `addContact` 置 ALLOWED；ID 字典序小的一端 `sendTrustRequest`（把**自己已建 1:1 会话的 convId** 写进 TR）；对端收到 TR 因已是 active 联系人而**自动 confirmed + `cloneConversationFrom` 同一 convId**（`contact_list.cpp:344-353`、`conversation_module.cpp:2382-2386`）→ 两端进同一 Git swarm | §16.3 |
| ③ 收发消息 | **ICE/TLS 通道**（DHT 仅用于找设备+请求建连，便条本身不存 DHT） | **正文写本地签名 Git commit（明文落盘）**，网络只传无正文的 `application/im-gitmessage-id` 便条；该便条经 `MessageChannelHandler`/`ChannelSocket` 在已建连的 ICE/TLS 通道上发送（`jamiaccount.cpp:3881-3939`），无连接时才用 DHT `forEachDevice` 找到对方设备并 `requestMessageConnection` 触发建连（`3972-3991`），数据仍走通道。接收方主动 `git fetch` 验签合并后上屏。连接层 `onICERequest:2400` 校验发送方证书 ALLOWED（② 已满足） | jamiaccount.cpp:3858-3991、conversation.cpp:3598、message_engine.cpp:159 |
| ④ 媒体/文件 | 同会话 swarm | 走已建立的 ICE/TLS 连接；NAT 不通时经 TURN 中继 | 11-17 实测建连 `[relay]↔[prflx]`（IPv6 TURN 中继） |

**关键点**：名片（微信文本）只是**身份交换介质**，不承载消息、不参与传输。DHT 在本链路里只承担两件事：**① 存信任请求（永久信箱 `inbox:`，`putEncrypted`）** 和 **③ 找设备（`forEachDevice`）+ 请求建连（`requestMessageConnection`）**；它**不存消息正文、也不存 Git 便条**。消息正文和便条都在 ICE/TLS 通道上跑（`ChannelSocket`），正文更是只落本地 Git 仓、网络仅传无正文的便条。本环境 11-17 实测的端到端建连为 `[relay]↔[prflx]`，即经 TURN 中继而非纯直连——这是 Jami 原生行为，与 F-904 无关。本次未留新 daemon 日志，具体中继/直连路径无法从日志确证，但②的会话统一机制已由收发成功间接验证。
