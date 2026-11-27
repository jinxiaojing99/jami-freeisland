# Jami 系统整体方案（基于源码）

基于：jami-daemon master + jami-client-qt master 源码（本地 `jami-src/`），全部关键论断附代码/文档证据
编写：2026-11-02

---

## 〇、系统一句话

**Jami 是一个"以 X.509 证书为身份、以签名 Git 仓库为消息载体、以认证 TLS 直连为传输"的 P2P 通讯系统。** 没有服务器存储任何消息；每个用户（设备）既是客户端也是网络节点；所有数据在设备间点对点复制与验证。

---

## 一、总体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        用户设备（每个都是节点）                      │
│  ┌─────────────┐  ┌──────────────────────────────────────────┐  │
│  │  UI 客户端   │  │            jami-daemon（核心）             │  │
│  │ (Qt/QML/    │  │  ┌──────────┐ ┌──────────────────────┐   │  │
│  │  Android/iOS)│──│  │ 账户管理   │ │  会话仓库层 (libgit2)   │   │  │
│  └─────────────┘  │  │ 证书/密钥  │ │  消息=commit/签名     │   │  │
│                    │  └──────────┘ └──────────────────────┘   │  │
│                    │  ┌──────────────────────────────────────┐ │  │
│                    │  │  dhtnet 传输层：ICE/STUN/TURN + UPnP  │ │  │
│                    │  │  + 认证 TLS 连接 + 多路复用通道        │ │  │
│                    │  └──────────────────────────────────────┘ │  │
│                    │  ┌──────────────────────────────────────┐ │  │
│                    │  │  媒体栈 pjproject：RTP/SRTP/Opus/VP8  │ │  │
│                    │  └──────────────────────────────────────┘ │  │
│                    └──────────────────────────────────────────┘  │
└───────────────┬───────────────────────────────────┬─────────────┘
                │                                   │
        ┌───────▼────────┐                   ┌──────▼──────┐
        │  直接 peer 设备  │◄─── P2P 直连 ───►│  其他 peer 设备 │
        │ (ICE 洞穿成功)   │                   │ (任意数量)   │
        └────────────────┘                   └─────────────┘
                ▲
        ┌───────┴────────┐
        │ 可选降级/辅助角色：│
        │ 1. bootstrap 节点（入网，可自建）  │
        │ 2. TURN 中继（洞穿失败，可自建）    │
        │ 3. name server（名字解析，可弃用）  │
        └────────────────┘
```

三个"非必需但默认存在"的服务器角色（bootstrap/TURN/name server）全部可自建替换，**但网络中没有任何组件存储用户消息**——这是与 Signal/Telegram 类系统的本质区别。

---

## 二、身份体系：没有账号系统，只有密码学身份

### 2.1 账户 = 一对自签 CA 证书

- 创建账户时**离线生成**：一个账户根证书（X.509 self-signed CA）+ 私钥，全程不需要网络、不需要手机号/邮箱（`account.cpp` 账户工厂）。
- 账户 ID = 根证书公钥的哈希（即对外显示的 40+ 位 ID）。**ID 本身就是公钥指纹**——知道 ID 就等于知道如何验证该账户签发的任何设备证书。

### 2.2 设备 = 账户签发的子证书

- 每台设备（手机/PC/平板）由账户根证书签发一张**设备证书**，组成两级证书链：`账户证书 → 设备证书`。
- 加载时强制校验：设备证书必须由账户证书签发、链可验证、私钥匹配（`account_manager.cpp:121-214`）。
- 设备数量无上限，但**设备私钥只在设备本地**；账户私钥在归档（`archive.gz`，Argon2 派生口令加密）里，用于签发新设备证书与群管理操作。

**安全含义**：冒充一个用户 = 拿到其设备私钥或账户根私钥之一，无"改密码重置"类服务端攻击面。

### 2.3 新设备加入

- 走 `jami-auth://` 深链 + P2P `auth:` 通道直接传输加密归档（ADR-009，2024-12），**不再把归档发布到 DHT**（旧做法的隐私缺陷已修复）。
- 新设备获得证书链后成为完全平等的仓库副本节点。

---

## 三、用户如何建立通讯机制（完整流程）

### 步骤 1：入网（Join DHT）

1. 设备启动 daemon，向 **bootstrap 节点**（默认 `bootstrap.jami.net`，可配自建）请求当前 DHT 网络中若干节点地址；
2. 与这些节点完成 **ICE + 认证 TLS** 连接，加入 **OpenDHT**（Kademlia 类 DHT 结构）；
3. 在本设备证书上定期向 DHT 做**在线公告**（presence），其他节点据此知道"谁在线、在哪个地址可达"。

> DHT 里只有：设备证书、地址公告、在线状态、trust 请求、CRL（吊销列表）。**没有任何消息内容**（ADR-005 明确规定 "Message payloads must not be reintroduced on DHT keys"）。

### 步骤 2：找到对方

三种方式（按隐私递减）：
1. **直接交换 ID/QR**：把对方 ID 输入或扫码（最隐私，零元数据泄露）；
2. **联系人列表**：把对方 ID 存入本地 contacts（`contact_list.cpp`），之后按名字显示；
3. **名字系统（可选，建议弃用）**：名字注册在 Ethereum 私有链（JamiNS）上，经 `ns.jami.net` 解析——依赖外部分布式账本，纯自用场景直接弃用。

### 步骤 3：建立 P2P 连接（ICE + 认证 TLS）

这是整个通讯机制的核心，分四档自动降级：

| 档位 | 方式 | 代码 | 说明 |
|---|---|---|---|
| ① 直连 | 双方公网/同 NAT 下直接连 | ICE host candidate | 延迟最低 |
| ② 洞穿 | STUN 打洞 | `sipvoiplink.cpp:1620` `pjstun_get_mapped_addr2` | 双方经公网 IP 打洞建立直连 |
| ③ UPnP | 路由器端口映射 | `jamiaccount.cpp:2000-2035` `dhtnet::upnp::Mapping` | 家庭宽带常见有效 |
| ④ TURN 中继 | 经 TURN 服务器中转密文 | `turn.jami.net`（可自建） | 对称 NAT 下唯一出路 |

连接建立后的关键一步：**两端以各自设备证书做 TLS 认证**（ADR-002："one authenticated TLS connection per peer device, Peer devices are authenticated once, at the TLS layer, by certificate"）。且会话仓库层做**证书 pinning**（`conversationrepository.cpp:3176` `repo->pinCertificates(true)`）——TLS 对端证书必须与 DHT 公告中该设备的已知证书匹配，**中间人换证书直接失败**。

### 步骤 4：通道多路复用（一条 TLS 连接跑所有业务）

这条 TLS 连接不是"一连接一会话"，而是复用出多个逻辑通道（ADR-002 + 各 `*_channel_handler.cpp`）：

| 通道 | 用途 | 文件 |
|---|---|---|
| `conversation` | 会话仓库的 commit 同步/抓取 | `conversation_channel_handler.cpp` |
| `msg:` | 轻量实时消息：新 commit 通知、typing、已读回执（msgpack 编码，ADR-008） | `message_channel_handler.cpp` |
| `sync:` | 多设备账户状态同步 | `sync_channel_handler.cpp` |
| `data-transfer:` | 文件传输（断点续传） | `transfer_channel_handler.cpp` |
| `ydoc:` | 协作文档同步 | `ydoc_channel_handler.cpp` |
| `auth:` | 新设备配对 | `auth_channel_handler.cpp` |

**设计收益**：peer 之间只需要维持**一条**加密连接，所有业务复用——降低 NAT 穿透次数、降低流量特征、提升移动端连接成功率。

### 步骤 5：通话/会议

- 1v1 通话：在已有 P2P TLS 连接上协商媒体参数（SDP），媒体流走独立 **RTP/SRTP**（密钥经 DTLS-SRTP 协商，`audio_rtp_session.cpp:224`），编解码 Opus+VP8。
- 群组通话（Swarm Call）：一个成员设备担任 **rendezvous 主持人**，混音全部媒体后转发（ADR-004）。主持人断线/退会 = 通话结束。

---

## 四、如何收发消息（会话 = 签名 Git 仓库）

### 4.1 数据模型（ADR-001）

每个 1v1 或群组统一叫 **swarm**，底层是一个 **libgit2 Git 仓库**，在群内所有设备的**本地磁盘**上多点复制：

```
swarm 仓库结构：
├── commit N   ← 一条消息（JSON 内容，type 区分：文本/图片引用/已读/邀请/拉黑…）
├── admins/    ← 管理员设备证书
├── members/   ← 成员设备证书
├── devices/   ← 成员名下全部设备证书
├── invited/   ← 邀请中的设备
├── banned/    ← 被拉黑设备
├── CRLs/      ← 设备证书吊销列表
└── (文件附件等作为 repo 内对象)
```

- 会话 ID = 初始 commit 哈希；
- **每条 commit 由作者设备私钥签名**；
- 历史是 DAG：**不可改写、不可删**（"删除消息"的语义 = 该消息从仓库视图消失需全员协作，仓库本身只增不减）。

### 4.2 发送消息（A 对 B 或群）

1. A 本地 `git commit`：消息 JSON 写入 commit message，用 **A 的设备私钥签名**（`conversationrepository.cpp:998-1029`）；
2. A 立即通过 `msg:` 通道向已连接的对端**推送"有新 commit"通知**（msgpack，不含正文）；
3. 对端收到通知 → 走 `conversation` 通道 `git fetch` 拉取该 commit → **验签** → 合入本地仓库 → UI 刷新。

对群：通知发给当前已在线的成员设备；离线成员的仓库**不需要实时收到**（见下）。

### 4.3 接收消息（离线补收机制）

1. **在线接收**：收到 `msg:` 通知 → fetch → 验签 → 合并。
2. **离线补收（关键能力）**：任何设备上线后，`conversation_module` 的 startFetch 队列会向**任意持有该仓库副本的在线设备**（其他群成员或自己的另一台设备）发起 `git fetch`（`conversationrepository.cpp:3671`），一次性补收全部缺失历史。
   - 也就是说：**离线消息不丢失，补收不依赖任何服务器**——只要群里有一个人在网上，或你自己在另一台设备（如手机）上有副本，PC 上线即可全量对齐。
3. **重试队列**：本地消息持久化在 msgpack 队列（`message_engine.cpp:236-269`），对端上线后 `onPeerOnline()` 触发重发（`:85-89`）。

### 4.4 已读回执 / 编辑 / 删除

全部是特殊 type 的 commit（走同样的签名+仓库复制路径），编辑/删除在 UI 层通过 commit 关联实现，**仓库历史本身不可变**——保证了审计性。

---

## 五、加密体系（三层 + 本地存储）

| 层 | 保护对象 | 机制 | 证据 |
|---|---|---|---|
| L1 传输层 | peer 连接上的一切（数据通道） | **认证 TLS**：每 peer 一条连接，证书 = 设备证书（账户签发链），双向认证；会话上下文强制证书 pinning | ADR-002；`conversationrepository.cpp:3176` |
| L2 消息层 | 每条消息的**真实作者与完整性** | **逐 commit 设备私钥签名**；fetch 后 `git verify-commit` 验签，失败整批丢弃 | `conversationrepository.cpp:2074`（Verify the signature）；ADR-001 |
| L3 媒体层 | 语音/视频/屏幕共享 | **SRTP**，密钥经 DTLS 协商 | `audio_rtp_session.cpp:224`；`media/srtp.h` |
| L4 本地存储 | 账户私钥与归档 | `archive.gz` 加密，口令经 **Argon2** 派生 | `jamiaccount_config.h:73-74`；`archive_account_manager.cpp` |
| L5 群信任 | 成员/设备/吊销 | 树文件变更逐一 `verifyCertificate`（gnutls 证书链+时间+CRL 校验） | `conversationrepository.cpp:113,1212,1223,1294,1861,2141` |

**明确不存在的**（全仓库搜索证实）：Double Ratchet / X3DH / 每消息会话密钥 / 信号协议。PFS 只依赖 TLS 套件（ECDHE 会话密钥），**无消息级前向保密**——这是 Jami 与现代 E2EE 的唯一代差。

---

## 六、如何防止中间节点窃听或篡改（威胁模型逐项）

这是本方案的核心保证。按攻击者位置分类：

### 6.1 攻击者在链路上（网络窃听者 / ISP / 路由器 / TURN 中继本身）

| 威胁 | 防御 | 机制细节 | 证据 |
|---|---|---|---|
| 窃听数据通道 | TLS 端到端加密 | TURN 中继（若被用到）只经手**密文**——TLS 端点是两台设备，证书属于设备而非 TURN，TURN 无密钥无法解密 | ADR-002 |
| 窃听 DHT | DHT 不载消息 | DHT 只存证书/公告/状态/吊销列表；消息载体（git 仓库与 commit）只在可信成员设备间复制 | ADR-005 "Message payloads must not be reintroduced on DHT keys" |
| 窃听通话媒体 | SRTP + DTLS | 媒体密钥与数据通道独立协商，即使数据通道被降级攻击，媒体仍独立加密 | `audio_rtp_session.cpp:224` |
| **中间人替换对端**（最危险） | 证书认证 + **pinning** | 连接必须出示"DHT 公告中该设备的、由该账户证书链签发的"设备证书；`pinCertificates(true)` 强制历史已知证书绑定——MITM 换证书即失败，且失败会被记录 | `conversationrepository.cpp:3176`；ADR-002 |
| 元数据暴露 | 部分防御，有残余 | TLS 对端 IP/流量大小/时序对 TURN 与网络观察者可见。**Jami 无流量混淆、无隐藏时序**（无 WebRTC/443 伪装通道）——这是中国场景的主要风险点 | 全仓库 grep 零命中（见差距分析） |

### 6.2 攻击者在仓库复制层（恶意成员设备 / 被攻破的设备）

| 威胁 | 防御 | 机制细节 | 证据 |
|---|---|---|---|
| 篡改消息历史 | 签名 DAG，篡改不可行 | Git 历史是哈希链 + 每 commit 签名；改任何一个字节，后续 verify-commit 全链失败，**整批 fetch 被丢弃**（不是静默降级） | ADR-001；`conversationrepository.cpp:2074` |
| 伪造"我发的消息"冒充其他成员 | 作者 = 设备证书，非用户名 | commit 作者身份 = 签名证书；接收端校验该证书属于仓库 `members/`+`devices/` 树内的可信设备（`validateDevice`），不在名单内或已吊销 → 拒收 | `conversationrepository.cpp:2166` validateDevice；`:113` gnutls 校验 |
| 伪造"拉我进群 / 改角色 / 拉黑某人" | 树文件变更逐证书校验 | 对 `admins/members/invited/banned/votes/CRLs` 的每次 commit 变更，`verifyCertificate` 独立验证新证书是否由该成员账户签发、链是否有效、是否已吊销 | `conversationrepository.cpp:1212,1223,1294,1861,2141,2148` |
| 群管理员作恶（恶意拉黑/移除） | 信任模型透明可审计 | 所有管理操作是签名 commit，群内任何设备可随时查看"谁在何时以哪个证书做了什么"；CRL 与 banned 树公开在仓库里 | ADR-001 树结构 |
| 设备证书泄露后继续冒充 | **CRL 吊销** | 吊销列表 `CRLs/` 树 + gnutls 验证时加载（`account_manager.cpp:131` load revocation lists），吊销后该设备所有 commit 与 TLS 连接被拒 | `account_manager.cpp:131,206-243` |
| 历史投毒（构造假历史骗新成员） | 首次 clone 全量验证 | 新加入者 clone 仓库后先**验证全部历史**再使用："once the clone has succeeded and been validated" | `conversationrepository.cpp:3067,3176` |

### 6.3 攻击者持有受害者的密钥（最坏情况）

| 威胁 | 现状（诚实评估） |
|---|---|
| 设备私钥泄露 | 设备证书吊销（CRL）后其余设备拒收其一切——**止损机制存在且是协议级** |
| 账户根私钥泄露 | 攻击者可签发任意设备证书。唯一止损 = 放弃该账户换新身份。**无密钥轮换方案** |
| 历史 TLS 记录 + 长期密钥泄露 | **无消息级 PFS**：这是残余风险的核心。现代 E2EE（Signal）通过每消息棘轮使历史流量对长期密钥泄露免疫；Jami 没有该层 |
| 成员设备本身被完全攻破 | 该设备本地仓库中的**消息明文可读**（消息只在传输层加密，成员是受信方）。群聊场景：一台被攻破的成员设备可读到全群历史。**Jami 不提供"群消息对普通成员加密、仅对管理员明文"的加密层**——与 MLS 的密钥分层模型有代差 |

### 6.4 防窃听/防篡改保证小结（一句话版）

- **链路上的任何人（含 TURN 中继、DHT 节点、ISP）只能看到密文与元数据**，因为端到端端点是设备证书而非任何基础设施；
- **中间人无法替换对端**，因为身份 = 密码学证书 + pinning；
- **消息无法被篡改**，因为每条消息是签名 commit、历史是哈希 DAG、验证失败整批丢弃；
- **身份无法被冒充**，因为伪造任何群操作都需要对应成员的私钥；
- **已知代差**：无消息级前向保密、群消息对受信成员本地明文、无元数据保护（流量混淆）——这三项正是 fork 改造的 M2/M3 目标。

---

## 七、多设备与群组治理（通讯机制的延伸）

- **多设备**：同一账户的 N 台设备 = 同一仓库的 N 个副本，经 `sync:` 通道（msgpack 版本快照）对齐账户状态；消息经任意路径（设备 A→B 直连 或 设备 A→DHT 发现→设备 C→设备 B）最终一致。
- **群组治理**：admins/members/invited/banned 全部是仓库内文件 + 签名操作；无中心管理员，**治理逻辑完全去中心化**（ADR-007 的 DRT 路由表决定"该问谁"）。
- **群规模**：DRT bucket 上限 2、移动节点缓存 128（`swarm/routing_table.h:59`）+ Git 全量复制 → 架构上限在几十人量级，无硬编码 MAX 但无扩展设计。

---

## 八、完整时序示例（A 给群发消息，含离线补收）

```
时间轴  A(发送方)                  DHT/网络                  B(在线)            C(离线,晚到)
──────────────────────────────────────────────────────────────────────────────────────
 t1     本地 git commit(签名)
 t2     msg: 通知 B ───────────────► 收到通知
 t3     (C 不在线, 不通知)                     git fetch commit
 t4                                            验签+validateDevice ✓
 t5                                            合入本地仓库, UI 显示
        ... B 离线, C 上线 ...
 t6                                  C: 从 DHT 发现 B 在线
 t7     C ──conversation 通道──► B: git fetch(全量/增量 refs/remotes/)
 t8                                            C 收到 A 的所有 commit
 t9                                            逐条 verify-commit ✓ → 合入 → UI 补齐历史
 t10     A 再上线, 从 B fetch 对齐自己可能缺的其他消息
```

---

## 九、方案评价：强项与代差（对照"六项需求"的技术结论）

| 维度 | 状态 | 说明 |
|---|---|---|
| 身份与信任 | ✅ 领先 | 离线生成、无中心注册、两级证书链 + CRL，是同类最干净的身份模型 |
| 消息完整性与真实作者 | ✅ 领先 | 逐 commit 签名 + DAG 不可篡改，审计能力超过多数商用 IM |
| 离线消息 | ✅ 原生解决 | Git 多副本 + 上线 fetch，不依赖任何服务器 |
| 传输层防窃听/防篡改 | ✅ 扎实 | 设备证书 TLS + pinning + SRTP |
| 消息级 PFS | ❌ 代差 | 无棘轮；长期密钥+历史流量组合下历史消息可解密（理论残余风险） |
| 群内成员隔离 | ❌ 缺失 | 成员设备本地明文，无 MLS 式分层密钥 |
| 元数据保护 | ❌ 缺失 | 无流量混淆/时序保护，中国场景最大短板 |
| 群规模 | ⚠️ 封顶 | Git 全量复制 + 单主持混音，几十人量级 |

**结论**：Jami 把"身份、完整性、离线、传输安全"四件事做到了 P2P 领域的最高完成度之一，缺的是"现代 E2EE 的密钥管理（棘轮/MLS）"与"元数据保护（流量伪装）"——恰好都是可以在其 Git 载体 + 通道复用架构上**插拔式补强**的位置（对应差距分析的 M2/M3）。

---

## 附：证据索引（源码相对路径）

- 身份/证书链校验：`src/jamidht/account_manager.cpp:106-243,418`
- 默认服务器常量（可配）：`src/jamidht/jamiaccount_config.h:22-60`
- 会话=Git 仓库 / commit 签名 / 验签：`doc/adr/ADR-001`、`src/jamidht/conversationrepository.cpp:998-1029,2074,3176,3067,2166`
- DHT 不载消息：`doc/adr/ADR-005`
- TLS 连接 + 通道复用 + 证书认证：`doc/adr/ADR-002`
- 实时通知 msgpack 通道：`doc/adr/ADR-008`、`src/jamidht/message_channel_handler.*`
- ICE/STUN/TURN/UPnP：`src/sip/sipvoiplink.cpp:1620-1677`、`src/jamidht/jamiaccount.cpp:1017-1022,2000-2035`
- SRTP：`src/media/audio/audio_rtp_session.cpp:224`、`src/media/srtp.h:74-83`
- 离线补收 fetch：`src/jamidht/conversationrepository.cpp:3671`、`src/jamidht/conversation_module.cpp:177-297`
- 消息重试队列：`src/im/message_engine.cpp:85-89,236-269`
- 树变更证书校验：`src/jamidht/conversationrepository.cpp:113,1212,1223,1294,1861,2141,2148`
- 群路由表参数：`src/jamidht/swarm/routing_table.h:59`、`src/jamidht/swarm/swarm_protocol.h:38`
- 群通话单主持：`doc/adr/ADR-004`
- 设备配对 P2P：`doc/adr/ADR-009`、`src/jamidht/auth_channel_handler.*`
- 归档 Argon2：`src/jamidht/jamiaccount_config.h:73-74`、`src/jamidht/archive_account_manager.cpp:63-120`
