# Jami 公共聊天室（F-901）需求与技术方案

| 项目 | 内容 |
|---|---|
| 版本 | v2 规格 + 2026-11-12 20:13 新增 4 项需求（独立交付件） |
| 状态 | **方案阶段，功能代码 0 行，未获「开工」指令** |
| 主规格 | `Jami功能详细设计文档.md` 6.3 节 F-901（A~J 节）；本文为独立需求说明，新增内容待确认后回填主文档 |
| 硬约束 | **禁止任何锚点 / 常开节点 / 专用机器 / 服务器 / 额外部署进程**（用户 2026-11-12 17:17 明确否决）；**22:34 追加：网络中每个节点功能完全相同、可随时上下线，不允许功能特化节点，也不允许任何正确性依赖「某节点永远在线」**（逐项核验见 §1.4）；零服务端、零新增语言、GPLv3+ 衍生分发同证开源 |
| 结论速览 | 10 项需求中 **6 项 v2 已覆盖、4 项为本次新增**；其中「消息保密」按当前方案**无法达到强语义**，「房间别名」受权限模型限制只能做本地版，两者均需单独拍板 |
| 已拍板 | **20:49：新人加入后 UI 只显示加入之后的聊天记录**（底层 Git 全量拉取不变，纯 UI 截断，见 §2.9） |
| 待决架构方向 | 用户提出用免费公共 MQTT broker 替代 Git swarm；**与「零常开节点」硬约束冲突，评估见 §8，未获确认前不纳入方案** |

> 本文所有结论均以本机 `jami-src/jami-daemon-master` 与 `jami-client-qt-master` 源码逐条核对为准，行号见附录 A。凡标注「需实测」的条目，均未完成编译验证，不作为已验证结论。

## 0. 十项需求一次答完

| # | 需求 | 现状结论 | 采用方案 | 状态 |
|---|---|---|---|---|
| 1 | 如何发现聊天室列表 | 官方无公共房间目录 | 随包签名 `rooms.json`，本地解析 + 验签；`refreshPublicRooms` 只做本地重解析，**不联网** | v2 已定 |
| 2 | 如何进入 | `ConversationMode::PUBLIC=3` 骨架已在，但 `checkValidJoins()` 强制 invited 检查 | 释放随包创世 bundle → 本地加载 → 免邀请 JOIN；只跳过 invited 两段检查，签名/证书链全保留 | v2 已定，**协议变更** |
| 3 | 消息如何被多个人收到 | 不 fan-out 消息本体 | 本地写 Git commit → 只发 `MIME_TYPE_GIT` 便条 → 接收方各自 `git fetch` 拉取 → 验签 → merge → 上屏 | v2 已定 |
| 4 | 消息如何保密，防非成员看到 | 传输有 TLS/DTLS，但落盘明文、免邀请自由进入 | **V1.0 只能达「防网络外人」**；要达「防未加入者」必须引入群组会话密钥，属协议变更 | **本次新增，需立项** |
| 5 | 如何查看活动用户列表 | 成员表 ≠ 在线表 | 用 `getConnectedNodes()` 的直连节点映射成 URI，UI 称「**当前可见成员**」，不承诺全局在线总数 | v2 已定措辞，本次补实现细节 |
| 6 | 如何在聊天室里增加好友 | 已有 `sendTrustRequest` / `addContact` API | 成员列表点选 → 取 `uri` → 调既有 libjami API 发信任请求；**不改 daemon**，纯 UI | **本次新增，零协议风险** |
| 7 | 如何保证对话时序 | 不依赖墙上时钟 | Git DAG 父子指针定因果序 + `GIT_SORT_TOPOLOGICAL` 为主 + UI `linearizedParent` 线性化 | v2 已定，有诚实边界 |
| 8 | 如何退出 | 官方 `leave()` 写 LEAVE commit 并删自己的成员文件 | 复用 `removeConversation()`；最后一名成员直接删本地仓库，否则发通知后等待同步 | v2 已定方向，**重进需实测** |
| 9 | 如何保存对话历史 | 历史 = 本地 Git 仓，不可改写/删除 | 底层全量落盘 `conversations/<roomId>/` 不变；**UI 用 JOIN 锚点截断，只显示加入后消息**；单次拉取 256MB 上限需 UI 降级提示 | **20:49 用户已拍板**，见 §2.9 |
| 10 | 房间专属别名（默认自己的名称） | 账户名在账户级 `profile.vcf`；会话级 `profile.vcf` 是**全房间共用**且仅 ADMIN 可改 | **V1.0 本地别名**（`uri → alias` 本地映射，零协议变更）；全房可见方案需改 commit schema | **本次新增，V1.0 建议本地版** |

## 1. 需求基线

### 1.1 用户可见的产品定义

一个标准化的公共聊天室列表，每个人可以自由进入，无需邀请、无需审批。列表由官方预置、只读、随客户端版本发布，V1.0 不允许用户自建房间。所有功能必须在**不部署任何常开节点**的前提下成立。

### 1.2 为什么是二开而不是现成方案（选型结论前置）

| 路线 | 核查结论 | 判定 |
|---|---|---|
| 官方现成功能 | 官方稳定版 / beta / Qt / Web / 插件均无可用 public chat；官方论坛 2026-01-27 仍明确「Public groups are still in research and development」 | 不可用 |
| 换其它现成软件 | IRC / Matrix / XMPP MUC 有成熟公共频道，但都要求服务端；与本项目「基于 Jami 二开、零服务端」定位冲突 | 不适用 |
| 基于 Jami 源码二开 | daemon 内 `ConversationMode::PUBLIC=3`、`join()`、`checkValidJoins()` 骨架已存在，缺的是目录层、准入放行、种子装载、API 与 UI | **采用**（GPLv3+，衍生分发同证开源） |
| 从零自研 | 需重造 DHT、证书体系、Git 同步、三端客户端 | 不采用 |

### 1.3 明确不做的范围

| 不做的事 | 原因 |
|---|---|
| 任何锚点 / 常开节点 / 服务器 / 额外进程 | 用户 2026-11-12 明确否决 |
| 用户自建房间、在线动态更新目录 | V1.0 只做官方预置只读清单 |
| 0 人在线时离线写消息、上线后分叉合并 | 用户选「只读浏览」，规避分叉签名链风险 |
| 房间改名 / 踢人 / 封禁 / 投票 | 创世私钥打包即销毁后无法做管理员签名 |
| 全局在线人数、成员名册权威计数 | 无服务器即无全局权威数据源 |
| 好友列表导入导出 | 用户 2026-11-12 已放弃该需求 |
| 功能特化节点（锚点 / 管理员 / 种子服务器 / 中继 / 目录服务） | 22:34 用户强调「每个节点功能都一样」 |

### 1.4 节点同构约束：逐项核验与上游边界（22:34 用户强调后补）

> **用户原话**：「这个网络中每个节点的功能都一样，不能指望有某个固定节点永远在线。」
> 推论：所有节点运行同一份客户端、具备相同能力、可随时上下线；**不允许**功能特化节点，也**不允许**任何正确性或可用性建立在「某节点一定在线」之上。

| 判定项 | v2 方案做法 | 是否依赖固定节点 | 依据 |
|---|---|---|---|
| 房间清单 | 随包签名 `rooms.json`，本地解析 + 验签；`refreshPublicRooms` **不联网** | 否 | §2.1 |
| 房间「存在」 | 每节点首次启动从本地 `seeds/<roomId>.bundle` 释放创世仓库 | 否；反而因节点随时下线而**必须**随包 | `conversationrepository.cpp:3027-3035`；17:17 推导 |
| 创世身份 | 创世私钥打包即销毁，无 ADMIN 节点、无管理员签名操作 | 否，无特权节点 | 主规格 6.3 E 节 |
| 消息投递 | 本地写 commit → 只发 `MIME_TYPE_GIT` 便条 → 任意在线成员各自 fetch | 否，机会性 | §2.3；`conversation_module.cpp:1474-1540` |
| 0 人在线 | 只读浏览 + 输入框禁用；**定时重估**直连节点，有成员上线自动解除只读 | 否，按「随时上下线」设计 | P1-5 采纳项；§2.11 |
| 活动成员 | 本地直连节点观测，文案「当前可见成员」，不承诺全局总数 | 否 | §2.5 |
| 历史保存 | 各节点本地全量副本、互为副本，任一在线即可补 | 否 | §2.9 |
| 测试环境 | P0 同时开多个客户端实例验证收发 | **不是**常开节点，测完即关 | §4 |
| 发布方角色 | `rooms.json` 离线签名、按版本轮换 `roomId` 均在**构建期**完成 | 不是运行时节点；但存在「决定清单内容」的信任根 | §2.1、§8.5 |

**结论**：F-901 新增部分**满足节点同构约束**。17:17 之后「标准化清单 + 无常开节点 ⇒ 必须随包创世 bundle」的推论在这条更严的约束下**更加成立**——既然不能指望任何成员持续在线提供仓库，创世仓库只能随每个安装包分发；已放弃的「离线写 + 上线分叉合并」同样因为无法假设在线节奏而不做。

**必须说清的上游既有事实（不是 F-901 引入、也不受本项目控制）**：

| 上游固定设施 | 源码 | 作用 | 不可用时的后果 | 是否房间数据路径 |
|---|---|---|---|---|
| `bootstrap.jami.net` | `jamiaccount_config.h:22,34` | DHT 入网引导 | 新节点无法入网、发现不了新成员；已建连 / 已缓存路由的节点间仍可通信 | 否，只找人 |
| `config.jami.net/bootstrapList` | `jamiaccount_config.h:52` | 拉取引导节点列表 | 同上 | 否 |
| `turn.jami.net` | `jamiaccount_config.h:23,35,39`；`jamiaccount.cpp:5103-5108` | NAT 穿透中继，默认开启 | 对称 NAT 下可能无法建连 | **可能承载连接流量**，端到端 TLS 加密 |
| 局域网 `dht::PeerDiscovery` | `jamiaccount.cpp:1982-1984,2125-2126`；`jamiaccount_config.h:49-50` | mDNS 类本地发现 | 可作**局域网内**兜底 | 否；两个开关默认均为 `false` |

`loadBootstrap()` 支持 `hostname` 用 `;` 分隔多个引导节点（`jamiaccount.cpp:1810-1816`；`:2039-2040` 逐个 `dht_->bootstrap`），所以上游并非单点，但默认仍是官方域名。**若该约束要严格外推到 Jami 网络层**（连官方 bootstrap / TURN 也不可依赖），已超出 F-901 范围：需改网络层配置（多个自定义 bootstrap + 开启局域网发现 + 自建或多家 TURN / 纯直连），而**广域网首次入网在完全没有 rendezvous 节点的前提下做不到**，这是分布式系统的固有下限。V1.0 按「不新增任何节点」执行，不改动上游网络层，也不对外宣称「完全无固定节点依赖」。

## 2. 十项需求逐条方案

### 2.1 如何发现聊天室列表

用户看到的是客户端内一个固定的「公共聊天室」列表页，含房间名、描述、当前可见成员数，**断网也能打开**。清单不来自网络。

| 环节 | 做法 | 源码 / 落点 |
|---|---|---|
| 数据 | 随包 `share/jami/public_rooms/rooms.json`，含 `roomId`、名称、描述、种子文件名；整体离线签名，客户端内置验签公钥 | 新增数据文件 |
| 装载 | 首次启动 / 新增账号时验签解析，签名失败的房间在列表标注「不可用」并跳过 | 新增 `installPublicRoomSeeds(accountId)` |
| 刷新 | `refreshPublicRooms(accountId)` 只做**本地重解析 + 重新验签**，不联网、不请求任何服务器 | 新增 API |
| 一致性 | 各端 `roomId` 集合由同一份随包清单保证；`roomId` = 创世 commit hash | `conversationrepository.cpp:3027-3035` |

边界：清单是静态快照，新增房间必须随客户端版本发布；`rooms.json` 与验签公钥同属 GPLv3+ 分发物。

### 2.2 如何进入

用户点房间即进，无邀请、无审批、无密码框。

| 步骤 | 行为 | 依据 |
|---|---|---|
| ① 种子释放 | 把 `seeds/<roomId>.bundle`（仅创世 commit + 元信息，**不带历史消息**）解到 `<data_dir>/<accountId>/conversations/<roomId>/`；已有本地仓库不覆盖，多账号各自独立、重复启动幂等 | `conversation_module.cpp:1995-2044` 启动扫描；`:2094-2119` 缺 convInfo 自动补登记 |
| ② 本地加载 | `git_repository_open()` 纯本地打开，断网也能构造 Conversation 对象 | `conversationrepository.cpp:243-250` |
| ③ JOIN | `ConversationRepository::join()` 生成 JOIN commit，写入 `members/<uri>.crt` 与 `devices/<device>.crt` | `conversationrepository.cpp:4370` |
| ④ 准入放行 | mode=3 下**只跳过 `:1579-1587` 的 invited 两段检查**，`:1605-1626` 证书链 / 文件白名单 / 签名校验一律保留 | `conversationrepository.cpp:1553-1628` |
| ⑤ 拉取历史 | 有在线成员时 `cloneConversationFrom()` / `fetch` 补齐全量历史；0 人在线则只读浏览本地已有内容，输入框禁用 | `conversation_module.cpp:1703,1844,1879` |

**协议变更**：改 `checkValidJoins()` 受 ADR-001 约束，旧客户端遇 mode=3 房间必须优雅失败（`ERR_MODE_UNSUPPORTED` + 本地日志），不得崩溃。保守方案不引入 `convInfo min_version` 版本栅栏。

### 2.3 发出的消息如何被多个人收到

**Jami 不 fan-out 消息本体**。正文只写进发送者本地仓库；网络上只传一张不含正文的便条。

| 阶段 | 发生什么 | 依据 |
|---|---|---|
| 本地写 | `createCommit()` 生成带 `text/plain` JSON 的签名 commit，`announce()` **本地上屏**（不是网络发送） | `conversation.cpp:2507-2527`、`:663-698` |
| 通知 | `sendMessageNotification()` 发 `{id, commit, deviceId}` 便条，过滤 `{INVITED, BANNED, LEFT}` 后只投给有效在房成员 | `conversation_module.cpp:1474-1540`、`:1482` |
| 投递路径 | 自己账号其他设备 + 最多 2 个 `nonConnectedMembers` + DRT `peersToSyncWith()` 设备 + 移动端唤醒，收到者再扩散 | `conversation_module.cpp:1467-1472,1474-1514,1517-1524,1533-1540` |
| 接收 | `jamiaccount.cpp` 收 `MIME_TYPE_GIT` → `fetchNewCommits()` → `Conversation::pull()` → `git_remote_fetch` → 逐 commit 验签 → merge → 上屏 | `jamiaccount.cpp:4292-4308`；`conversation.cpp:2798` |
| 单成员守卫 | **房间除自己外有效成员数为 0 时不产生任何对外发送、不写 `notSyncedNotification_` 待发队列**；守卫放在 `:1482` 之后，保留自己多设备同步 | R1 / R2，设计文档 6.3 G 节 |

订正：`sync_module.cpp:288-299` 的 `onSyncData` 走设备**元数据**同步（会话列表 / convInfo / 已读状态），不是消息本体通道，两者不可混为一条链。

### 2.4 消息如何保密，防止被非聊天室里的人看到

**这一项必须先澄清需求语义，当前 v2 方案达不到强版本。**

| 要防的是谁 | 现有机制是否挡得住 | 依据 |
|---|---|---|
| 网络窃听者、中间人 | **挡得住**：TLS / DTLS 传输加密，设备证书 | `jamiaccount.cpp:3350,3430` |
| 未加入房间、但同在 DHT 上的 Jami 用户 | **基本挡得住**：便条只发给成员表有效成员，不广播；Git 仓靠 `git://` 直连对端设备取 | `conversation_module.cpp:1474-1540` |
| 「点一下就进房」的任意人 | **挡不住**：公共房间免邀请自由进入，进入即成为成员，可 `git fetch` 拿到全量明文历史 | `conversationrepository.cpp:3748,4821-4827`；ADR-001 |
| 拿到本机 / 仓库副本的人 | **挡不住**：commit message 是明文 `text/plain` JSON | `commit_message.h:93-119` |

也就是说，**「非聊天室里的人」这个集合在免邀请公共房间里本质上是空的**。要真正做到「只有房内成员能解密」，必须引入群组会话密钥，这会连带 5 个硬问题：

| 硬问题 | 说明 |
|---|---|
| 密钥分发无载体 | 无服务器 ⇒ 没有 MLS 依赖的 Delivery Service；新成员只能从某个在线成员拿密钥，0 人在线时无人可给 |
| 后向保密为零 | 拿到当前密钥的新成员能解开全量历史（历史密文本来就会被 fetch 到本地），除非按 epoch 分段且旧 epoch 密钥不下发 |
| 退出轮换 | 成员退出必须轮换密钥，否则退出者仍能读后续消息；v2 无管理员、无踢人能力 |
| 协议变更 | body 由明文改密文 ⇒ `commit_message.h` schema 与 `validCommits()` 受影响，旧节点会 `EVALIDFETCH` 拒绝，属 ADR-001 约束 |
| 与 Git 分叉冲突 | MLS 每 epoch 只允许一个 Commit 胜出，Git 允许并发 commit 再 merge，需专门设计冲突规则 |

**分档建议**（V1.0 只允许第一档，其余单独立项规格 v3）：

| 档 | 方案 | 能防到哪 | 代价 |
|---|---|---|---|
| A | **沿用现状**：TLS/DTLS + 成员表定向投递 + commit 签名防篡改；产品说明明确写「公共房间不是私密空间」 | 网络外人、未加入的路人 | 零开发；已加入者可读全部明文历史 |
| B | 轻量共享会话密钥：每房间一个 AES 密钥，用 `getPublicKey().encrypt(aesKey)` 分发给成员，`PrivateKey::decrypt` 解开；成员变动轮换 | 后来者读不到旧 epoch、外人拿到仓库也解不开 | 密钥分发/轮换/版本三套机制；body 改密文 = 协议变更；混合加密积木本项目已 6/6 PASS（`toolchain/dl/crypto_export_selftest.cpp:60,69`） |
| C | MLS RFC 9420（Cisco `mlspp` / OpenMLS）：TreeKEM + epoch，前向保密与后向保密完整 | 标准级群组加密 | 无 Delivery Service、与 Git 分叉冲突，工作量最大 |

订正用户此前设想：「发消息的人用**私钥加密**、其他人用**公钥解密**」方向反了——公钥公开，人人可解，那叫**数字签名**（Jami commit 已有，`:2333` 签名 / `:2076` 验签）。正确方向是「公钥加密 → 对应私钥解密」，或用共享会话密钥对称加密一次。另：账户归档密码只保护 `.jac` 备份文件，不是聊天消息密码。

### 2.5 如何查看聊天室活动用户列表

**不能叫「在线人数」，只能叫「当前可见成员」**——没有服务器就没有全局权威计数源。

| 数据源 | 含义 | 能否当在线列表用 |
|---|---|---|
| `getMembers()` / `memberUris()` | 成员表快照 `{"uri","role"}`，含离线、可含 LEFT | **不能**，是「曾加入过的人」而非「现在在线」 |
| `peersToSyncWith()` | `swarmManager_->getConnectedNodes()` + `gitSocketList_` = **当前直连设备节点** | **可以**，这是本机能观测到的活动集合 |
| `swarmManager_->isConnected()` | 本机是否连上 DRT | 用于判断自身单成员态 |
| deviceId → uri | `uriFromDevice()`、证书 `issuer` 或已验证 lease 的 `issuer_id` | 用于把设备节点显示成人名 |

| 依据 | 位置 |
|---|---|
| `peersToSyncWith()` 合并直连节点与 git socket | `conversation.cpp:2389-2397` |
| 设备 → 账户 URI 的映射 | `conversation.cpp:2400-2416` |
| 成员角色枚举与 `toMap()` | `conversationrepository.h:115,127-149` |
| 现网收件人过滤仅排除 `{BANNED}`，需收窄为 `{INVITED, BANNED, LEFT}` | `conversation_module.cpp:1482` |

边界：这是 best-effort 的本地视图，不同机器看到的集合可能不同；公共房间成员表会持续累积，UI 应显示活动子集而不是全量名册。

### 2.6 如何在聊天室里增加好友

**这是本次唯一零协议风险的新增需求**：Jami 已有完整的好友/信任请求 API，聊天室里加好友只是给它一个 UI 入口。

| 步骤 | 做法 | 依据 |
|---|---|---|
| ① 取目标 | 成员列表点选某人，从 `getMembers()` 拿 `uri`（账户 URI / InfoHash） | `conversation.cpp:2371`；`conversationrepository.h:127-149` |
| ② 发请求 | 调 `sendTrustRequest(accountId, to, payload)`；内部 `dht::InfoHash(to)` 校验后 `putEncrypted` 到 `inbox:<deviceId>` | `configurationmanager.cpp:660`；`account_manager.cpp:711-750` |
| ③ 落库 | 本地先 `addContact(uri, confirmed=false, convId)` 记为待确认，并 `syncDevices()` 同步自己其他设备 | `account_manager.cpp:726-728` |
| ④ 生效 | **对方接受后才成为好友**，不是单方面生效；对方离线时请求留在 DHT inbox，上线收取 | `account_manager.cpp:742-750` |
| ⑤ 已有关系 | 已是好友 / 已拉黑 / 已发过请求时，按钮置灰或改文案；拉黑态可用 `getContactDetails()` 判定 | `configurationmanager.cpp:611` |

不需要改 daemon、不需要新增 libjami API、不需要动四套绑定，只需在新列表页加菜单项并接 Qt 既有调用。边界：加好友是 1:1 信任关系，与房间成员身份互不影响；退出房间不会删除好友。

### 2.7 如何保证对话时序

**不用墙上时钟定序**，主序是 Git commit 父子指针构成的因果图（DAG）。

| 层次 | 靠什么 | 时钟不同步是否影响 | 保证强度 |
|---|---|---|---|
| 因果序（谁回复谁） | parent 指针，`behind()` 等价 `git log HEAD..from` | 完全不影响 | 强保证，所有节点一致 |
| 并发序（两人同时发） | `GIT_SORT_TOPOLOGICAL + GIT_SORT_TIME`，拓扑序为主、时间为辅 | 不决定依据，但次级排序键会扰动 | **不保证全序**，不同机器可能显示不同 |
| 显示时间 | `git_commit_time()` → `convCommit.timestamp` → `message["timestamp"]` | 直接影响：机器快一年就显示「未来消息」 | 只是标签，不参与定序 |
| 证书 / 验签 | 官方显式 `GNUTLS_VERIFY_DISABLE_TIME_CHECKS` 规避误判 | 不受影响 | 强保证 |
| 合并后重排 | `relinearizeAfterMerge()` 回放 `SwarmMessageUpdated` | 无关 | 尽力收敛，非全序一致 |

依据：`conversationrepository.cpp:2513,2570`（排序）、`:113`（禁用时间检查）、`:843,914`（`git_signature_new`）、`:2864,2910`（时间字段）；`conversation.cpp:1767-1777`（`linearizedParent` / `emplace_front`）、`:1842-1939`（重排与注释「commits sharing a timestamp are not guaranteed to keep their relative order once the graph changes」）。

诚实边界：**并发且时间戳相同的两条消息，相对顺序不保证**。时钟不同步不会让回复跑到原消息前面，但两人同时发的话在不同机器上可能一前一后。v2 的「0 人在线只读」「不做离线写 + 上线分叉合并」恰好压小了分叉窗口，但 JOIN commit 仍可能并发。若将来上 MLS，其「每 epoch 只能一个 Commit 胜出」与 Git 分叉 merge 直接冲突。

### 2.8 如何退出

复用官方 `removeConversation()`，不另造退出协议。

| 环节 | 行为 | 依据 |
|---|---|---|
| 写 LEAVE | `Conversation::leave()` → `ConversationRepository::leave()` 删除自己的 `members/<uri>.crt`、`admins/`、CRLs、devices 文件，生成 LEAVE commit | `conversation.cpp:2951`；`conversationrepository.cpp:4375-4415` |
| 有他人在线 | `hasMembers` 为真时把 LEAVE commit 通知其他成员，然后**等待其他 peer 同步后才真正删本地数据**，避免自己又收到消息 | `conversation_module.cpp:1374-1428` |
| 自己是最后一人 | 直接 `removeRepositoryImpl(conv, true)` 删本地仓库，不发通知 | `conversation_module.cpp:1425-1427` |
| 其他人视角 | 该成员角色变 `LEFT`，被 `{INVITED, BANNED, LEFT}` 过滤出收件人集合 | `conversationrepository.cpp:2812` |

两个必须写进验收的边界：

1. **「退出后不残留会话」只在本地成立**。其他成员仍持有含该用户历史消息的全量副本；重新进房会再次 fetch 回全部历史，退出并不等于删除聊天记录（ADR-001 历史不可改写）。
2. **退出后重新自由进入未实测**。mode=3 跳过 invited 检查后，曾 `LEFT` 的成员重新 JOIN 时 `members/` 文件由无到有，逻辑上可行；但 `initMembers()` 是否会把角色恢复为 `MEMBER`、`checkValidJoins()` 是否放行，必须列为 P0 编译实测项，静态分析不能替代。

### 2.9 如何保存对话历史

历史就是本地那个签名 Git 仓库，**不是数据库、不是服务端日志**。

| 项目 | 结论 | 依据 |
|---|---|---|
| 存放位置 | `<data_dir>/<accountId>/conversations/<roomId>/`，`roomId` = 创世 commit hash | `conversationrepository.cpp:3027-3035` |
| 内容 | 全量 commit DAG，含**加入之前**的全部消息；消息正文是明文 JSON `{"body":...,"type":"text/plain"}` | `commit_message.h:93-119` |
| 新成员拿到多少 | clone/fetch 传 `nullptr` refspec = 全分支全历史；`validClone()` 逐 commit 验签且**不按读取者加入时间裁剪** | `conversationrepository.cpp:3748,4821-4827,3214-3280` |
| 首屏显示 | **V1.0 已定：只显示 JOIN 锚点之后的消息**；原 Qt 分页 `loadChunkSize_ = 20` 作为锚点内的分页基础 | `messagesadapter.h:197`；`conversation_module.cpp:2857-2879`；`conversation.cpp:1547-1549` |
| 能否删除单条 | 不能。ADR-001 规定历史不可改写/删除，「删除」是空 body 的 edit commit | 设计文档 `:103,782` |
| 四种进入场景 | 全新+0 人在线 = 空房间；全新+有人在线 = 拉全量；老用户重进 = 看本地那份；0 人在线进房后有人上线 = 重试 fetch 一次补齐 | 20:07 核准，见主文档 A-1 节 |
| 硬上限 | 单次 clone / fetch **256MB**，超出直接中止失败而非降级；**V1.0 必须做 UI 降级提示** | `conversationrepository.cpp:64,3096-3105,3733-3746` |

> **术语消歧（21:21 补）**：本文「锚点」有两种完全不同的含义，勿混。
> ① **常驻锚点节点**（§0 硬约束、§8 所禁）= 为把「至少 1 个在线成员」从碰运气变成保证，而**额外部署、预先指定、7×24 常开**的一台普通 Jami 客户端；它是房间成员之一，不中转消息、不做鉴权，本地持一份完整仓库副本。属规格 v1 第 ③ 项，已于 17:17 被用户明确否决作废。
> ② **JOIN 锚点**（本节及以下）= 本地持久化的一个 **commit hash 字符串**，用来标记 UI 从哪条消息开始显示，**不是节点、不是进程、不联网**。
> ③ 「**任意在线成员**」（谁在线谁提供历史副本）是 Jami 原生 P2P：无额外部署、不承诺常开、不承担引导职责，**不属于锚点**，v2 正是依赖它补历史。硬约束禁的是 ①，不是 ③；判定维度是「是否为可用性而额外部署并常开」，不是「固定还是任意」。

**用户 20:49 拍板：新人加入后，UI 只显示加入之后的聊天记录。** 已核准这条可以**零 daemon 改动、零新 libjami API、零协议变更**落地：

| 环节 | 做法 | 依据 |
|---|---|---|
| 锚点 | 首次 JOIN 成功后，把「自己的 JOIN commit hash」按 `<accountId, roomId>` 持久化在本地（与 §2.10 本地别名同一处存储） | `conversationrepository.cpp:4370` 生成 JOIN commit |
| 截断加载 | Qt 侧由 `loadConversationMessages(convId, 20)` 改调**既有公共 API** `loadSwarmUntil(accountId, convId, from="", to=joinCommitId)` | `conversation_interface.h:100`；`conversation_interface.cpp:204-211`；`conversation_module.cpp:2886-2905` 设 `options.to` 与 `includeTo=true` |
| 截断语义 | `loadMessages()` 按 newest→oldest 遍历，遇到 `id == options.to` 即停止；`includeTo=true` 会保留 JOIN commit 本身（UI 显示为「加入了房间」） | `conversation.cpp:1562-1576`；`conversationrepository.h:70-77` |
| 退出重进 | **锚点只在首次加入时写入，重进不刷新**，否则用户重进会丢失自己首次加入以来的消息视图 | 20:49 补入约束 |
| 底层不变 | `git fetch` 仍拉全量历史，签名校验 / merge / 断点续传全部不动 | `conversationrepository.cpp:3748,4821-4827` |

两个必须写进验收的缺口：① `loadSwarmUntil` 未传 `nbOfCommits`（默认 0 = 不设上限），会**一次加载 JOIN 之后的全部消息**，V1.0 需在 Qt 侧用 `from` 分段循环或给该 API 加 count 参数（后者属 ABI 变更，优先前者）；② 「看不到」**不等于**「没拿到」——Git 仓必须完整才能过 `validClone()` 与后续 merge，本地落盘仍是全量明文，因此 §2.4 的保密结论与 P1‑1 风险提示**不因 UI 截断而缓解**。

历史长期增长仍会撞 256MB 硬墙：V1.0 必须实现「fetch 超限时 UI 明确提示『该公共房间历史仓库过大，暂时无法加入』，不得静默空白」，并对**本地运行时**仓库体积做监控预警；构建期无法预估（种子只有创世 commit，不含运行时历史）。改 shallow clone 会破坏全历史验签，属协议变更。

### 2.10 房间专属别名（默认自己的名称）

用户措辞中「可以运行设置」按「可以**自行**设置」理解：进入每个房间可单独设一个显示名，未设置时显示自己的默认名称。

| 核查项 | 结论 | 依据 |
|---|---|---|
| 默认名称来源 | 账户级 `profile.vcf` 的 `FORMATTED_NAME`，或名称服务注册名 `registeredName_` | `jamiaccount.cpp:1481,4203,4077-4078` |
| 会话级 `profile.vcf` 是什么 | **整个房间共用的 vCard**（房间名 / 描述 / 头像），不是成员级字段 | `commit_message.h:399-409`；`conversationrepository.cpp:4960,5003` |
| 谁能改它 | `updateProfilePermLvl_` 默认 `ADMIN`，`updateInfos()` 校验 `member.role <= ADMIN` 否则 `EUNAUTHORIZED` | `conversationrepository.cpp:491,4938,1813-1852` |
| v2 房间还有 admin 吗 | **没有**。创世私钥打包即销毁，V1.0 无管理操作 | 设计文档 6.3 I 节 |

⇒ **官方 `updateInfos()` 路线在 v2 房间走不通**，且就算能改也是改全房间名而不是自己的别名。三个可行档位：

| 档 | 做法 | 全房可见 | 协议变更 | 结论 |
|---|---|---|---|---|
| 1 | **本地别名**：`<accountId, roomId, alias>` 存本地 convInfo / 配置，UI 渲染时用 `uri → alias` 覆盖显示名；默认值取账户 `FORMATTED_NAME` | 否，只对自己可见 | 无 | **V1.0 采用** |
| 2 | 每条文本 commit 的 body 加可选 `alias` 字段，发消息时带上 | 是 | 低-中（commit schema 扩展，旧节点兼容性需实测） | V1.1 候选；历史消息不会回填别名 |
| 3 | 新增 `application/set-alias` commit + `aliases/<uri>` 文件 | 是 | **高**，`checkValidProfileUpdate` / 文件白名单 / `validCommits()` 全要改，旧节点会拒；且无 admin 时别名可被冒名 | 不做 |

V1.0 本地别名的产品边界必须写进 UI：别名只影响自己这台设备上的显示，别人看到的仍是账户默认名；同一账号多设备之间不同步（除非另做设备同步，属后续需求）。`CommitMessage` 里已有 `displayName` 字段（`commit_message.h:108`），但那是文件传输的文件名，不能拿来当昵称用。

## 2.11 评审采纳后追加的协议 / 产品约束（20:49）

下表是 §7 裁定中「采纳」项对 §2.1~§2.10 的增量要求，逐节就地修改成本过高，统一在此登记并指向落地位置；实施时以本表为准。

| # | 约束 | 对应章节 | 具体落地 | 验收 |
|---|---|---|---|---|
| 1 | 种子**逐个独立验签** | §2.1 | 每个 `seeds/<roomId>.bundle` 独立签名，`rooms.json` 携带各 bundle 摘要与签名；装载前逐个验签，任一失败即跳过该房间并标注不可用 | P0‑2：篡改任一 bundle 拒绝加载 |
| 2 | 升级时种子摘要比对 | §2.1 | 已有本地仓库不覆盖；比对随包种子摘要，变更时日志告警 + UI 提示「官方房间种子签名发生变更，请确认客户端完整性」 | P2 |
| 3 | mode=3 **新增 `banned/` 守卫** | §2.2 | `checkValidJoins()` 原本不查 banned（BANNED 检查在 `checkValidAdd()` `:1539-1547`）；mode=3 分支必须显式检查 parent tree 中不存在 `banned/members/<uri>.crt`，证书链 / 签名 / 文件白名单全保留 | P0‑1：BANNED 账号加入 mode=3 应被拒 |
| 4 | 旧节点被动收通知 | §2.2 | 不造 peer 版本协商（Jami 无此机制）；改为验证 `onSyncData` → `cloneConversation()` 失败后 `EVALIDCLONE` + 整体回滚，不残留半截仓库、不崩溃 | P0‑1 补充用例 |
| 5 | 只读态自动恢复 | §2.2 | 定时重估 `peersToSyncWith()` 外部节点集合，出现外部成员自动解除只读；不得只在进房瞬间判断一次；状态标签「只读模式：未检测到其他房间参与者」 | P1‑5 网络抖动用例 |
| 6 | R1/R2 区分本机设备 | §2.3 | 守卫条件显式写为「外部有效成员数 == 0」，自己账号其他设备（`conversation_module.cpp:1467-1472`）**无论如何保留投递**；收件人是成员 URI 集合而非设备集合 | P0‑5 分叉 + 多设备用例 |
| 7 | 首次进入风险弹窗 | §2.4 | 首次进入任意公共房间弹出且不可仅埋在文档：「本公共聊天室为分布式 P2P 公开空间，任何人可自由加入；所有消息明文会被所有加入者完整同步保存；消息无法撤回删除，请勿发送敏感隐私信息」；产品须接受「恶意节点长期抓取归档」风险 | P1‑1 UI 检查 |
| 8 | 成员列表按 URI 聚合 | §2.5 | 同账户多设备合并为单条（hover 标注多台设备在线）；数量文案「本机可见：N」；小字说明「仅代表当前设备能直连到的参与者，不等于房间全部参与者」 | P1‑2 |
| 9 | 加好友文案 | §2.6 | 提示「从公共聊天室发送的是普通陌生人请求，同在房间不等于对方信任你」；记录 V1 无法阻止骚扰性请求，V1.1 评估「拒绝来自公共房间的请求」选项 | P1‑3 |
| 10 | 本地别名 UI 提示 | §2.10 | 设置处提示「该别名仅在当前设备本地生效，其他设备 / 其他用户看到的名字仍为你的账户名称」 | P1‑4 |
| 11 | 256MB UI 降级 | §2.9 | fetch 超限时明确提示、禁止静默空白；运行时本地仓库体积监控预警 | P0‑4 |
| 12 | 退出重进完整路径 | §2.8 | 已有本地仓库 ⇒ 跳过种子释放、直接 JOIN 重建成员文件；仓库已删 ⇒ 重新释放创世 bundle 后 JOIN | P0‑6 两子场景 |

## 3. 改动清单（待确认后再动源码）

| # | 文件 / 模块 | 改动 | 协议 / ABI | 对应需求 |
|---|---|---|---|---|
| 1 | 新增 `share/jami/public_rooms/rooms.json` + `seeds/*.bundle` | 静态清单 + 创世 bundle，整体签名 | 无（纯数据） | 1, 2 |
| 2 | `conversation_module.cpp` | `installPublicRoomSeeds()`；`sendMessageNotification()` 收窄成员过滤 + 单成员守卫；`getPublicRooms` / `joinPublicConversation` / `leavePublicConversation` / `refreshPublicRooms` | daemon 内部 + 新增 API | 1, 2, 3, 8 |
| 3 | `conversationrepository.cpp:1579-1587` | mode=3 跳过 invited 两段检查 | **协议变更**，旧节点优雅失败 | 2 |
| 4 | `conversation_interface.h` | 新增 4 个 API + 独立符号 `startConversationWithMode(accountId, int mode)`；**保留原单参 `startConversation(accountId)` 不动** | **ABI**：公共头 + DBus / JNI / Nodejs / Qt 四套绑定同步 | 1, 2, 8 |
| 5 | `bin/dbus/cx.ring.Ring.ConfigurationManager.xml`、`dbusconfigurationmanager.hpp`、`bin/jni/conversation.i`、`bin/nodejs/conversation.i` | 同步新增方法 | 四套绑定 | 1, 2, 8 |
| 6 | `qtwrapper/configurationmanager_wrap.h` + Qt 新列表页 / 房间页 | 列表、只读态、当前可见成员、加好友按钮、本地别名设置、中文 `.qm` | 无 | 1, 5, 6, 10 |
| 7 | 新增 `getActivePublicRoomMembers(accountId, roomId)`（Qt 侧可由 `peersToSyncWith()` 组合，若复用既有 API 则无需新增 libjami 符号） | 直连节点 → URI 映射 | 待定，优先复用 | 5 |
| 8 | 本地别名持久化（Qt `QSettings` 或 convInfo 扩展，取改动小者） | `uri → alias` 本地映射 | 无 | 10 |
| 9 | Windows 部署脚本 + 构建期种子生成 / 离线签名脚本（python3） | 资源打包、许可证清单 | 无 | 全部 |
| 10 | 新增测试用例 | P0-0 ~ P0-6 + 新增 4 项 + §2.11 验收 | 无 | 全部 |
| 11 | `conversationrepository.cpp:checkValidJoins()` | mode=3 分支**新增** `banned/` 守卫（原本不查），其余校验全保留 | 协议变更，与第 3 行合并评审 | 2, 8 |
| 12 | Qt 房间页 | 复用既有 `loadSwarmUntil()` 实现 JOIN 锚点截断，持久化锚点（首次写入、重进不刷新），`from` 分段分页 | **零 daemon / 零新 API** | 9（用户已拍板） |
| 13 | Qt 房间页 | 首次进入风险弹窗、成员列表 URI 聚合、只读态自动轮询恢复、加好友 / 本地别名文案、256MB 超限降级提示 | 无 | 4, 5, 6, 10 |
| 14 | 构建期种子生成 / 离线签名脚本（python3） | **每个 bundle 独立签名**，`rooms.json` 写入摘要与签名；补脚本单测保证可复现 | 无 | 1, 2 |

第 12 行是实现「UI 只显加入后」的**唯一**改动面：经核准 `loadSwarmUntil` 已是 libjami 公共 API（`conversation_interface.h:100`），故不需要新增接口、不改四套绑定、不动 Git 底层。§2.11 的 12 条约束按「对应章节」并入上述各行，不另起独立模块。

**明确不改动**：任何现有 API 签名、任何既有信号 `cb_type`、`doc/adr/` 已 Accepted 决策、F-01~F-17 基线代码路径、commit message 结构（V1.0 不上加密、不上全房别名）。

## 4. 验收清单（P0 未全过不得进入 UI 开发）

| 阶段 | 验证项 | 通过标准 |
|---|---|---|
| P0-0 | 零功能改动全量重编基线 | 现有 Windows 客户端编译 + 启动通过，作为对照基线 |
| P0-1 | mode=3 创建 + 自由进入 | 未受邀账号可加入并读到创世信息；篡改 commit 全拒 |
| P0-2 | 种子仓库本地加载 | 断网、0 在线成员下 `loadConversations()` 能加载该房间，重复启动幂等 |
| P0-3 | 创世私钥销毁后可写性 | 销毁私钥后普通成员仍可正常发消息（静态已判定可行，仍需编译实测） |
| P0-4 | 篡改 commit 全拒 | 伪造 / 改 body 的 commit 触发 `EVALIDFETCH` |
| P0-5 | R1 / R2 投递语义 | 单成员时无任何对外发送、无待发队列残留；收件人集合 ⊆ 成员表 − `{INVITED, BANNED, LEFT}` |
| P0-6 | **退出后重新进入**（本次新增） | `LEFT` 成员点房间能再次 JOIN、角色恢复为有效成员、可正常收发 |
| P1 | 三端清单一致性 | Win / Linux / Android 同版本 `roomId` 集合一致（Linux Qt 客户端从未编译，属 V1.1 独立任务） |
| P2 | 旧节点兼容 + ABI | 旧二进制遇 mode=3 优雅失败不崩；四套绑定可编译 |
| P3 | UI 功能 | 列表、当前可见成员、加好友、本地别名、中文 `.qm` 零英文残留 |
| P4 | 分发与合规 | 三端种子释放幂等；GPLv3+ 许可证清单含新增 C++ / 绑定 / UI / 构建期脚本；种子生成 / 验签脚本单测可复现 |
| P0‑1a | BANNED 守卫 | 被标记 BANNED 的账号尝试加入 mode=3 房间应被拒（20:49 新增） |
| P0‑1b | 旧节点被动同步 | 旧客户端被动收到 mode=3 会话元数据后触发 `EVALIDCLONE` 并整体回滚，不产生半截仓库、不崩溃（不依赖版本协商） |
| P0‑2a | 种子验签粒度 | 篡改 `seeds/` 下任意单个 bundle，客户端拒绝加载该房间并在列表标注不可用 |
| P0‑4a | 256MB 降级 | fetch 超限时 UI 给出明确文案，不是空白页、不是静默失败 |
| P0‑5a | 分叉 + 多设备 | 本地成员视图短暂分叉时，单成员守卫不误伤自己账号其他设备同步；外部无有效成员时不产生对外发送 |
| P0‑6a/b | 退出重进两子场景 | A：远端仍有成员、本地仓库保留 ⇒ 跳过种子释放直接 JOIN；B：自己是最后一人、仓库已删 ⇒ 重新释放 bundle 后 JOIN |
| P1‑a | UI 约束 | 首次进入风险弹窗、成员按 URI 聚合显示「本机可见：N」、网络抖动后只读态自动解除、加好友 / 本地别名文案 |

## 5. 风险与诚实边界

| 风险 | 影响 | 缓解 / 状态 |
|---|---|---|
| 无常开节点 ⇒ 不保证任何时刻多人在线 | 冷门房间接近异步公告板，这是信息论限制不是实现缺陷 | 已写入产品说明，用户已知 |
| 「消息保密」按 V1.0 达不到强语义 | 任何点进房的人可读全量明文历史 | **待用户拍板**：接受 A 档并明示，或立项 B / C 档走规格 v3 |
| 房间别名 V1.0 只对自己可见 | 与「专属别名」的直觉预期可能有落差 | **待用户拍板**：是否接受 V1.0 本地版，或要求全房可见（触发协议评估） |
| 256MB 单次拉取上限 | 长期活跃房间新成员首次拉取直接失败，老用户正常、新用户进不来 | **不再「显式接受」**：V1.0 必做 UI 降级提示 + 运行时本地仓库体积监控；运营侧按版本轮换 `roomId`。评审建议的「CI 构建期体积预警」**不可行**（种子只有创世 commit，构建期无运行时历史），已驳回 |
| 并发消息无全序保证 | 不同机器显示顺序可能不同 | 官方机制，不可消除；UI 不承诺严格全序 |
| 退出后重进角色恢复 | 未实测，可能导致「退出后加不回来」 | 已列 P0-6 |
| `convInfo min_version` 版本栅栏 | 属协议/持久化结构变更 | 保守方案先用 `ERR_MODE_UNSUPPORTED` + 本地日志，不引入 |
| Linux Qt 客户端从未编译 | 三端一致性门禁无法全跑 | 列 V1.1 独立任务，发布说明标注「公共聊天室实验性」 |
| UI 截断不缓解保密风险 | 新人虽然看不到加入前的消息，本地磁盘仍有全量明文 | §2.4 保密结论与 P1‑1 风险弹窗**不因 20:49 拍板而减弱**，产品文案不得暗示「新人看不到 = 新人拿不到」 |
| `loadSwarmUntil` 无数量上限 | 一次加载 JOIN 之后全部消息，房间活跃时首屏卡顿 | Qt 侧用 `from` 分段循环分页；给 API 加 count 属 ABI 变更，不优先 |
| 静态清单无法动态更新 | 新增官方房间必须发完整客户端新版本 | V1.1 评估「带签名的静态 CDN 清单」（静态文件，不引入常开节点） |
| MQTT 架构方向未定 | 与「零常开节点」硬约束冲突，评估见 §8 | **未获用户明确放宽约束前不纳入方案、不动代码** |

## 6. 待用户拍板

| # | 待定事项 | 可选结论 |
|---|---|---|
| 1 | 消息保密档位 | A 现状 + 明示「非私密空间」 / B 轻量共享会话密钥（规格 v3） / C MLS（规格 v3） |
| 2 | 历史可见策略 | ✅ **20:49 已拍板：底层全量拉取不变，UI 只显加入后消息**（§2.9）。剩余子项：退出重进锚点不刷新已按默认确定；是否额外做定期轮换 `roomId` 仍待定 |
| 3 | 房间别名 | V1.0 本地版（推荐） / 要求全房可见（触发 commit schema 或新 commit type 评估） |
| 4 | UI 文案 | 确认「当前可见成员」而非「在线人数」 |
| 5 | 开工授权 | 确认第 3 节 **14 项**改动清单与 §2.11 的 12 条约束后给「开工」，先跑 P0-0 全量重编基线 |
| 6 | **架构方向（本轮新增，优先级最高）** | 是否正式放宽「禁止任何锚点 / 常开节点 / 服务器 / 额外部署进程」硬约束，改用 MQTT broker？**默认不放宽**，继续 Git swarm；若放宽，F-901 需重开规格 v3 与第三方服务合规评估（§8） |

在以上 1、3、6 项未拍板前，**不改任何源码**；本文只锁定需求与方案，F-901 仍为「待实现（方案已定，待开工）」。第 6 项若选「放宽」，§1~§6 的整套方案需重新评估，不是局部替换。

## 7. 外部评审裁定（2026-11-12 20:49）

评审输入：第三方《Jami F‑901 公共聊天室方案评审报告》（P0 5 项 / P1 5 项 / P2 5 项 + 架构建议 + 开工清单）。原则：**逐条回源码验证后决定采纳、部分采纳或驳回**，不接受未经核对的事实断言。

| 评审项 | 裁定 | 源码核准 / 理由 | 落地位置 |
|---|---|---|---|
| P0‑1 mode=3 保留 BANNED | **采纳，但订正事实** | 评审称「`checkValidJoins()` 除 invited 还有 BANNED 判断」**不成立**：BANNED 检查在 `checkValidAdd()`（`conversationrepository.cpp:1539-1547`），`checkValidJoins()`（`:1553-1628`）**本来就没有** banned 检查。真实风险更大：免邀请 JOIN 需要**新增** `banned/` 守卫，而不是「别意外跳过」 | 改动清单 2、P0‑1 用例 |
| P0‑1 旧节点被动收通知防护 | **部分采纳，驳回其补救手段** | `onSyncData`（`conversation_module.cpp:3022-3077`）确实会 `startConversation(convInfo)` + `cloneConversation()` 被动克隆，被动路径无 mode 校验，**缺口成立**。但全局搜 `min_version` / `capabilities` 0 命中，Jami **没有 peer 版本/能力协商机制**，评审建议的「对端能力过滤」需自造协议。且 `cloneConversation()` 校验失败有整体回滚（`:3177-3199`），「仓库损坏」已被官方机制覆盖 | 改为 P0 测试「旧节点被动克隆 mode=3 后 `EVALIDCLONE` 并整体回滚、不残留半截仓库」，不引入版本栅栏 |
| P0‑2 种子签名粒度 | **采纳** | 原文「整体离线签名」确实模糊。改为**每个 `*.bundle` 独立签名**，`rooms.json` 携带各 bundle 摘要与签名，装载前逐个验签 | §2.1、改动清单 1、P0‑2 用例 |
| P0‑2 升级时二次校验 | **采纳，降级 P2** | 合理但非开工阻断。已有本地仓库不覆盖（保护用户历史），仅比对随包种子摘要，变更时日志告警 + UI 提示，不强制覆盖 | 风险表 |
| P0‑3 R1/R2 分叉误判 | **部分采纳** | 分叉导致成员视图不一致确有可能（JOIN/LEAVE 可并发）。但评审把「成员表有效成员数」与「设备数」混为一谈；R1 收件人是**成员 URI 集合**，不是设备集合。文档 §2.3 与主规格 G 节**已写明**守卫置于 `:1482` 之后、保留 `:1467-1472` 自己账号其他设备同步，评审「改动清单没写清」不属实 | 改动清单 2 增补显式约束 + P0‑5 分叉用例 |
| P0‑4 256MB UI 降级 | **采纳** | 静默失败是真实产品缺陷，必须有明确提示 | §2.9、改动清单 6、P0‑4 用例 |
| P0‑4 CI 构建期体积预警 | **驳回，方案不可行** | 构建期种子**只有创世 commit，不含运行时历史**，CI 无法预估仓库膨胀。改为**运行时**本地仓库体积监控（接近阈值 UI 预警）+ 运营侧房间轮换 | §5 风险表 |
| P0‑5 退出‑重进路径 | **采纳，补充完整** | 评审补的两个场景有价值。已存在本地仓库时**跳过种子释放、直接走 JOIN commit 重建成员文件**；仓库已删时才重新释放 bundle | §2.8、P0‑6 两子场景 |
| P1‑1 首次进入风险弹窗 | **采纳，订正一处** | 恶意节点长期归档、消息不可撤回、无全局封禁、需 UI 明示，均成立。但「BANNED 仅本地视图生效」不准：`banned/` 是仓库内目录、会同步；准确说法是 **v2 无 admin ⇒ 无人能签发 BAN commit**，且攻击者可新建账户重进 | §2.4 UI 文案、改动清单 6 |
| P1‑2 成员列表聚合 | **采纳** | NAT/分区下列表差异、同账户多设备重复条目，是真缺口 | §2.5：按 URI 聚合单条、显示「本机可见：N」、加小字说明 |
| P1‑3 加好友骚扰 | **采纳** | 同房间不构成任何信任、被拉黑后请求仍会投递到 DHT inbox，成立 | §2.6 UI 文案；V1.1 评估「拒绝来自公共房间的请求」选项 |
| P1‑4 别名多设备不同步 | **采纳 UI 提示** | 文档 §2.10 已写明该边界，评审的「未披露」不属实；补 UI 提示即可 | §2.10 |
| P1‑5 只读态自动恢复 | **采纳，真缺口** | 文档只写「输入框禁用」，确实没写状态轮询与自动解除 | §2.2：定时重估 `peersToSyncWith()`，出现外部节点自动转可写 |
| P2‑1 ~ P2‑5 | **全部采纳记录** | 四套绑定 CI 一致性检查、Linux Qt 标记实验性、静态清单动态更新候选（签名静态文件仍无常开节点）、并发无序 UI 提示、构建脚本单测 | §5 风险表 / V1.1 待办 |
| 架构建议 1/2/3 | **采纳** | 与我方裁定一致；其中第 3 条「UI 支持仅显示 JOIN 之后消息、不动 Git 底层」已被用户 20:49 拍板 | §2.9 落地 |
| 开工清单定级 | **部分采纳** | 「全部 P0 闭环才开工」方向对，但 P0‑2 升级校验应降 P2、P0‑4 的 CI 预警不可行；调整后门禁见 §4 | §4 |

**评审未覆盖、本轮补入的两个约束**：① mode=3 需**新增** `banned/` 守卫（不是「保留」，因为该函数原本不查）；② 「UI 只显加入后」的 JOIN 锚点在**退出重进后不得刷新**，否则用户重进会丢失自己首次加入以来的消息视图。

## 8. MQTT 替代方案评估（用户 2026-11-12 20:49 提出）

用户提出：Git 仓库当会话存储「感觉很低效」，考虑改用网络上现成的免费 MQTT 服务器，加入聊天室 = 订阅一个主题，并要求防 broker 恶意监听或篡改。

**结论：不推荐替换；若坚持，必须先正式放宽「禁止任何常开节点 / 服务器」这条硬约束，且该方案仍无法满足「保存对话历史」这一项需求。**

### 8.1 与十项需求的能力对照

| 需求 | 当前 Git swarm | 公共免费 MQTT broker |
|---|---|---|
| 发现房间列表 | 随包签名 `rooms.json`，断网可用 | 需另配；topic 可被任何人用 `#` 枚举 |
| 免邀请进入 | JOIN commit + 成员文件 | **订阅即进入，最简单** |
| 消息一对多 | 便条 + 各自 fetch | **broker 原生 fan-out，唯一明显优势** |
| 保存对话历史 | Git 仓全量、可验签、断点续传 | **不提供**。retained 只留每 topic 最后一条，公共 broker 明示 retained 可能丢失 |
| 离线消息 | 上线后 fetch 自动补齐 | 需持久会话 + QoS1/2；公共 broker 会话有效期上限约 30 分钟、无 SLA |
| 对话时序 | Git DAG 因果序，不依赖时钟 | 多发布者并发**无因果序**；QoS1 可能重复，QoS2 只保证单连接恰好一次 |
| 防篡改 | 每 commit 设备签名 + 全链校验 | 无，需自建签名；签名也防不住 broker 丢消息 / 重放 |
| 活动成员列表 | 直连节点 best-effort | broker 才知道真实连接数，等于把成员信息交给第三方 |
| 退出 | LEAVE commit | 退订即退出，干净 |
| 零服务端硬约束 | **满足** | **违背**：broker 是常开中心节点，且是第三方运营 |

### 8.2 「防 broker 恶意监听或篡改」能防到哪一层

| 层 | 能否防住 | 说明 |
|---|---|---|
| 消息内容被 broker 读取 | **能**（靠端到端加密） | 每房间对称密钥 + 每条签名即可。但密钥分发回到老问题：免邀请自由加入 + 无常开节点 ⇒ 新成员从哪拿密钥？放 `rooms.json` 则所有拿到安装包的人（含 broker 运营方）都能拿，等于没加密；靠在线成员分发则 0 人在线时拿不到 |
| 消息内容被 broker 改写 | **能**（靠签名） | 用设备私钥签 payload，接收方验签 |
| broker 选择性丢消息 / 重放 / 注入刷屏 / 直接关停 | **不能** | 签名防不住「压根不发给你」。要防丢包需消息序号 + 哈希链 + 多方交叉补缺，这又要一个 P2P 覆盖网络 |
| 元数据（谁订阅哪个 topic、IP、时间、频率、消息大小） | **不能** | 公共 broker 匿名且任何人可订阅 `#`；防这个需要 cover traffic 或匿名网络，代价极高 |

### 8.3 免费公共 broker 的工程现实（2026-11-12 检索核对）

| 限制 | 实际值 | 对聊天室的影响 |
|---|---|---|
| 匿名接入 / 任何人可订阅 `#` | 普遍如此 | 房间名即密码，topic 可枚举，陌生人可直接订阅窃听 |
| 单条消息上限 | 典型 64 KB | 图片 / 文件消息基本不可用 |
| 发布速率 | 典型 10 条/秒或 100 条/分钟 | 活跃房间很快触发配额 |
| 订阅数 / 连接数 | 典型 10 订阅每客户端、单 IP 5 连接 | 多房间场景受限 |
| 会话有效期 / retained | 上限约 30 分钟；retained 可能丢失 | **无法承载历史** |
| 服务条款 | 无 SLA，明确「仅供学习/测试，不用于生产」「completely wrong for personal data」 | 引入不可控的第三方单点故障与合规风险 |

补充：TLS 只保护「客户端↔broker」链路，**broker 是 TLS 终结点**，自身仍能看到明文。所以「用 TLS 就不用担心免费服务器监听」是错的；只有端到端加密才解决内容，且如上表仍不解决元数据与丢包。

### 8.4 「Git 低效」这个判断：对的部分与不对的部分

对的部分：每条消息一个 commit（含树对象与设备签名）、全量历史同步、256MB 硬上限、首次 clone 开销、逐 commit 验签 CPU。高频公共房间确实不经济，用户的直觉没错。

不对的部分：Git 在这套架构里承担的不只是「存储格式」，而是**零服务端前提下的一致性模型**——去重、因果序、离线可用、断点续传、防篡改、任意成员互为副本、可验证历史，全部由同一个签名 DAG 免费提供。MQTT pub/sub 是纯在线管道，这七项一项都不提供。换 MQTT 不是换存储层，是把「可验证的复制状态机」换成「依赖 broker 的实时管道」，历史、时序、离线、防篡改要全部另做；做到最后通常又长出一个带签名的 append-only 日志，那正是 Git 在做的事。而且免费 broker 的 retained 只有最后一条，**直接满足不了需求清单第 9 项**。

### 8.5 想优化效率、但不改架构的三条路

| 路径 | 做法 | 代价 |
|---|---|---|
| 房间轮换 + 历史窗口（**推荐**） | 按客户端版本发布新 `roomId`，把单仓体积压在 256MB 以下；配合已拍板的「UI 只显加入后」，新用户体验也变轻 | 运营侧需跟踪仓库体积；旧房间转只读 |
| 通知层调参 | `MIME_TYPE_GIT` 便条已很轻，正文只传一次；V1.1 评估按房间规模调整「最多 2 个 `nonConnectedMembers`」等参数 | 低，不改协议 |
| 本地签名 append-only 日志替代 Git 对象 | 去掉树对象 / pack 开销，只同步增量 | **重写 `conversationrepository.cpp` + 协议级变更**，旧节点全不兼容，工作量远大于 F-901 本身 |

### 8.6 若仍要坚持 MQTT：三种折中及代价

| 折中 | 说明 | 代价 |
|---|---|---|
| MQTT 只当实时通知通道，Git 继续做历史层 | 用 broker 替代便条扩散 | Jami 已有 DRT 扩散，收益有限，却引入第三方运行时依赖，性价比差 |
| 房间分两类：轻量实时房（MQTT、不保历史）+ 存档房（swarm） | 产品上区分用途 | 两套系统、两套 UI、维护成本翻倍 |
| 自托管 broker + E2EE | 可控性与保密性最好 | **就是 17:17 明确否决的常开服务器**，需重新立项 |

**broker 与 v1 常驻锚点不是同一量级**：v1 锚点只是房间里一个普通成员，挂了其他成员之间仍可互发消息、历史也仍有多份本地副本；broker 是所有消息的**必经中心**，挂了全员停摆，且它终止 TLS、能读明文、能选择性丢消息或直接关停。所以把 broker 类比成「锚点」实际是**低估**了中心化程度——它违反「零常开节点」硬约束的程度更重，而不是「换一台机器当锚点」就能等价替换。

**必须确认的决策点**：`禁止任何锚点 / 常开节点 / 服务器 / 额外部署进程`是用户 2026-11-12 17:17 明确否决后写入项目长期约束的硬要求。采用任何 MQTT broker 都与之直接冲突。在用户明确「正式放宽该约束」之前，本文档不把 MQTT 纳入方案，也不改动任何代码；若确认放宽，需重新走规格 v3 与合规评估（第三方服务条款、用户数据跨境、GPLv3+ 分发物边界）。

## 附录 A 源码索引

| 主题 | 位置 |
|---|---|
| 会话 ID / 仓库落盘 / 本地打开 | `conversationrepository.cpp:3027-3035,243-250` |
| 启动扫描 / convInfo 补登记 | `conversation_module.cpp:1995-2044,2094-2119` |
| 本地 `file://` 引导通道 | `conversationrepository.cpp:3077-3083` |
| `ConversationMode` / CommitMessage / update-profile | `commit_message.h:84,93-119,399-409` |
| `checkValidJoins` / 证书链 | `conversationrepository.cpp:1553-1628,1605-1626` |
| clone / 全量 fetch / 全历史验签 | `conversationrepository.cpp:3177,3748,4821-4827,3214-3280` |
| 256MB 拉取上限 | `conversationrepository.cpp:64,3096-3105,3733-3746` |
| 消息通知与四条投递路径 | `conversation_module.cpp:1467-1540` |
| `MIME_TYPE_GIT` → `fetchNewCommits` | `jamiaccount.cpp:4292-4308`；`conversation_module.cpp:742,3242` |
| 本地上屏 / pull / 线性化 | `conversation.cpp:663-698,2798,1767-1777,1842-1939` |
| 分页加载 | `messagesadapter.h:197`；`conversation_module.cpp:2857-2879`；`conversation.cpp:1537-1633` |
| 排序 / 时间字段 / 禁用时间检查 | `conversationrepository.cpp:2513,2570,843,914,2864,2910,113` |
| 成员表 / 直连节点 / URI 映射 | `conversation.cpp:2371,2389-2397,2400-2416`；`conversationrepository.h:115,127-149` |
| leave / 移除会话 | `conversation.cpp:2951`；`conversationrepository.cpp:4375-4415,2812`；`conversation_module.cpp:1374-1428` |
| 加好友 / 信任请求 | `configurationmanager.cpp:597,611,660`；`account_manager.cpp:711-750` |
| 账户默认名 | `jamiaccount.cpp:1481,4203,4077-4078` |
| 会话 profile 权限 | `conversationrepository.cpp:491,1813-1852,4938,4960,5003` |
| TLS / DTLS | `jamiaccount.cpp:3350,3430` |
| 混合加密积木自测 | `toolchain/dl/crypto_export_selftest.cpp:60,69`（6/6 PASS） |
| BANNED 检查实际位置（**不在** `checkValidJoins` 内） | `conversationrepository.cpp:1539-1547`（属 `checkValidAdd`）；`checkValidJoins` 为 `:1553-1628` |
| 被动同步克隆路径 / 校验失败整体回滚 | `conversation_module.cpp:3022-3077`；`conversationrepository.cpp:3177-3199` |
| JOIN 锚点截断可复用既有 API | `conversation_interface.h:100`、`conversation_interface.cpp:204-211`、`conversation_module.cpp:2886-2905`、`conversationrepository.h:70-77`、`conversation.cpp:1562-1576` |

## 附录 B 术语

| 术语 | 含义 |
|---|---|
| swarm | Jami 的会话实现，一个由 libgit2 管理的签名 Git 仓库 |
| roomId | 公共房间 ID，等于创世 commit 的 hash |
| 创世 bundle | 随包分发的 Git bundle，只含初始 commit 与元信息，不含历史消息 |
| `MIME_TYPE_GIT` | 不含正文的轻量通知便条 `{id, commit, deviceId}`，触发接收方 fetch |
| DRT | Distributed Routing Table，Jami 用于在 swarm 内找直连节点 |
| ADR-001 | 本项目架构决策记录：会话 = 签名 Git 仓库，改 commit 类型 / 仓库布局 / 校验规则均属协议变更 |
| R1 / R2 | 投递语义：只发给房间成员表有效成员 / 除自己外无成员时不产生对外发送 |
| 当前可见成员 | 本机能观测到的直连节点集合，非全局在线人数 |
