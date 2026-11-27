# Jami 节点发现与连接建立机制（源码核准版）

> 输出日期：2026-11-13　｜　核准对象：本地 `jami-daemon-master`（jami-core 16.0.0）+ 随构建拉取的 `dhtnet` / `opendht` 源码　｜　状态：**仅描述官方现行实现，未修改任何功能代码**

## 0. 结论速览

| # | 结论 | 依据 |
|---|---|---|
| 1 | Jami **没有任何一台「聊天服务器」**。消息正文、Git 会话仓库、TLS 通道都不经过官方设施；官方提供的是「引导、翻译、打洞兜底、推送」四类辅助服务 | 本文 §3、§5 |
| 2 | 节点发现是**四层**：DHT 引导入网 → 路由表持久化 → 局域网组播发现 → 在 DHT 里找人/找设备 | 本文 §2 |
| 3 | 连接建立是**六步**：准入校验 → ICE 候选收集 → **ICE 信令经 DHT 加密交换** → ICE 连通性检查 → 双向证书 TLS → 命名通道复用 | 本文 §3 |
| 4 | **ICE 信令走 DHT，不走服务器**：`dht()->putEncrypted(InfoHash("peer_request"+deviceId), devicePk, value)` | `dhtnet/connectionmanager.cpp:755-788` |
| 5 | **官方设施全部可配置替换**（bootstrap 支持 `;` 分隔多个、名称服务/TURN/proxy 均可改、组播发现是内置开关），不需要改代码 | `jamiaccount_config.h:22-68` |
| 6 | **服务器被封锁 ≠ 不能用，要分场景**：局域网/已知对端/老用户可用；广域网全新设备 + 无任何可达引导节点 = 无解 | 本文 §6 |
| 7 | 早期专题「`ns.jami.net` 是明文 HTTP」「bootstrap 是必须连上的单点」两处口径**已过时**，订正见 §7 | `namedirectory.cpp:52`；`dht.cpp:2914-2961` |

## 1. 范围与阅读约定

| 项 | 说明 |
|---|---|
| 覆盖 | Jami（RING）账户的节点发现、设备寻址、P2P 建连、官方网络设施依赖与封锁影响 |
| 不覆盖 | SIP 账户（走传统 SIP 注册服务器，机制完全不同）、F-901 公共聊天室二开、F-902 本地消息库加密（已挂起） |
| 外部依赖源码位置 | `toolchain/vcpkg/buildtrees/dhtnet/src/…`、`toolchain/vcpkg/buildtrees/opendht/src/…`（本工程已随构建拉取，故可核准到行号） |
| 术语 | DHT = OpenDHT Kademlia 网络；bootstrap = 入网引导节点；ICE = 交互式连通性建立；ringId = 账户公钥 hash；deviceId = 设备证书 longId |

## 2. 节点是怎么被发现的：四层

### 2.1 L0 · DHT 入网引导（bootstrap）

| 项 | 实现 | 依据 |
|---|---|---|
| 默认引导节点 | `hostname = DHT_DEFAULT_BOOTSTRAP = "bootstrap.jami.net"` | `jamiaccount_config.h:22,34` |
| 支持多个 | `loadBootstrap()` 按 `;` 切分 `config().hostname`，逐个喂给 `dht_->bootstrap(bootstrap)` | `jamiaccount.cpp:1807-1817,2039-2040` |
| 动态列表（**21:10 订正：当前是死键**） | `bootstrapListUrl = "https://config.jami.net/bootstrapList"` 只在 `JamiAccountConfig` 声明、且只在 `fromMap()` 里被解析；**daemon 全文没有读取、也没有发起 HTTP 请求消费它**。`toMap()` 亦不输出该键。`loadCachedUrl()` 当前只服务于 `proxyListUrl` | `jamiaccount_config.h:52`；`jamiaccount_config.cpp:185`；`jamiaccount.cpp:3187,3260` |
| 网络号 | `config.dht_config.node_config.network = 0`（主网） | `jamiaccount.cpp:2114` |
| 本机端口 | `dht_->run(conf.dhtPort, …)` 后取 `getBoundPort()`；若为 0 表示 proxy-only 模式无监听 socket，不再申请 UPnP 映射 | `jamiaccount.cpp:1995-2007` |
| 入网后 | 靠 Kademlia 路由表自行发现其他节点，之后不再需要引导节点常在线 | OpenDHT 标准行为 |

**关键**：bootstrap 只在「路由表为空」时是入场券，不是持续依赖。这与项目长期约束「不得对外宣称完全无固定节点依赖」并不矛盾——它确实是一个**冷启动时必须可达的外部设施**。

**21:10 补充订正**：本文初版把 `bootstrapListUrl` 写成「可作为引导节点的补充来源」，静态复核后确认该键在 daemon 内**只有声明与 `fromMap()` 解析、没有任何消费点**，因此现有版本不能靠它动态拉取引导列表。这不改变 §6 的封锁裁定（绕行靠的是 `hostname` 多节点 + `dhtstate` + 组播），但影响「远程引导清单」类方案的工作量估算：它不是零代码，而是要补一段消费逻辑。

### 2.2 L1 · DHT 路由表持久化（老用户绕开 bootstrap 的关键）

| 项 | 实现 | 依据 |
|---|---|---|
| 持久化路径 | `config.dht_config.node_config.persist_path = (cachePath_ / "dhtstate").string()` | `jamiaccount.cpp:2116` |
| 写盘内容 | `DhtState{ id, nodes = exportNodes(), values = exportValues() }`，msgpack 序列化 | `opendht/src/dht.cpp:2910-2922` |
| 退出/周期保存 | `Dht` 析构路径调用 `saveState(persistPath)` | `dht.cpp:73` |
| 启动恢复 | `loadState()` → 逐个 `network_engine.insertNode(node.id, node.addr)` | `dht.cpp:2021,2049,2925-2956` |
| 活跃判定（09:43 补充） | `exportNodes()` 只收 `isGood(now)` 节点；`isGood = !expired` 且 `reply_time >= now - 120min` 且 `time >= now - 10min` | `dht.cpp:2485-2516`；`node.cpp:34-39`；`node.h:135,138` |

**推论**：一台曾经成功入网的设备，重启后可以直接用 `dhtstate` 里缓存的已知节点恢复路由表，**不必再解析 `bootstrap.jami.net` 域名**。前提是缓存节点里还有活着的、且 IP 未变的节点。这是「域名被污染但网络还在」场景下仍能用的主因，早期专题文档未覆盖这一层。`FIND_NODE` 与 `exportNodes()` 的完整源码级链路见 `Jami接入韧性与IPv6优先方案.md` §12。

### 2.3 L2 · 局域网组播发现（内置、默认关闭、完全不需要官方服务器）

OpenDHT 自带 `PeerDiscovery`，Jami 用两个开关把它接出来：

| 开关 | 作用 | 默认值 | 依据 |
|---|---|---|---|
| `dhtPeerDiscovery` | 同时置 `config.peer_discovery` 与 `config.peer_publish`，让 **DHT 层**互报节点信息 | `false` | `jamiaccount_config.h:49`；`jamiaccount.cpp:2125-2126` |
| `accountPeerDiscovery` / `accountPublish` | 让 **Jami 层**发布/发现 `AccountPeerInfo`（accountId + displayName），只上屏「附近的人」，不建连接 | `false` | `jamiaccount_config.h:50-51`；`jamiaccount.cpp:1982-1990,4089-4135` |

| 组播参数 | 值 | 依据 |
|---|---|---|
| IPv4 组播地址 | `239.192.0.1`（Organization-local Scope） | `opendht/src/peer_discovery.cpp:19` |
| IPv6 组播地址 | `ff08::101` | `peer_discovery.cpp:20` |
| 端口 | UDP `8888`（`DEFAULT_PORT`） | `opendht/include/opendht/peer_discovery.h:23` |
| 发布周期 | 10 秒，最大退避 1 分钟 | `peer_discovery.cpp:51-52` |
| 发现后的动作 | 收到 `NodeInsertionPack` → `addr.setPort(v.port)` → **`bootstrap(v.nodeId, addr)`**，直接把局域网对端当作 DHT 引导节点 | `opendht/src/dhtrunner.cpp:258-265` |
| **硬边界** | 整段逻辑包在 `if (config.proxy_server.empty())` 内，**proxy 模式下组播发现不启用** | `dhtrunner.cpp:246` |

**这是「官方服务器全封还能不能用」的最强答案**：在同一局域网内，两台都打开 `dhtPeerDiscovery` 的设备会互相引导成 DHT 网络，ICE 信令随后就在这张小网里交换，全程不碰 `*.jami.net`。注意它**默认关闭**，且只在同一广播域有效。

### 2.4 L3 · 在 DHT 里找「人」和「设备」

| 目的 | 手段 | 依据 |
|---|---|---|
| 用户名 → RingID | 名称服务 REST：`GET https://ns.jami.net/name/<name>`、`/addr/<addr>`；可配置 `nameServer` 换自建 | `namedirectory.cpp:49-52,196-199`；`jamiaccount_config.h:62` |
| 好友请求 / 会话邀请 | 加密值放 DHT `inbox:`（只装请求/邀请，**不装聊天正文**） | 1:1 机制文档 §4、附录 E；`account_manager.cpp:742,772` |
| 找设备证书 | `findCertificate(deviceId, cb)` 从 DHT / 本地证书库取，用于加密与准入 | `jamiaccount.cpp:2392` |
| 在线状态 | `presence_manager.cpp` 订阅；`NearbyPeerNotification` 上报局域网发现 | `jamiaccount.cpp:1820,4114` |
| 谁可以连我 | `allowPeersFromHistory / Contact / Trusted`（默认 true）+ `allowPublicIncoming`（默认 false）；`onICERequest` 逐项校验 | `jamiaccount_config.h:65-68`；`jamiaccount.cpp:2388-2423` |
| 同账号多设备 | DHT 同步通道 + `sync://` 命名通道 | `sync_channel_handler.cpp:40`；ADR-002 |

### 2.5 补充：Jami 有**两层路由表**，讨论「拉活跃节点」时不要混淆（21:20 核准）

| 层 | 存什么 | 怎么交换节点 | 关键依据 |
|---|---|---|---|
| **DHT 路由表**（OpenDHT Kademlia，用于入网与查找） | `NodeExport{InfoHash id, SockAddr addr}`，含 IP/端口 | `FIND_NODE` 应答带回**对端认定健康、且离 target XOR 最近的 8 个**节点（不是整张表）；`exportNodes()` **只导出 `isGood(now)` 的健康节点**；`insertNode()` / `bootstrap(std::vector<NodeExport>)` 注入；`saveState()` 存进 `dhtstate` | `network_engine.cpp:740-748,1254-1283`；`dht.cpp:2485-2524,2603-2612,2914-2918`；`dhtrunner.cpp:506-512,1082-1092` |
| **会话 DRT 路由表**（`src/jamidht/swarm/`，用于会话内消息扩散） | `NodeId = dht::PkId`（设备 ID），**不含 IP**；另有 mobile 节点与租约 | **已有 PEX**：`sendRequest(..., Query::FIND, BUCKET_MAX_SIZE)` → 对端 `sendAnswer()` 用 `routing_table.closestNodes()` 回 `Response{FOUND, nodes, mobile_node_infos}` → 本地 `setKnownNodes()` 注入；mobile 节点带 `MobileLease` 签名 + `CertRequest/CertResponse` 证书验证，明确 gossip 不传证书 | `swarm_protocol.h:52-125`；`swarm_manager.cpp:803-845,888-902,936` |
| **proxy 模式边界** | 无 DHT 路由表 | `DhtProxyClient::exportNodes()` 直接返回 `{}`、`insertNode()` 为空实现；Android 默认该模式 | `dht_proxy_client.h:277,280` |

**推论**：「从某个在线对端拉活跃节点」在**两层里都已经实现**——DHT 层靠 `FIND_NODE`/`exportNodes`，会话层靠 `FIND`/`FOUND` PEX。因此这不需要新协议，缺的只是把结果显式导出、持久化，并解决「连上那个对端之前」的冷启动第一跳（见 `Jami接入韧性与IPv6优先方案.md` §11、§12）。

**09:43 精确化**：DHT 层的「对端」只要求是一个可达的 DHT 节点地址，**不需要是好友、不需要 TLS、不需要 ICE**，一个 UDP `FIND_NODE` 包即可问路（`network_engine.cpp:916-954`）；只有走应用层「从已建连好友拉名单」才需要先 `connectDevice` 的循环（§11.4）。两种语义勿混用。

## 3. 连接是怎么建立的：六步链路

| 步 | 动作 | 关键实现 | 依据 |
|---|---|---|---|
| 1 | 业务层请求连某设备 | 各 ChannelHandler 调 `connectionManager_.connectDevice(deviceId, channelName, cb)`；git/swarm/sync/msg 都是同一入口 | `conversation_channel_handler.cpp:37`；`swarm_channel_handler.cpp:42`；`message_channel_handler.cpp:83` |
| 2 | **准入校验**（在耗资源前） | `onICERequest` 查证书 → `isAllowed` → 会话成员授权 `isPeerAuthorizedByConversation` → BANNED 直接丢弃 | `jamiaccount.cpp:2388-2423` |
| 3 | 收集 ICE 候选 | `getIceOptions()` 汇总：本机接口 host（v4/v6）、DHT 观测的公网地址 `publishedIp_`（`accountPublicAddr`）、UPnP 映射、可选 STUN srflx、TURN relay | `dhtnet/connectionmanager.cpp:649-651,1931-1953,1962-1993` |
| 4 | **ICE 信令经 DHT 交换** | 把 `ufrag / pwd / 候选地址列表` 装进 `PeerConnectionRequest`，`putEncrypted(InfoHash("peer_request"+deviceLongId), devicePk, value)`；对端回复 answer；本地 `parseIceCandidates` → `startIce`；超时报 `No response from DHT to ICE request` | `connectionmanager.cpp:745-788,827-837` |
| 5 | ICE 成功后建 TLS | 双向证书认证，`connectDeviceOnNegoDone` 起 TLS 会话 | `connectionmanager.cpp:841-872,1426` |
| 6 | 一条 TLS 连接复用多个命名通道 | `MultiplexedSocket`：channel 0 控制，`git://`、`swarm://`、`sync://`、`sip`、`msg:` 等按需开子通道 | ADR-002；`jamiaccount.cpp:5118-5125` |

**最容易误解的一点**：第 4 步不是「找服务器要对方地址」，而是把加密的 SDP-like 数据放到 **DHT 上一个由对端设备公钥推导的 key** 下，对端一直在 `listen`。所以服务器不在建连路径上，**但 DHT 网络本身在**——DHT 全断则连不上（除非已有直连缓存）。

**通道复用**：一旦 TLS 建好，Git fetch、swarm 消息、多设备同步、文件传输都在这一条连接上开子通道，不重复握手。这与 1:1 消息机制文档附录 C 的「`git://` 不是标准 Git daemon 协议」是同一套机制。

## 4. 用到的手段清单（一表看全）

| 类别 | 手段 | 是否依赖官方设施 | 作用 | 依据 |
|---|---|---|---|---|
| 网络入网 | OpenDHT Kademlia + bootstrap 列表 | **是**（冷启动） | 拿到第一批 DHT 节点 | `jamiaccount.cpp:2039-2040` |
| 网络入网 | `dhtstate` 路由表持久化 | 否 | 重启免引导 | `dht.cpp:2914-2961` |
| 网络入网 | IPv4/IPv6 组播 peer discovery | 否 | 局域网自组 DHT | `dhtrunner.cpp:258-265` |
| 寻址 | 名称服务 HTTPS REST | 是（仅用户名） | 用户名 → RingID | `namedirectory.cpp:52` |
| 寻址 | DHT `inbox:` / presence / 证书查询 | 否 | 好友请求、在线、设备证书 | `account_manager.cpp:742,772` |
| 准入 | 设备证书 + 好友/会话白名单 | 否 | 拒掉未授权 ICE 请求 | `jamiaccount.cpp:2388-2423` |
| NAT 穿透 | host 候选 + `publishedIp_` 公网地址 | 否 | 直连首选 | `connectionmanager.cpp:1931-1953` |
| NAT 穿透 | UPnP / NAT-PMP 端口映射 | 否 | 让入站可达 | `jamiaccount.cpp:2003-2036` |
| NAT 穿透 | STUN srflx（可选） | 视配置 | 反射地址发现 | `connectionmanager.cpp:1969-1970` |
| NAT 穿透 | TURN relay 兜底 | **是**（默认 `turn.jami.net`） | 对称 NAT 双端时中继 | `connectionmanager.cpp:1971-1993` |
| 信令 | DHT `putEncrypted` 交换 ICE 候选 | 否（需 DHT 网络） | 无服务器信令 | `connectionmanager.cpp:785` |
| 传输 | ICE（PJNATH）+ 双向证书 TLS | 否 | 加密 P2P 字节流 | `connectionmanager.cpp:841-872` |
| 传输 | 命名通道复用 | 否 | 一条连接承载全部业务 | ADR-002 |
| 移动唤醒 | FCM / APNs 推送（`deviceKey` / `notificationTopic`） | **是** | 后台唤醒 App | `jamiaccount.cpp:2119-2123` |
| 移动省电 | DHT proxy（`dhtproxy.jami.net:[80-95]`，HTTP） | **是**（仅 proxy 模式） | 移动端不跑完整 DHT | `jamiaccount_config.h:56`；`jamiaccount.cpp:2127-2139` |

## 5. 官方设施清单与可替换性

| 设施 | 默认值 | 承载什么 | 是否承载聊天内容 | 可替换方式 | 配置键 |
|---|---|---|---|---|---|
| DHT bootstrap | `bootstrap.jami.net` | 冷启动引导 | 否 | `hostname` 填 `;` 分隔的多个自建节点 IP；或局域网组播；或 `dhtstate` 缓存 | `Account.hostname` |
| 引导列表（**配置项存在但未启用**） | `https://config.jami.net/bootstrapList` | 预留的动态引导列表，**当前 daemon 不请求该 URL** | 否 | 现有版本不可用；若要启用需复用 `loadCachedUrl()` 补一段小改动 | `Account.bootstrapListUrl` |
| 名称服务 | `https://ns.jami.net` | 用户名 → RingID | 否 | 改 `nameServer` 指向自建；或直接用 RingID / 二维码 / 链接加好友 | `Account.nameServer` |
| DHT proxy | `dhtproxy.jami.net:[80-95]` | 移动端代跑 DHT | 否（只转发 DHT 操作） | 改 `proxyServer`，或关闭 `proxyEnabled` 自己跑完整 DHT | `Account.proxyServer` / `proxyEnabled` |
| TURN | `turn.jami.net`（`ring`/`ring`） | 穿透失败时中继**加密字节流** | 否（TLS 端到端，中继看不到明文） | 自建 coturn，改 `turnServer`；或 `turnEnabled=false` 只走直连 | `Account.turnServer` / `turnEnabled` |
| 推送 | FCM / APNs | 后台唤醒 | 否 | 桌面端不需要；移动端可保持前台 | `deviceKey` / `notificationTopic` |

**关键澄清**：TURN 中继的是**已经端到端加密的 TLS 字节流**，不是聊天明文；它只是转发，不作为消息落地或存储节点。这与「Jami 没有聊天服务器」不冲突。

## 6. 如果 Jami 的服务器被封锁，还能用吗

### 6.1 逐场景裁定

| 场景 | 能否使用 | 决定因素 | 需要做什么 |
|---|---|---|---|
| **同一局域网，两人都要通信** | ✅ **能** | 打开 `dhtPeerDiscovery`（双方），组播互引导成小 DHT；ICE 走 host 候选直连 | 无需任何官方设施；注意 proxy 模式下组播被禁用，需关 `proxyEnabled` |
| **老用户重启，bootstrap 域名被污染** | ✅ **大概率能** | `dhtstate` 缓存的已知节点是否还活 | 保留 `cachePath_/dhtstate`；若节点全失效则退回下一行 |
| **广域网，但能自建/知道一个可达 DHT 节点** | ✅ **能** | 把该节点填进 `hostname`（支持多个 `;` 分隔） | 自建 dhtnode 或用社区节点；名称服务可不用（走 RingID） |
| **广域网，官方全封、双方能直连（公网 IP / IPv6 / UPnP 成功）** | ⚠️ **建立过关系后能** | 需要一次 DHT 可达来交换 ICE 信令与好友请求 | 换自建 bootstrap / TURN 即可；本地证书与连接缓存可续用 |
| **广域网，双方都在对称 NAT/CGNAT，官方 TURN 被封** | ❌ **不能**（无替代时） | ICE 直连失败且无 relay | 必须自建 TURN 并改 `turnServer`；IPv6 直连是另一条出路（但注意项目已核准「IPv6 TURN 被官方禁用」） |
| **只封名称服务 `ns.jami.net`** | ✅ **几乎无感** | 只影响「用户名查人」 | 用 RingID / 二维码 /邀请链接加好友、发消息、通话全部照常 |
| **只封推送（中国 FCM 本就不可用）** | ⚠️ **桌面端无感，Android 后台受影响** | 移动端需推送唤醒 | 保持前台 / 允许后台运行；桌面端不涉及 |
| **广域网全新设备 + 所有引导/TURN 全封 + 无任何已知节点/自建节点** | ❌ **无解** | 任何 P2P 网络冷启动都需要 rendezvous | 无解，不是 Jami 独有；这与项目长期约束「广域网首次入网无 rendezvous 时无解」一致 |

### 6.2 三句话总结

| # | 结论 |
|---|---|
| 1 | **封「域名」不等于封「网络」**：`bootstrap.jami.net` 只是默认引导，能换成任意 IP；`dhtstate` 缓存和局域网组播都是内置绕行路径。 |
| 2 | **真正卡死的是「DHT 网络整体不可达」**：ICE 信令要靠 DHT 交换，好友请求要靠 DHT 投递，设备证书要靠 DHT 找。DHT 起不来，再多的直连能力也无从知道该连谁。 |
| 3 | **可配置性是现成的、不需要二开**：所有官方设施在 `JamiAccountConfig` 里都有对应配置项；「自建全部设施」在技术上成立，成本是运维一套 bootstrap + TURN + 名称服务，不是改 Jami 代码。 |

### 6.3 与本项目 F-901 的边界

| 项 | 说明 |
|---|---|
| F-901 公共聊天室 | 房间成员发现依赖 DHT/Git 通道，创世 bundle 随包分发解决「房间数据从哪来」，**但不能替代 DHT 入网**：设备仍要先连上 DHT 才能与房间成员交换 ICE 候选 |
| 节点同构硬约束 | 自建 bootstrap / TURN 属于「额外部署的固定节点」，**在本项目 V1.0 明确不作为方案**，本文件仅作为「封锁后可用手段」的事实陈述 |
| 不得外推 | 项目长期约束要求：**不得对外宣称「完全无固定节点依赖」**。上游 `bootstrap.jami.net` / `turn.jami.net` 是默认存在的外部设施，V1.0 不新增不改动；若要外推到网络层需单独立项 |

## 7. 对早期专题文档的口径订正

早期 `I2P与Jami在中国使用问题专题.md`（2026-11-01，基于官方文档）有三处与当前源码不符，此处订正，**核心结论「广域网冷启动依赖外部引导」不变**：

| # | 早期表述 | 当前源码事实 | 依据 |
|---|---|---|---|
| 1 | 「`ns.jami.net` 是明文 HTTP（官方文档里就是 `http://`）」 | 已改为 **`https://ns.jami.net`** 的 HTTPS REST（`/name/`、`/addr/`，restinio + JSON） | `namedirectory.cpp:33,49-52,179-199` |
| 2 | 「bootstrap 是首次入网/缓存失效时**必须**连上的单点」 | 有 **3 条绕行**：`;` 分隔多 bootstrap、内置 `dhtstate` 路由表持久化、IPv4/IPv6 组播 peer discovery 直接把局域网对端当引导节点。但**广域网全新设备仍无解** | `jamiaccount.cpp:1807-1817,2116`；`dht.cpp:2914-2961`；`dhtrunner.cpp:258-265` |
| 3 | 「自建需自跑 Ethereum 节点」 | 当前名称服务是 HTTPS REST，源码中**未见 Ethereum 强依赖**；`managerUri` 是可选的 JAMS 管理服务器，不是入网必需 | `namedirectory.cpp`；`jamiaccount_config.h:70-71` |

| 项 | 早期结论是否仍成立 |
|---|---|
| 「Jami 不是无服务器，是一组默认指向同一公司的可自建设施」 | ✅ 成立，且本文件 §5 补齐到 6 类 |
| 「中国 CGNAT/对称 NAT 下会退化到官方 TURN」 | ✅ 成立，但补一句：TURN 转发的是 TLS 密文，不是聊天明文 |
| 「FCM 在中国不可用会放大 Android 后台收不到消息」 | ✅ 成立 |
| 「广域网冷启动依赖外部引导，是结构性依赖」 | ✅ 成立，是 §6 最后一行「无解」的依据 |

## 8. OpenDHT 报文特征与 DPI 可识别性（2026-11-14 13:30 源码核准）

本节回答「为什么 DPI 能认出 OpenDHT 报文」。**核心结论：DPI 不需要解密、不需要读到聊天内容**。OpenDHT 的控制面是**明文 UDP + msgpack 固定字段 + 每包固定的 User-Agent 字符串**，首包即可判定；加密只作用于 `value` 的 body。因此只要丢弃 DHT 控制包，整个建连链就断在第一跳，后续 TLS 通道根本建不起来。

### 8.1 一个 find 请求长什么样

| 层 | 实际内容（msgpack map） | 传输状态 | 依据 |
|---|---|---|---|
| 传输 | 单个 UDP 数据报，`sendTo()` 直接发；socket 是 `net::UdpSocket` | **明文**，无 TLS、无握手、无填充、无长度随机化 | `network_engine.cpp:831-834`；`dhtrunner.cpp:147,158` |
| 报文头 | `y:"q"`、`q:"find"`、`t:` 4 字节 tid、**`v:"o2"`** | **全明文固定字符串** | `network_engine.cpp:919-954`；`parsed_message.h:18-28,48-54` |
| 参数 | `a:{id: 20B 自身 NodeId, target: 20B, w:[AF_INET, AF_INET6]}` | **全明文** | `network_engine.cpp:924-937` |
| 应答 | `r:{id, sa, n4, n6, token, values}`、`y:"r"`、同样带 `v:"o2"` | **全明文**（除 value 的 cypher） | `network_engine.cpp:1187-1246`；`insertAddr()` :819-828 |
| 值 | `values:[{id, dat: cypher 二进制块}]` | **仅此处加密** | `value.h:558-592`；`securedht.cpp:411,433,449-451` |

`OPENDHT_UA = "o2"` 定义在 `network_engine.cpp:31`，查询 / 应答 / 分片 / 错误等 **14 处打包点全部无条件写入**（:206,277,855,901,945,1016,1234,1341,1389,1438,1539,1594,1649,1686）。接收侧 `msgpack::unpack()` 直接解析（:485-486），`ua` 字段只赋值不参与准入（:222-223），因此它不构成任何「隐藏」机制，只是稳定指纹。

### 8.2 DPI 可识别特征清单（按识别成本从低到高）

| # | 特征 | 源码事实 | DPI 怎么用 |
|---|---|---|---|
| 1 | 默认端口 | `DHT_DEFAULT_PORT = 4222`（UDP）；Jami 本地 `conf.dhtPort` 默认 0 是**随机监听**，但引导地址缺省端口时补 4222 | 最粗一档：直接封 UDP/4222 |
| 2 | 传输形态 | 纯 UDP、无 TLS、无 SNI、无证书、无 443 伪装；daemon 全树 0 处混淆 / 可插拔传输 | 「非 Web 端口 + 明文小包」先入嫌疑集 |
| 3 | **固定明文 magic** | 每包都有 `v:"o2"`，键名 `v` 固定、值固定 | **单包即可精确命中，几乎零误报**，这是最强特征 |
| 4 | 固定字段名集合 | `y q a r u e p t v n s id h target w sa n4 n6 token values sid vid exp re ve fileds` | 解析首个 map 的键集合即可比对 |
| 5 | 源码拼写笔误 | `KEY_REQ_FIELDS = "fileds"`（应为 fields），出现在 :44 与 :1217 | 该字符串几乎独一份，可作二次确认指纹 |
| 6 | 固定方法名 | `ping / find / get / put / listen / refresh / update`（:48-54） | 区分请求类型，也能统计行为 |
| 7 | 定长二进制节点块 | `n4` 每条 = 20B id + 4B IPv4 + 2B port = **26B**；`n6` = 20+16+2 = **38B**；必须整除，一次最多 `SEND_NODES=8` 条 | 长度整除校验 + 包长模式即可判定，不必解内容 |
| 8 | 包长分布 | `MAX_PACKET_VALUE_SIZE=600` 内合并为单包；超过则按 `MTU=1280` 切成 `p:{i:{o,d}}` 分片 | 分片结构与长度分布稳定，可训练分类器 |
| 9 | 行为画像 | 与大量不同 IP 的短小请求-应答、周期性 `neighbourhoodMaintenance` / `bucketMaintenance`、上下行近似对称 | 不依赖单包内容也能做流量画像 |
| 10 | 网络无隔离 | Jami `node_config.network = 0`（:2114），故**不发送** `n` 字段；收包侧默认 `NetId{0}`，比对恒通过 | 任何人跑一个 OpenDHT 节点即可加入并持续 `FIND_NODE` 枚举全网节点 IP |
| 11 | 地址明文外泄 | `sa` 回显对端看到的公网 IP（:825-827）；`n4` / `n6` 明文带 8 个节点 IP:port（:1262-1280） | 一个被动观测点就能批量测绘节点池，供后续定点封禁 |

### 8.3 加密到底保护了什么

| 项 | 状态 | 说明 |
|---|---|---|
| value 的 data body | ✅ 密文 | `SecureDht::encrypt()` 把整个 signed body 换成 `cypher` 二进制块；Jami 的 `putEncrypted(peer_request+deviceId, ...)` 用它装 ICE offer |
| 外层 `id`、字段头、`a.h`（InfoHash）、`t`、`v`、`n4` / `n6`、`sa` | ❌ **全明文** | 加密发生在 `Value` 层，不在 `NetworkEngine` 层；`network_engine.cpp` 全文无 encrypt/decrypt 调用 |
| 聊天正文 | ✅ 不经过 DHT | 正文写本地签名 Git commit，网络只传不含正文的便条；消息本体走端到端 TLS 命名通道 |
| DPI 能看到什么 | 元数据 | 谁在跑 OpenDHT、往哪个 InfoHash 读写、与哪些节点通信、上下线节奏；**看不到聊天内容，但这已足够封锁** |
| 签名 / 证书 | 不影响识别 | 签名在加密 value 内部；TLS 双向证书只出现在后续设备间直连，DHT 控制包里没有 |

### 8.4 为什么「丢掉 DHT 控制包」是致命的

| 步 | 依赖 DHT 的动作 | 丢包后果 | 依据 |
|---|---|---|---|
| 1 | `loadBootstrap()` → `dht_->bootstrap()` 发 ping | 拿不到任何节点 ID，路由表为空 | `jamiaccount.cpp:1807-1817,2039-2040` |
| 2 | `FIND_NODE` 迭代扩表、`isGood()` 淘汰、`dhtstate` 落盘 | 无表可用，重启也恢复不了 | `dht.cpp:2914-2962`；`network_engine.cpp:916-970` |
| 3 | `putEncrypted(peer_request + deviceId, ...)` 交换 ICE offer | 信令发不出去 / 收不到，`listen` 无回复 | `dhtnet/connectionmanager.cpp:745-788` |
| 4 | ICE 连通 → 双向证书 TLS → 命名通道复用 | **根本到不了这一步** | `dhtnet/connectionmanager.cpp:841-872`；ADR-002 |
| 5 | 消息正文、Git fetch、会话同步 | 全部无从发生 | `Jami好友1对1消息收发机制.md` |

**关键**：换 `hostname`、换 `dhtPort`、导入节点（F-903）、局域网组播，都只是换「门牌号 / 第一跳地址」，报文长相不变。端口级封锁可躲，内容级识别躲不过。

### 8.5 能力边界（与 §6 的 L3 结论一致）

| 手段 | 能躲 | 躲不过 | 裁定 |
|---|---|---|---|
| 改 `dhtPort` / 换 bootstrap IP | 只按 4222 的粗封 | `v:"o2"` + 字段集 + 定长节点块的内容匹配 | 部分有效 |
| 导入节点 / 签名清单 / 组播 | 域名被封、冷启动第一跳 | 协议被识别后的整体丢包 | 只解决 L1 |
| 隧道 / 混淆 / 443 伪装 / 可插拔传输 | 内容识别 | 熵与行为画像分类器 | 需 Jami 之外叠加或改协议层，引入额外固定设施，**V1.0 不做** |
| 局域网组播自组 | 未部署 DPI 的同一广播域 | 该域内做了同样识别 | 场景受限可用 |

### 8.6 `v:"o2"` 的归属与可改性（2026-11-14 13:45 追加核准）

| 对象 | 是否 OpenDHT 专属 | 核准结论 | 依据 |
|---|---|---|---|
| `v` 键名 | ❌ 不专属 | 沿用 Kademlia KRPC 的单字母字段约定；在 OpenDHT 里 `v` 装的是 **UA**，版本键另有 `ve`（不是 `v`） | `parsed_message.h:18-28,46` |
| `o2` 这个值 | ✅ **库专属，但不是 Jami 专属** | 任何链接 OpenDHT 4.x 的程序都带：Jami、`dhtnode`、官方 bootstrap 节点、其他下游项目；DPI 命中的是「整个 OpenDHT 生态」，不需要区分哪个应用 | `network_engine.cpp:30-31` |
| 组合指纹 | ✅ 特有 | msgpack 明文结构 + 固定字段集 + 定长 `n4/n6` + 固定方法名 + `o2`，组合起来几乎零误报 | `network_engine.cpp:919-954,1254-1283`；§8.2 |

| 源码事实 | 具体内容 | 对「能不能改」的影响 |
|---|---|---|
| 定义方式 | `constexpr std::string_view OPENDHT_UA {"o2"};`，`network_engine.cpp:31` 文件内常量 | 编译期写死，**不在任何头文件中** |
| 是否为配置项 | 否。`Config` / `NodeConfig` / vcpkg port / CMake 全树无 UA 选项 | 运行时改不了、配置改不了，只能改源码重编 |
| 打包点 | 14 处无条件写入（查询、应答、分片、错误等所有报文类型） | 改一处会漏包，必须改常量本身 |
| **收包侧** | `parsed_message.h:222-223` 仅 `ua = o.val.as<std::string>()`；全树**零处读取、零处比较、零处准入判断** | **改值不破坏协议互通**，原版节点照常处理你的包 |
| 唯一准入检查 | `network_engine.cpp:495-499` 只比对 `msg->network != config.network` | 与 UA 完全无关 |
| Jami 侧设置 | `initDhtConfig()` 显式 `node_config.network = 0`，连 `n` 字段都不带 | `jamiaccount.cpp:2114` |

| # | 只改 `o2` 后仍然暴露的特征 | 结论 |
|---|---|---|
| 1 | 对端（官方 / 公共节点）回包依旧带 `o2` | 双向流里**入站方向仍单包精确命中** |
| 2 | msgpack 明文结构 + 固定字段集 `y/q/a/r/t/v/n4/n6/token/values` | 结构指纹一字未变 |
| 3 | `fileds` 源码笔误、`ping/find/get/put/listen/refresh/update` 方法名 | 字符串规则仍命中 |
| 4 | `n4` 每条 26B、`n6` 每条 38B、整除校验、最多 8 条 | 二进制布局未变 |
| 5 | 纯 UDP、无 TLS / 无填充 / 无长度随机化，引导缺省补 4222 | 传输画像未变 |
| 6 | 周期性 `neighbourhoodMaintenance` / `bucketMaintenance`、短请求-应答、上下行对称 | 行为画像未变 |
| 7 | `sa` 明文回显对端公网 IP、`n4/n6` 明文带节点地址 | 主动测绘仍成立 |

| 维度 | 裁定 |
|---|---|
| 改动落点 | 第三方库 `opendht` 源码 patch，**不在 Jami 代码里**；Win / Linux / Android 三端静态库全部重编 |
| 构建代价 | vcpkg 二进制缓存失效、port 升级会覆盖 patch、需长期自维护 fork |
| 兼容性与彻底性 | 报文层兼容（收包不校验 UA），但入站仍带 `o2`；要彻底去掉必须全网同版本 ⇒ 等于分叉私有网络 |
| 反效果 | 自定义 UA 值比 `o2` 更稀有，在白名单制过滤下反而更易被命中 |
| 硬约束 | 属协议层改动，与「不改协议、不引入额外设施、节点同构」及 F-901 规格 v2 冲突 |
| **最终裁定** | **技术上可改、工程上可改、安全上无效；V1.0 不做** |

**另一条看似可行但实际不解决问题的路**：OpenDHT 内建 `config.network`（NetId）机制——非 0 时发包带 `n` 字段，收包侧 `network_engine.cpp:495-499` 会拒绝 netId 不同的包，可自成私有 DHT 网络。但它是**网络隔离**而非流量伪装：报文结构一字未改，还多一个固定字段；且 Jami 当前 `network = 0`、未暴露成账户配置项，启用即与公共 OpenDHT 网络断开，冷启动第一跳更依赖自备节点。

### 8.7 加密边界：哪条信道加密、哪条没有（2026-11-14 15:10 追加核准）

§8.1 说「控制面明文」容易被误读成「Jami 通信不加密」。Jami 同时存在多条信道，加密状态各不相同：

| 层 | 承载内容 | 传输形态 | 加密状态 | 源码依据 |
|---|---|---|---|---|
| L0 DHT 控制面 | bootstrap ping、`FIND_NODE` 与应答、路由维护、节点地址列表 | 明文 UDP + msgpack | ❌ **无传输层加密**，字段、hash、IP 全部可见 | `network_engine.cpp:831-834,919-954`；§8.1 |
| L1 DHT 值层 | ICE offer、好友请求、会话邀请、`inbox:` 应答 | 装在上述明文包的 `values` 里 | ✅ **应用层数据加密**：`Value::encrypt()` = 签名 + 收件人公钥加密（RSA 包裹 AES-GCM 密钥） | `securedht.cpp:411,433,449-451`；`value.cpp:266-276`；`crypto.cpp:661-680`；`dhtnet/connectionmanager.cpp:785` |
| L2 ICE 连通性检查 | STUN binding、候选地址交换 | UDP | ❌ STUN 本身明文（PJNATH / RFC 5245 语义） | 见 `Jami接入韧性与IPv6优先方案.md` §7 |
| L3 **设备间业务信道** | 消息便条、`git://` fetch、通话、文件、`sync://` | **TLS / DTLS over ICE socket** | ✅ GnuTLS TLS1.3 / DTLS，**双向证书认证** `GNUTLS_CERT_REQUIRE` | `dhtnet/connectionmanager.cpp:867-881`；`tls_session.cpp:55,76,448,482`；ADR-002 |
| L4 TURN 中继 | 转发 L3 的字节流 | relay 通道 | ✅ 只见 L3 的 TLS 密文，看不到明文 | TLS 建在 `IceSocketEndpoint` 之上（:867-881）的架构推论 |
| L5 名称服务 | 用户名 → RingID | HTTPS REST | ✅ TLS | `namedirectory.cpp:52,157` |
| L6 局域网组播发现 | peer discovery 通告 | UDP 组播 msgpack | ❌ 明文，无任何加密调用 | `peer_discovery.cpp:196-203,298-301` |
| L7 DHT proxy（Android 默认） | 代理全部 DHT 操作 | HTTP 客户端 / REST | ⚠️ 走独立 HTTP 栈，是否 TLS 取决于 proxy URL，**本轮未逐行核准，不作结论** | `dht_proxy_client.cpp:312-333`；`jamiaccount_config.h:56` |

| # | 必须讲清的三件事 |
|---|---|
| 1 | **聊天正文根本不进 DHT**：正文写本地签名 Git commit，网络只传不含正文的 `application/im-gitmessage-id` 便条，对端再经 L3 的 TLS 命名通道 `git fetch` 拉回。DHT 只负责「找到人、约好怎么连」，不承载消息体（`Jami好友1对1消息收发机制.md` 附录 E） |
| 2 | **「数据加密」≠「信道加密」**：L1 是给内容上锁，L3 才是给管道上锁。L0 设计上就不能加密——陌生节点必须能读懂 `FIND_NODE` 才能帮你问路，Kademlia 路由表本就是公开信息；能加密的只有装在里面的 value |
| 3 | **加密救不了封锁**：链路是 `bootstrap ping → FIND_NODE → putEncrypted(ICE offer) → ICE → TLS → 消息`，前三步全在明文控制面。DPI 只需识别并丢弃 L0 的控制包，L3 的 TLS 根本建立不起来。**它打的是前置依赖，不是密码学**；且元数据（谁在跑 OpenDHT、往哪个 hash 写、和谁通信、公网 IP、在线时间）全在明文层，加密保护的是内容，不是「你在不在用 Jami」 |

> 本节仅作协议可达性的技术边界分析，不提供任何规避网络管理的操作指引；实际部署与使用应遵守所在地区法律法规。

### 8.8 能否把底层通信伪装为 WebRTC / 443（2026-11-15 22:30 追加核准）

「伪装」有三个层次，技术含义与代价完全不同，逐层裁定：

| 层次 | 具体动作 | 现成程度 | 判定 |
|---|---|---|---|
| ① 只改端口 | 把 `Account.dhtPort` 或引导地址端口写成 443 | 配置可改：`jamiaccount_config.h:48` 默认 `0`（本地随机监听）；UPnP 随机范围 `DHT_PORT_RANGE {4000, 8888}` | ❌ **无效且更糟**：443 上跑明文 msgpack + `v:"o2"` 比原端口更显眼；Linux 绑定 443 需特权；端口范围本就不含 443 |
| ② 给控制面套 TLS / WSS | 把 OpenDHT 的 UDP 报文装进 TLS 443 或 WebSocket | **0 现成**：OpenDHT 传输抽象只有 `net::UdpSocket` + `sendTo()`，无 TCP / TLS / WS 后端 | ❌ 属**协议层改造**：要新增握手、证书、对端能力协商、分片与保活 |
| ③ 换成 WebRTC 协议栈 | ICE + DTLS-SRTP + SCTP DataChannel + SDP/JSEP | 只有**同名零件**：PJNATH ICE、GnuTLS DTLS、DTLS-SRTP profile、SIP 的 SDP | ❌ 栈不兼容，且 WebRTC 需信令服务器；等于换协议而非伪装 |

| # | 源码核准事实 | 依据 |
|---|---|---|
| 1 | Jami 里的 "WebRTC" 只指 `webrtc-audio-processing`（AEC / VAD 音频处理库），与 WebRTC 传输无关 | `meson_options.txt:16`；`doc/adr/ADR-003:17-19`；`contrib/vcpkg/README.md:23-25` |
| 2 | 代码里所有 SDP 都是 SIP / PJSIP 的 SDP，不是 WebRTC 的 JSEP offer/answer | `sipvoiplink.cpp:79-89,1458`；`sipcall.h:192-194` |
| 3 | 已有 DTLS-SRTP profile 只用于 SRTP 密钥导出（媒体通道），不是 DataChannel | `dhtnet/tls_session.cpp:51-57,579-584,1759-1779` |
| 4 | 已有 `ice_config.tcpEnable = true` 是 ICE-TCP 候选，不是 443 或 TLS 伪装 | `dhtnet/connectionmanager.cpp:1090,1649-1650` |
| 5 | TURN 配置只有普通 URI，全树无 `turns:` / TURN-over-TLS / 443 相关实现 | `dhtnet/connectionmanager.cpp:1962-1994`；全树检索 0 命中 |
| 6 | daemon 全树无 WebSocket、HTTP Upgrade、ALPN、HTTP/2；restinio 只用于 OCSP 请求与 DHT proxy REST | `dhtnet/tls_session.cpp:790-795`；`opendht/dht_proxy_client.cpp:312-334`；全树检索 0 命中 |
| 7 | 设备间 TLS 是**双向证书** `GNUTLS_CERT_REQUIRE`，无 CA 链 / SNI / ALPN，与浏览器 HTTPS 指纹不同 | §8.7 L3；`dhtnet/connectionmanager.cpp:867-881` |
| 8 | `dhtnet_crtmgr` 里出现的 `port: 443` 只是「授权服务」示例注释，与监听或伪装无关 | `dhtnet/tools/dhtnet_crtmgr/main.cpp:150-159` |
| 9 | 官方无可插拔传输；「Add new transports (e.g. QUIC?)」仍只是点子 | `dl.jami.net/docs/developer/feature-requests.html` |
| 10 | 控制面报文仍是带 `v:"o2"` 的明文 msgpack，装进任何隧道后内容不变 | §8.1、§8.6 |

| 断点 | 为什么过不去 |
|---|---|
| 控制面是 UDP 且只有一种 socket | OpenDHT 全树仅 `UdpSocket`（`network_utils.h:57,124-128`）；要换 TLS 443 得改库，不是改配置 |
| 陌生节点必须能读懂 `FIND_NODE` | Kademlia 路由表本就公开；若把问路集中到网关，就重新变成服务器依赖，且冷启动第一跳仍在 |
| 443 要「像 HTTPS」才有用 | 需合法证书、SNI、ALPN、HTTP 语义与相符的 TLS 指纹；裸 TLS 或明文 msgpack 在 443 上一眼可辨，比放原端口更容易被分类 |
| 双向证书 TLS 与浏览器不同 | 客户端证书、GnuTLS 密码套件优先级、无 ALPN ⇒ JA3/JA4 指纹自成一类 |
| 流量画像对不上 | DHT 是与大量不同 IP 的周期性短小 UDP 包、上下行对称；WebRTC 是少量对等连接加 RTP 恒定码率；HTTPS 是请求-响应。换端口不改包长分布与时序 |
| 全网必须同版本 | 协议变更要求所有节点同步升级，否则分叉成私有网络（与 §8.6 NetId 结论一致）；公共 OpenDHT 节点回包仍带原特征 |
| 项目硬约束 | 引入常开网关 / 信令 / 中继等于额外固定设施；改协议脱离 F-901 规格 v2 与 F-903「不改协议、不改消息平面」基线 |

**裁定**：技术上开源可改，工程上却是**重写传输层 + 全网协议升级**，不是「加个伪装配置」。与项目「不部署服务端、节点同构、无常开节点、不改协议」硬约束冲突，**V1.0 不做**。外部隧道（VPN / 代理）属于 OS 层能力而非 Jami 支持，且 UDP / ICE / TURN 问题依旧，同样不在范围内。

**与 §8.6、§8.7 并列的三条口径**：改 `o2` 无效、加密救不了封锁、改端口或套 WebRTC 外壳也救不了——三者都只动「门牌号 / 外衣」，控制面 `v:"o2"` 与流量结构不变。真正的抗识别需要整体改传输形态（padding、长度与时序随机化、可插拔传输），属协议层单独立项，不是 V1.0 的功能开关。

> 本节仅作协议可达性的技术边界分析，不提供任何规避网络管理的操作指引；实际部署与使用应遵守所在地区法律法规。

## 附录 A 源码索引

| 主题 | 文件与行号 |
|---|---|
| 默认设施常量 | `jamiaccount_config.h:22-26,34-40,49-68` |
| 引导节点加载与调用 | `jamiaccount.cpp:1807-1817,2039-2040` |
| DHT 配置 / 状态持久化路径 | `jamiaccount.cpp:2110-2141`（`persist_path` 在 :2116） |
| 组播发现开关与启动 | `jamiaccount.cpp:1978-1995,4089-4135` |
| UPnP 映射 | `jamiaccount.cpp:2003-2036` |
| ConnectionManager 装配 | `jamiaccount.cpp:5096-5125` |
| ICE 准入校验 | `jamiaccount.cpp:2388-2423` |
| 名称服务 | `namedirectory.cpp:49-52,196-199` |
| ICE 信令经 DHT | `dhtnet/src/connectionmanager.cpp:717-788,827-837` |
| ICE 候选与 TURN/STUN/UPnP 选项 | `dhtnet/src/connectionmanager.cpp:649-651,1931-1953,1962-1993` |
| TLS / 通道复用 | `dhtnet/src/connectionmanager.cpp:841-872,1426`；ADR-002 |
| 组播地址与端口 | `opendht/src/peer_discovery.cpp:19-20,51-52`；`opendht/include/opendht/peer_discovery.h:23` |
| 组播发现后直接 bootstrap | `opendht/src/dhtrunner.cpp:246-287` |
| DHT 状态存/取 | `opendht/src/dht.cpp:73,2021,2049,2910-2961` |
| 命名通道入口 | `conversation_channel_handler.cpp:37`；`swarm_channel_handler.cpp:42`；`sync_channel_handler.cpp:40` |

---

## 附：公共节点归属与 boot 服务器失效时的入网能力（2026-11-21 源码核准）

用户两问：①前面 DHT 节点清单里的"公共 DHT 节点"是不是 Jami 提供的？②若 `bootstrap.jami.net` 被封，能否直连 DHT 节点入网？以下均为源码事实，带行号。

### A. 节点归属：bootstrap 域名由 Jami 硬编码，公共节点 IP 不由任何库内置

| 项 | 结论 | 源码证据 |
|---|---|---|
| `bootstrap.jami.net` 定义 | daemon 全仓**仅一处**硬编码常量 `DHT_DEFAULT_BOOTSTRAP`，默认值赋给 `hostname` | `jamiaccount_config.h:22,34`；`jamiaccount_config.cpp:184`（空值回退） |
| 配置入口 | `Account.hostname`，分号分隔多项 | `account_const.h:119`；`account_config.cpp:163`；`loadBootstrap()` 按 `';'` 切分 `jamiaccount.cpp:1807-1817` |
| daemon 默认 bootstrap | **只有域名，无硬编码 IP** | 全仓 IPv4 字面量均为注释/示例 |
| 那批 4222 公共 IP（203.0.113.220 / 203.0.113.137 / 203.0.113.232 等） | **不是 OpenDHT 库内置默认节点**——逐个在 OpenDHT v4.4.1 全树搜索 **0 命中**；库内只有端口常量 `DHT_DEFAULT_PORT=4222`（`network_utils.h:29`）和文档/示例里的 `bootstrap.jami.net:4222` | `opendht/include/opendht/network_utils.h:29`；`opendht/doc/dhtnode.1:168`、`docker/*`、`tools/systemd/*` 仅示例 |
| 动态拉取 bootstrap 列表 | `bootstrapListUrl = https://config.jami.net/bootstrapList` 字段存在，但**全仓无 HTTP 拉取消费者**（仅定义/key/parse 三处），是遗留/预留配置 | `jamiaccount_config.h:52`；`account_const.h:155`；`jamiaccount_config.cpp:185` |

**结论**：bootstrap 域名（`bootstrap.jami.net`）是 Jami 官方提供的入口；清单里那批公共 IP 与其的对应关系属 DNS/服务端范畴（源码无证据），**不是**打包进 OpenDHT 库的默认节点。dhtnet 库另有一个独立默认域名 `bootstrap.sfl.io`（`dhtnet/src/connectionmanager.cpp:98`），正常 Jami 运行时复用账户的 `dht_`，不走它。

### B. boot 服务器失效时的入网能力：能直连 IP:port，但缓存不会自动当引导

| 路径 | 能否 | 源码依据 |
|---|---|---|
| hostname 直接填 `IP:port` 作 bootstrap | **能**。`loadBootstrap()` 原样切分 → `dht_->bootstrap(string)` → `splitPort` → `getaddrinfo(host,service)`，数字 IP 字面量无需该域名 DNS；端口填 0 时自动补 4222 | `jamiaccount.cpp:1811-1813,2040`；`opendht/src/dhtrunner.cpp:1009-1017`；`opendht/src/utils.cpp:40-80`；`opendht/src/dht.cpp:2286-2301` |
| 填多个节点 | **能**，`"IP1:4222;IP2:4222"` 分号分隔逐个 bootstrap | `jamiaccount.cpp:1812` |
| `dhtstate` 缓存的 good 节点在 boot 失效时自动重连 | **不能**。`loadState()` 只 `network_engine.insertNode()` 预热路由表，**从不写 `bootstrap_nodes`**；周期重连 `Dht::bootstrap()` 只遍历 `bootstrap_nodes`（唯一写入口 `addBootstrap()`，即 hostname）。缓存节点全过期后无自动重入网路径 | `opendht/src/dht.cpp:2924-2962`（loadState）、`2270-2306`（bootstrap）；`opendht/include/opendht/dht.h:91-97,411` |
| 绕过 bootstrap 直接 insertNode | 库底层有 `Dht::insertNode` / `bootstrap(vector<NodeExport>)`（注释"用于从已知节点高效 bootstrap"），但 **Jami daemon 未暴露也未调用**，唯一入口是 hostname | `opendht/include/opendht/dht.h:100-105`；`dhtrunner.cpp:1028-1092`；全仓搜索确认 jamiaccount.cpp 仅 `2040` 一处 bootstrap |
| DHT HTTP 代理（备用入网） | **存在**。`proxyEnabled` + `dhtproxy.jami.net:[80-95]` 默认，`bootstrapListUrl`/`proxyListUrl` 从 `config.jami.net` 拉列表（24h×3 缓存 `dhtproxylist`），失败回退默认；`config.proxy_server` 注入 dht 配置 | `jamiaccount_config.h:56-57`；`jamiaccount.cpp:3252-3275,2128,2042-2048` |
| 组播本地发现 | **存在**，`peer_discovery.cpp` 组播地址 + `dhtrunner.cpp:246-287` 组播发现后直接 bootstrap，可绕过域名（限同网段） | `Jami节点发现与连接建立机制.md` 前文表格行 |

**对本项目（F-903 导入节点入网）的意义**：用户手填 `IP:port` 到 hostname 是**唯一零代码**的"boot 域名被封后直连已知节点"路径，已在方案 F-903 记录。但要注意：①必须显式写端口（写 0 虽补 4222，但对方实例未必在 4222）；②缓存节点**不会**自动顶替 bootstrap，所以"先连过一次、以后域名被封还能靠缓存自愈"**不成立**；③ICE 建连的 offer 经 DHT `putEncrypted("peer:"+id)` 发（`dhtnet/src/connectionmanager.cpp:755-788`），DHT 不通则 P2P 信令也失效——所以入网是通讯的前置条件，不是可选项。

---

> 文档状态：源码核准完成（2026-11-13；2026-11-21 补"公共节点归属与 boot 失效入网能力"一节），**未修改任何功能代码**。F-901 公共聊天室仍为「方案已定、待开工，功能代码 0 行」；F-902 本地消息库加密处于【暂缓，禁止启动开发】。
