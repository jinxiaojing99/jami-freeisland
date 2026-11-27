# Jami 功能详细设计文档（伪代码级 · 可扩展基线）

**源码基线**：`jami-src/jami-daemon-master`（本地快照，2026-11-09 核对）；客户端 `jami-client-qt-master` / `jami-client-android-master`；官方 ADR `doc/adr/ADR-000 ~ ADR-011`（全部 Accepted）。
**文档定位**：这不是调研报告，也不是官方 ADR 的翻译。它是一份**逐功能的实现规格 + 与源码行号绑定的伪代码**，用途是：你在本文里追加一条新功能条目，我按条目定位文件、修改代码、自测、回填状态。
**证据等级**：文中所有 `文件:行号` 与符号名均来自本次对本地源码的实读（含并行源码调研 + 关键项抽查复核）。行号随源码版本漂移时，**以符号名为准、行号为辅**。凡未能在源码中证实的，一律写 `NOT_FOUND`，不做推测。

---

## 0. 协作契约：你加功能，我改代码

### 0.1 工作流（每次扩展都按这个闭环跑）

| 步骤 | 谁做 | 输入 | 输出 | 硬约束 |
|---|---|---|---|---|
| 1 | 你 | 在「第 6 章 待实现功能区」按模板新增一条 `F-9xx` 条目 | 一条结构化需求（功能说明、触发入口、期望行为、伪代码、验收） | 字段尽量填满；没想清楚的写 `待定`，我会先问 |
| 2 | 我 | 读该条目 + 第 5 章改动映射矩阵 | 一份改动清单：要动哪些文件、是否触碰 wire format / ADR、能在哪条链验证 | **若与 Accepted ADR 冲突，先明确告知你，不擅自动手** |
| 3 | 我 | 改动清单经你确认（或属于「bug 修复方案明确」类可直接执行） | 修改源码，保留 `.original` / `.bak` 备份 | 不改无关文件；不擅自改 `doc/adr/` 里已 Accepted 的决策 |
| 4 | 我 | 编译 + 运行验证 | 测试输出、日志、截图等证据 | 未验证不报完成 |
| 5 | 我 | 把该条目状态改为 `已实现 / 已验证`，附证据路径 | 更新的本文档 + 编译记录追加一节 | 状态与证据必须一一对应 |

### 0.2 条目状态机

`待定` → `待实现` → `实现中` → `已实现（未验证）` → `已验证`。已存在的 F-01~F-17 是**现状规格**，状态标 `基线（已实现）`；新增条目从 `待实现` 起。

### 0.3 我会先停下来问你的情况

- 需求要改 `CommitType` / commit JSON 字段 / msgpack 通道结构 / channel scheme —— 这是**协议变更**，旧版本节点会拒绝（ADR-001:30-31），需要先定兼容策略。
- 需求与任一 Accepted ADR 的决策直接冲突（官方 `doc/agent` 与 `.agents/skills/adr/SKILL.md` 明确：Accepted ADR 是约束）。
- 需求要改 libjami 已有 API 的签名或已有信号的 `cb_type`（会破坏 ABI，且三套绑定都要同步）。
- 条目里没有验收条件，或验收条件与当前可验证边界冲突（见 0.4）。

### 0.4 当前可验证边界（诚实清单，决定「改完能不能自测」；含 2026-11-12 订正）

> **【2026-11-12 订正】下表是 2026-11-09 的历史快照，其中「Windows 10 完整 MSVC GUI 客户端」一行已作废。** 当时按「系统安装版」口径检查（`vswhere.exe` / VS 安装目录 / `C:/Qt` / `C:/vcpkg` / 系统 PATH 里的 `cl.exe`）得出「未通过」，但本工程实际用的是**免提权 portable 绿色工具链**：官方组件二进制包直接解压在 `toolchain/`，不装 VS IDE、不写注册表、不进系统 PATH，只在编译会话里由 `toolchain/msvc-env.ps1` 注入 `INCLUDE / LIB / PATH` 等变量，因此常规检测口径必然查不到。11-11 15:08 首版 GUI 已产出，11-12 11:13 部署 SSDP 崩溃修复版。**原表格与原文结论保留作历史，正确口径见下方复核表与订正后边界。**

| 平台链路 | 当前状态（2026-11-09，历史快照） | 改 daemon 代码后能验证什么 | 不能验证什么 |
|---|---|---|---|
| Android x86_64（雷电 9 / LDPlayer） | **全链路打通并实机验证**：x86_64 原生 APK 62,539,140 B，创建账户成功、无 SIGSEGV | 编译 `libjami-core-jni.so`、打 APK、装到雷电、走 UI 流程、抓 logcat | 真机 ARM、推送（当前是 noPush 变体） |
| Android arm64-v8a | 已产出 APK（59,409,789 B），雷电 Houdini 路径曾崩，未做真机对照 | 编译与打包 | 真机实测未做 |
| Linux daemon | 已验证完成 | 编译、单测、`test/agent` 场景 | — |
| Windows MinGW（GNU ABI） | `jamid-minimal.exe`、`libjami-core.a` 已产出，`--smoke` 通过 | 编译与 smoke | GUI、MSVC ABI 链接 |
| Windows 10 完整 MSVC GUI 客户端 | **未通过**：`jami.exe` / `jami-core.lib` 未产出，`jami-client-qt` 未 configure，Qt6WebEngine 未编（**此行已作废，见下方订正**） | 暂无 | 改完 daemon 后无法在 Windows GUI 上端到端验证 |

**按 2026-11-12 实测口径复核**（仅订正 Windows GUI 一行；其余各行是 11-09 状态，本轮未复核，不作改动）

| 检查项 | 11-09 表内结论 | 2026-11-12 实测 | 依据 |
|---|---|---|---|
| MSVC 编译器 | 未找到 `cl.exe` | **有**：MSVC v143 `14.44.35207`（`toolchain/msvc-kit/`，portable）；系统另装有 VS2022 BuildTools | `toolchain/msvc-env.ps1` 注入 `INCLUDE/LIB/PATH`；编译记录 §13.56 |
| Windows SDK | 不存在 | **有** `10.0.26100.0`（官方要求 20348） | `toolchain/msvc-kit/.../Windows Kits/10/`，`ucrt.lib` / `um` 齐全 |
| Qt 6.12 | 不存在 | **有** `6.12.0`，`QMAKE_SPEC=win32-msvc`；WebEngine 用 `-DWITH_WEBENGINE=OFF` 绕过 | `toolchain/Qt/`；编译记录 §13.52 / §13.56 |
| vcpkg / Rust | 均不存在 | **有** `toolchain/vcpkg-jami`（153 个 `.lib`）、`C:\Users\jin_x\.cargo\bin`（rustup） | 同上 |
| `jami.exe` / `jami-core.lib` | 未产出 | **已产出并部署**：修复版 11-12 11:13，77,668,352 B；`dumpbin /dependents` 依赖 `MSVCP140/VCRUNTIME140(_1)/Qt6Core.dll`，纯 MSVC ABI，全目录无 `libgcc/libstdc++/libwinpthread` | `jami-build/output/windows/jami.exe`；编译记录 §13.57 |
| 运行时崩溃 | 未通过 | **已修复并验证**：libupnp SSDP 符号扩展指针 `0xffffffffc4c291b0` 由 `JAMI_HARDEN_SSDP_STR` 守卫拦截；单元自测 5/5 PASS、45 s headless 冒烟 PASS；真实桌面实例（11:38 及 11:50 重启）稳定存活、无新增 `jami.exe.*.dmp`（最新仍是 11-11 21:43） | 编译记录 §13.55 / §13.57 |
| 界面多语言 | 未涉及 | **已补齐** 121 个 `.qm`（含简中 `zh_CN`），部署到 `output/windows/share/jami/translations/`；重启后按系统 `zh-CN` 自动加载 | 编译记录 §13.58 |

> **【11-09 原文结论，已由下方订正取代】** 因此：改 daemon 的 C++ 逻辑，我会用 Docker 里的 Android x86_64 / Linux 链做编译与运行验证；涉及 Qt/Android UI 的改动，需要先说明只能静态检查或等待 Windows MSVC 链打通。

**订正后的可验证边界（2026-11-12）**：改 daemon 的 C++ 逻辑，仍可用 Docker 里的 Android x86_64 / Linux 链做编译与运行验证；**此外 Windows MSVC GUI 链也已打通** —— 可在本机经 `build-jami-msvc.ps1` → `run_build.py`（看门狗，必需）重编 daemon + GUI 并重链 `jami.exe`，随后用 `toolchain/dl/selftest_libupnp.c` 做单元自测、`verify_gui_launch.py` 做 headless 冒烟、真实桌面实例做稳定性观察。仍须如实保留的边界：① headless 冒烟（`offscreen` + `QT_QUICK_BACKEND=software`）只证明二进制加载与 Qt/QML/daemon 初始化，不等于真实桌面全交互路径；② 涉及 Qt QML / 界面呈现的改动，端到端确认仍需用户在正常桌面会话实测；③ `WITH_WEBENGINE=OFF` 使 WebEngine 相关功能不在本次覆盖范围内；④ libupnp 指针守卫是启发式，只拦符号扩展／低地址／内核地址类垃圾指针，不能拦任意无效指针。

---

## 1. 现有资料盘点：有没有你要的详细设计文档

**结论：没有。** 现有资料分三类，都不是「每个功能的说明 + 详细方案 + 伪代码」：

| 资料 | 类型 | 覆盖范围 | 伪代码级 | 能否直接据以改代码 |
|---|---|---|---|---|
| `doc/adr/ADR-000 ~ 011`（官方，12 份） | 架构决策记录（Context / Decision / Consequences，部分含 Historical evidence） | 只记「重大架构决策及理由」，如会话用签名 Git 仓库、通道多路复用、msgpack 消息通道 | 否 | 否，只能当硬约束 |
| `doc/README`、`BUILD.md`、`INSTALL.md`、`doc/doxygen/*`、`doc/trace/*`、`doc/agent/*` | 构建 / API 文档生成配置 / tracepoint / Guile 测试 agent 说明 | 工具与流程，不描述业务功能实现 | 否 | 否 |
| `src/jami/*_interface.h` + `src/client/*.cpp` | 头文件即对外 API 契约；实现是薄转发 | API 签名与信号名齐全 | 否 | 只能定位入口 |
| 本项目已有 5 份报告：`P2P聊天软件调研报告.md`、`I2P与Jami在中国使用问题专题.md`、`Jami源码与需求差距分析.md`、`Jami系统整体方案.md`、`Jami三平台编译记录.md` | 调研 / 差距分析 / 系统级原理 / 构建日志 | 《系统整体方案》有步骤与时序，但属**系统级原理说明**，不绑定源码行号、不含逐功能实现方案与失败分支 | 《系统整体方案》有 6 处流程描述，非伪代码 | 否 |
| **本文** | 逐功能实现规格 + 伪代码 + 代码落点 + 扩展契约 | F-01~F-17 覆盖 daemon 主要业务域 | **是** | **是** |

补充：官方 `.agents/skills/adr/SKILL.md` 规定 ADR 只写「有长期架构影响」的决策，明确**不写** bug 修复、局部实现细节、重构、实验——所以官方仓库在可预见的将来也不会产出这份文档，只能自建并随代码维护。

---

## 2. 系统分层与代码落点（新增功能先查这张表）

| 层 | 目录 | 职责 | 代表文件 | 新增功能时通常要动 | 协议影响 |
|---|---|---|---|---|---|
| 对外 API 契约 | `src/jami/` | libjami C++ API 与 Signal 结构定义 | `conversation_interface.h`、`configurationmanager_interface.h`、`callmanager_interface.h`、`datatransfer_interface.h`、`jami.h` | 加 API / 加 Signal / 扩 `SwarmMessage` | 纯新增不改签名则 ABI 安全；改已有签名 = 破坏 ABI |
| 接口实现（薄转发） | `src/client/` | API → Manager / Account 转发；信号注册表 | `conversation_interface.cpp`、`configurationmanager.cpp`、`callmanager.cpp`、`datatransfer.cpp`、`jami_signal.cpp` | 加转发分支；**新信号必须登记** `jami_signal.cpp` | 无 |
| 全局编排 | `src/` 根 | Manager 单例、账户工厂、配置、通话/会议/传输 | `manager.cpp`、`account_factory.cpp`、`conference.cpp`、`data_transfer.cpp`、`jami.cpp` | 跨账户/跨模块逻辑 | 视情况 |
| 业务核心 | `src/jamidht/` | Jami 账户、会话、Git 仓库、名字服务、联系人、协作文档、通道 | `jamiaccount.cpp`、`conversation_module.cpp`、`conversation.cpp`、`conversationrepository.cpp`、`commit_message.*`、`namedirectory.cpp`、`contact_list.cpp` | **绝大多数消息类功能的落点** | 改 commit type = 协议变更（ADR-001） |
| 通话与媒体 | `src/sip/`、`src/media/` | SIP/SDP/ICE(pjproject)、编解码、RTP | `sipcall.cpp`、`sdp.cpp`、`sipvoiplink.cpp` | 媒体类功能 | 改 SDP/ICE 触碰 ADR-006/011 |
| 连通性适配 | `src/connectivity/`、`src/jamidht/*channel_handler*` | IP/TLS 工具 + 命名通道接入 | `message_channel_handler.cpp`、`channeled_transport.cpp`、`gittransport.cpp` | 新 channel scheme | 触碰 ADR-002/008 |
| 插件扩展 | `src/plugin/` | C ABI 插件、Chat/Media/Preference/WebView Handler | `jamiplugin.h`、`chathandler.h`、`chatservicesmanager.cpp`、`pluginmanager.cpp` | **不改协议的功能优先走这里** | 无（本地 ABI） |
| 绑定生成 | `bin/jni/`、`bin/dbus/`、`bin/nodejs/` | SWIG / DBus / Node 接口 | `jni_interface.i`、`conversation.i`、`*.xml`、`dbus*manager.hpp` | 新增 API 或信号时**三套都要同步** | 无 |
| 三端客户端 | 另外两个仓库 | Qt QML / Android Kotlin UI | 不在 daemon 仓库 | 客户端 UI 改动需单独核实 | 无 |

**API → 实现的最短路径（所有功能通用）**：
`src/jami/<域>_interface.h` → `src/client/<域>.cpp`（薄转发）→ `Manager` 或 `JamiAccount` → `src/jamidht/<子系统>.cpp` → 事件经 `emitSignal<...>()` → `jami_signal.cpp` 注册表 → 绑定层 → 客户端。
**新增 Signal 漏登记 `jami_signal.cpp`，运行时 `handlers.at(Ts::name)` 抛异常**（`jami_signal.h:70`），这是最常见的低级故障。

---

## 3. 架构硬约束：Accepted ADR 一览（改功能前必查）

| ADR | 决策（官方原文要点） | 状态 | 对新增功能的约束 | 什么改动会触发 |
|---|---|---|---|---|
| 000 | 采用 ADR 记录架构决策 | Accepted | 新决策要按 `.agents/skills/adr/SKILL.md` 模板写、更新 INDEX | 任何长期架构影响 |
| 001 | 会话 = libgit2 管理的**签名 Git 仓库**；ID = 初始 commit hash；消息是带 `type` 的 commit；每个 commit 由设备签名；拉取先校验再合并 | Accepted | **改 commit types / 仓库布局 / 校验规则 = 协议变更，旧节点会拒绝**；历史不可改写、不可删除 | 新消息类型、新目录、新成员规则 |
| 002 | 设备对之间**一条连接多路复用**；命名通道 `sip` / `git://` / `data-transfer://` / `sync://` / `swarm://` / `msg:` / `auth:` / `ydoc://` | Accepted | 新业务流优先复用已有连接，不要新建每功能一条 TCP | 新增 channel scheme、改连接生命周期 |
| 003 | 可插拔音频处理（WebRTC 为默认） | Accepted | 音频前处理走该接口，不要与 `src/plugin/` 的 JAMI 插件框架混淆（两者不是一回事） | 改音频处理链 |
| 004 | Swarm 群呼 = 某个成员作为 **rendezvous 主机** 的会议 | Accepted | 群组通话改动要维持 host 语义与活跃通话 commit | 改群呼/会议拓扑 |
| 005 | 弃用 DHT 即时消息，消息统一走 swarm commit；DHT 只做发现/信令 | Accepted | 不要把新消息塞回 DHT `inbox:`；通知/输入状态/回执走 p2p 通道 | 改消息投递路径 |
| 006 | P2P 连通性（ICE/TLS/TURN/UPnP）统一放在 **dhtnet**（外部仓库） | Accepted | 连通性改动先落 dhtnet，再经 contrib bump 进 daemon；本仓库 `src/connectivity/` 里没有 ICE 实现 | 改 ICE/SDP/TURN |
| 007 | 每个 swarm 用 SwarmManager + 分布式路由表做成员设备可达性 | Accepted | 新同步逻辑要复用路由表，不要自行广播全网 | 改同步/多跳传播 |
| 008 | 对等协议消息走 `msg:` 通道 + **msgpack** `Message{id,t,c,req}` | Accepted | 改 `Message` 结构 = wire format 变更；且 msg 通道与 SIP MESSAGE 回退**两条路径必须等价** | 改 `message_channel_handler.h` |
| 009 | 新设备通过 `jami-auth://` + `auth:` 通道的认证 P2P 链路绑定 | Accepted | `deviceAuthStateChanged` / `addDeviceStateChanged` 是客户端契约；两设备须同时在线；`exportOnRing` 已移除 | 改设备绑定流程 |
| 010 | 协作文档 = Y-CRDT（yffi/libyrs）over `DOCUMENT` 模式 swarm 仓库，checkpoint commit 持久化 | Accepted | daemon 只传输 CRDT update，**不解释内容**；实时走 `ydoc://` | 改协同文档 |
| 011 | 网络切换时保持通话（call handover / ICE restart） | Accepted | 改通话状态机要保留恢复路径（`sipcall.cpp:500/789/866`） | 改呼叫状态机 |

---

## 4. 功能详细规格（F-01 ~ F-17）

### 4.0 阅读约定

每节固定七块：**功能说明 / 入口与信号 / 源码落点 / 关键数据结构 / 伪代码 / 不变量与失败分支 / 扩展点与验收**。伪代码是**可读的控制流规格**，不是可编译代码；其中函数名、分支条件、返回值均对应真实源码，注释里的 `:数字` 是该分支所在行号。

---

### F-01 账户创建与密码学身份 · `基线（已实现）`

**功能说明**：创建一个 Jami 账户 = 生成一套三层证书体系（自签 CA → 账户证书 → 设备证书）+ 归档文件 + 账户配置，并把账户注册到 DHT。没有服务器账号，账户 ID 由证书公钥派生。

| 入口 / 信号 | 声明位置 | 实现入口 | 说明 |
|---|---|---|---|
| `addAccount(details, accountId = {})` | `src/jami/configurationmanager_interface.h:51` | `src/client/configurationmanager.cpp:678` | 单行转发 `Manager::addAccount` |
| `getAccountTemplate(type)` | `configurationmanager_interface.h:50` | `configurationmanager.cpp:667` | `RING`→`JamiAccountConfig().toMap()`；`SIP`→`SipAccountConfig` |
| `AccountsChanged` / `RegistrationStateChanged` / `ProfileReceived` | `configurationmanager_interface.h` 的 `ConfigurationSignal` | `manager.cpp:2818`、`jamiaccount.cpp:1488` | 异步结果靠信号，不靠返回值 |

| 源码落点 | 层 | 符号 | 作用 |
|---|---|---|---|
| `src/manager.cpp:2789` | 全局编排 | `Manager::addAccount` | 生成 ID、选类型、建账户、落盘、`doRegister` |
| `src/account_factory.cpp:29` | 工厂 | `DEFAULT_ACCOUNT_TYPE` | **默认 SIP，不是 Jami** |
| `src/account.h:125` | 账户基类 | `Account::setAccountDetails` | `buildConfig → fromMap → loadConfig → saveConfig` |
| `src/jamidht/jamiaccount.cpp:1530` | Jami 账户 | `JamiAccount::loadAccount` | 按 `managerUri` 选 Archive/Server AccountManager |
| `src/jamidht/archive_account_manager.cpp:226` | 身份 | `createAccount` | 生成 CA、账户证书、eth_key |
| `src/jamidht/archive_account_manager.cpp:1215` | 身份 | `onArchiveLoaded` | 签发设备证书、构造 ContactList、回调 |
| `src/jamidht/jamiaccount.cpp:1425` | 身份 | `onAuthenticationSuccess` | 写 `.key/.crt`、回写配置、注册、发信号 |

**关键数据结构**：`AccountArchive`（`src/jamidht/accountarchive.h:62`：`id / ca_key / eth_key / config / contacts / revoked / conversations`）；`dht::crypto::Identity`（私钥 + 证书）；配置键见 `src/jamidht/jamiaccount_config.cpp:55-73`（`nameServer / registeredName / archivePath / managerUri / receipt ...`）。

**伪代码**

```text
FUNCTION addAccount(details, accountId):                    # manager.cpp:2789
  id  = accountId.empty() ? getNewAccountId() : accountId    # :2792
  typ = details["Account.type"] OR DEFAULT_ACCOUNT_TYPE      # :2796-2800
  acc = accountFactory.createAccount(typ, id)                # :2804, account_factory.cpp:40
  IF acc == null: RETURN ""                                  # :2807（ID 已存在 / 类型未知）
  acc.setAccountDetails(details)                             # :2810 → account.h:125
      buildConfig(); config.fromMap(details); loadConfig(); saveConfig()
  saveConfig(acc)                                            # :2811
  acc.doRegister()                                           # :2812
  preferences.addAccount(id); emit AccountsChanged           # :2814-2818
  RETURN id                                                  # 注意：此时身份可能仍在 INITIALIZING

ASYNC loadAccount():                                         # jamiaccount.cpp:1530
  IF registrationState == INITIALIZING: RETURN
  manager = managerUri.empty() ? ArchiveAccountManager : ServerAccountManager  # :1553/:1567
  loadIdentity() → useIdentity()
  IF 身份有效: scheduleAccountReady(); RETURN                  # :1598
  IF managerUri 非空 AND 无密码: state = ERROR_NEED_MIGRATION; RETURN  # :1606
  state = INITIALIZING → buildAccountCredentials() → auth()

ASYNC auth() → createAccount(ctx):                           # archive_account_manager.cpp:226
  ca  = generateIdentity("Jami CA")                          # :229  自签 CA
  acc = generateIdentity("Jami", ca, 4096, true)             # :233  账户证书（CA 标志）
  IF 任一失败: THROW / 走错误状态
  eth_key = dev::KeyPair::create()                           # :242
  onArchiveLoaded(ctx, archive, isLinkDev=false)             # :244

ASYNC onArchiveLoaded():                                     # archive_account_manager.cpp:1215
  archive.save(archivePath, scheme, password)                # :1225/:1233
  REQUIRE archive.id.second->isCA()                          # :1238
  deviceCert = Certificate::generate(request, archive.id)    # :1268  由账户证书签发设备证书
  receipt = makeReceipt(...); sign by CA                      # :1273
  contacts = ContactList(...); foundAccountDevice(...)       # :1288-1292
  ctx.onSuccess(accountInfo)

ASYNC onAuthenticationSuccess(info):                         # jamiaccount.cpp:1425
  check_dir(idPath, 0700)
  saveIdentity(id, idPath, DEVICE_ID_PATH)                   # :1201 写 <name>.key/.crt，0600
  editConfig({archiveHasPassword, registeredName, deviceName, receipt, ...})   # :1437-1465
  state = UNREGISTERED → updateTrustedCa() → doRegister()    # :1476-1502
  runOnMainThread(emit ProfileReceived)                      # :1488 线程切换点
```

| 不变量 / 坑 | 源码依据 | 违反后果 |
|---|---|---|
| 必须显式传 `Account.type = RING`，否则建的是 SIP 账户 | `account_factory.cpp:29` | 完全没有 CA / 归档 / DHT |
| 证书链是三层，设备证书由账户证书签发 | `archive_account_manager.cpp:229/233/1269` | 对端 `useIdentity` 校验失败（`account_manager.cpp:204`） |
| Jami 账户配置写 `<accountPath>/config.yml`，**不是** `dring.yml` | `jamiaccount.cpp:996`、`manager.cpp:1786-1790` | 误改全局配置、升级丢设置 |
| `addAccount` 返回 ≠ 创建完成，身份在 `INITIALIZING`；`doRegister` 此时直接 return | `jamiaccount.cpp:1794-1796` | 客户端过早认为账户可用 |
| 回调多在 DHT/IO 线程，发信号需切主线程 | `jamiaccount.cpp:1488` | 线程安全问题 |

**扩展点与验收**：新增账户字段 → 改 `JamiAccountConfig` 的 `serialize/unserialize/fromMap`（`jamiaccount_config.cpp:37/80/174`）+ 归档结构（注意旧键兼容）；验收用雷电 x86_64 APK 走创建账户流程，检查 `config.yml` 字段与 `dring.yml` 非零、`REGISTERED` 日志。

---

### F-02 设备链接 / 撤销 / 备份归档 · `基线（已实现）`

**功能说明**：把已有账户的授权凭据经认证 P2P 通道转移到新设备（`jami-auth://` + `auth:`），或撤销某设备（CRL 上 DHT），或导出加密归档。受 ADR-009 约束。

| 入口 | 声明位置 | 实现入口 | 说明 |
|---|---|---|---|
| `addDevice(accountId, uri) -> int32` | `configurationmanager_interface.h:67` | `configurationmanager.cpp:333` | 返回 `op_id`，错误码见下 |
| `confirmAddDevice` / `cancelAddDevice` | `:68` / `:69` | `configurationmanager.cpp:343/352` | 状态机推进 |
| `exportToFile(id, path, scheme, pwd)` | `:71` | `configurationmanager.cpp:374` | 仅本地归档账户可用 |
| `revokeDevice(id, deviceId, scheme, pwd)` | `:75` | `configurationmanager.cpp:386` | 结果靠回调，**返回值不可信** |
| `provideAccountAuthentication` | `:63` | `configurationmanager.cpp:361` | 导入侧提供密码 |
| `AddDeviceStateChanged` / `DeviceRevocationEnded` / `deviceAuthStateChanged` | `ConfigurationSignal` | `archive_account_manager.cpp:965`、`jamiaccount.cpp:1193` | 客户端契约（ADR-009:20-21,32） |

| 源码落点 | 符号 | 作用 |
|---|---|---|
| `src/jamidht/jamiaccount.cpp:1093` | `JamiAccount::addDevice` | 校验 AUTH handler 已注册并转发 |
| `src/jamidht/archive_account_manager.cpp:891` | `ArchiveAccountManager::addDevice` | URI 校验、状态机、发起通道连接 |
| `src/jamidht/archive_account_manager.cpp:976` | `doAddDevice` | 归档传输与设备证书签发 |
| `src/jamidht/archive_account_manager.cpp:1474` | `revokeDevice` | 撤销 + CRL + DHT put + sync |
| `src/jamidht/archive_account_manager.cpp:1523` | `exportArchive` | 读归档 → 更新 → 写 → 复制到目标 |

**关键数据结构**：`AddDeviceError {INVALID_URI=-1, ALREADY_LINKING=-2, GENERIC=-3}`（`account_manager.h:151`）；`DeviceAuthState {INIT, TOKEN_AVAILABLE, CONNECTING, AUTHENTICATING, IN_PROGRESS, DONE}`（`archive_account_manager.h:28`）；`AUTH_URI_SCHEME="jami-auth://"`、`CHANNEL_SCHEME="auth:"`（`archive_account_manager.cpp:47-48`）；归档加密 scheme `""` / `"password"`（`fileutils.h:116-117`）。

**伪代码**

```text
FUNCTION addDevice(uri):                                     # archive_account_manager.cpp:891
  IF authCtx_ != null: RETURN ALREADY_LINKING(-2)            # :895
  IF NOT uri.starts_with("jami-auth://"): RETURN INVALID_URI(-1)  # :901
  s = uri.substr(scheme_len)
  IF s[64] != '/' OR len(code) != 6 OR !parseOperationId(code)
     OR !dht::PkId(peerTempAcc): RETURN INVALID_URI          # :906-923  硬编码格式
  token = random(); authCtx_ = make AuthContext
  connect(peerId, "auth:" + code, onConnect)                 # :958
  onConnect: IF socket == null → state DONE(NETWORK); reset authCtx_
             ELSE doAddDevice(...)  # :976 失败 → state UNKNOWN
  runOnMainThread(emit AddDeviceStateChanged(token, CONNECTING))  # :965 线程切换
  RETURN token

FUNCTION revokeDevice(device, scheme, password, cb):          # :1474  异步
  future = ThreadPool.computation().getShared<AccountArchive>(readArchive)
  findCertificate(DeviceId(device), LAMBDA(crt):
    IF !crt: cb(ERROR_NETWORK); RETURN                       # :1484
    IF future.get() throws: cb(ERROR_CREDENTIALS); RETURN    # :1495
    contacts.foundAccountDevice(crt)
    archive.revoked.revoke(crt); archive.id.first.sign(...)  # :1499-1503
    pinRevocationList(); loadRevocations()
    dht.put(h, crlValue, priority=1, permanent=true)         # :1509 必须在 DHT 上生效
    saveArchive(); contacts.removeAccountDevice(device)
    cb(SUCCESS); syncDevices()                               # :1517
  )
  RETURN false                                               # :1519 固定返回，勿用来判成败
```

| 不变量 / 坑 | 源码依据 | 违反后果 |
|---|---|---|
| `AAM::revokeDevice` 固定 `return false`，真实结果在回调 / `DeviceRevocationEnded` | `archive_account_manager.cpp:1519`、`jamiaccount.cpp:1193` | 误判撤销失败 |
| `exportArchive` 对 ServerAccountManager（JAMS）账户直接 false | `jamiaccount.cpp:1128-1131` | 备份功能对托管账户不可用 |
| 撤销是复合动作：CRL 签名 → pin/load → DHT put → save → sync | `archive_account_manager.cpp:1499-1517` | 漏一步则被撤销设备仍能连入 |
| 两设备必须同时在线；`exportOnRing` 已移除 | ADR-009 | 离线迁移不可用 |

**扩展点与验收**：改绑定流程会触碰 ADR-009，先走第 0.3 节。验收需两实例（雷电 + 真机/第二雷电实例）走 `addDevice` 状态机与撤销后重连。

---

### F-03 用户名注册与查找（Name Server）· `基线（已实现）`

**功能说明**：把 40 字符 Jami ID 映射为易记用户名。默认 `https://ns.jami.net`（可自建），查询与注册走 HTTPS，结果带公钥签名校验；本地有缓存。

| 入口 | 声明位置 | 实现入口 | 说明 |
|---|---|---|---|
| `lookupName` / `lookupAddress` | `configurationmanager_interface.h:86/87` | `configurationmanager.cpp:1272/1296` | `account` 为空时**绕过账户**直连 NameDirectory |
| `registerName(account, name, scheme, pwd)` | `:88` | `configurationmanager.cpp:1328` | 成功后写 `config.registeredName` |
| `searchUser` | `:92` | `configurationmanager.cpp:1319` | 搜索 |
| `RegisteredNameFound` / `NameRegistrationEnded` / `VolatileDetailsChanged` | `ConfigurationSignal` | `jamiaccount.cpp:1682/1734` | 结果一律靠信号 |

| 源码落点 | 符号 | 作用 |
|---|---|---|
| `src/jamidht/namedirectory.cpp:86` | `lookupUri` | 解析 `user@ns` / 裸名，选默认 ns |
| `src/jamidht/namedirectory.cpp:266` | `lookupName` | GET `/name/<urlencoded>`、验签、写缓存 |
| `src/jamidht/namedirectory.cpp:188` | `lookupAddress` | GET `/addr/<id>`、4xx 二次查缓存 |
| `src/jamidht/namedirectory.cpp:355` | `registerName` | POST `{addr,owner,signature,publickey}` |
| `src/jamidht/jamiaccount.cpp:1713` | `JamiAccount::registerName` | 成功回写配置并发信号 |

**关键数据结构**：`Response {found=0, invalidResponse, notFound, error}`、`RegistrationResponse {success, invalidName, invalidCredentials, alreadyTaken, error, incompleteRequest, signatureVerificationFailed, unsupported}`（`namedirectory.h:54-64`）；常量 `DEFAULT_SERVER_HOST="https://ns.jami.net"`、`SAVE_INTERVAL=5s`、`MAX_RESPONSE_SIZE=1MB`（`namedirectory.cpp:49-69`）。

**伪代码**

```text
FUNCTION lookupName(name, cb):                               # namedirectory.cpp:266
  IF addrCache_.has(name): cb(addr, name, found); RETURN     # :268 同步回调（注意！）
  GET serverUrl + "/name/" + urlEncode(name)
  on_done(response):
    CASE status:
      400        → cb(..., invalidResponse)                 # :286
      4xx (其余) → cb(..., notFound)                        # :282
      非 2xx     → cb(..., error)
      2xx        → parse JSON {name, addr, publickey, signature}
                   IF addr.empty(): notFound
                   IF pk AND (pk.getId().toString() != addr OR !verify(name, pk, sig)):
                       invalidResponse                       # :307-317  公钥绑定 + 签名校验
                   addrCache_/nameCache_ 写入 + scheduleCacheSave()   # 5s 延迟落盘
                   cb(..., found)
  IF send() throws: cb(..., error)                           # :348 「callers must always get a reply」

FUNCTION lookupAddress(addr, cb):                            # :188
  IF nameCache_.has(addr): cb(...found); RETURN
  GET "/addr/" + addr
  on_done:
    IF 400 → invalidResponse
    IF 4xx(其余) → 再查一次缓存 → found / notFound          # :203-208  与 lookupName 不对称
    IF 2xx → json["name"] 空 ? notFound : 双写缓存 + found

FUNCTION registerName(account, name, scheme, pwd):            # jamiaccount.cpp:1713
  accountManager.registerName(name, password, LAMBDA(res):
    res = min(res, error)                                     # :1723
    IF success: setRegisteredName(); editConfig(registeredName); emit VolatileDetailsChanged
    emit NameRegistrationEnded(accountId, res, name)         # :1734 无论成败
  )
```

| 不变量 / 坑 | 源码依据 | 违反后果 |
|---|---|---|
| 缓存命中是**同步回调**，未命中是异步 HTTP | `namedirectory.cpp:190/268` | 调用方按纯异步写会死锁/竞态 |
| `send()` 抛异常也必须回调（源码注释 "callers must always get a reply"） | `namedirectory.cpp:254/348` | UI 永久转圈 |
| `lookupName` 校验 `publickey` + `signature`；`registerName` 有 `toLower` 与 `pendingRegistrations_` 去重 | `:307-317` / `:364-379` | 名字劫持、重复注册 |
| `account.empty()` 是独立路径，不经过 JamiAccount | `configurationmanager.cpp:1274-1287` | 误以为一定要登录才能查名 |

**扩展点与验收**：换自建 nameserver 只改配置键 `Nameserver::URI`（`jamiaccount_config.cpp:126`），不动代码。验收：雷电 APK 注册唯一用户名、反查地址、断网后查缓存命中。

---

### F-04 联系人与信任请求 · `基线（已实现）`

**功能说明**：维护联系人、黑名单、收到的信任请求，并在多设备间同步。数据用 **msgpack** 落盘（不是 YAML），且必须兼容旧版键名。

| 入口 | 声明位置 | 实现入口 | 说明 |
|---|---|---|---|
| `addContact` / `removeContact` / `getContacts` | `configurationmanager_interface.h:239/240/243` | `configurationmanager.cpp:597/604/619` | `getAccount<JamiAccount>` 后转发 |
| `sendTrustRequest` / `acceptTrustRequest` / `discardTrustRequest` | `:233/231/232` | `configurationmanager.cpp:660/644/652` | 带 payload 与 conversationId |
| `IncomingTrustRequest` | `:446` | `contact_list.cpp:406` | 信号载荷键见 `TrustRequest::{FROM,RECEIVED,CONVERSATIONID,PAYLOAD}` |

| 源码落点 | 符号 | 作用 |
|---|---|---|
| `src/jamidht/contact_list.cpp:90` | `addContact` | 幂等判断 + TrustStore 置 ALLOWED + 落盘 |
| `src/jamidht/contact_list.cpp:326` | `onTrustRequest` | 时间戳比较、重放、回调分流 |
| `src/jamidht/contact_list.cpp:431` | `acceptTrustRequest` | erase 请求 → `addContact(confirmed=true, convId)` |
| `src/jamidht/contact_list.cpp:625` | `getSyncData` | 构造 `DeviceSync`，>20 条随机采样 |
| `src/jamidht/jamiaccount.cpp:3440` | `JamiAccount::addContact` | 账户层转发 |

**关键数据结构**：`Contact{added, removed, confirmed, banned, conversationId}`、`TrustRequest{device, conversationId, received, payload, invited}`、`KnownDevice{certificate, name, last_sync}`（`src/jamidht/jami_contact.h:57/235/444`）；`ContactList::OnChangeCallback`（`contact_list.h:47-55`）；文件 `<path>/contacts`、`<path>/incomingTrustRequests`。

**伪代码**

```text
FUNCTION addContact(h, confirmed, convId):                   # contact_list.cpp:90
  LOCK mutex_
  c = contacts_[h] or emplace empty
  IF c.isActive() AND c.confirmed == confirmed AND c.conversationId == convId:
      RETURN false                                           # :94-98 幂等
  c.added = nowMs(); c.removed = {}; c.confirmed |= confirmed; c.conversationId = convId
  trust_.setCertificateStatus(h.toString(), ALLOWED)         # :107
  saveContacts()                                             # :108 msgpack
  UNLOCK                                                     # :109 关键：先解锁
  callbacks_.contactAdded(h, confirmed)                      # :110 回调在锁外，防重入死锁

FUNCTION onTrustRequest(from, convId, payload, received, confirm):  # :326
  LOCK
  IF contacts_.has(from):
     IF banned: RETURN false                                  # :341
     IF active: IF !confirm → accept = true; IF !confirmed → saved + contactAdded  # :344-354
  ELSE:
     IF !trustRequests_.has(from): emplace TrustRequest(...)
     ELSE IF new(received|invited) > existing: 覆盖           # :356-377 只接受更新的时间戳
            ELSE: 记日志忽略
     saveTrustRequests()
  UNLOCK
  IF !confirm: callbacks_.trustRequest(...)                   # :381
  IF confirm AND active: callbacks_.onConfirmation(...)
  RETURN accept

FUNCTION getSyncData():                                      # :625
  ds = DeviceSync{date=now, peers=getContacts(), trust_requests, devices}
  IF trust_requests.size() <= 20: 全量
  ELSE: 从 lower_bound(InfoHash::getRandom()) 起随机取 20 条   # :642-656
  ds.trust_requests[].payload = {}                            # :640 不同步 payload
  RETURN ds
```

| 不变量 / 坑 | 源码依据 | 违反后果 |
|---|---|---|
| `TrustRequest` 手写 msgpack 同时输出 `received`/`receivedMs`/`invitedMs`；`KnownDevice` 兼容旧数组 `[name,lastSyncSeconds]` 与新 map/millis | `jami_contact.h:79-94/243-265/361-365` | **加字段不保留旧键 = 升级丢数据、降级解析失败** |
| 回调必须在解锁后触发 | `contact_list.cpp:109-110/380` | 重入死锁 |
| `loadTrustRequests` 会对每条重放 `onTrustRequest(..., confirm=false)` | `contact_list.cpp:315-322` | 误判为「新请求」重复弹窗 |
| `MAX_TRUST_REQUESTS=20`，超出随机采样 | `contact_list.cpp:632` | 同步不完整是设计，不是 bug |

**扩展点与验收**：给 `TrustRequest` 加字段 = 存储格式变更，需双写兼容并考虑 ADR-008（消息通道）；验收用双设备互发信任请求 + 重启后重放。

---

### F-05 配置持久化（dring.yml / config.yml）· `基线（已实现）`

**功能说明**：全局配置写 `dring.yml`，每个 Jami 账户写自己目录下的 `config.yml`；序列化只写与默认值不同的字段，读取用 `parseValueOptional` 容错。

| 入口 | 声明位置 | 实现入口 | 说明 |
|---|---|---|---|
| `getAccountDetails` / `setAccountDetails` | `configurationmanager_interface.h:45/47` | `configurationmanager.cpp:220` → `manager.cpp:2743` | `setAccountDetails` 先 `doUnregister()` 再改配置 |
| `getVolatileAccountDetails` | `:46` | `configurationmanager.cpp` | 运行时状态，不落盘 |
| `getAccountList` | `:95` | `configurationmanager.cpp` | 账户枚举 |

| 源码落点 | 符号 | 作用 |
|---|---|---|
| `src/config/serializable.h:26` | `Serializable` | `serialize(YAML::Emitter&) / unserialize(YAML::Node)` 抽象 |
| `src/config/yamlparser.h:31/38` | `parseValue<T>` / `parseValueOptional<T>` | 后者 catch 异常返回 false（容错读取） |
| `src/manager.cpp:554` | `retrieveConfigPath` | `get_config_dir()/dring.yml`（注释 TODO 迁到 jami.yml） |
| `src/manager.cpp:1767` | `Manager::saveConfig()` | 写全局 + preferences；Jami 账户仅在 `config.yml` 不存在时补写 |
| `src/jamidht/jamiaccount.cpp:993` | `JamiAccount::saveConfig()` | 文件锁 + `config().serialize(out)` |
| `src/jamidht/jamiaccount_config.cpp:37/80/174` | `serialize` / `unserialize` / `fromMap` | Jami 专属字段读写 |

**关键数据结构**：继承链 `AccountConfig → SipAccountBaseConfig → JamiAccountConfig`；`serializeDiff(out, DEFAULT_CONFIG)` 只写差异（`account_config.cpp:59`）；`getFileLock()` 保护写。

**伪代码**

```text
FUNCTION saveConfig():                                       # manager.cpp:1767
  out << BeginMap << "accounts" << BeginSeq
  FOR acc IN accountFactory.getAllAccounts():
    IF JamiAccount:
       IF NOT exists(acc.getPath()/"config.yml"): acc.saveConfig()   # :1786-1790
       # 注意：Jami 账户详情【不】写进 dring.yml
    ELSE: acc.config().serialize(out)
  out << EndSeq
  preferences.verifyAccountOrder(getAccountList())
  preferences/voipPreferences/audioPreference/videoPreferences/pluginPreferences .serialize(out)
  LOCK getFileLock(path_) → ofstream → write                # :1809-1811

FUNCTION JamiAccount::saveConfig():                           # jamiaccount.cpp:993
  path = config().path / "config.yml"
  lock = getFileLock(path)
  ofstream fout(path); YAML::Emitter out(fout)
  config().serialize(out)                                    # :1000 只写与默认不同的字段
  ON YAML::Exception / runtime_error: 仅记日志，不抛出         # :1002

FUNCTION setAccountDetails(id, details):                     # manager.cpp:2743 / account.h:125
  doUnregister(id)
  acc.setAccountDetails(details):
      IF !config_: config_ = buildConfig()
      config_->fromMap(details)      # jamiaccount_config.cpp:174，MANAGER_URI 非 http 自动补 https://
      loadConfig()                  # jamiaccount.cpp:1008 → loadAccount() → 可能触发身份重建
      saveConfig()
```

| 不变量 / 坑 | 源码依据 | 违反后果 |
|---|---|---|
| `dring.yml` 里没有 Jami 账户详情，别画错数据流 | `manager.cpp:1786-1790` | 改错文件、字段丢失 |
| `unserialize` 用 `parseValueOptional` 容错，新增字段必须给默认值 | `jamiaccount_config.cpp:80` | 旧配置读取中断 |
| `setAccountDetails` 会**同时触发 loadConfig + 身份加载 + 落盘** | `account.h:125-133` | 以为是纯赋值，结果触发重注册 |
| 写配置要拿文件锁 | `jamiaccount.cpp:997`、`manager.cpp:1809` | 多进程/线程写坏 YAML |

**扩展点与验收**：新增持久字段 = 改 `SERIALIZE_CONFIG` 宏区块（`jamiaccount_config.cpp:55-73`）+ `fromMap` + `unserialize` 三处，缺一不可；验收用雷电 APK 改设置 → 杀进程重启 → 读回一致，并检查 `dring.yml` / `config.yml` 实际内容。

---

### F-06 会话（Swarm）创建与加入 · `基线（已实现）`

**功能说明**：会话是一个 libgit2 仓库，会话 ID = 初始 commit 的 hash。创建者写 `admins/`、`devices/`、`CRLs/` 等初始文件并签名初始 commit；加入者从成员设备 clone 全量历史，用 `validClone()` 逐 commit 校验，失败就回滚删除仓库。受 ADR-001 / 007 约束。

| 入口 / 信号 | 声明位置 | 实现入口 | 说明 |
|---|---|---|---|
| `startConversation(accountId)` | `src/jami/conversation_interface.h:57` | `src/client/conversation_interface.cpp:35` | `getAccount<JamiAccount>` → `convModule(true)->startConversation()` |
| `acceptConversationRequest` / `declineConversationRequest` | `:58/59` | `conversation_interface.cpp:44/52` | 接受会同时 `acceptTrustRequest` |
| `removeConversation` / `getConversations` / `getConversationRequests` | `:60/61/62` | `conversation_interface.cpp:59/...` | 列表与请求 |
| `ConversationReady` / `ConversationRequestReceived` / `ConversationCloned` / `OnConversationError` | `conversation_interface.h:474/462/499/511` | `conversation_module.cpp:2607` 等 | 已在 `jami_signal.cpp:132-155` 登记 |

| 源码落点 | 符号 | 作用 |
|---|---|---|
| `src/jamidht/conversation_module.cpp:2555` | `ConversationModule::startConversation` | 建 `Conversation`、注册回调、bootstrap、发 `ConversationReady` |
| `src/jamidht/conversation_module.cpp:2513` | `acceptConversationRequest` | 查请求 → 接受信任 → clone |
| `src/jamidht/conversation_module.cpp:1844` | `Impl::cloneConversationFrom` | 遍历成员设备逐个 clone |
| `src/jamidht/conversationrepository.cpp:2973` | `createConversation` | 建仓库 → `add_initial_files` → `initial_commit` |
| `src/jamidht/conversationrepository.cpp:724` | `add_initial_files` | 写 `admins/<id>.crt`、`devices/<id>.crt`、`CRLs/...`，1:1 另写 `invited/<uri>` |
| `src/jamidht/conversationrepository.cpp:3177` | `cloneConversation` | `validClone()` 全量校验，失败回滚 |

**关键数据结构**：`ConversationMode {ONE_TO_ONE=0, ADMIN_INVITES_ONLY, INVITES_ONLY, PUBLIC, DOCUMENT=4}`（`commit_message.h:84`）；`ConversationRequest`（`conversation.h:99-105`）；`MemberPath`（`conversationrepository.h:117-125`）`admins/ members/ invited/ banned/ devices/`，另有 `CRLs/`、`votes/` 字面量路径。

**伪代码**

```text
FUNCTION startConversation(mode, otherMember):               # conversation_module.cpp:2555
  conv = make Conversation(account, mode, otherMember)        # conversation.cpp:2117 → Impl :451
      repository = ConversationRepository::createConversation(account, mode, ...)  # :2973
          createRepository() → add_initial_files() → initial_commit(signed)       # :724 / :829
  conv.onMessageStatusChanged / onMembersChanged / onNeedSocket 注册            # :2565-2577
  conv.bootstrap(...)                                          # :2581 连接路由表（ADR-007）
  saveConvInfo(); needsSyncingCb_({})
  emit ConversationReady(accountId, convId)                   # :2607
  RETURN convId                                               # = 初始 commit hash

FUNCTION acceptConversationRequest(convId, deviceId):          # :2513
  req = getRequest(convId)
  IF !req AND conv has no conversation:
      cloneConversationFrom(conv, deviceId); RETURN            # :2520-2525 容错分支
  rmConversationRequest(convId)
  acceptTrustRequest(req.from, includeConversation=true)      # :2535
  cloneConversationFrom(*req)                                # :2536

FUNCTION cloneConversationFrom(request):                      # :1844
  REQUIRE valid InfoHash(request.from)
  startConversation(convId)                                  # :1851 先建空壳
  fill info
  forEachDevice(memberHash, LAMBDA(dev): cloneConversationFrom(conv, dev))  # :1869-1874

FUNCTION cloneConversation():                                # conversationrepository.cpp:3177
  IF !validClone():                                          # :4821 全历史逐 commit 校验
      repo.erase(); restoreBackup(); THROW InvalidRepositoryError   # :3182-3199
```

| 不变量 / 坑 | 源码依据 | 违反后果 |
|---|---|---|
| 会话 ID 是初始 commit hash，不能事后改 | ADR-001:18-20 | 所有引用失效 |
| clone 校验失败必须**整体回滚**，不能留半截仓库 | `conversationrepository.cpp:3182-3199` | 后续 fetch 永远校验失败 |
| `acceptConversationRequest` 找不到请求时仍有 clone 容错分支 | `conversation_module.cpp:2520-2525` | 误以为一定失败 |
| 新 Signal 必须在 `jami_signal.cpp:132-155` 登记 | `jami_signal.h:70` | `handlers.at()` 抛异常 |

**扩展点与验收**：改仓库初始布局 = 协议变更（ADR-001），旧节点 `checkInitialCommit`（`conversationrepository.cpp:2095`）会拒绝未知文件。验收：雷电 APK 创建会话、第二实例加入、检查 `OnConversationError` 无 `EVALIDCLONE`。

---

### F-07 发送消息 · `基线（已实现）`

**功能说明**：文本消息不是一个网络包，而是**一条带 `type` 的签名 Git commit**；写完 commit 后通过 `msg:` 通道向本账号其它设备与最多 2 个未直连成员发通知，由对方来 fetch。受 ADR-001 / 005 / 008 约束。

| 入口 | 声明位置 | 实现入口 | 说明 |
|---|---|---|---|
| `sendMessage(accountId, conversationId, message, replyTo, flag=0)` | `conversation_interface.h:91` | `conversation_interface.cpp:172` | `flag` 0=发送/回复、1=编辑、2=回应 |
| `sendMessage` 的 `CommitMessage::text` | `commit_message.h:154` | `conversation_module.cpp:1551` | `{"body":"<文本>", "type":"text/plain"}` |

| 源码落点 | 符号 | 作用 |
|---|---|---|
| `src/client/conversation_interface.cpp:181-187` | flag 分流 | `sendMessage` / `editMessage` / `reactToMessage` |
| `src/jamidht/conversation_module.cpp:2773` | `ConversationModule::sendMessage` | → `Impl::sendMessage` `:1544` → `Impl::createCommit` `:1556` |
| `src/jamidht/conversation.cpp:2507` | `Conversation::createCommit` | 校验 replyTo、投递线程池、持 `writeMtx_` |
| `src/jamidht/conversationrepository.cpp:4093` | `commitMessage` | → `Impl::commit` `:2262` 生成并签名 commit |
| `src/jamidht/conversation_module.cpp:1445` | `sendMessageNotification` | 组装 `MIME_TYPE_GIT` 通知并发送 |

**关键数据结构**：`CommitKey` / `CommitType` / `CommitMessage`（`commit_message.h:27-48` / `:50-74` / `:93-114`）；`MessageStates {UNKNOWN, SENDING, SENT, DISPLAYED, FAILURE, CANCELLED}`（`src/jami/account_const.h:64-71`）；`commitMessage.toJson()` 的 `body` **仅** `TEXT|EDITED_MESSAGE|CHECKPOINT` 写入（`commit_message.cpp:29`）。

**伪代码**

```text
FUNCTION apiSendMessage(accountId, convId, body, replyTo, flag):  # conversation_interface.cpp:172
  acc = Manager.getAccount<JamiAccount>(accountId); IF !acc: RETURN
  cm  = acc.convModule(true); IF !cm: RETURN
  SWITCH flag:
    0: cm.sendMessage(convId, body, replyTo)                 # 发送 / 回复
    1: cm.editMessage(convId, body, replyTo)                 # 编辑
    2: cm.reactToMessage(convId, body, replyTo)              # 表情回应
    default: 无动作

FUNCTION createCommit(msg, onCommit, onDone):                # conversation.cpp:2507
  IF !replyTo.empty() AND !repository.hasCommit(replyTo):
      LOG error; RETURN                                      # :2509-2514 静默丢弃，不发信号
  ThreadPool.run([lock writeMtx_]:
      commitId = repository.commitMessage(msg.toString())    # :2519 → conversationrepository.cpp:4093
          Impl.commit:                                       # :2262
            IF verifyDevice AND !validateDevice(): RETURN ""
            git_index_write_tree(); git_commit_create_buffer(parent = HEAD)
            sig = account.identity().first.sign(to_sign_vec); base64::encode(sig)  # :2333
            git_commit_create_with_signature(..., "signature")                      # :2335
            git_reference_create("refs/heads/main", ...)
            ON failure: emit OnConversationError(ECOMMIT, ...)  # :2351
      announce(commitId, true)                                # :2523
      onDone(!commitId.empty(), commitId)
  )

FUNCTION sendMessageNotification(convId, sync, commitId):     # conversation_module.cpp:1445
  payload = {"id": convId, "commit": commitId, "deviceId": deviceId_}  # MIME_TYPE_GIT
  IF sync: 先发给本账号其它设备                                # :1467-1472
  peers = peersToSyncWith() - memberUris(exclude BANNED)
  随机取 <=2 个未直连成员发送                                  # :1477-1498
  IF 未 bootstrap: 暂存，待连接后补发                          # :1499-1501
```

| 不变量 / 坑 | 源码依据 | 违反后果 |
|---|---|---|
| 每个 commit 由设备私钥签名（`signature` header），签名数据是 commit 缓冲 | `conversationrepository.cpp:2333-2335` | 对端 `isValidUserAtCommit` 拒绝 |
| `replyTo` 指向本地不存在的 commit 时**静默返回**，不报错 | `conversation.cpp:2509-2514` | UI 误报已发送 |
| 通知只发「来 fetch」的信号，不携带消息正文（ADR-005） | `conversation_module.cpp:1445-1501` | 以为改通知就能带内容 = 走错层 |
| `toJson()` 目前只对 3 种 type 写 `body`，**新类型需显式改** | `commit_message.cpp:29` | 新类型 body 丢失 |

**扩展点与验收**：新增消息类型 = 至少改 `commit_message.h/.cpp`、`conversationrepository.cpp::validCommits` 的 type 分支、`conversation.cpp::addToHistory`、`conversation_module.cpp::sendMessage`、`conversation_interface.cpp` 的 flag + `jami_signal.cpp` + 三套绑定 —— 完整清单见第 5 章。验收：雷电 APK 发消息 → 对端收到 → `SwarmMessageReceived` 带正确 `type/body`。

---

### F-08 接收消息与离线补收 · `基线（已实现）`

**功能说明**：收到「有新 commit」通知后 fetch 对端仓库、逐 commit 严格校验、合并（必要时生成 merge commit）、把 commit 转成 `SwarmMessage` 并 `emitSignal`。本地历史加载与远端补收是两条不同的路径。

| 入口 / 信号 | 声明位置 | 实现入口 | 说明 |
|---|---|---|---|
| `loadConversation(accountId, convId, from, n)` | `conversation_interface.h:96` | `conversation_interface.cpp:191` | 返回 `uint32_t` 请求 id，结果异步 |
| `loadSwarmUntil` | `:100` | `conversation_interface.cpp:203` | 带 `to/includeTo` |
| `SwarmLoaded` / `MessagesFound` / `SwarmMessageReceived` / `ConversationSyncFinished` / `OnConversationError` | `:409/417/425/493/511` | `conversation_module.cpp:2871`、`conversation.cpp:1811` | 均已登记信号 |

| 源码落点 | 符号 | 作用 |
|---|---|---|
| `src/jamidht/conversation_module.cpp:2858` | `loadConversation` | 生成请求 id、构造 `LogOptions`、异步 `loadMessages` |
| `src/jamidht/conversation.cpp:1537` | `Impl::loadMessages` | 遍历 commit → `convCommitToMap` → `addToHistory` |
| `src/jamidht/conversation.cpp:1942` | `Impl::addToHistory` | 去重、状态推算、分发 reaction / edit / message |
| `src/jamidht/conversation.cpp:2798` | `Conversation::pull` | 去重 → 排队 → 线程池 fetch |
| `src/jamidht/conversationrepository.cpp:3671` | `fetch` | `git://<deviceId>/<convId>`，超限中断 |
| `src/jamidht/conversationrepository.cpp:3962` | `mergeHistory` | 校验 → merge → 丢弃非法分支 |
| `src/jamidht/conversationrepository.cpp:3214` | `Impl::validCommits` | **逐 commit 校验核心** |

**关键数据结构**：`LogOptions{from,to,includeTo,nbOfCommits,fastLog,authorUri,logIfNotFound}`（`conversation.cpp:1547-1632`）；`History{messageList, quickAccess, pendingReactions, pendingEditions}`；`MAX_FETCH_SIZE`（`conversationrepository.cpp:3739`）。

**伪代码**

```text
FUNCTION loadConversation(convId, from, n):                  # conversation_module.cpp:2858
  id = random<uint32_t>()
  options = LogOptions{from = from, nbOfCommits = n}
  conv.loadMessages(LAMBDA(messages):
      emit SwarmLoaded(id, accountId, convId, messages)      # :2871
  , options)
  RETURN id

FUNCTION Impl::loadMessages(cb, options):                    # conversation.cpp:1537
  IF !repository OR history.loading: RETURN {}
  FOR commit IN log(options):
      m = convCommitToMap(commit)
      addToHistory(m, announce=false, fromSelf=false)         # :1616
  cb(history.messageList)

FUNCTION addToHistory(m, ...):                               # conversation.cpp:1942
  IF 已存在 id: SKIP                                         # :1995
  IF type == MERGE: SKIP                                     # :1999
  状态推算: SENDING < SENT < DISPLAYED，且不可回退            # :2030-2095
  SWITCH:
    has "react-to" → handleReaction()                        # :1647
    has "edit"     → handleEdition()                         # :1677
    ELSE           → handleMessage() → emit SwarmMessageReceived  # :1811

FUNCTION pull(deviceId, commitId, cb):                       # conversation.cpp:2798
  IF (deviceId, commitId) 正在处理: cb(false); RETURN false  # :2807-2813 去重
  ThreadPool.run: Impl::pull                                # :2826
      IF repo.hasCommit(commitId): cb(true); RETURN           # :2851 已经有了
      IF !repo.fetch(deviceId): cb(false); RETURN             # :2856
          git_fetch_options{update_fetchhead = 0}
          URL = "git://" + deviceId + "/" + convId            # :3703
          transfer_progress 超 MAX_FETCH_SIZE → -1 中断        # :3739
      newCommits = repo.mergeHistory(remoteHead)             # conversationrepository.cpp:3962
      announce(newCommits) → emit SwarmMessageReceived
      仅当 commitId 真到手才 cb(commitFound)                 # :2873-2898 防止互相广播不存在的 commit
      IF profile.vcf 变化: emit ConversationProfileUpdated
      repo.repack()

FUNCTION mergeHistory(uri):                                  # conversationrepository.cpp:3962
  remote = remoteHead(uri); IF empty: RETURN {}
  {newCommits, err} = validFetch(uri)                        # :4807
  IF err OR newCommits.empty(): removeBranchWith(uri); RETURN {}   # :3979 丢弃非法分支
  cid = merge(remote)
  IF merge failed: removeBranchWith(uri); RETURN {}          # :3990
  IF cid 非空（生成了 merge commit）: newCommits += cid       # :3993
  FOR c IN newCommits: IF c.type == member → refreshMembers(); IF action == ban → disconnect
  RETURN convCommitsToMap(newCommits)
```

**`validCommits` 逐 commit 校验（最关键不变量，`conversationrepository.cpp:3214-3550`）**：

```text
FOR commit IN commitsToValidate:
  sig = git_commit_extract_signature(..., "signature")
  IF 提取失败: emit OnConversationError(EVALIDFETCH, "Malformed commit"); RETURN false
  SWITCH commit.parents.size():
    0 → checkInitialCommit(userDevice, id, msg)               # :2095 只允许 admins/devices/CRLs/invited
       + isValidUserAtCommit(...)                             # :3268
    1 → IF type.empty(): 失败("Malformed commit (empty type)")  # :3284
       SWITCH type:
         VOTE            → checkVote                          # :1344
         MEMBER          → 按 action 分派 add/join/remove/ban/unban 校验  # :3309-3411
         UPDATE_PROFILE  → checkValidProfileUpdate            # :1813
         CHECKPOINT      → checkValidCheckpoint               # :1238（仅 DOCUMENT 仓库）
         EDITED_MESSAGE 或 editedId 非空 → checkEdit          # :1307
         ELSE（自由格式消息）:
            IF mode == DOCUMENT: 拒绝 "Message commit in document repository"  # :3459
            ELSE: checkValidUserDiff(...)                     # :3476 文件白名单
       + isValidUserAtCommit(...)                             # :3496
    >=2 → isValidUserAtCommit + checkValidMergeCommit         # :1902 两父 diff 交集必须为空
  任一步失败 → RETURN false → 整批 fetch 被丢弃

isValidUserAtCommit(...):                                     # :1986
  tree = treeAtCommit(commitId)
  REQUIRE devices/<userDevice>.crt 存在，且文件名 == 证书内公钥 getLongId()   # :2006-2019
  REQUIRE members|admins/<userUri>.crt 存在
  REQUIRE isDeviceOfMember：memberCert.getId() == uri 且 gnutls 验证通过         # :2050
  REQUIRE 设备证书 / 成员证书 expiration >= commit time                          # :2064-2069
  REQUIRE deviceCert.publicKey.checkSignature(sig_data, base64_decode(sig))      # :2076
  未 pin 则 pinCertificate                                     # :2086

checkValidUserDiff(...):                                      # :1176（允许改动的文件白名单）
  changed = changedFiles(diffStats(...))
  IF changed.empty(): RETURN true
  FOR f IN changed:
     OK IF admins|members/<userUri>.crt（旧树已存在 + verifyCertificate）
     OK IF devices/<userDevice>.crt（可新增/更新 + verifyCertificate）
     ELSE: RETURN false                                       # :1227 其它任何文件一律拒绝
```

| 不变量 / 坑 | 源码依据 | 违反后果 |
|---|---|---|
| 校验失败**丢弃整条远端分支**，不是跳过单个 commit | `conversationrepository.cpp:3979/3990` | 消息永久收不到 |
| 文件白名单极窄；新消息类型若想写 tree 必须同步扩白名单 | `:1176-1235`、`:1238-1304`、`:1902`、`:2095` | 新 commit 被判非法 |
| `isDeviceOfMember` 忽略证书内嵌 issuer 链，靠树内成员证书 + gnutls 验证 | `:103-115`、`:2050` | 自己实现校验时易判错 |
| merge commit 两父的 diff 交集必须为空 | `:1902-1983` | 防止 merge 夹带文件 |
| 只有真的拿到目标 commit 才回调成功 | `conversation.cpp:2873-2898` | 否则双方互播不存在的 commit |

**扩展点与验收**：新增 commit type 必须同时在 `validCommits` 里加分支，否则落入 `else` 走 `checkValidUserDiff`（或 DOCUMENT 模式直接被拒）。验收：双实例互发 + 一端离线后上线补收，检查 `OnConversationError` 计数为 0。

---

### F-09 编辑 / 删除 / 表情回应 / 已读回执 · `基线（已实现）`

**功能说明**：这四类都不是独立消息类型，而是**用特定字段的文本 commit 表达**：编辑/删除 = `edit` 指向原 commit，回应 = `react-to`，已读回执走独立状态通道（不是 `ConversationSignal`）。

| 入口 / 信号 | 声明位置 | 实现入口 | 说明 |
|---|---|---|---|
| `sendMessage(..., flag=1)` 编辑 / `flag=2` 回应 | `conversation_interface.h:91` | `conversation_interface.cpp:183-186` | 复用同一个 API |
| `SwarmMessageUpdated` / `ReactionAdded` / `ReactionRemoved` | `:432/439/447` | `conversation.cpp:795/1667/1711` | 均已登记 |
| **已读回执**：`AccountMessageStatusChanged` | `ConfigurationSignal` | `conversation.cpp:1827` | `ConversationSignal` 里**没有**独立已读信号 |

| 源码落点 | 符号 | 作用 |
|---|---|---|
| `src/jamidht/conversation_module.cpp:1582` | `Impl::editMessage` | 本地预校验作者与允许类型 |
| `src/jamidht/conversation_module.cpp:2799` | `reactToMessage` | `CommitMessage::reaction` |
| `src/jamidht/conversation.cpp:1647` | `handleReaction` | 目标不存在则存 `pendingReactions` |
| `src/jamidht/conversation.cpp:1677` | `handleEdition` | 编辑/删除/撤回应 |
| `src/jamidht/conversation.cpp:3386` | `setMessageDisplayed` | 已读 → `updateStatus(DISPLAYED)` |
| `src/jamidht/conversationrepository.cpp:1307` | `checkEdit` | 远端编辑合法性校验 |

**关键数据结构**：删除 = `{"body":"","edit":"<原id>","type":"text/plain"}`；回应 = `{"body":"👍","react-to":"<id>","type":"text/plain"}`；撤销回应 = 空 body 的 edit（`commit_message.h:131-151`）；`edit` 与 `reply-to` / `react-to` 互斥（`:140-141,153`）。

**伪代码**

```text
FUNCTION editMessage(convId, body, editedId):                # conversation_module.cpp:1582
  base = getCommit(editedId)
  IF DATA_TRANSFER: fileId = base.fileId
  valid = (base.author == username_)
      AND base.type IN {TEXT, DATA_TRANSFER, COLLAB_DOC}
      AND (base.type != DATA_TRANSFER OR !fileId.empty())
  IF !valid: LOG error; RETURN                               # :1605
  IF DATA_TRANSFER 删除: 删除本地文件
  createCommit(CommitMessage{type=TEXT, body=body, edit=editedId})

FUNCTION handleEdition(m):                                   # conversation.cpp:1677
  IF 编辑目标是 reaction（base.body 含 "react-to"）:
      IF m.body.empty(): 删除该 reaction; emit ReactionRemoved  # :1711
  ELSE 普通消息:
      editions.prepend(m); body[toReplace] = m.body; latestEditionId = m.id
      IF m.body.empty(): 清空 reactions                       # :1752 「删除」语义
  IF 目标未加载: pendingEditions.push(...)                   # :1757

FUNCTION announce(commits):                                  # conversation.cpp:663
  relinearizeAfterMerge(...)                                 # :699 处理 merge 交错
  FOR c WITH c.edit:
      IF 有插件: injectEditionOverwrites(...)                # :763（含 react-to 的不发）
      ELSE: 查 quickAccess 原消息 → emit SwarmMessageUpdated   # :795-806

FUNCTION setMessageDisplayed(uri, interactionId):            # conversation.cpp:3386
  IF messagesStatus_[uri]["read"] == interactionId: RETURN false  # :3389 幂等
  ThreadPool.run: updateStatus(uri, DISPLAYED, interactionId, ts, true)  # :3391
```

| 不变量 / 坑 | 源码依据 | 违反后果 |
|---|---|---|
| 编辑只允许作者本人，且只允许 3 种原类型 | `conversation_module.cpp:1598-1601`、`conversationrepository.cpp:1307-1341` | 远端拒绝编辑 commit |
| 「删除」不是删 commit（历史不可改写，ADR-001:32），是空 body 的 edit | `commit_message.h:131-133` | 试图真删会导致仓库分叉 |
| 已读回执没有独立 ConversationSignal | `conversation.cpp:1827` | 找错信号，收不到事件 |
| 状态 `SENDING < SENT < DISPLAYED` 且不可回退 | `conversation.cpp:2030-2095` | UI 状态倒退 |

**扩展点与验收**：新增「消息动作」应优先复用 `edit`/`react-to` 语义，避免新增 `CommitType`。验收：雷电 APK 编辑、删除、回应、已读，逐一核对信号。

---

### F-10 成员治理（邀请 / 加入 / 移除 / 封禁 / 投票）· `基线（已实现）`

**功能说明**：成员身份与权限存在 Git 树的 `admins/ members/ invited/ banned/ devices/ CRLs/ votes/` 里，每一次变更都是一条 `member` 类型 commit，远端在 `validCommits` 中按 `action` 分派白名单校验。

| 入口 / 信号 | 声明位置 | 实现入口 | 说明 |
|---|---|---|---|
| `addConversationMember` / `removeConversationMember` / `getConversationMembers` | `conversation_interface.h:81/84/87` | `conversation_interface.cpp:134/148/162` | 先 `dht::InfoHash h(uri)`，非法 URI 记错返回 |
| `updateConversationInfos` / `setConversationPreferences` | `:69/74` | `conversation_interface.cpp` | 元数据与偏好 |
| `ConversationMemberEvent` / `ConversationProfileUpdated` | `:484/455` | `conversation.cpp:708-734` | `MemberEvent {ADD=0,JOIN,REMOVE,BAN,UNBAN}`（`:54`） |

| 源码落点 | 符号 | 作用 |
|---|---|---|
| `src/jamidht/conversation_module.cpp:3258` | `addConversationMember` | 已是成员则只重发邀请 |
| `src/jamidht/conversation.cpp:2150` | `Conversation::addMember` | 1:1 只允许初始成员重新加入 |
| `src/jamidht/conversationrepository.cpp:3567` | `addMember` | 写 `invited/<uri>` 空文件 + `member(ADD)` commit |
| `src/jamidht/conversationrepository.cpp:1465/1553/1632/1690/1344` | `checkValidAdd / Joins / Remove / VoteResolution / Vote` | 远端白名单校验 |
| `src/jamidht/conversationrepository.cpp:4002-4009` | `mergeHistory` 的 member 分支 | `refreshMembers()`，`ban` → 断连 |

**关键数据结构**：`CommitAction {add, join, remove, ban, unban}`（`commit_message.h:76-82`）；`ConversationMember{uri, role}` 与 `MemberRole`（`conversationrepository.h:127`）；投票路径 `votes/<ban|unban>/<role>/<voter>/<target>`（`conversationrepository.cpp:1716`）。

**伪代码**

```text
FUNCTION addConversationMember(convId, uri, sendRequest):    # conversation_module.cpp:3258
  IF !InfoHash(uri): LOG; RETURN                              # conversation_interface.cpp:140
  IF 已是成员或 invited:
      info.invited = uri; generateInvitation(); sendMsgCb_(); RETURN   # :3270-3286 只重发邀请
  conv.addMember(uri, LAMBDA(ok, commitId):
      IF !ok: RETURN
      sendMessageNotification(convId, true, commitId)
      IF sendRequest: generateInvitation() + sendMsgCb_()    # :3298-3310
  )

FUNCTION ConversationRepository::addMember(uri):             # :3567
  IF admins|members/<uri>.crt 已存在: WARN; RETURN {}
  write "invited/<uri>" (空文件)
  commitId = commit(CommitMessage::member(ADD, uri))
  IF failed: RETURN {}
  members_.add({uri, INVITED}); saveMembers()
  RETURN commitId

远端校验 validCommits 的 MEMBER 分支:                        # :3309-3411
  REQUIRE InfoHash(uriMember) 合法
  SWITCH action:
    ADD    → checkValidAdd    # 只许 devices/<self>.crt、invited/<uri>、CRLs/<uri>；改动 1..3 个
                              # 不能加自己；invite 文件须为空；被加者不能在 banned/members；1:1 仅限初始成员
    JOIN   → checkValidJoins
    REMOVE → validUserAtCommit 改为 parents[0]；checkValidRemove
    BAN/UNBAN → checkValidVoteResolution
                 # votes/<ban|unban>/... 路径正则、投票文件必须已存在、作者须 admin、
                 # nbVotes/nbAdmins >= 0.5、bannedFiles 非空
    未知   → 失败
  mergeHistory 收到 member: refreshMembers(); IF ban → disconnectFromPeerCb
```

| 不变量 / 坑 | 源码依据 | 违反后果 |
|---|---|---|
| 邀请文件 `invited/<uri>` 必须是**空文件** | `conversationrepository.cpp:1534` | 远端判非法 |
| ban/unban 需要管理员投票且过半 | `:1781/1803` | 单个管理员不能随意封禁 |
| 改成员目录布局 / 校验规则 = 协议变更（ADR-001:30-31） | `doc/adr/ADR-001` | 旧节点拒绝 |
| `BAN / REMOVE` 还要更新活跃通话、移除 typing | `conversation.cpp:723-727` | 被封者仍在会议里 |

**扩展点与验收**：新增「角色」或「权限」需同时改 `MemberPath`、`checkValidXxx`、初始文件、信号映射，属协议变更。验收：三实例建群 → 邀请 → 加入 → 投票封禁 → 检查被封者断连。

---

### F-11 协作文档（Y-CRDT over Swarm）· `基线（已实现）`

**功能说明**：daemon 只负责传输 CRDT update，不解释文档内容。每个文档是一个 `DOCUMENT` 模式 swarm 仓库；实时更新走 `ydoc://` 通道，定期把一批 update 写成 `CHECKPOINT` commit 持久化。受 ADR-010 约束。

| 入口 / 信号 | 声明位置 | 实现入口 | 说明 |
|---|---|---|---|
| `createCollaborativeDocument` / `openCollaborativeDocument` / `closeCollaborativeDocument` | `conversation_interface.h:150/162/201` | `conversation_interface.cpp:275-421` | 全部转发 `acc->collaborativeEditing()` |
| `applyCollaborativeUpdate` / `collaborativeDocumentState` | `:216/222` | 同上 | update 是 `vector<uint8_t>` |
| `addCollaborativeAttachment` / `collaborativeAttachment` | `:296/310` | 同上 | 内容寻址 blob |
| `CollaborativeDocumentUpdate` / `AwarenessChanged` / `Replicated` / `AttachmentAdded` | `:330/347/401/390` | `jami_signal.cpp:149-155` | 均已登记 |

| 源码落点 | 符号 | 作用 |
|---|---|---|
| `src/jamidht/collaborative_editing.cpp:337` | `createDocument` | 建 DOCUMENT 仓库、发布 `collab-doc` 公告 |
| `src/jamidht/collaborative_editing.cpp:761` | `applyUpdate` | 先 `ytransaction_apply` 再转发，非法 update 不入库 |
| `src/jamidht/collaborative_editing.cpp:1246` | `onLocalUpdate` | 实时广播 + 累积待 checkpoint |
| `src/jamidht/collaborative_editing.cpp:1353` | `checkpointNow` | 写 `CHECKPOINT` commit，失败放回 pending |
| `src/jamidht/collaborative_editing.cpp:1516` | `onRepositoryUpdated` | 回放收到的 checkpoint |
| `src/jamidht/yrs_document.cpp:23` | `extern "C" { #include <libyrs.h> }` | yffi / Rust `libyrs` FFI |

**关键数据结构**：`YOptions` + `Y_OFFSET_UTF16`（`yrs_document.cpp:42-47`）；`CHECKPOINT_MAX_PENDING=200`、`CHECKPOINT_IDLE=10s`、`MAX_UPDATE_SIZE=8 MiB`（`collaborative_editing.cpp:45-71`）；`CommitMessage::checkpoint` 的 body 为 base64 update，每行一条（`commit_message.h:375-385`）。

**伪代码**

```text
FUNCTION createDocument(convId, name, mimeType):             # collaborative_editing.cpp:337
  IF mimeType.empty(): mimeType = "text/plain"                # :345
  documentId = convModule.startDocument(convId, mimeType)     # :355 → conversation_module.cpp:2612
      ConversationRepository::createDocument(..., DOCUMENT 模式)
  updateConversationInfos(documentId, {{"title", truncatedName(name)}})
  ensureSession(); announced_.insert(documentId)
  future = convModule.createCommit(convId, CommitMessage::collabDocCreated(documentId,...))
  IF future.wait_for(30s) TIMEOUT: RETURN {}                 # :429-437
  IF !ok: 回滚 announced_ + closeDocument + dropLocalReplica + removeDocumentReplica  # :409-423
  RETURN documentId

FUNCTION applyUpdate(docId, update):                          # :761
  session = findSession(docId); IF !session: RETURN
  IF update.size() > 8 MiB: 丢弃; RETURN                     # :768
  IF !session.doc.applyUpdate(update): RETURN               # :779 先 merge，非法则不入库不广播
  onLocalUpdate(session, update):
      broadcastFrame(session, FRAME_UPDATE, update)         # 实时通道
      queueUpdate(session, update):
          pending += base64(update)
          IF pending >= 200 OR 空闲 >= 10s: scheduleCheckpoint()

FUNCTION checkpointNow(session):                             # :1353
  IF !documentConversation OR pending.empty(): RETURN
  cm.createCommit(documentId, CommitMessage::checkpoint(batch))
  ON failure: pending 放回 batch + 重新调度                # :1384-1398

远端 checkValidCheckpoint:                                  # conversationrepository.cpp:1238
  REQUIRE mode == DOCUMENT
  只允许 attachments/*（文件名 == blob 的 git_oid）与 devices/<self>.crt
  其它任何文件 → 拒绝
```

| 不变量 / 坑 | 源码依据 | 违反后果 |
|---|---|---|
| daemon 不解释 CRDT 内容，只搬运 update | `conversation_interface.h:122-149` | 在 daemon 里做语义处理 = 架构错位 |
| `CHECKPOINT` 只在 DOCUMENT 仓库合法，且文件白名单极窄 | `conversationrepository.cpp:1238-1304` | 普通会话里收到即丢弃 |
| update 必须**先 CRDT merge 成功再广播** | `collaborative_editing.cpp:779` | 非法 update 污染所有副本 |
| checkpoint 失败要把 batch 放回 pending，不能丢 | `:1384-1398` | 文档内容永久丢失 |

**扩展点与验收**：改文档语义触碰 ADR-010；新增附件类型需扩 `checkValidCheckpoint` 白名单（协议变更）。验收：双实例同时编辑、断线重连后状态一致、检查 checkpoint commit 数量合理。

---

### F-12 一对一通话（SIP / SDP / ICE）· `基线（已实现）`

**功能说明**：呼叫建立在 pjproject SIP 之上，媒体经 dhtnet 的 ICE 传输（ICE/TURN 实现**不在本仓库**，ADR-006）。通话状态由 `Call::CallState` + `ConnectionState` 双状态机表达。

| 入口 / 信号 | 声明位置 | 实现入口 | 说明 |
|---|---|---|---|
| `placeCall(accountId, to)` / `placeCallWithMedia` | `src/jami/callmanager_interface.h:37/39` | `src/client/callmanager.cpp:42/50` | `placeCall` 已 deprecated，转 `placeCallWithMedia` |
| `accept` / `acceptWithMedia` / `refuse` / `hangUp` | `:43/60/42/44` | `callmanager.cpp:83/89/77/112` | 转发 `Manager::acceptCall` 等 |
| `requestMediaChange` / `answerMediaChangeRequest` | `:63/77` | `callmanager.cpp` | 媒体重协商 |
| `CallSignal::StateChange` / `IncomingCall` / `ConnectionUpdate` / `MediaNegotiationStatus` | `callmanager_interface.h:185/218/283/299` | `jami_signal.cpp:27-48` | 登记在 Call 段 |

| 源码落点 | 符号 | 作用 |
|---|---|---|
| `src/manager.cpp:1091` | `Manager::outgoingCall` | → `newOutgoingCall` `:3116` → `account->newOutgoingCall` `:3131` |
| `src/manager.cpp:1118/1170/1326` | `acceptCall` / `hangupCall` / `refuseCall` | 状态检查 + 转发 |
| `src/sip/sipcall.cpp:1303` | `SIPCall::answer` | SDP offer/answer、ICE 响应、200 OK |
| `src/sip/sipcall.cpp:3287` | `startIceMedia` | ICE 协商 → `onIceNegoSucceed` → `setupNegotiatedMedia` |
| `src/sip/sipcall.cpp:1537/1590` | `hangup` / `refuse` | `pjsip_inv_terminate` |
| `src/sip/sipvoiplink.cpp:464` | `pjsip_inv_create_uas` | 收呼边界（`PJSIP_INV_SUPPORT_ICE`） |

**关键数据结构**：`CallState` / `ConnectionState`（`RINGING`、`ACTIVE`、`CONNECTED`）；`pjmedia_sdp_session* localSession_/remoteSession_`（`sdp.h:258-259`）；`pjsip_inv_session* inviteSession_`；`std::shared_ptr<dhtnet::IceTransport>`（`sipcall.cpp:3326`）。

**伪代码**

```text
FUNCTION placeCall(accountId, to, mediaList):                # callmanager.cpp:50
  IF to.empty(): RETURN {}
  RETURN Manager.outgoingCall(accountId, to, mediaList)       # manager.cpp:1091
      → newOutgoingCall → account->newOutgoingCall          # :3116/:3131
      → new SIPCall → pjsip_inv_create_uac → createSDPOffer
        → initIceMediaTransport → addLocalIceAttributes

FUNCTION acceptCall(accountId, callId, mediaList):            # manager.cpp:1118
  call = getCall(callId)
  IF call.connectionState != RINGING: RETURN true             # :1135 幂等短路
  call.answer(mediaList)                                     # :1145 → sipcall.cpp:1303
      buildMediaAttributesList() → updateMediaStream(逐路)
      sdp_.processIncomingOffer(mediaAttrList)               # :1357
      IF isIceEnabled() AND remoteHasValidIceAttributes(): setupIceResponse()  # :1359-1361
      pjsip_inv_answer(200, localSdp) + contact/UA header → pjsip_inv_send_msg  # :1412-1428
      setState(ACTIVE, CONNECTED)                            # :1433
  ASYNC startIceMedia():                                     # :3287
      attrs = sdp.getIceAttributes()
      iceMedia.startIce(rem_ice_attrs, getAllRemoteCandidates())
      onIceNegoSucceed → setupNegotiatedMedia → newIceSocket → startAllMedia  # :3370-3401

FUNCTION hangupCall(accountId, callId):                      # manager.cpp:1170
  removeAudio(); IF 会议参与者: removeParticipant()
  call.hangup(0) → pjsip_inv_terminate                      # :1198 / sipcall.cpp:1537

网络切换（ADR-011）:                                         # sipcall.cpp:500/789/866
  beginCallRecovery() → requestIceRestart() → restartIceAfterRecovery()
```

| 不变量 / 坑 | 源码依据 | 违反后果 |
|---|---|---|
| ICE/TURN/UPnP 在**外部仓库 dhtnet**，本仓库 `src/connectivity/` 只有 IP/TLS 工具 | ADR-006:10-19、`channeled_transport.cpp:21` | 在 daemon 里找 ICE 实现会扑空 |
| `placeCall` 已 deprecated，真实入口是 `placeCallWithMedia` | `callmanager.cpp:42-46` | 新功能挂在旧 API 上 |
| 改 SDP/ICE 属跨仓库改动，需先落 dhtnet 再 bump contrib | ADR-006:23-25 | 本地编不过 |
| 呼叫恢复路径必须保留 | ADR-011、`sipcall.cpp:500/789/866` | 切网必断 |

**扩展点与验收**：新增通话控制信令优先走 SIP INFO + 会议协议 JSON（有 version 字段，见 F-13）。验收：雷电 APK 语音/视频呼叫、切网恢复；当前雷电无摄像头，视频路径只能静态验证。

---

### F-13 群组通话与会议（Rendezvous）· `基线（已实现）`

**功能说明**：Swarm 群呼 = 某成员作为 rendezvous 主机，其它成员以普通 SIP 呼叫连到主机，主机用 `Conference` 混音/混流；控制命令通过 SIP INFO 里的 JSON（V0/V1 协议）下发。受 ADR-004 / 001 约束。

| 入口 / 信号 | 声明位置 | 实现入口 | 说明 |
|---|---|---|---|
| `joinParticipant` / `createConfFromParticipantList` / `addParticipant` | `callmanager_interface.h:82/86/90` | `callmanager.cpp:170/179/208` | 会议编排 |
| `setConferenceLayout` / `setModerator` / `muteStream` / `setActiveStream` / `raiseHand` | `:88/118/128/138/153` | `callmanager.cpp:185/...` | 本地 host 直接调，远程走 `sendConfOrder` |
| `getActiveCalls(accountId, convId)` | `conversation_interface.h:65` | `conversation.cpp:555` | 活跃通话由 commits 推导 |
| `CallSignal::ConferenceCreated/Removed/OnConferenceInfosUpdated` | `callmanager_interface.h:238/253/288` | `conference.cpp` | 会议事件 |

| 源码落点 | 符号 | 作用 |
|---|---|---|
| `src/conference.cpp:50/221/1249` | `Conference` / `registerProtocolHandlers` / `onConfOrder` | 会议对象、控制命令注册与分发 |
| `src/conference.cpp:741/817/956/998` | `addSubCall` / `removeSubCall` / `setLayout` / `sendConferenceInfos` | 子呼叫与布局 |
| `src/conference_protocol.cpp:50/67/107` | `ConfProtocolParser::parse / parseV0 / parseV1` | 按 `version` 分派 JSON 命令 |
| `src/jamidht/conversation.cpp:3732` | `Conversation::hostConference` | 写 announcement commit |
| `src/jamidht/jamiaccount.cpp:682/5192` | `convModule()->hostConference` / `CallConnectionRequest` | 群呼入口 |

**关键数据结构**：`Conference::State {ACTIVE_ATTACHED, ACTIVE_DETACHED, HOLD}`（`conference.h:186`）；`ConfInfo : vector<ParticipantInfo>`；`ConferenceProtocol` 的 V0 键 `HANDRAISED/MUTEPART/HANGUPPART`、V1 键 `DEVICES/MEDIAS/RAISEHAND/HANGUP/ACTIVE`，`:43-45` 还预留 "Future" 段（`MUTEVIDEO/VOICEACTIVITY`）。

**伪代码**

```text
发起群呼: placeCall("swarm:<convId>")
  → Uri::Scheme::SWARM 分支选 rdv: 主机                    # jamiaccount.cpp:429/520
  → host 写 announcement commit（activeCalls_ 由 commits 推导）  # conversation.cpp:3732/505
  → 成员 SIP 呼叫 "rdv:<accountUri>/<deviceId>/<convId>/<confId>"
  → host: getConference() → addSubCall() → attachHost() → bindSubCallAudio()
        → setupVideoMixer() 合成布局

控制命令: setConferenceLayout / muteStream / raiseHand ...
  IF conf 在本地: conf->setLayout(...) 等直接调用               # callmanager.cpp:189
  ELSE: call->sendConfOrder(root)                             # :193
        → 对端 onConfOrder()                                  # conference.cpp:1249
        → ConfProtocolParser.parse(): 按 version → parseV0() / parseV1()  # conference_protocol.cpp:50-107
        → 触发 registerProtocolHandlers 里注册的 lambda        # conference.cpp:223-244
        → setLayout / setActiveStream / muteStream / hangupParticipant
```

| 不变量 / 坑 | 源码依据 | 违反后果 |
|---|---|---|
| 群呼拓扑是「主机 rendezvous」，不是全网状 | ADR-004 | 改拓扑 = 架构变更 |
| 控制协议有 `version` 字段与 V0/V1 分派，新增 key 要走版本协商 | `conference_protocol.cpp:50-107` | 旧客户端解析失败 |
| 活跃通话是 swarm commits 推导出来的，不是独立状态 | `conversation.cpp:505/555` | 直接改内存状态会被同步覆盖 |

**扩展点与验收**：新增会议控制命令优先扩 `conference_protocol.cpp` 的 V1/Future 键 + `registerProtocolHandlers`，属于两端协商但不动 git 协议。验收：三实例群呼 + 静音/举手 + 检查 `OnConferenceInfosUpdated`。

---

### F-14 文件传输 · `基线（已实现）`

**功能说明**：文件本身走 `data-transfer://` 通道流式传输，但**文件在会话里的存在感是一条 swarm commit**（含 `interactionId` / `fileId`），因此离线也能看到「有个文件待下载」。

| 入口 / 信号 | 声明位置 | 实现入口 | 说明 |
|---|---|---|---|
| `sendFile(accountId, convId, path, displayName, replyTo)` | `src/jami/datatransfer_interface.h:106` | `src/client/datatransfer.cpp:32` | → `acc->sendFile(...)` |
| `downloadFile(..., interactionId, fileId, path)` | `:120` | `datatransfer.cpp:44` | → `convModule->downloadFile` |
| `cancelDataTransfer` / `fileTransferInfo` | `:138/153` | `datatransfer.cpp:57/68` | 取消 / 查询 |
| `DataTransferSignal::DataTransferEvent` | `:161-163` | `data_transfer.cpp` | 进度与状态 |

| 源码落点 | 符号 | 作用 |
|---|---|---|
| `src/jamidht/jamiaccount.cpp:4869` | `JamiAccount::sendFile` | 登记为 swarm commit（含 fileId） |
| `src/jamidht/jamiaccount.cpp:4930/4976` | `transferFile` / `onIncomingFileTransfer` | 通道接入 |
| `src/jamidht/conversation.cpp:3230` | `Conversation::downloadFile` | 会话侧下载编排 |
| `src/jamidht/conversation_module.cpp:2981` | `ConversationModule::downloadFile` | 转发 |
| `src/data_transfer.cpp:456/495/741` | `TransferManager::transferFile / cancel / waitForTransfer` | 传输状态机 |
| `src/jamidht/transfer_channel_handler.cpp:216` | `dt->transferFile(...)` | 通道回调 |

**关键数据结构**：`DataTransferInfo{accountId,lastEvent,flags,totalSize,bytesProgress,author,peer,conversationId,displayName,path,mimetype}`（`datatransfer_interface.h:63-77`）；`TransferManager` 的 `outgoings_ / incomings_ / waitingIds_`；`dhtnet::ChannelSocket`；`CommitType::DATA_TRANSFER = "application/data-transfer+json"`（`commit_message.h:54`）。

**伪代码**

```text
发送: sendFile(...)
  → JamiAccount::sendFile 写一条 DATA_TRANSFER commit（interactionId / fileId / displayName / totalSize）
  → 对端 Conversation 收到 commit 后生成 waitingRequests()       # conversation.cpp:2924
  → 客户端调 downloadFile(convId, interactionId, fileId, path)
      → ConversationModule::downloadFile → Conversation::downloadFile
      → TransferManager::waitForTransfer
      → 在 "data-transfer://" 通道上 JamiAccount::transferFile
      → TransferManager::transferFile 建 OutgoingFile
      → ThreadPool.io().run(of.process())                        # data_transfer.cpp:488-491
      → 进度经 DataTransferSignal::DataTransferEvent 上报

取消: cancelDataTransfer(...) → TransferManager::cancel(fileId)  # data_transfer.cpp:495
      清 waitingIds_ / IncomingFile::cancel() / 清理临时文件
```

| 不变量 / 坑 | 源码依据 | 违反后果 |
|---|---|---|
| 文件锚定到 swarm commit，删 commit 不能删文件记录（ADR-001） | `datatransfer_interface.h:72` | 历史里出现悬空引用 |
| `TransferManager` 由每个 `Conversation` 持有 | `conversation.cpp:3184` | 跨会话共享实例导致串文件 |
| 断点续传靠 `start/end` 参数与 `waitForTransfer` | `data_transfer.cpp:456/741` | 重传整文件 |

**扩展点与验收**：新增「文件类型」通常只需扩 `displayName/mimetype` 与 commit JSON（仍需 `checkEdit` 允许）；验收：双实例互传 → 中途取消 → 续传 → 校验 sha3sum。

---

### F-15 连接建立、通道多路复用与 NAT 穿透 · `基线（已实现）`

**功能说明**：两台设备之间只建**一条**经 ICE 协商 + TLS 证书认证的连接，其上按名字开多路通道（`sip` / `git://` / `msg:` / `sync://` / `auth:` / `data-transfer://` / `ydoc://` / `swarm://`），channel 0 为控制通道。受 ADR-002 / 006 / 008 / 009 约束。

| 可证实的面 | 位置 | 说明 |
|---|---|---|
| 无专门「建立连接」libjami API | `NOT_FOUND` | 由账户注册隐式建立 |
| `CallSignal::ConnectionUpdate` | `callmanager_interface.h:283` | 连接状态信号 |
| TURN 配置键 | `src/account_schema.h:82-86` | `TURN_SERVER / TURN_ENABLE / TURN_SERVER_UNAME / PWD / REALM` |
| `msg:` 通道 | `src/jamidht/message_channel_handler.cpp:24` | `MESSAGE_SCHEME = "msg:"sv` |
| SIP over ChannelSocket | `src/jamidht/channeled_transport.cpp:32/134` | 把通道包装成 pjsip transport |

| 源码落点 | 符号 | 作用 |
|---|---|---|
| `src/jamidht/message_channel_handler.cpp:71` | `MessageChannelHandler::connect` | `connectDevice(deviceId, "msg:"+deviceId, ...)` |
| `src/jamidht/message_channel_handler.cpp:155` | `onReady` | 校验证书、登记 `connections_`、装 msgpack reader |
| `src/jamidht/message_channel_handler.cpp:214` | `sendMessage` | `msgpack::pack` → `socket->write` |
| `src/jamidht/channeled_transport.cpp:134/154` | 注册 pjsip transport / 收包 | `pjsip_tpmgr_receive_packet` |
| `src/gittransport.cpp:22` | `#include <dhtnet/connectionmanager.h>` | dhtnet 边界 |

**关键数据结构**：`Message {uint64_t id; std::string t; std::string c; unique_ptr<ConversationRequest> req; MSGPACK_DEFINE_MAP(id,t,c,req)}`（`message_channel_handler.h:81-88`）；`connections_` 为 `map<string, map<DeviceId, vector<shared_ptr<ChannelSocket>>>>`；TLS 证书链 `cert->issuer->getId()`。

**伪代码**

```text
账户注册 → dhtnet ConnectionManager 与每个对端设备协商一条连接
   ICE 候选交换（TURN 兜底，配置键 account_schema.h:82-86）
   TLS 双向认证：设备证书由账户证书签发（F-01 的三层链）
   channel 0 = 控制通道

业务申请通道: MessageChannelHandler::connect(deviceId)       # :71
   channelName = "msg:" + deviceId                          # :77
   connectionManager.connectDevice(deviceId, channelName, onConnect)
   onReady(cert, socket):                                  # :155
       cert.issuer.getId() 校验                            # :162
       connections_[channelName][deviceId].push_back(socket)
       socket.setOnRecv(buildMsgpackReader<Message>())      # :175
       onMessage(cert, msg.t, msg.c)                       # :176
   sendMessage(msg): msgpack::pack(buffer, msg); socket.write(buffer)  # :220-222

SIP 复用同一条连接: ChanneledSIPTransport(socket)             # channeled_transport.cpp:32
   pjsip_transport_register(tpmgr, &base)                    # :134
   socket.onRecv → pjsip_tpmgr_receive_packet                # :154
```

| 不变量 / 坑 | 源码依据 | 违反后果 |
|---|---|---|
| 新业务流应复用已有连接，**不要每功能新建 TCP** | ADR-002 | 连接数爆炸、NAT 穿透成功率下降 |
| 改 `Message` 结构 = wire format 变更（ADR-008），且 msg 通道与 SIP MESSAGE 回退必须等价 | `message_channel_handler.h:81-88`、ADR-008:24 | 新旧节点互通失败 |
| 新增 channel scheme 触碰 ADR-002/009 | ADR-002:20-22 | 控制通道不认识新 scheme |
| 连通性改动先在 dhtnet 落，再 bump contrib | ADR-006:23-25 | 本仓库编不过 |

**扩展点与验收**：新增通道需注册 handler 并确认 scheme 不冲突；验收：双实例建连后 `connections_` 只有一条底层连接，抓包核对。

---

### F-16 信号回调与 libjami 生命周期 · `基线（已实现）`

**功能说明**：daemon → 客户端的事件通道。所有事件是 `struct { constexpr static const char* name; using cb_type; }`，运行时用 `map<name, CallbackWrapperBase>` 查表调用，这样设计正是为了跨版本保持 ABI。

| 入口 | 声明位置 | 实现入口 | 说明 |
|---|---|---|---|
| `libjami::init(InitFlag)` / `start(path)` / `fini()` / `initialized()` | `src/jami/jami.h:67/74/79/81` | `src/jami.cpp:35/104/122/116` | **命名空间是 `libjami`，`DRing::` 旧称在源码中 NOT_FOUND** |
| `registerSignalHandlers(map)` / `unregisterSignalHandlers` | `jami.h:268/269` | `src/client/jami_signal.cpp:170/184` | 未知名字记日志忽略 |
| `emitSignal<Ts>(args...)` | `src/client/jami_signal.h:63` | `:70 handlers.at(Ts::name)` → `cb(args...)` | `:76` 捕获异常 |

| 源码落点 | 符号 | 作用 |
|---|---|---|
| `src/jami/jami.h:111/127/181` | `CallbackWrapperBase` / `CallbackWrapper` / `SerializedCallbackWrapper` | 回调包装与线程投递 |
| `src/client/jami_signal.cpp:23` | `getSignalHandlers()` | 静态表：Call / Configuration / Presence / Audio / DataTransfer / Video / Conversation / Plugin |
| `src/client/jami_signal.cpp:132-155` | Conversation 段 | 24 个信号逐条 `exported_callback<...>()` |
| `src/jami.cpp:145` | `CallbackWrapperBase::post` | `asio::post(*io, ...)` |

**关键数据结构**：`using SignalHandlerMap = std::map<std::string, std::shared_ptr<CallbackWrapperBase>>`（`jami_signal.h:54`）；`InitFlag` 位标志；`exportable_callback` / `exportable_serialized_callback`（`jami.h:246/258`）。

**伪代码**

```text
libjami.init(flags)                                        # jami.cpp:35
  设置日志 → 提前实例化 getSignalHandlers() 静态表 → Manager::instance()
libjami.registerSignalHandlers(map<name, CallbackWrapperBase>)
libjami.start(configPath) → Manager::instance().init(config_file, flags)   # :104-107
业务侧 emitSignal<libjami::ConversationSignal::SwarmMessageReceived>(args)
  handlers = getSignalHandlers()
  wrapper = handlers.at(Ts::name)                          # jami_signal.h:70  ← 未登记会抛
  static_cast<CallbackWrapper<Ts::cb_type>>(wrapper)->cb(args...)
  catch(...) 记日志，不让异常穿透                          # :76
libjami.fini() → Manager::finish() + Logger::fini()         # :122-124
```

| 不变量 / 坑 | 源码依据 | 违反后果 |
|---|---|---|
| 新增 Signal **必须**在 `jami_signal.cpp` 追加 `exported_callback` | `jami_signal.h:70` | 运行时 `map::at` 抛异常 |
| 新增 API / Signal 要同步三套绑定：`bin/jni`（SWIG）、`bin/dbus`（XML + hpp）、`bin/nodejs` | `bin/jni/jni_interface.i:205-213,325-366` | 客户端拿不到新接口 |
| 改已有信号的 `cb_type` 破坏 ABI | `jami.h:90-105` | 旧客户端崩溃 |
| 回调可能在 IO 线程，需 `SerializedCallbackWrapper` 投递 | `jami.h:181-198` | 客户端线程安全问题 |

**扩展点与验收**：验收清单固定为「接口头文件 → 实现转发 → 信号注册 → 三套绑定 → 客户端」五处齐全；可用 `bin/jni/make-swig.sh` 重新生成 Java 包装验证。

---

### F-17 插件扩展机制（最优先的扩展点）· `基线（已实现）`

**功能说明**：`src/plugin/` 提供本地 C ABI 插件框架（ChatHandler / CallMediaHandler / PreferenceHandler / WebViewHandler）。**插件改的是本地展示与本地处理，不进 wire format**，因此是扩展新功能风险最低的路径。注意它与 ADR-003 的「可插拔音频处理」不是同一机制。

| 入口 / 信号 | 声明位置 | 实现入口 | 说明 |
|---|---|---|---|
| `loadPlugin` / `unloadPlugin` / `installPlugin` / `uninstallPlugin` | `src/jami/plugin_manager_interface.h:30-46` | `src/client/plugin_manager_interface.cpp:31/44` | 插件生命周期 |
| `getChatHandlers` / `toggleChatHandler` / `getChatHandlerStatus` | `:48/50/57` | `plugin_manager_interface.cpp:160/179/222` | 会话插件开关 |
| `sendWebViewMessage` / `Attach` / `Detach` | `:61/66/71` | `plugin_manager_interface.cpp:253/267/281` | 插件 ↔ 客户端通道 |
| `PluginSignal::WebViewMessageReceived` | `:73` | `jami_signal.cpp:158` | 已登记（`#ifdef ENABLE_PLUGIN`） |

| 源码落点 | 符号 | 作用 |
|---|---|---|
| `src/plugin/jamiplugin.h:32/36/110` | `JAMI_PLUGIN_ABI_VERSION=1` / `API_VERSION=3` / `JAMI_dynPluginInit` | C ABI 版本与初始化入口 |
| `src/plugin/pluginloader.cpp:23` | `Plugin::load` | `dlopen(RTLD_NOW)` |
| `src/plugin/pluginmanager.cpp:161/274` | `registerPlugin` / `registerObjectFactory` | 填 `JAMI_PluginAPI` 三函数指针、登记工厂 |
| `src/plugin/chathandler.h:85` | `ChatHandler::transformSwarmMessages` | **纯本地展示改写** |
| `src/plugin/chatservicesmanager.cpp:140/169/321/375` | `registerChatService` / `sendTextMessage` / `transformSwarmMessages` / `publishMessage` | 消息管道 |
| `src/plugin/chatservicesmanager.cpp:584` | `toggleChatHandler(id, account, peer, bool)` | 开关状态机 |

**关键数据结构**：`JAMI_PluginAPI{version, context, registerObjectFactory, invokeService, manageComponent}`（`jamiplugin.h:91`）；`JamiMessage{accountId, peerId, direction, data, fromPlugin, isSwarm, fromHistory}`（`streamdata.h:66`）；`SwarmMessage.pluginData`（`conversation_interface.h:39-41`，**明确「Never stored to git — local display only」**）。

**伪代码**

```text
【注册】 loadPlugin(path)
  → JamiPluginManager::loadPlugin → getPluginDetails()["soPath"]
  → Plugin::load: dlopen(RTLD_NOW) → DLPlugin
  → PluginManager::registerPlugin: 填充 JAMI_PluginAPI 三个函数指针
  → callPluginInitFunction: 调 JAMI_dynPluginInit(api)
  → 插件调 api.registerObjectFactory("ChatHandler", factory{create,destroy})
  → registerObjectFactory 记入 objectFactories_，registerComponentManager 挂生命周期
  → ChatServicesManager::registerChatService 注册 8 个 service:
      sendTextMessage / editMessage / reloadBodyOverwritesAllConversations /
      updateMessageBodyOverwrite / clearBodyOverwritesAllConversations /
      loadMissingBodyOverwritesAllConversations / clearBodyOverwrites / loadMissingBodyOverwrites

【消息管道】 daemon 收到/发出消息 → publishMessage(msg)       # chatservicesmanager.cpp:375
  IF msg.fromPlugin: RETURN                                 # :377 防插件自产消息回环
  LOCK: 按 {accountId, peerId} 读 chatHandlerToggled_ / allowDenyList_ / alwaysPreference
        得到 toCall；每个启用 handler 共享 PublishObservable
  UNLOCK → handler.notifyChatSubject(pair, subject)         # :439
        → subject.publish(msg) → 各 Observer 收到 JamiMessage
  插件改写展示: ChatHandler::transformSwarmMessages 只能设
        SwarmMessage.pluginData["bodyOverwrite"]            # chathandler.h:76-88，其它就地修改被忽略
  插件发消息: invokeService("sendTextMessage", JamiMessage*)
        → chatservicesmanager.cpp:169 → convModule()->sendMessage(...)
  插件回写改写结果: invokeService("updateMessageBodyOverwrite", ...)
        → :204 校验 enabled → conv->updateMessageBodyOverwrite → SwarmMessageUpdated
```

| 不变量 / 坑 | 源码依据 | 违反后果 |
|---|---|---|
| `pluginData` **永不写入 git**，只是本地展示 | `conversation_interface.h:39-41` | 误以为能借插件改协议内容 |
| 插件只能设 `bodyOverwrite`，其它就地修改被 daemon 忽略 | `chathandler.h:76-88` | 插件「改了没反应」 |
| `JamiMessage.fromPlugin` 防回环 | `streamdata.h:93`、`chatservicesmanager.cpp:377` | 插件自产消息无限循环 |
| 加 service 可能需评估 `JAMI_PLUGIN_API_VERSION` | `jamiplugin.h:36` | 老插件加载失败 |
| 插件系统**无专属 ADR**；ADR-003 是另一套机制 | `NOT_FOUND` / ADR-003 标题 | 误引用约束 |

**扩展点与验收**：优先用 `transformSwarmMessages + bodyOverwrite` 做翻译/过滤/标注类功能（零协议风险）；需要发消息时 `invokeService("sendTextMessage")`；需要 UI 时用 WebViewHandler。验收：启用插件后检查 `SwarmMessageUpdated`、关闭插件后恢复原 body、git 仓库内容无变化。

---

## 5. 改动映射矩阵（条目 → 文件 → 协议影响）

新增功能时先在这张表里定位"要动哪几层"，再写条目。

| 扩展类型 | 必改文件（按源码证据） | 需同步的绑定 | 是否触发 wire format / ADR | 风险 |
|---|---|---|---|---|
| 本地展示改写（翻译、过滤、标注） | `src/plugin/chathandler.h:85`、`src/plugin/chatservicesmanager.cpp:140/321` | 无（`pluginData` 不进 git） | **否** | 最低，首选 |
| 纯新增 libjami API（不改已有签名） | `src/jami/<域>_interface.h`、`src/client/<域>.cpp` | `bin/jni` + `bin/dbus` + `bin/nodejs` | 否（ABI 安全，见 `jami.h:90-105`） | 低 |
| 纯新增 Signal | `src/jami/<域>_interface.h` 的 Signal 结构、**`src/client/jami_signal.cpp`**、业务侧 `emitSignal` | 三套绑定 + `jni_interface.i` 的 `conversationHandlers` | 否 | 低，但漏登记必崩 |
| 新增插件 Service / Handler | `src/plugin/chatservicesmanager.cpp:140`、`src/plugin/jamiplugin.h:36`（评估版本） | 无 | 否（本地 C ABI） | 低-中 |
| 新增会议控制命令 | `src/conference_protocol.cpp:43-45`、`src/conference.cpp:223-244` | 无 | 需两端版本协商，不动 git 协议 | 中 |
| **新增 swarm commit type / 改 commit JSON** | `src/jamidht/commit_message.h:27-114`、`commit_message.cpp:24-139`（注意 `:29` 的 body 白名单）、`conversationrepository.cpp:3214-3550` 的 type 分支 + 文件白名单 `:1176/:1238/:1902/:2095` + `checkEdit :1307`、`conversation.cpp:1942/663/1763`、`conversation_module.cpp:1544/2773/1582`、`src/client/conversation_interface.cpp:172`、`src/jami/conversation_interface.h`、`src/client/jami_signal.cpp` | 三套绑定 | **是，触碰 ADR-001 + ADR-005**；旧节点拒绝（ADR-001:30-31，需兼容策略，参照 `EDITED_MESSAGE` 仍保留解析 `commit_message.h:71-73`） | 高 |
| 改 msgpack 通道 `Message{id,t,c,req}` | `src/jamidht/message_channel_handler.h:81-88`、`.cpp:214` | 无 | **是，ADR-008**；且 msg 通道与 SIP MESSAGE 回退必须等价 | 高 |
| 新增 channel scheme | `src/jamidht/*channel_handler*`、连接管理 | 无 | **是，ADR-002 / ADR-009** | 高 |
| 改联系人/信任请求存储字段 | `src/jamidht/jami_contact.h:57/235/444`、`contact_list.cpp:281-295` | 无 | 存储格式变更；**必须双写旧键**（`jami_contact.h:243-265/361-365`），否则升级丢数据、降级解析失败 | 中-高 |
| 改 ICE / SDP / TURN | 外部仓库 **dhtnet** 优先，`src/sip/sipcall.cpp`、`src/sip/sdp.h` | 无 | **是，ADR-006 / ADR-011**，需 contrib bump | 高（跨仓库） |
| 改群呼拓扑 / 会议主机语义 | `src/jamidht/conversation.cpp:3732`、`src/conference.cpp` | 无 | **是，ADR-004 + ADR-001** | 高 |
| 改协同文档 | `src/jamidht/collaborative_editing.cpp`、`conversationrepository.cpp:1238` | 无 | **是，ADR-010** | 高 |
| 新增账户持久字段 | `src/jamidht/jamiaccount_config.cpp:37/80/174`（三处缺一不可） | 无 | 否，但要给默认值（`parseValueOptional`） | 低 |
| 新增 Qt / Android UI | 另外两个仓库（本仓库只有绑定生成入口） | `bin/jni/conversation.i` 等 | 否 | 中，且当前 Windows GUI 链未打通 |

---

## 6. 待实现功能区（你在这里追加新功能）

> **使用方式**：复制下面的模板，把 `F-901` 换成下一个编号，填完保存。我读到 `状态: 待实现` 的条目就会按第 0.1 节闭环执行。字段不确定就写 `待定`，我会先问再动手。

### 6.1 条目模板（复制这一段）

```markdown
### F-901 <功能名称> · `待实现`

| 字段 | 内容 |
|---|---|
| 编号 / 状态 | F-901 / 待实现 |
| 目标 | <一句话说明要解决什么问题、给谁用> |
| 触发入口 | <用户操作 / API / 信号 / 定时；写清是哪个已有入口，或要新增哪个> |
| 期望行为 | <正常路径的可见结果，逐条写> |
| 失败与边界 | <失败时怎么表现、超时、离线、并发、旧版本对端> |
| 数据 | <新增字段 / 结构体 / 持久化位置；是否要进 git commit> |
| 不变量 | <不能破坏的既有约束，引用本文 F-xx 或 ADR 编号> |
| 是否接受协议变更 | 否 / 是（是则写兼容策略） |
| 验收条件 | <可执行的检查：编译目标、操作步骤、预期信号/日志> |

**改动落点（我来核）**：<可留空，我会按第 5 章补>

**伪代码**

    FUNCTION <name>(<args>):
      ...

**实现记录（我来回填）**：状态 / 改动文件 / 编译结果 / 验证证据路径 / 遗留问题
```

### 6.2 待实现条目登记

| 编号 | 功能名称 | 状态 | 目标摘要 | 协议变更 | 验收条件 |
|---|---|---|---|---|---|
| F-901 | 公共聊天室（无锚点 v2） | 待实现 | 标准化清单 + 自由进入 + 零常开节点 | 是（mode=3 的 JOIN 放行规则 + 4 个新增 libjami API） | 见 6.3 F 节 |

### 6.3 F-901 公共聊天室规格正文（2026-11-12 方案 v2，用户已拍板）

> **状态说明**：本条目**只锁定规格，尚未获得「开工」指令，功能代码一行未落地**。执行前仍须按第 0.1 节第 2 步先给改动清单并经确认。v1 第 ③ 项「部署常驻锚点」已被用户否决（「不能有锚点服务器」），作废；v2 选定「随包创世种子 + 0 人在线只读浏览」。

| 字段 | 内容 |
|---|---|
| 编号 / 状态 | F-901 / 待实现（方案已定，待开工） |
| 目标 | 三端看到同一份标准化公共聊天室清单，不需邀请/审批即可自由进入；**不部署任何常开节点、专用机器或额外进程** |
| 触发入口 | 新增 UI「公共聊天室」列表页；daemon 首次启动或新增账号时释放种子仓库；点房间 → `joinPublicConversation(roomId)` |
| 期望行为 | ① 列表来自随包签名 `rooms.json`，各端 ID 集合一致；② 点击即进，无邀请、无管理员审批；③ 有在线成员时正常收发消息；④ 0 人在线时可进房并只读浏览本地已有内容，输入框禁用并提示；⑤ 退出后不残留会话；⑥ **消息只投递给已加入该房间的有效成员；房间除自己外无成员时不产生任何对外发送**（见 G 节） |
| 失败与边界 | 0 人在线 = 只读，不写本地 commit、不产生「待同步」消息；单成员时发送层守卫短路、不写待发队列；种子签名校验失败则跳过该房间并在列表标注不可用；旧客户端遇 mode=3 房间应优雅失败而非崩溃；多账号各自独立释放种子、重复启动幂等 |
| 数据 | `rooms.json`（签名）+ `seeds/<roomId>.bundle`（签名 Git bundle，**仅创世 commit + 元信息，不带历史消息**）；释放到 `<data_dir>/<accountId>/conversations/<roomId>/` |
| 不变量 | 不新增任何服务端组件；不破坏 F-06 / F-08 / F-10 的 P2P swarm 语义；只新增 API、不改既有 API 签名；遵循 ADR-001 协议兼容；**收件人集合必须是房间成员表的子集（R1）、单成员必须无对外发送（R2）** |
| 是否接受协议变更 | 是。`ConversationMode::PUBLIC=3` 已存在，但 `checkValidJoins()` 强制 invited 检查 → 仅跳过 `:1579-1587` 两段，签名/证书链（`:1605-1626`）与文件白名单一律保留；旧节点遇 mode=3 需优雅失败 |
| 验收条件 | 见下方 F 节 P0~P5；P0 未全过不得进入 UI 开发 |

**A. 用户可见功能点**

| 功能点 | Windows | Linux | Android | 0 人在线时 |
|---|---|---|---|---|
| 公共聊天室列表（官方预置、只读、V1.0 不可自建） | V1.0 先做 | 后续 | 后续 | 列表始终可见（本地 rooms.json） |
| 房间名 / 描述 / **当前可见成员数** | 支持 | 支持 | 支持 | 显示 0；措辞已按 18:17 裁定改为「当前可见成员」，不叫「在线人数」（无全局权威计数源） |
| 免邀请进入房间 | 支持 | 支持 | 支持 | 可进，只读 |
| 浏览房间信息与本地已有消息 | 支持 | 支持 | 支持 | 仅本地仓库内已有内容 |
| 发消息 / 表情 / 文件 | 支持 | 支持 | 支持 | 输入框禁用 + 提示「暂无在线成员」 |
| 退出房间 | 支持 | 支持 | 支持 | 可用 |

**A-1. 历史可见范围（20:07 源码核准；20:49 用户已拍板「UI 只显加入后」）**

| 场景 | 本地仓库实际内容 | 用户首屏看到 | 能否翻到加入前的消息 |
|---|---|---|---|
| 全新用户首次进房，0 人在线 | 仅随包创世 commit + 元信息 | 空房间（房间名 / 描述） | 不能，暂无历史可拉 |
| 全新用户首次进房，≥1 人在线 | 从在线成员 clone/fetch **全量历史** | 最近 20 条 | **能**，向上滚动可一直翻到创世 |
| 老用户重进（本地已有仓库），0 人在线 | 自己上次同步到的全部历史 | 最近 20 条 | 能（本地已有部分） |
| 全新用户 0 人在线进房，之后有人上线 | daemon 重试 fetch 后一次补齐全量 | 补齐后显示最近 20 条 | 能 |

- **「看不到」≠「没拿到」**：Git 仓必须完整才能过 `validClone()` 逐 commit 验签与后续 merge，**本地落盘无法裁剪**。若要「只显示加入之后的消息」，只能做 UI 层按 JOIN commit 位置截断显示，明文/密文仍在磁盘上。
- 随包 `seeds/*.bundle` 本身不带历史；上表中的历史全部来自 P2P 同步，不是种子带进来的。
- 首屏 20 条是 Qt 客户端分页行为（`messagesadapter.h:197`），不是 daemon 侧的历史截断；Android / 后续端各自实现分页，条数可能不同。

**20:49 用户拍板（产品语义已定）**：新人加入后 **UI 只显示加入之后的聊天记录**，底层 Git 全量拉取、验签、merge 一律不变。经源码核准可**零 daemon 改动 / 零新 libjami API / 零协议变更**落地：复用既有公共 API `loadSwarmUntil(accountId, convId, "", joinCommitId)`（`conversation_interface.h:100`、`conversation_module.cpp:2886-2905` 设 `options.to` 与 `includeTo=true`），Qt 侧按 `<accountId, roomId>` 持久化自己的 JOIN commit hash 作为锚点；`loadMessages()` newest→oldest 遍历遇 `options.to` 即停止（`conversation.cpp:1562-1576`）。**锚点只在首次加入时写入、退出重进不刷新**，否则重进会丢失首次加入以来的消息视图。两个缺口：`loadSwarmUntil` 未传 `nbOfCommits`（默认 0 = 不设上限）需在 Qt 侧用 `from` 分段分页；「看不到」仍**不等于**「没拿到」，本地落盘仍是全量明文，19:17 保密结论与首次进入风险弹窗不因此减弱。详见独立文档 `Jami公共聊天室需求与方案.md` §2.9 / §2.11 / §7。

**B. 技术方案（分层与落点）**

| 层 | 落点 | 改动 | 风险 |
|---|---|---|---|
| 目录层 | 随包 `share/jami/public_rooms/rooms.json` + `seeds/*.bundle` | 新增；`roomId` = 创世 commit hash；清单与 bundle 一体签名校验 | 包体积、静态快照需随版本更新 |
| 种子装载 | `src/jamidht/conversation_module.cpp` 新增 `installPublicRoomSeeds(accountId)` | 首次启动把 bundle 解到 `conversations/<roomId>/`；已有本地仓库不覆盖 | 多账号、重复启动幂等 |
| daemon 会话层 | `src/jamidht/conversationrepository.cpp:1579-1587` | mode=3 下跳过 invited 检查，其余校验全保留 | 篡改 commit 必须仍被拒 |
| libjami API | `src/jami/conversation_interface.h:57` 起；DBus `bin/dbus/cx.ring.Ring.ConfigurationManager.xml:2084` + `dbusconfigurationmanager.hpp:603`；`bin/jni/conversation.i`、`bin/nodejs/conversation.i`；Qt `qtwrapper/configurationmanager_wrap.h:1026` | 新增 `getPublicRooms` / `joinPublicConversation` / `leavePublicConversation` / `refreshPublicRooms` + `startConversationWithMode(accountId, int mode)`（18:17 裁定：**保留原单参 `startConversation(accountId)` 符号不动**，不用同名重载，详见 H 节第 5 行） | 改公共头触发三套原生库 + 三端整体重编 |
| 三端 UI | Qt 客户端新增列表页、Android 新增页面 | 中文 `.qm` 随部署 | Linux Qt 客户端从未编译，属已知阻塞项 |

**C. 关键源码依据（v2 成立的前提，均已核对）**

| 结论 | 依据 |
|---|---|
| 会话 ID = 初始 commit hash，仓库落盘 `conversations/<id>` | `conversationrepository.cpp:3027-3035` |
| 仓库纯本地打开，不需网络 | `conversationrepository.cpp:243-250` `git_repository_open` |
| 启动扫描该目录并本地构造 Conversation；缺 convInfo 会自动补登记 | `conversation_module.cpp:1995-2044`、`:2094-2119` |
| 官方已有 `file://` 本地引导通道 | `conversationrepository.cpp:3077-3083` `FETCH_FROM_LOCAL_REPOS` |
| 加入房间本需在线成员（故 v1 才想要锚点） | `conversation_module.cpp:1734`、`:1885-1899` |
| PUBLIC 骨架已存在，不是从零做 | `commit_message.h:84,329-331`；`conversationrepository.cpp:2389,4370,1553-1628` |
| **新成员 clone/fetch 拉的是全量历史，不是增量** | `conversationrepository.cpp:3748` `git_remote_fetch(remote.get(), nullptr, ...)` 传 `nullptr` refspec = 默认全分支；`:4821-4827` `validClone()` → `log({})` 全历史逐 commit 校验；F-06 `:470` 已记「加入者从成员设备 clone 全量历史」 |
| **全历史校验与「读取者何时加入」无关** | `:3214-3280` `validCommits()` 与 `:1986` `isValidUserAtCommit()` 只检查该 commit **作者**的设备证书是否在当时树中存在；`:1553-1628` `checkValidJoins()` 只管 JOIN commit 自身的文件白名单，不截断历史 |
| UI 首屏分页 20 条，但可一直上翻 | `jami-client-qt-master/src/app/messagesadapter.h:197` `loadChunkSize_ {20}`；`conversation_module.cpp:2857-2879` `loadConversation(..., n)` → `options.nbOfCommits = n`；`conversation.cpp:1547-1549` 仅 `nbOfCommits == 0` 才不设上限 |
| **单次 clone / fetch 有 256MB 硬上限** | `conversationrepository.cpp:64` `MAX_FETCH_SIZE {256 * 1024 * 1024}`；clone 路径 `:3096-3105`、fetch 路径 `:3733-3746` 两处 `transfer_progress` 超阈值即 `return -1` 中止 |

**D. 硬约束与诚实边界**

- **无常开节点时，「任何时刻实时多人在线」不可达**，这是信息论限制而非实现缺陷。v2 保证「不设准入门槛 + 随时可进可读」，不保证「随时有人陪你聊」。
- 极端冷门房间实际体验接近异步公告板；要 100% 实时必须引入中心化服务器，已被用户否决。
- 创世身份私钥打包即销毁，随包只带公钥/证书。**17:38 静态源码分析已判定「销毁后普通成员仍可写」成立，详见 I 节**；代价是此后无法再做管理员操作（改房间名 / 踢人 / 封禁），v2 为官方预置只读清单、V1.0 无管理操作，故可接受。
- 离线写 + 分叉 merge 在 v2 中**明确不做**（用户选只读），故 `merge()` 分叉签名链风险不进入 V1.0。
- 原有 Jami DHT bootstrap 节点仍用于「找人」，不存消息，不算新增节点。
- **新加入者能读到加入之前的全部历史消息**（20:07 核准）：clone/fetch 是全量 Git DAG，`validCommits()` 不按「读取者加入时间」裁剪，ADR-001 又规定历史不可改写 / 不可删除。公共房间语义下这符合直觉，但 A-1 的产品语义**尚未经用户确认**；若要求「只看加入后」，只能 UI 层截断显示，不能阻止本地落盘。这与 19:17 密码保护结论、19:22 群组加密「新成员密钥分发 / 前向保密」硬骨头同源。
- **历史无上限增长会撞 256MB 硬墙**：`MAX_FETCH_SIZE` 对 clone 与 fetch 同时生效，公共房间长期活跃（尤含文件传输记录）后，新成员首次全量拉取可能**直接失败而非降级**（fetch 中止 / `EVALIDFETCH`）。v2 无历史窗口、归档或换房机制，V1.0 必须显式接受此上限；后续若要做「定期轮换 `roomId` + 历史窗口」须单独立项。直接改 shallow clone 会破坏 `validClone()` 全历史校验，属 ADR-001 协议变更，不可当小改动。

**E. 明确不做**

| 不做的事 | 原因 |
|---|---|
| 任何锚点 / 常开节点 / 专用机器 / 额外进程 | 用户 2026-11-12 明确否决 |
| 用户自建公共房间、在线更新目录 | v2 只做官方预置只读清单，后续版本再议 |
| 0 人在线时离线写消息并上线合并 | 用户选了只读浏览，规避分叉签名链风险 |
| 房间管理操作（改名 / 踢人 / 封禁 / 投票） | 创世私钥打包即销毁后无法做管理员签名，V1.0 能力边界显式声明 |
| 好友列表导入导出 | 用户 2026-11-12 已放弃该需求 |
| 功能特化节点（锚点 / 管理员 / 种子服务器 / 中继 / 目录服务） | 22:34 用户强调「每个节点功能都一样、不能指望某个固定节点永远在线」 |

> **22:34 节点同构约束补记**：所有节点运行同一份客户端、能力相同、可随时上下线，不允许任何正确性依赖「某节点永远在线」。逐项核验（清单 / 创世仓库 / 无特权节点 / 机会性投递 / 只读态定时重估 / 本地成员观测 / 互为副本）见 `Jami公共聊天室需求与方案.md` §1.4：**F-901 新增部分满足**。上游 Jami 既有 `bootstrap.jami.net` / `turn.jami.net` 属不受本项目控制的网络层设施，V1.0 不新增、不改动，但不得对外宣称「完全无固定节点依赖」；若约束要外推到网络层，属 F-901 范围外改动且广域网首次入网无 rendezvous 时无解。

**F. 验收清单（P0 先行，未全过不得动 UI）**

| 阶段 | 验证项 | 通过标准 | 依据 |
|---|---|---|---|
| P0-0 | 零功能改动全量重编基线（18:17 新增） | 依赖 55/55、selftest 5/5 PASS、45s LAUNCH_PASS 可复现 | 编译记录 §13.57；动公共头前必须先有干净基线 |
| P0-1 | mode=3 创建 + 自由进入 | 未受邀账号可加入并读到创世信息 | `checkValidJoins` |
| P0-2 | 种子仓库本地加载 | 断网、0 在线成员下 `loadConversations()` 能加载该房间 | `conversation_module.cpp:1995` |
| P0-3 | 创世私钥销毁后可写性 | 销毁私钥后普通成员仍可正常发消息 | 静态已判定可行（I 节），仍需编译实测 |
| P0-4 | 篡改 commit 全拒 | 10 类伪造 / 越权 commit 10/10 被拒 | `:1605-1626` |
| P0-5 | 投递语义 R1 + R2 | 未加入者 / 已退出者 0 收到；单成员房间 0 次对外 `sendMsgCb_`、0 条 `notSyncedNotification_` | G 节；`:1466-1540` |
| P1 | 三端目录一致 | `rooms.json` ID 集合 100% 一致 | — |
| P2 | 旧版本兼容 | 旧二进制连新 daemon 不崩，遇 mode=3 优雅失败 | — |
| P3 | 中文与部署 | `.qm` 零英文残留，三端种子释放幂等 | — |
| P4 | Windows 单机闭环 | 真实桌面创建→自由进入→发消息→无 GNU runtime | 编译记录 §13.55-13.58 |

> **编号防混淆（18:17 团长裁定）**：上表 P0-x 是**验收项编号**，全栈研发师 `[技术研发方案]` 第 10 节的 P0-x 是**实施顺序编号**，两者不是同一套，禁止混用。映射如下；**以本表为准**，方案中的「P0-4 旧节点兼容」不得顶替本表 P0-4「篡改 commit 全拒」，旧节点兼容归入 P2。

| 实施顺序（方案第 10 节） | 对应验收编号（本表） | 说明 |
|---|---|---|
| P0-0 基线复现 | P0-0 | 一致，已补入本表 |
| P0-1 种子装载、P0-2 清单解析 | P0-2 | 两者共同支撑「断网下本地加载」 |
| P0-3 mode=3 JOIN 验签 | P0-1 + P0-3 + **P0-4** | 方案把「篡改 commit 10/10 被拒」并入验签用例，本表仍**单列 P0-4**，不得省略 |
| P0-4 旧节点兼容 | **P2**（不是 P0-4） | 双二进制互操作属兼容性验证 |
| P0-5 R1/R2 投递语义 | P0-5 | 一致 |

**G. 消息投递语义（2026-11-12 17:32 用户补充，已纳入规格）**

用户原话：「消息只发给加入房间的人，如果房间里面只有自己一个人，则不需要发送出去。」

| 规则 | 定义 | 实现落点 | 是否改代码 |
|---|---|---|---|
| R1 收件人集合 | 仅该房间仓库成员表中**有效在房**的成员（过滤 `INVITED` / `BANNED` / `LEFT`），不含未加入者、不含全网广播、不含其它房间订阅者 | `conversation_module.cpp:1482` `memberUris()`、`:1511-1514`、`:1517-1524`、`:1533-1540` | 需**收窄过滤集**：现网 `:1482` 只传 `{BANNED}`，会把已退出 / 仅被邀请者算进候选 |
| R2 单成员短路 | 「除自己外有效成员数 = 0」时不产生任何对外发送、不写 `notSyncedNotification_` 待发队列；本地 commit 仍可落盘（自己可见） | 在 `sendMessageNotification()` `:1482` 之后加守卫并提前返回 | 是，1 处守卫 |

**R2 守卫放在 `sendMessageNotification()` 而非 `sendMessage()`**：该函数是唯一的**对外（网络）通知**出口，编辑 / 删除 / 表情回应 / 文件 / 通话结束等路径也走它（`:919`、`:1094`、`:1414`、`:2127`、`:3295`），一处覆盖全部 commit 类型，不会漏。`createCommit()` 的 `announce=false`（`:1570`）会连自己其他设备一并停掉，故不采用。

> **19:06 措辞订正**：别把 `Conversation::Impl::announce()` 当成网络发送。`conversation.cpp:663-698` 的 `announce()` 走 `addToHistory(loadedHistory_, ...)`，是**本地 UI 上屏 / 历史填充**；网络侧唯一出口仍是 `sendMessageNotification()`。二者在 `Conversation::createCommit`（`conversation.cpp:2507-2527`）里的顺序是：`commitMessage()` 写本地 commit → `onCommit` → `announce()` 本地上屏 → `cb()` 回调进 `ConversationModule::Impl::createCommit` → `sendMessageNotification()` 通知 peer。R2 守卫只加在网络侧，不影响自己这边立即看到消息。

**同账号多设备**：`:1466-1472` 的 `sendMsgCb_(username_, ...)` 位于守卫之前，**保留**。它同步的是自己的其他设备，不是外部收件人，且是 Jami 多设备一致性所必需。若要求连这条也停，把守卫上移到 `:1466` 之前即可（一行改动）。

**与「0 人在线只读」互补，不冲突**：

| 除自己外有效成员数 | 当前在线其他成员 | 输入框 | 对外通知 |
|---|---|---|---|
| 0 | 0 | 禁用（只读浏览） | 无（R2 守卫短路） |
| ≥1 | 0 | 禁用（只读浏览） | 无 `sendMessage` 调用；其它 commit 路径若产生通知，也只发给成员表 |
| ≥1 | ≥1 | 允许 | 仅发给成员表（R1） |

**源码依据**：`conversationrepository.h:115` `enum class MemberRole { ADMIN, MEMBER, INVITED, BANNED, LEFT }`；`conversationrepository.cpp:473-484` `memberUris(filter, filteredRoles)` 用 `filter` 排除自己、用 `filteredRoles` 排除指定角色；`conversation_module.cpp:1474-1540` 三条发送路径全部以会话成员表为收件人，**不存在 DHT 全局广播**；`:1499-1507` 未 bootstrap 时把通知存入 `notSyncedNotification_` 等待重放 —— R2 守卫必须放在这段之前，否则单成员房间会留下永不消费的待发项。

**H. 改动清单（第 0.1 节第 2 步产物，待确认后再动源码）**

| # | 文件 | 改动 | 协议 / ABI 影响 | 验证链 |
|---|---|---|---|---|
| 1 | 新增 `share/jami/public_rooms/rooms.json` + `seeds/*.bundle` | 静态清单 + 创世 bundle，整体签名 | 无（纯数据） | P1 三端 ID 一致 |
| 2 | Windows 部署脚本 | 拷贝上述资源到部署目录 | 无 | P4 |
| 3 | `src/jamidht/conversation_module.cpp` | 新增 `installPublicRoomSeeds(accountId)`；`sendMessageNotification()` 加 R1 过滤收窄 + R2 单成员守卫 | daemon 内部行为，不改 wire format | P0-2 / P0-5 |
| 4 | `src/jamidht/conversationrepository.cpp:1579-1587` | mode=3 下跳过 invited 两段检查 | **协议变更**：按 0.3 节定兼容策略，旧节点遇 mode=3 优雅失败 | P0-1 / P0-4 |
| 5 | `src/jami/conversation_interface.h` | 新增 4 个 API + 带 mode 入口。**18:17 裁定落地形式**：既有单参 `startConversation(accountId)` 保留原符号不动，另新增独立符号 `startConversationWithMode(accountId, int mode)`（C ABI 按符号名分发、DBus 按 method+signature 分发，同名不同参在 SWIG/JNI 下易冲突）；C++ 内部仍保留 `startConversation(ConversationMode)` 重载 | **ABI**：公共头改动，三套绑定 + Qt wrap 必须同步，只追加不删改 | P2 旧二进制不崩 |
| 6 | `bin/dbus/cx.ring.Ring.ConfigurationManager.xml:2084` + `dbusconfigurationmanager.hpp:603` | 同步新增方法 | DBus 接口 | P2 |
| 7 | `bin/jni/conversation.i`、`bin/nodejs/conversation.i` | 同步新增方法 | JNI / Node 绑定 | Android / Node 编译 |
| 8 | `qtwrapper/configurationmanager_wrap.h:1026` + Qt 新列表页 | UI 入口、只读态、提示文案、中文 `.qm` | 无 | P3 / P4 |
| 9 | 新增测试用例 | P0-1 ~ P0-5 | 无 | 全链 |

**明确不改动**：任何现有 API 签名、任何既有信号 `cb_type`、`doc/adr/` 已 Accepted 决策、F-01~F-17 基线代码路径。

**I. P0-3 静态分析结论（2026-11-12 17:38，未编译实测）**

原判断「创世私钥销毁后房间可能写不了」是**想多了**。逐条核对：

| 环节 | 源码 | 结论 |
|---|---|---|
| 每个 commit 用谁的私钥签 | `:2333` `account->identity().first->sign(to_sign_vec)`；创世 commit 也是 `:876` 同一写法 | 用的是**当前账号自己的设备私钥**，与房间创建者无关 |
| 验证端怎么验签 | `:2076` `deviceCert->getPublicKey().checkSignature(...)`；`:2932` `git_commit_extract_signature` | 验的是 commit 自带 `deviceCert` 的公钥，不需要创世公钥 |
| 外来 JOIN 的证书链 | `:1617-1621` `isCertificateOfDevice` + `isDeviceOfMember(deviceCert, memberCert, uriMember)` | 只要求 deviceCert 由该用户自己的 memberCert 签发并匹配 URI，加入者自带证书链即自洽 |
| JOIN 能改哪些文件 | `:1558-1569` 白名单 = `invited/<uri>`、`members/<uri>.crt`、`devices/<dev>.crt` | 恰好是自加入所需的三个文件；mode=3 只需跳过 `:1579-1587` 的 invited 两段 |

⇒ **P0-3 从「最高风险、未实测」降级为「静态可行，待编译实测」**。风险转移到别处：创世私钥销毁后，任何需要管理员签名的操作（改房间名、踢人、封禁、投票）都无法执行。v2 房间为官方预置只读清单、V1.0 不提供管理操作，可接受；若后续要做房间治理，必须改为「不销毁私钥」或引入门限签名，届时重新评估。

**J. 团长整合裁定（2026-11-12 18:17，对全栈研发师 `[技术研发方案]` 第 11 节的回复）**

方案 8 章模板齐全、产出标记 `[技术研发方案]` 正确，选型/架构/模块/数据模型/API/多端/非功能/风险与已验证源码事实一致，未提出任何服务器或锚点，未把未实测写成已验证。对其上报的 4 个澄清点裁定如下：

| # | 澄清点 | 裁定 | 已回填位置 |
|---|---|---|---|
| 1 | 「在线人数」无全局权威计数源 | **采纳**。UI 措辞改为「**当前可见成员**」，语义为本地可观测在线成员数（best-effort）；不得显示成「全平台在线总数」 | A 节功能点表 |
| 2 | 创世私钥销毁 ⇒ 无房间管理操作 | **确认，不改方案**。V1.0 能力边界显式声明「无改名/踢人/封禁/投票」 | E 节明确不做表 + I 节 |
| 3 | `refreshPublicRooms` 是否要刷新出最新清单 | **确认为本地重解析 + 重新验签，不联网**。与 v2「禁止目录在线更新」一致，无冲突 | 本节（作为 API 语义约束） |
| 4 | GPLv3+ 合规范围 | **采纳**。衍生分发同证开源范围含新增 C++、四处绑定、三端 UI、**构建期种子生成/离线签名脚本（python3）**；`rooms.json` 与验签公钥同属分发物 | 本节（P4 门禁证据须含许可证清单） |

**新增风险裁定（团长补充，方案未标注）**：方案 R2 提出「由 `convInfo` 携带 `min_version` 做版本栅栏」。**`convInfo` 加字段属于协议/持久化结构变更，受 ADR-001 约束，不得擅自添加**。P0-4/P2 实测前先采用保守方案：新节点遇不支持 mode=3 的 peer 时返回 `ERR_MODE_UNSUPPORTED` + 本地日志，不投递该 commit；若实测证明不足，再单独立项评估版本栅栏并走 0.3 节协议变更流程。

**结论**：方案可作为 P0 实施的技術依据；功能代码仍 0 行，须用户确认 H 节改动清单并给「开工」后方可动源码。

**改动落点（我来核）**：见 H 表，已按第 5 章映射矩阵逐项列出；协议变更须按 0.3 节先定兼容策略。

**实现记录（我来回填）**：状态 / 改动文件 / 编译结果 / 验证证据路径 / 遗留问题 —— 暂空，未开工。

---

## 7. 扩展工作流检查清单（我每次执行都逐项过）

| # | 检查项 | 通过标准 | 对应源码 / 依据 |
|---|---|---|---|
| 1 | 条目字段完整、验收条件可执行 | 无空项；`待定` 项已确认 | 第 6.1 节模板 |
| 2 | 与 12 条 Accepted ADR 不冲突 | 逐条比对第 3 章；冲突先告知 | `doc/adr/INDEX.md`、`.agents/skills/adr/SKILL.md` |
| 3 | 新增 Signal 已登记 | `jami_signal.cpp` 出现 `exported_callback<新信号>` | `jami_signal.h:70` |
| 4 | 新增 API 三套绑定同步 | `bin/jni` / `bin/dbus` / `bin/nodejs` 均已改 | `jni_interface.i:205-213`、`make-swig.sh` |
| 5 | 新增 commit type 已在 `validCommits` 加分支 | 不会落入 `:3454` 的 else 被当成自由格式消息 | `conversationrepository.cpp:3292-3492` |
| 6 | `toJson()` 是否写 `body` | 新类型需要 body 时已改 `:29` 条件 | `commit_message.cpp:29` |
| 7 | 文件白名单是否同步 | `checkValidUserDiff` / `Checkpoint` / `Merge` / `Initial` | `conversationrepository.cpp:1176/1238/1902/2095` |
| 8 | 存储格式向后兼容 | msgpack 旧键保留读取；YAML 新字段有默认值 | `jami_contact.h:243-265`、`yamlparser.h:38` |
| 9 | 回调与锁 | 回调在解锁后触发；跨线程用 `runOnMainThread` / `SerializedCallbackWrapper` | `contact_list.cpp:109`、`jamiaccount.cpp:1488` |
| 10 | 改动前备份 | 被改文件保留 `.original` / `.bak` | 本项目既有约定 |
| 11 | 编译验证 | Docker 中 Android x86_64 / Linux 链编译通过 | 第 0.4 节 |
| 12 | 运行验证 | 雷电实机走通该功能的 UI 路径 + logcat 无 `SIGSEGV` / `tombstone` / `OnConversationError` | 第 0.4 节 |
| 13 | 回填 | 条目状态 + 证据路径 + 编译记录新增一节 | 第 0.1 节步骤 5 |

---

## 8. 附录

### 8.1 本文覆盖范围与诚实边界

| 项 | 说明 |
|---|---|
| 已覆盖 | daemon 的 17 个主要功能域（F-01~F-17），每个含源码落点、数据结构、伪代码、不变量、扩展点、验收 |
| 未逐功能展开 | `src/media/` 编解码细节、tracepoint、`test/agent` Guile 场景、Qt QML / Android Kotlin UI 内部实现、dhtnet（外部仓库）的 ICE/DHT 算法、name server 服务端实现、JAMS 服务端 |
| 客户端落点 | 本仓库**只有绑定生成入口**（`bin/jni` / `bin/dbus` / `bin/nodejs`），Qt 与 Android 客户端源码在另外两个仓库，涉及 UI 的改动需另行核实 |
| 行号漂移 | 行号对应 2026-11-09 本地快照；以符号名为准 |
| `NOT_FOUND` 项（未编造） | 无 `DRing::` 命名空间（现为 `libjami`）；无 `libjami.h`；`Conversation::sendMessage` 不存在（真实链路是 `ConversationModule::sendMessage → Conversation::createCommit → ConversationRepository::commitMessage`）；`ConversationSignal` 无独立已读信号；`src/connectivity/` 无 ICE/TURN 实现；插件系统无专属 ADR；`getContactDetails` 头文件声明行号未直接命中（实现在 `configurationmanager.cpp:611`）；`AccountArchive::save` 函数体位置未定位（声明 `accountarchive.h:90`） |

### 8.2 与官方 ADR 工作流的关系

官方 `.agents/skills/adr/SKILL.md` 规定：Accepted ADR 是约束，冲突要告知；新 ADR 只在「长期架构影响」时写，并同步 `doc/adr/INDEX.md`；不写局部实现细节。**本文定位为 ADR 之下的「实现规格层」**：本文不改 ADR，但第 3 章把 ADR 转成了可执行的检查项；若某条新功能确实构成架构决策，我会按官方 `ADR-NNN-short-kebab-slug.md` 模板另写 ADR 并更新 INDEX，再改代码。

### 8.3 证据索引

| 类别 | 路径 |
|---|---|
| 官方 ADR | `jami-src/jami-daemon-master/doc/adr/INDEX.md` 及 `ADR-000 ~ 011` |
| ADR 写作规范 | `jami-src/jami-daemon-master/.agents/skills/adr/SKILL.md` |
| 对外 API | `jami-src/jami-daemon-master/src/jami/*_interface.h`、`src/jami/jami.h` |
| 信号注册表 | `jami-src/jami-daemon-master/src/client/jami_signal.cpp` |
| 会话协议核心 | `src/jamidht/commit_message.h`、`conversationrepository.cpp`、`conversation.cpp`、`conversation_module.cpp` |
| 绑定生成 | `jami-src/jami-daemon-master/bin/jni/`、`bin/dbus/`、`bin/nodejs/` |
| 构建与验证现状 | `Jami三平台编译记录.md`（13.17~13.21 节）、`.workbuddy/memory/2026-11-09.md` |
| 可直接运行的验证脚本 | `jami-build/tools/pipeline_x86_64.py`、`verify_x86_64_apk.py`、`install_verify_ldplayer.py` |
