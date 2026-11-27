# Jami 源码与六项需求的差距分析

分析对象：jami-daemon master（2026-11 快照，156 个 .cpp / 206 个 .h，C++）+ jami-client-qt master，含 doc/adr 全部 12 份架构决策记录
分析时间：2026-11-02 ｜ 源码位置：`jami-src/`（GitHub 官方镜像 savoirfairelinux/jami-daemon、jami-client-qt）

---

## 〇、先说结论

**Jami 与需求的总体差距比之前"从文档推断"的要小，但性质更微妙：它在架构上比想象的更去中心化（消息是 Git 仓库多副本复制，离线补收有原生方案），但在加密现代化和抗封锁两方面比宣传的更薄弱（无双棘轮 E2EE、零抗审查代码）。**

六项需求逐条判定：

| # | 需求 | 判定 | 差距性质 |
|---|---|---|---|
| 1 | 无中心服务器 | ⚠️ 部分满足 | 架构上可全替换，**默认值全部指向 jami.net**，差距在"运营现实"而非"代码能力" |
| 2 | 通讯安全 | ⚠️ 部分满足 | 认证与完整性扎实（证书链+逐消息签名），**缺现代 E2EE（无双棘轮/无每消息前向保密）** |
| 3 | 性能好 | ✅ 基本满足 | 直连 P2P 低延迟、文件无大小限制支持断点续传，是六项里最强的一项 |
| 4 | 大规模用户 | ❌ 不满足 | 群基于 Git 仓库全量复制 + 每会话路由表，设计目标是"小圈子"，不是万人群 |
| 5 | 中国+全球使用 | ❌ 不满足 | 默认 bootstrap/TURN/proxy 在中国不可达，且无任何替代引导机制 |
| 6 | 抗封锁 | ❌ 不满足 | **全仓库零抗审查代码**（无混淆/WebSocket/Tor/443 伪装），比预期更差 |

---

## 一、代码证实的架构事实（改变此前认知的部分）

### 1.1 消息系统比想象的先进：会话 = 签名 Git 仓库（ADR-001，2019 至今）

这是读源码最大的发现。Jami 的每个会话（1v1 和群组统一叫 swarm）是一个 **libgit2 仓库**，在多设备间多点复制：

- 消息 = commit（JSON 内容写在 commit message 里避免合并冲突）；会话 ID = 初始 commit 哈希；
- 成员/管理员/邀请/拉黑/吊销列表全部是仓库树里的文件（`admins/`、`members/`、`banned/`、`CRLs/`）；
- **每个 commit 由作者设备密钥签名**，fetch 后逐条验签（`conversationrepository.cpp:998-1029`），非法历史直接丢弃；
- 离线用户上线后通过 `git fetch` 从任意持有副本的 peer 补收全部历史（`conversationrepository.cpp:3671`、`conversation_module.cpp` startFetch/deferFetch 队列）。

**意义**：这直接回应了此前评估里"纯 P2P 无法离线收消息"的判词——Jami 用"多副本 Git 仓库 + 上线 fetch"给出了原生解法。只要群内任何一个在线成员（或你自己的另一台设备）持有副本，离线消息就能补收，不需要任何服务器。这一点比 Tox/Jami-旧架构强，设计上是超前的。

- ADR-005（2022）：DHT 已彻底退役消息承载职能，只做发现与信令（设备公告、trust 请求、连接请求）。
- 代价（ADR 原文承认）：**历史不可删（"删除意味着退群"）、仓库无限增长、pack 与 fetch 成本固有**。这是"会话即账本"模型的原罪，直接封顶了群组规模。

### 1.2 加密：认证扎实，但停留在"TLS 时代"（代码级确认）

搜遍全仓库，无 double ratchet / X3DH / Signal 协议任何实现。现有保密性由三层构成：

1. **传输层**：每个 peer 设备间一条认证 TLS 连接（ADR-002），证书由账户私钥签发的 X.509 链认证；
2. **应用层**：每个 commit（消息）由作者设备密钥签名——提供**不可抵赖的完整性与真实性**，但消息正文的机密性依赖 TLS 通道本身；
3. **媒体**：SRTP，密钥经 DTLS 协商。

缺陷判定：
- **消息级无前向保密**。TLS 层若启用 ECDHE 套件可给会话级 PFS，但长期身份密钥（CA key）泄露 + 历史流量留存（TLS 记录无棘轮混合）时，历史消息可解密的风险结构存在；且离线暂存在接收设备上的消息本来就依赖本地加密存储。这与 Signal/MLS 的"每消息棘轮"有代差。
- 对照此前报告结论不变：**若以 Jami 为基础，加密层需要换成 Double Ratchet（1v1）或 MLS（群组），等于替换其消息语义内核，只保留 Git 仓库载体**——这是可行的移植方向（commit 内容 JSON 里换密文字段），但属于协议级改动（ADR-001 明示"改 commit 类型即协议变更，旧 peer 拒收"）。

### 1.3 中心化依赖：全部是可配置的默认值（代码确认），但没有"逃生通道"的产品化

`src/jamidht/jamiaccount_config.h` 逐行确认：

```cpp
DHT_DEFAULT_BOOTSTRAP = "bootstrap.jami.net"
DEFAULT_TURN_SERVER   = "turn.jami.net"  (user/pwd/realm 全是 "ring")
bootstrapListUrl      = "https://config.jami.net/bootstrapList"
proxyServer           = "dhtproxy.jami.net:[80-95]"
proxyListUrl          = "https://config.jami.net/proxyList"
```

- 好的一面：以上**全部**经 YAML 配置/`setConfigDetails` API 可覆盖（`jamiaccount_config.cpp` 的 SERIALIZE 链证实）；name server（`namedirectory.cpp:52` `https://ns.jami.net`）同样可换。name server 弃用不影响使用（直接用 40 位 ID 通信）。
- 坏的一面（代码层面确认的两个硬点）：
  1. `src/plugin/store_ca_crt.cpp` 内嵌官方 CA 根证书作为信任锚（插件签名验证用）——纯自用无碍，做衍生产品则涉及信任模型重做；
  2. 官方客户端没有任何"引导用户配置替代 bootstrap"的产品化路径。在中国，一个普通用户装了 Jami = 连 bootstrap.jami.net 失败 = 界面直接显示红色离线状态。**架构给了后门，但没给门把手**——fork 版本必须内置替代引导/多 bootstrap/内置 IP 列表才能落地。

### 1.4 NAT 穿透与传输层：pjproject ICE + TURN 兜底，零混淆（代码确认，与文档判断一致）

- 音视频与数据通道共用 pjproject 的 ICE/STUN/TURN（`sipvoiplink.cpp` 调 pjstun；`jamiaccount.cpp` 传入 turnServer）+ UPnP 端口映射（`dhtnet::upnp::Mapping`）。
- **全仓库（daemon + Qt 客户端）搜 obfuscation/obfs/tor/i2p/websocket(作为传输)/443 伪装：零命中**。唯一的 WebSocket 是 Qt 客户端给本地 API 用的 localhost 监听，与网络传输无关。
- 判定不变：对称 NAT 下退官方 TURN（默认值在中国不可达）→ 实际效果是"中国移动网络用户大概率直接失联"，而不是"降质可用"。

### 1.5 群组规模：代码无硬上限，但架构封顶在"几十人"量级

- 无 `MAX_MEMBERS` 常量；swarm 用每会话的分布式路由表 DRT（ADR-007，仿 OpenDHT Kademlia，bucket 仅容 2 节点、移动设备缓存上限 128）组织成员连接。
- 群通话 = 单设备主持的 rendezvous 会议，**主持人设备混音全部媒体**（ADR-004 原文："quality and capacity depend on that device, and the call ends with it"）——无 SFU，群通话规模由最强的一台成员设备决定，且主持人退会全员通话结束。
- 叠加 1.1 的"Git 全量历史复制"，结论：**设计上支持的是 8~50 人量级的小圈子 swarm（官方宣传口径也是 groups up to 8 members 起步的迭代史），与"万人群/百万频道"无交集**。

### 1.6 两年内的演进（doc/adr 证实项目仍活跃，方向值得注意）

| 时间 | ADR | 内容 | 对需求的意义 |
|---|---|---|---|
| 2024-11 | ADR-008 | msg: 通道（msgpack）替代 SIP MESSAGE | 已读回执/typing/commit 通知轻量化 |
| 2024-12 | ADR-009 | 新设备配对改走 `jami-auth://` P2P 传输归档，不再发布 DHT | 减少 DHT 依赖，方向正确 |
| 2026-07 | ADR-010 | **Y-CRDT 协作文档**（libyrs/Rust）：群内实时协同编辑，文档也是 Git 仓库 | "Git 仓库万物容器"路线的延续 |
| 2026-09 | ADR-011 | SIP 通话 Wi-Fi↔蜂窝切换 handover | 移动端可用性改进 |

演进方向全部在"深化 Git 模型 + 完善 P2P 细节"，**没有任何一条投向抗审查**。这与之前"roadmap 无审查环境专项"的判断一致，现在有了 ADR 级的证据。

### 1.7 其余功能盘点（与需求相关部分）

- **文件传输**：P2P 直连、无大小限制、支持断点续传（start/end 分片）、SHA3 校验（`data_transfer.cpp`、`transfer_channel_handler.cpp`）——质量好于多数 IM。
- **多设备**：账户 = 多设备证书集合；`sync://` 通道同步账户状态；归档加密（Argon2 派生密码）。设备数无硬上限，但每台设备都是 Git 仓库副本节点，设备越多 fetch/同步成本越高。
- **插件**：C ABI 动态库（非 JS），带签名校验，可做功能扩展——对中国化改造是个可用的扩展点。
- **SIP 兼容**：可同时做 SIP 硬电话/网关客户端（媒体栈 pjsip 的副产品）。

---

## 二、差距清单（按"若要达标需要动什么"组织）

| 差距 | 严重度 | 需要的动作 | 工作量级 |
|---|---|---|---|
| G1 默认 bootstrap/TURN/proxy 全指 jami.net，中国不可达 | 🔴 阻断 | fork 后内置自建 bootstrap 列表 + 多活 bootstrap + 内置备援 IP/域名池；TURN 自建或换 Cloudflare TURN 类免费服务（可依赖但不依赖） | 小改动大收益，2-4 周 |
| G2 零抗审查传输层 | 🔴 阻断 | 新增传输通道：把 dhtnet 的 TLS 连接与 SIP/RTP 承载改写进 WSS-443/WebRTC 数据通道封装。dhtnet 已是"TLS 连接上复用通道"架构（ADR-002），**给整条 TLS 连接外面套一层 WebSocket/HTTPS 壳是所有改动里性价比最高的一处**；音视频的 SRTP over DTLS 本身可跑在 ICE-TCP/443 或 TURN-WSS 上 | 大，3-6 人月，全项目最硬的活 |
| G3 消息无每消息前向保密（无双棘轮） | 🟠 高 | 在 commit JSON 内容字段内引入 Double Ratchet（库：libsignal / Olm / vodozemac），Git 仓库载体与签名机制不动——commit 密文化后 Git 模型天然兼容（密文对 git 是不透明字节） | 中，2-3 人月，接口侵入面可控 |
| G4 群组规模架构封顶小圈子 | 🟠 高（若坚持大规模） | DRT + Git 全量复制模型无法靠调参扩展；万人群需引入分片/摘要节点或分层中继，属重新设计，**建议直接修改需求为"≤200 人群组"** | 不建议做 |
| G5 群通话单主持人混音瓶颈 | 🟡 中 | 引入 SFU（自建或 P2P 级联）；ADR-004 已预留 rendezvous 抽象 | 大，独立子项目 |
| G6 中国 Android 推送依赖 FCM（经 dhtproxy 中转） | 🟡 中 | 国内推送通道（厂商 push）只能插件化外挂或放弃（保持"打开 app 才能收"的 Briar 式定位） | 中，看取舍 |
| G7 内置 CA 信任锚 | 🟢 低 | 纯自用/私有部署无碍；公开分发 fork 需自建 CA + 兼容官方证书信任（双信任链） | 小 |

---

## 三、判定：拿 Jami 当底座，还是自研？

**代码读完后的修正判断：Jami 是"值得 fork 的底座"，优先级上调至高于纯自研 libp2p 方案。** 理由：

1. **最难补的课它已经修完了**：去中心化身份（离线可生成、无手机号）、多设备同步、NAT 环境下的 P2P 连接治理（10 年实战的 ICE/UPnP/peer 管理代码）、离线消息补收（Git 多副本 fetch）——这些在自研路线里各是 2-3 人月的坑，Jami 全部有生产级实现。
2. **它缺的三课是"可插拔位置"的**：抗审查传输（给 TLS 连接套 WSS 壳）、现代 E2EE（往 commit 内容里塞棘轮）、bootstrap 运营（配置替换）。三项相互独立，可分期做。
3. **GPLv3 是战略决定而非技术障碍**：若接受"衍生产品必须开源"（对定位抗审查的工具反而合理，可审计性也是卖点），则无法律障碍；纯内部部署不受传染。
4. 对照组：libp2p 自研路线（上一份报告的方案）在传输伪装上确实更自由（原生 WebRTC/relay 生态），但发现层、身份、群组同步、多设备、离线补发全部要从零写——两年内到不了 Jami 今天的完成度。

**建议路径（fork 路线里程碑）**：

- M1（2-4 周）：私有部署 bootstrap + TURN + 改默认配置的 fork 客户端，先在真实中国移动网络测 ICE 成功率基线；
- M2（2-3 月）：dhtnet TLS 连接的 WSS-443 封装通道 + 自动降级链（直连 QUIC/TCP → WSS 伪装 → TURN-WSS）；
- M3（2-3 月）：commit 载荷级 Double Ratchet（复用 Olm/libsignal），完成一次第三方审计；
- M4（按需）：SFU 群通话、国内推送取舍、iOS 端（Apple 审核对无服务器 IM 的政策需单独评估）。

**风险对冲**：Jami 上游活跃且方向与你的需求不冲突（继续深化 Git/P2P），fork 与上游的合并成本可控；真正的一次性分叉点在 M2 的传输层改造。

---

## 附：证据文件索引

- 默认服务器常量：`jami-daemon/src/jamidht/jamiaccount_config.h:22-60`
- 可配置性：`jami-daemon/src/jamidht/jamiaccount_config.cpp`（SERIALIZE_CONFIG 链）
- Git 会话模型：`doc/adr/ADR-001`、`src/jamidht/conversationrepository.cpp:824-1029,3671`
- DHT 退役消息：`doc/adr/ADR-005`
- 通道复用：`doc/adr/ADR-002`；msgpack 通道：`doc/adr/ADR-008`、`src/jamidht/message_channel_handler.*`
- 会议主持混音：`doc/adr/ADR-004`；DRT：`doc/adr/ADR-007`、`src/jamidht/swarm/routing_table.h:59`
- ICE/TURN/UPnP：`src/sip/sipvoiplink.cpp:1620-1677`、`src/jamidht/jamiaccount.cpp:1017-1022,2000-2035`
- 无抗审查代码：全仓库 grep `obfuscat|obfs|tor|i2p|webrtc(传输)` 零命中；`apiserver.cpp:725` 仅 localhost
- 无 E2EE 棘轮：全仓库 grep `ratchet|X3DH|Signal|forward.secret` 零命中
- 文件传输：`src/data_transfer.cpp:39-42,148-189`、`src/jamidht/transfer_channel_handler.cpp:131-223`
- 插件：`src/plugin/jamiplugin.h`、`pluginloader.cpp`（C ABI + dlopen + 签名校验）
- CA 信任锚：`src/plugin/store_ca_crt.cpp`
