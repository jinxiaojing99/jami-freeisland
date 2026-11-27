# Jami 导入节点加入网络 · 实施方案

| 项 | 内容 |
|---|---|
| 文档状态 | **方案待用户拍板，未开工** |
| 编写日期 | 2026-11-14 |
| 需求来源 | 用户 2026-11-14 12:38：「Jami 里面是怎么增加通过导入一个节点来加入网络的」 |
| 核准范围 | `jami-daemon`（本地源码树）、`opendht 4.4.1`、`dhtnet`、`jami-client-qt`、`jami-client-android` |
| 关联文档 | `Jami节点发现与连接建立机制.md`、`Jami接入韧性与IPv6优先方案.md` §10 / §11 / §12、`Jami公共聊天室需求与方案.md` |
| 代码状态 | **本轮只出方案，未改任何功能代码**；F-901 仍「方案已定、待开工，功能代码 0 行」，F-902 仍挂起 |
| 功能编号建议 | **F-903 · 引导节点导入**（独立特性，不并入 F-901） |

---

## 0. 结论前置

| # | 结论 | 依据 |
|---|---|---|
| 1 | **这个功能 Jami 基本已经内置，最小实现是零代码**。把 `Account.hostname` 改成已知节点的 `IP:port`，重启注册即可入网 | `jamiaccount.cpp:1807-1817,2039-2040`；`account_config.cpp:64,96,137,163` |
| 2 | **三端都已有现成输入框**：Qt 高级 OpenDHT 设置的 Bootstrap 文本框、Android 高级设置的「初始服务器」 | `AdvancedOpenDHTSettings.qml:178-189`；`account_advanced_prefs.xml:28-33`；`ConfigKey.kt:48` |
| 3 | daemon 的入网入口**只有一个**：`loadBootstrap()` 按分号切分 `hostname`，逐条 `dht_->bootstrap()` | `jamiaccount.cpp:1807-1817,2039-2040`；全树仅此一处调用 |
| 4 | 改 `hostname` **不用重启 daemon**：`setAccountDetails` 会自动 `doUnregister → fromMap → loadConfig → saveConfig → doRegister`，重跑 `loadBootstrap()` | `manager.cpp:2743-2768`；`account.h:125-133`；`jamiaccount.cpp:1949-1995` |
| 5 | **真正要开发的是「导入体验 + 校验 + 生效反馈」，不是入网机制**：文件/二维码解析、地址规范化、验签、失败回落、状态提示 | 见 §4 |
| 6 | 导入**不消除冷启动结构性依赖**，只是把入口从 `bootstrap.jami.net` 换成用户手里的一个可达节点 | `Jami节点发现与连接建立机制.md` §6；`Jami接入韧性与IPv6优先方案.md` §10.1 |

**一句话**：不要去改 OpenDHT，也不要新造协议。守住现成的 `Account.hostname` 这个口子，在客户端补一层「导入 → 校验 → 写回 → 反馈」即可。daemon 侧 V1.0 建议 **0 行改动**。

---

## 1. 需求定义与边界

| 维度 | 定义 |
|---|---|
| 用户目标 | 官方 `jami.net` 引导 / 名称服务被封锁时，用户能通过导入一个（或几个）已知在线节点的地址，让本机加入 DHT 网络并正常收发消息 |
| 典型输入 | 好友给的一个 `[IPv6]:port` 或 `IPv4:port`、一份带签名的节点清单文件、一张二维码、一条 `jami://node/...` 链接 |
| 成功判据 | 本机 DHT 状态从 Disconnected 变为 Connected，能查到 / 被查到，能完成 `connectDevice` 并走 Git swarm 收发消息 |
| 失败判据 | 导入的地址 ping 不通、验签失败、条目过期、对端是 proxy 模式、双方均对称 NAT 且无可用 TURN |

| # | 硬边界（不可越过） | 原因 |
|---|---|---|
| 1 | 导入项**只产出候选地址**，不承载任何聊天内容、不参与 ICE 建连 | 消息永远走同一条 Git swarm + 设备签名 + TLS 通道，不出现第二套消息平面 |
| 2 | **知道 IP 不能绕过 DHT 直连**：ICE offer 固定经 `putEncrypted(peer_request + deviceId)` 走 DHT | `dhtnet/connectionmanager.cpp:745-788` |
| 3 | 不新增常开服务器、不新增进程、不引入功能特化节点 | 项目 17:17 / 22:34 硬约束；节点必须同构、可随时上下线 |
| 4 | 不指望任何导入节点永远在线，拿不到就换下一条 / 回落 L0 | 与「动态引导候选池」口径一致（`Jami接入韧性与IPv6优先方案.md` §11.2） |
| 5 | 不得对外宣称「完全无固定节点依赖」 | 上游 `bootstrap.jami.net` / `turn.jami.net` 仍存在，V1.0 不新增不改动 |

---

## 2. 现状：从配置到入网的完整链路

### 2.1 八步链路（全部现成，零代码）

| 步 | 环节 | 具体动作 | 源码依据 |
|---|---|---|---|
| 1 | UI 写入 | Qt `SettingsMaterialTextEdit` 的 `onEditingFinished: CurrentAccount.hostname = ...`；Android `EditTextPreference` key `Account.hostname` | `AdvancedOpenDHTSettings.qml:178-189`；`account_advanced_prefs.xml:28-33` |
| 2 | API 入口 | `libjami::setAccountDetails(accountId, details)` → `Manager::setAccountDetails` | `configurationmanager.cpp:220-222`；`configurationmanager_interface.h:47` |
| 3 | 触发重注册 | 先比对全量 map（相同则直接返回）→ `doUnregister()` → `setAccountDetails()` → `isUsable()` 则 `doRegister()` → 发 `AccountDetailsChanged` 信号 | `manager.cpp:2743-2768` |
| 4 | 配置读写 | `AccountConfig::toMap/fromMap` 用 `Account.hostname`；YAML 序列化键 `hostname`；`parseString` 只在 key 存在时赋值（**传部分 map 不会清空其他配置**） | `account_config.cpp:64,96,137,163`；`account_config.h:155-160`；`account_schema.h:52` |
| 5 | 读取清单 | `JamiAccount::loadBootstrap()`：`std::string_view stream(config().hostname)`，`getline(stream, node_addr, ';')` 逐条装入 vector | `jamiaccount.cpp:1807-1817`；`jamiaccount.h:800` |
| 6 | 注入 DHT | `doRegister_()` 里 `for (const auto& bootstrap : loadBootstrap()) dht_->bootstrap(bootstrap);` | `jamiaccount.cpp:2039-2040` |
| 7 | OpenDHT 接手 | `DhtRunner::bootstrap(hostService)` → `splitPort()` 拆 host/service → `pending_ops_prio` 投递 `dht.addBootstrap(host, service)` → `bootstrap_nodes.emplace_back` + `startBootstrap()` | `dhtrunner.cpp:1010-1017`；`dht.h:91-95,411` |
| 8 | 真正入网 | `Dht::bootstrap()` 遍历 `bootstrap_nodes` → `resolve(host, service)` → 端口 0 补 `DHT_DEFAULT_PORT` → `pingNode(ip)` → 收到回复后 `insertNode` → `FIND_NODE` 迭代扩表 → `isGood` 淘汰 → `saveState()` 写 `dhtstate` | `dht.cpp:2271-2305,2914-2962`；`jamiaccount.cpp:2116` |

**关键点**：步骤 7 的 `addBootstrap` 只把地址记进 `bootstrap_nodes` 并启动定时任务，并**不校验对方身份**；步骤 8 靠一个 UDP ping 拿到对端 NodeId 才算入网。`bootstrap()` 会按 `bootstrap_period` 指数退避周期重试（`dht.cpp:2304-2305`），所以「导入时对方恰好不在线」不会永久失败，后续上线会自动连上。

### 2.2 八条核准事实（决定实现细节）

| # | 事实 | 依据 | 对实施的影响 |
|---|---|---|---|
| 1 | `hostname` 支持**分号分隔多个节点**，逐条 bootstrap | `jamiaccount.cpp:1811-1813` | 一次可导入一整个候选池，不必只填一个 |
| 2 | 默认值为 `bootstrap.jami.net`；`fromMap` 中若为空会**强制回填**默认值 | `jamiaccount_config.h:22,34`；`jamiaccount_config.cpp:183-184` | 「清空导入」必须显式写回默认值，不能写空串 |
| 3 | IPv6 必须写 `[v6]:port`；裸 v6 因多冒号被 `splitPort` 判为「无端口」 | `opendht/utils.cpp:40-59`；`dhtrunner.cpp:1013` | 导入时必须做地址规范化，否则 v6 静默失效 |
| 4 | 无端口时 OpenDHT 自动补 `DHT_DEFAULT_PORT` | `dht.cpp:2290-2291`；`dhtrunner.cpp:1039-1040` | 建议 UI 仍强制要求显式端口，避免歧义 |
| 5 | `bootstrap(SockAddr)` 只 ping 取 NodeId，**不要求预先知道 NodeId** | `dhtrunner.cpp:1028-1052` | 裸 IP 可用，但也意味着无法证明对方身份 |
| 6 | `setAccountDetails` 会 `doUnregister → doRegister`；`doRegister_` 先 `dht_->join()` 再 `dht_->run()`，`DhtRunner::run` 会 `make_unique<SecureDht>` 重建实例 | `jamiaccount.cpp:1963-1995`；`dhtrunner.cpp:188` | 改 hostname 立即生效、不用重启；旧 `bootstrap_nodes` 不会无限累积 |
| 7 | 入网后 `FIND_NODE` 自动扩表、`isGood()` 淘汰下线节点、`dhtstate` 自动落盘 / 重启恢复 | `dht.cpp:2083-2089,2484-2516,2914-2962`；`node.cpp:34-39` | 「导入一个节点后长期自给自足」是既有行为，无需开发 |
| 8 | `Account.bootstrapListUrl` 是**死键**：只在 `config.h:52` 声明、`fromMap()` 解析，`toMap()` 不输出、daemon 无任何消费点 | `jamiaccount_config.h:52`；`jamiaccount_config.cpp:185`；全树检索 | 别把远程清单挂在这个键上就以为生效；V1.0 不用它 |

### 2.3 副作用必须写进 UI 文案

| 副作用 | 说明 | 缓解 |
|---|---|---|
| 短暂断线重连 | `setAccountDetails` 触发 `doUnregister()` + `doRegister()`，进行中的通话 / 传输可能受影响 | 导入前弹确认；建议空闲时操作；提示「将重新连接网络」 |
| 覆盖原有引导 | 写回 `hostname` 是全量替换该字符串，原 `bootstrap.jami.net` 会被换掉 | 提供「恢复默认」按钮，并把原值存一份便于回滚 |
| 本机成为潜在引导 | 完整 DHT 节点入网后可被别人当作 bootstrap；Android proxy 模式不行 | 默认不主动广播地址；`allowPublicIncoming` 保持 false |

---

## 3. 四个实施档位对比

| 档 | 做法 | daemon 改动 | 客户端改动 | 三端一致性 | 能解决什么 | 裁定 |
|---|---|---|---|---|---|---|
| **档 0** | 直接在现有 Bootstrap 输入框手填 `IP:port` 或多个用分号分隔 | **0 行** | **0 行** | 现成 | 立即可用、验证链路、应急 | ✅ **今天就可用；作为 P0 基线** |
| **档 1** | 一键导入清单文件 / 二维码 / 链接 → 校验 → 写回 `hostname` | **0 行** | 导入 UI + 解析器 + 验签 + 状态反馈 | Qt / Android 各一套；Linux 走配置 | 官方域名全封时的主用路径，且防伪造清单 | ✅ **推荐 V1.0** |
| **档 2** | 导入带 `NodeId` 的 `NodeExport` 条目，走 `bootstrap(vector<NodeExport>)` 直接 `insertNode` | 新增 API + SWIG 三套绑定 | 调用新 API | 需三端同步发版 | 抗 IP 漂移、可校验节点身份 | ⏸ V1.1 评估（见 §5） |
| **档 3** | 远程「签名引导清单 over HTTPS」，daemon 定时拉取 + 缓存 | 补 `bootstrapListUrl` 消费逻辑，复用 `loadCachedUrl()` | 少量配置 UI | 需 daemon 发版 | 不认识任何在线节点时拿到一批候选 | ⏸ V1.2 评估（见 §6） |

**为什么不建议一上来做档 2 / 档 3**：档 0 与档 1 已经覆盖「官方域名被封」这个核心场景，且完全不动 daemon，风险与发版成本最低；档 2 要动 SWIG 三套绑定（DBus / JNI / Node），档 3 要动 daemon 且引入「清单由谁维护」的运维问题。

---

## 4. 推荐 V1.0 规格（档 1）

### 4.1 导入数据格式

清单为**明文 JSON**，旁边附一个对规范化字节的签名文件（或内嵌 `sig` 字段）。**签名不是加密**：节点地址本来就是公开信息，签名只证明「这份文件由发布方签发、一个字节未被改过」。

```json
{
  "version": 1,
  "generatedAt": "2026-11-14T10:00:00Z",
  "expiresAt": "2026-12-14T10:00:00Z",
  "issuer": "friend-or-org",
  "nodes": [
    { "addr": "[2001:db8::1234]:4222", "deviceId": "...", "fingerprint": "...", "ts": 1763107200, "ttl": 86400 },
    { "addr": "203.0.113.9:4222",     "deviceId": "...", "fingerprint": "...", "ts": 1763107200, "ttl": 86400 }
  ]
}
```

| 字段 | 必填 | 作用 | 备注 |
|---|---|---|---|
| `version` | 是 | 格式版本，便于后续扩展 | 不认识的版本直接拒绝，不做猜测兼容 |
| `generatedAt` / `expiresAt` | 是 | 防重放、防拿半年前的旧清单 | 过期**整份拒绝**，不逐条降级 |
| `issuer` | 否 | 显示来源、便于用户判断可信度 | UI 必须显示，避免用户盲点确认 |
| `nodes[].addr` | 是 | 最终写进 `hostname` 的内容 | 支持域名、`IPv4:port`、`[IPv6]:port` |
| `nodes[].deviceId` / `fingerprint` | 建议 | 预留给档 2 的身份校验；V1.0 至少展示给用户 | V1.0 的 `bootstrap(SockAddr)` 不消费它们 |
| `nodes[].ts` / `ttl` | 是 | 单条时效，配合 `expiresAt` 双保险 | 超 TTL 的条目丢弃并提示 |
| `sig` | 是 | 对规范化 JSON 字节的签名 | 离线签名密钥 + 随包公钥，与 F-901 `rooms.json` 同一信任根 |

### 4.2 地址规范化与校验规则

| # | 规则 | 实现要点 | 失败处理 |
|---|---|---|---|
| 1 | 语法合法 | 复用 `splitPort()` 同款逻辑：首字符 `[` 走方括号分支，其余要求**有且仅有一个冒号** | 该条丢弃并在预览中标红 |
| 2 | IPv6 强制 `[v6]:port` | 裸 v6 自动补方括号并提示用户确认；不允许只写地址不写端口 | 拒绝或规范化后重显 |
| 3 | 端口范围 1–65535 | 0 虽会被 OpenDHT 补默认端口，但 UI 层不接受 | 拒绝 |
| 4 | 去重 + 条数上限 | 建议上限 16 条，按 IPv6 GUA 优先、公网 v4 次之、私网最后排序 | 截断并提示 |
| 5 | 私网 / 公网标记 | 私网地址仅在同一局域网场景可用，UI 显式标注「仅局域网」 | 不拒绝，但必须提示 |
| 6 | 时效 | `expiresAt` 未过、每条 `ts + ttl` 未过 | **整份拒绝**，不静默启用 |
| 7 | 验签 | 离线签名密钥 + 随包公钥；规范化字节后再验 | 验签失败整份拒绝，回落 L0 + 本地缓存 |
| 8 | 探活（可选） | 导入后由 OpenDHT 自己的 `pingNode` 完成，客户端不额外发探测包 | 不在线只是暂时失败，靠周期重试自动连上 |

**安全红线**：任何校验失败都必须「拒绝并回落」，**绝不静默启用未验签的地址**——否则攻击者可用一份假清单把用户逼到伪造节点上，形成日蚀攻击（`Jami接入韧性与IPv6优先方案.md` §7 P1-6）。

### 4.3 写回与生效（推荐调用路径）

| 步 | 动作 | 说明 |
|---|---|---|
| 1 | `details = libjami::getAccountDetails(accountId)` | 取全量配置 |
| 2 | `details["Account.hostname"] = join(validated_addrs, ";")` | 分号拼接；至少保留 2–3 条，避免单点失效 |
| 3 | `libjami::setAccountDetails(accountId, details)` | daemon 自动 `doUnregister → doRegister`，重跑 `loadBootstrap()` |
| 4 | 监听 `AccountDetailsChanged` / 注册状态回调 | 显示「重新连接中 → 已入网 / 失败」 |
| 5 | 保存原 `hostname` 到客户端本地，供「恢复默认」一键回滚 | 回滚同样走步骤 3 |

也可以直接复用客户端现有属性通道：Qt `CurrentAccount.hostname = ...`（内部由 `accountmodel.cpp:1058,354` 拼全量 map 调 `setAccountDetails`）；Android `Account.hostname`（`Account.kt:374-376`）同理。**这样连调用代码都不用新写**。

补充：`parseString` 只在 key 存在时赋值（`account_config.h:155-160`），所以理论上只传单键 map 也不会清空其他配置；但为与 Qt / Android 现有行为一致、并保证信号同步，仍建议走全量 map 或现有属性通道。

### 4.4 UI 流程与文案

| 步骤 | 界面行为 | 必需文案要点 |
|---|---|---|
| 1 | 设置 → 高级 → OpenDHT 区新增「导入引导节点」按钮（与现有 Bootstrap 输入框并列） | 「导入仅提供入网入口，不改变消息传输路径」 |
| 2 | 选择文件 / 扫码 / 粘贴链接 | 显示 `issuer`、生成时间、有效期、条数 |
| 3 | 预览节点列表（地址、地址族、公网/私网标记、校验结果） | 「清单为明文，签名仅用于验证来源」 |
| 4 | 用户确认 | 「将重新连接网络，进行中的通话可能中断」 |
| 5 | 写回并监听状态 | 「已入网 / 正在重试 / 全部不可达，已回落原有引导」 |
| 6 | 常驻操作 | 「查看当前引导」「恢复默认（bootstrap.jami.net）」「清空并重新导入」 |

### 4.5 三端落点

| 端 | 现成基础 | V1.0 改动 | 备注 |
|---|---|---|---|
| Windows / Linux Qt | `AdvancedOpenDHTSettings.qml:178-189` 的 `lineEditBootstrap`；`CurrentAccount.hostname` | 同页新增导入按钮、预览对话框、状态提示、恢复默认；`accountmodel` 一般无需改 | **Linux Qt 客户端从未编译**，V1.1 单列验证 |
| Android | `account_advanced_prefs.xml:28-33` 的 `Account.hostname`；`ConfigKey.kt:48`；`Account.kt:374-376` | 高级设置新增导入入口、文件/扫码解析、验签、状态文案 | Android 默认 **DHT proxy 模式**，可作「导入方」，但不能当别人的引导节点 |
| Linux daemon（无 UI） | `Account.hostname` YAML 键 `hostname`（`account_config.cpp:64,96`） | 0 行；由管理员直接改配置或通过 DBus `setAccountDetails` | 与现有配置管理方式一致 |

---

## 5. 档 2：NodeExport 注入（V1.1 可选增强）

| 维度 | `bootstrap(hostService)`（V1.0 用） | `bootstrap(vector<NodeExport>)`（档 2） |
|---|---|---|
| 输入 | 只有地址字符串 | `{InfoHash id, SockAddr addr}` |
| 行为 | 解析 → `pingNode` → 拿回 NodeId 后才入表 | **直接 `insertNode`，不 ping** |
| 身份校验 | 无 | 可比对清单里的 `deviceId` / 指纹 |
| 依据 | `dhtrunner.cpp:1010-1017`；`dht.h:91-95` | `dhtrunner.cpp:1082-1092`；`node_export.h:14-33` |
| 现成范式 | — | 局域网组播 `bootstrap(v.nodeId, addr)`（`dhtrunner.cpp:258-265`）；`NodeInsertionPack{net, nodeId, port}` |
| 代价 | 0 | 需新增 daemon API + **SWIG 三套绑定（DBus / JNI / Node）** + 三端调用 + 签名/TTL 外层包装 |

**风险**：`NodeExport` 结构本身**无签名、无 TTL、无打分**（`node_export.h:14-33`），且 `insertNode` 不做探活——恶意清单可以一次灌满假节点污染路由表。若要上档 2，必须先补齐「`ts + TTL + 设备签名 + 指纹`、≥3 独立来源交叉、注入数量上限、验签失败只回落」这套防护。**建议先跑完 V1.0 实测再评估必要性**。

---

## 6. 档 3：远程签名清单（V1.2 可选增强）

| 环节 | 现成 / 要补 | 依据 |
|---|---|---|
| 配置键 `Account.bootstrapListUrl` | 已存在但**是死键** | `jamiaccount_config.h:52`；`jamiaccount_config.cpp:185` |
| HTTPS 拉取 + 本地缓存 + 失败回落 | **现成可复用** `loadCachedUrl(url, cachePath, TTL, cb)`，目前只服务 `proxyListUrl` | `jamiaccount.cpp:3187-3210,3260` |
| 验签 | 复用 `dht::crypto`，公钥随安装包分发 | 与 F-901 `rooms.json` 同一信任根 |
| 写回 `hostname` → 入网 | 现成 | `jamiaccount.cpp:1807-1817,2039-2040` |

工作量是「补一段消费逻辑 + 验签 + 客户端配置 UI」，不是造系统。但它引入「清单由谁持续维护、IPv6 前缀轮换后谁更新」的运维问题，且远程 URL 本身又是一个可被封的入口，**不解决冷启动死结**，故排在 V1.2。

---

## 7. 改动清单（V1.0 / 档 1）

| 层 | 文件 / 位置 | 改动 | 风险 |
|---|---|---|---|
| daemon | **无** | **0 行**；完全复用 `loadBootstrap()` / `setAccountDetails` | 最低 |
| Qt 客户端 | `src/app/settingsview/components/AdvancedOpenDHTSettings.qml` | 新增「导入引导节点」按钮、预览对话框、状态提示、恢复默认 | 低，纯 UI |
| Qt 客户端 | `src/core/accountmodel.cpp`（按需） | 若现有 `CurrentAccount.hostname` 通道不够，补导入结果的写入封装 | 低；注意全量 map 覆盖语义 |
| Android | `res/xml/account_advanced_prefs.xml`、`AdvancedAccountPresenter.kt`、`Account.kt` | 新增导入入口、文件/扫码解析、验签、状态文案 | 中；权限与文件选择器 |
| 共用逻辑 | 新增 C++ / Kotlin 各一份 `bootstrap_import` 解析模块 | JSON 解析、地址规范化、时效、验签、去重、排序 | **中**；三端实现须一致，建议先落一份共享规格与测试向量 |
| 资源 | 随包公钥、`strings` 多语言文案 | 签名公钥、中英文提示 | 低 |
| 测试 | 单元测试 + 端到端 | 见 §8 | — |

**不在 V1.0 范围内**：SWIG 接口、daemon 改动、远程清单、MQTT、节点打分与分享、`exportNodes()` 显式导出、F-901 公共聊天室。

---

## 8. 验收项

| 编号 | 场景 | 期望结果 |
|---|---|---|
| P0-0 | 零改动基线 | 不导入任何东西，现有 `bootstrap.jami.net` 行为不变；记录当前 DHT 状态与 `hostname` 值 |
| P0-1 | 档 0 手工填 | 在现有输入框填一个可达 `IP:port`，`setAccountDetails` 后日志出现 `Bootstrap node: ...`，DHT 转 Connected |
| P0-2 | 多节点分号 | 填 `A:port;B:port;[v6]:port`，三条都被 `loadBootstrap()` 切分并逐条 bootstrap |
| P0-3 | IPv6 | `[2001:db8::1]:4222` 被正确解析；裸 `2001:db8::1:4222` 被拒或规范化后可读 |
| P0-4 | 导入合法清单 | 验签通过 → 预览条数一致 → 写回 `hostname` → 自动重注册 → 入网成功 |
| P0-5 | 验签失败 / 过期 | 整份拒绝，`hostname` **不变**，回落原有引导，UI 明确报错 |
| P0-6 | 全部节点不可达 | 周期退避重试，不崩溃、不静态卡死；UI 显示「正在重试 / 已回落」 |
| P0-7 | 恢复默认 | 一键写回 `bootstrap.jami.net`，`hostname` 与 P0-0 一致 |
| P0-8 | 重启持久化 | 重启后 YAML `hostname` 与 `dhtstate` 正常，路由表可从缓存恢复 |
| P0-9 | 三端一致性 | Qt / Android 对同一份清单的校验结果与写回字符串完全一致 |
| P0-10 | 副作用 | 导入时若正在通话，UI 已提前提示；重连后会话可恢复 |

---

## 9. 安全边界与硬约束检查

| 风险 / 约束 | 判定 | 说明 |
|---|---|---|
| 官方域名被封 | ✅ 能缓解 | 入口换成用户手里的可达节点；`dhtstate` 缓存 + `FIND_NODE` 扩表后长期自给自足 |
| 广域网全新设备、无任何已知可达节点 | ❌ 无解 | 任何 P2P 冷启动都需要 rendezvous，导入功能不创造第一跳 |
| 假清单 / 日蚀攻击 | ⚠️ 必须靠验签 + TTL + 拒绝策略 | `bootstrap(SockAddr)` 不验身份，占住该 IP 者可喂假路由表 |
| 导入后给陌生人发消息 | ❌ 仍不允许 | `onICERequest` 过证书 + 好友/会话白名单，`allowPublicIncoming` 默认 false（`jamiaccount.cpp:2390-2417`） |
| Android 当引导节点 | ❌ 不行 | proxy 模式 bound port = 0、无监听 socket、无 UPnP（`jamiaccount.cpp:1995-2007,2127-2132`） |
| 节点同构 / 无常开节点 | ✅ 不违反 | 导入的是普通在线节点地址，不新增角色、不新增进程、不依赖其常在线 |
| 消息平面 | ✅ 不变 | 只产出候选地址；正文仍走 Git swarm + 设备签名 + TLS |
| 对外口径 | ⚠️ 须谨慎 | 不得宣称「完全无固定节点依赖」「完全脱离官方设施」 |

---

## 10. 明确不做

| # | 不做的事 | 原因 |
|---|---|---|
| 1 | 改 OpenDHT / 改 `FIND_NODE` 协议 | 现成链路已完整，改动只会抬高崩溃与不兼容风险 |
| 2 | 绕过 DHT 直接用 IP 建 TLS 通道 | ICE offer 固定走 DHT，绕不过；且会破坏证书与好友白名单模型 |
| 3 | 新增 MQTT broker / 目录服务器 / 锚点 | 违反项目硬约束；§12 已裁定 L1+ 覆盖其唯一优势 |
| 4 | 把导入节点做成「超级节点」角色或权威名单 | 名单只是本机候选池，不广播、不排名、不选举 |
| 5 | V1.0 动 SWIG 三套绑定 / daemon | 档 1 不需要，发版成本与风险不成比例 |
| 6 | 并入 F-901 公共聊天室 | 属独立特性 F-903；F-901 规格 v2 一行不改 |
| 7 | 启动 F-902 本地消息库加密 | 用户 2026-11-13 20:49 明确挂起，禁止启动开发 |

---

## 11. 待用户拍板

| # | 决策点 | 建议 |
|---|---|---|
| 1 | V1.0 是否就按**档 1（客户端为主、daemon 0 改动）**定稿 | 建议同意；风险最低且已能解决封锁主场景 |
| 2 | 是否先只做「档 0 手工填 + 验收」，把一键导入放 V1.1 | 若只想先验证可行性，可先做档 0；但零代码，谈不上「开发」 |
| 3 | 清单签名是否沿用 F-901 `rooms.json` 的离线签名根 + 随包公钥 | 建议沿用，避免第二套信任体系 |
| 4 | 条数上限与是否允许私网地址 | 建议上限 16，私网允许但 UI 标注「仅局域网」 |
| 5 | 三端首版范围 | 建议 Windows Qt 先行、Android 跟进，Linux Qt 客户端单列 V1.1 |
| 6 | 是否保留档 2 / 档 3 作为后续路线 | 建议写进路线图但本轮不排期 |

---

## 附录 A 源码索引

| 主题 | 文件与行号 |
|---|---|
| 引导默认值 / 配置结构 | `jamiaccount_config.h:22,34,49-57,68` |
| `hostname` 序列化与 map 读写 | `account_config.cpp:64,96,137,163`；`account_config.h:155-160`；`account_schema.h:52`；`jami/account_const.h:119,155` |
| 配置解析（含死键 `bootstrapListUrl`） | `jamiaccount_config.cpp:135-170,183-185` |
| 引导清单读取 | `jamiaccount.cpp:1807-1817`；`jamiaccount.h:800` |
| 注册与注入 | `jamiaccount.cpp:1949-1995,2039-2040,2116,2127-2132` |
| 配置变更触发重注册 | `manager.cpp:2743-2768`；`account.h:125-133`；`configurationmanager.cpp:220-222` |
| Qt UI | `AdvancedOpenDHTSettings.qml:178-189`；`accountmodel.cpp:945,1058,354`；`api/account.h:94` |
| Android UI | `account_advanced_prefs.xml:28-33`；`ConfigKey.kt:48`；`Account.kt:374-376`；`AdvancedAccountPresenter.kt:84` |
| OpenDHT bootstrap | `dhtrunner.cpp:1005-1017,1028-1052,1072-1092,188`；`dht.h:91-97,411`；`dht.cpp:2271-2314,2484-2516,2914-2962` |
| 地址解析 | `opendht/utils.cpp:40-59` |
| 组播发现范式 | `dhtrunner.cpp:246-287` |
| 远程清单缓存原语 | `jamiaccount.cpp:3187-3210,3260` |
| ICE 不绕过 DHT | `dhtnet/connectionmanager.cpp:745-788`；`jamiaccount.cpp:2390-2417` |
| 活跃节点与 `dhtstate` | `Jami接入韧性与IPv6优先方案.md` §11 / §12；`Jami节点发现与连接建立机制.md` §2.2 / §2.5 |

## 附录 B 术语

| 词 | 含义 |
|---|---|
| bootstrap 节点 | 一台已在 DHT 网络里、愿意回 ping 的普通节点；新设备靠它拿到第一份路由表 |
| `hostname` | Jami 账户配置键 `Account.hostname`，**不是**本机主机名，而是分号分隔的引导地址列表 |
| `NodeExport` | OpenDHT 路由表条目 `{InfoHash id, SockAddr addr}`，无签名无 TTL |
| `dhtstate` | OpenDHT 自动持久化的路由表快照，重启时 `loadState()` 恢复 |
| `isGood()` | 节点健康判定：2 小时内回过正确回复 + 10 分钟内有往来 + 未过期 |
| 日蚀攻击 | 攻击者用假节点灌满受害者路由表，使其只看到攻击者想让它看到的网络 |
| L0 / L1 / L1+ | `Jami接入韧性与IPv6优先方案.md` 里的降级档：现有默认链路 / 签名清单导入 / 入网后活跃节点保鲜 |
