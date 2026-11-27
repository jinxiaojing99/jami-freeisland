# Jami 接入韧性与 IPv6 优先方案（V1.1 独立特性 · 待立项）

> 输入：用户 2026-11-12 22:40 自建 Mesh 设想 → 22:51 四级优雅降级链 → 23:03 RFC 8445 边界核准 → 23:10 新增「支持 IPv6，可用时优先 IPv6 直连」。
> 本文只做需求与方案核准，**不含任何功能代码**。F-901 公共聊天室规格 v2 不受本文影响。

| 项目 | 内容 |
|---|---|
| 文档定位 | 接入层韧性特性的独立方案，与 F-901 功能层解耦，建议列 **V1.1 独立立项** |
| 核心结论 | 需求成立；Jami / PJNATH / OpenDHT **已具备双栈能力**，但默认优先级是 IPv4 优先、IPv6 兜底，需反转；标准依据是 **RFC 8421**，不必自研协议 |
| 最大风险 | **有 IPv6 全局地址 ≠ 可被入站直连**。RFC 6092 规定家用 CE 路由器默认 stateful default-deny 入站；「IPv6 可用」必须定义为端到端可达性，而非地址存在性 |
| 与 F-901 关系 | 零耦合。本文改的是「怎么建连」，F-901 改的是「建连后做什么」；本文不阻塞 F-901 开工 |
| 功能代码 | **0 行**。方案已核准，待拍板，未动任何源码 |
| 硬约束 | 节点同构、不指望任何节点常在线（22:34）；禁止新增锚点 / 常开节点 / 服务器 / 额外部署进程（17:17）；业务消息永远走同一条 swarm + TLS 通道 |

## 1. 结论速览

| 判定项 | 结论 | 依据 |
|---|---|---|
| 中国境内 IPv6 可用性是否支撑该需求 | **支撑，但需保留回退**。2025-12 活跃用户 8.69 亿 / 网民 77.38%，移动网 IPv6 流量 >70%；但固网仍依赖 IPv4，官方材料明确「可达不等于好用」 | 《中国IPv6发展报告(2026)》、CERNET 2026-10 |
| 是否需要新建网络层 | **不需要**。ICE / DHT / TLS 全部现成，只加「候选地址来源插件链 + 地址族优先级」 | 本文 §3、§4；ADR-006 |
| 是否需要改协议 | **不需要**。RFC 8445 候选优先级公式里的 `local_pref` 就是为地址族偏好设计的，RFC 8421 给出直接做法 | RFC 8445 §5.1.2.1、RFC 8421 §4 |
| 是否破坏与旧节点互操作 | **不破坏**。两端 `local_pref` 算法可不同，pair priority 用 `MIN(G,D)` 使 checklist 仍同步 | RFC 8421 §5 |
| 主要改动落点 | daemon 内 IPv4-first 默认值、候选优先级注入、四级来源插件链；**ICE 实现属外部仓库 dhtnet** | §3、§7；ADR-006 |
| 是否需要用户裁定 | **是**。三项，见 §9 | — |

## 2. 中国 IPv6 现状（2026-11-12 检索核准）

| 指标 | 数值 | 时点 / 来源 |
|---|---|---|
| IPv6 活跃用户 | 8.69 亿，占网民 77.38% | 2025-12，《中国IPv6发展报告(2026)》 |
| 移动网络 IPv6 流量占比 | 突破 70% | 2025-12，同上；另一口径 69.05% |
| 固定网络 IPv6 流量占比 | 超过 32% | 2025-12，同上；另一口径 33.29% |
| 全国总流量 IPv6 占比 | 34.55% | 2026-06，同上 |
| 家庭网关 IPv6 开启率 | 98.41%（另口径 96%） | 2026-06，同上 |
| 运营商定制路由器 IPv6 支持率 | 99.12% | 2026-06，同上 |
| APNIC IPv6 支持率 | 0.47%（2017）→ 54.3%（2025），全球第 28 | CERNET 2026-10 |

**口径差异说明**：不同来源对「活跃用户」「流量」「支持率」统计口径与时点不同，上表保留原值不合并。CERNET 同时明确指出三类深层问题：**固移发展不均衡**（固定宽带 / 专线 / 园区 / 政企仍严重依赖 IPv4，高校校园网尤甚）、**可达不等于好用**、**双栈并行成本高**。因此「IPv6 优先」必须做成**优先 + 快速回退**，不能做成 IPv6 单栈或硬切。

## 3. Jami 现有能力核准（全部本地源码行号）

| 分类 | 事实 | 源码位置 |
|---|---|---|
| 已具备 | OpenDHT 双栈，IPv4 / IPv6 节点状态独立上报，注册状态取 `max(s4,s6)` | `src/jamidht/jamiaccount.cpp:2166-2169` |
| 已具备 | 按地址族取发布地址 `getPublishedIpAddress(uint16_t family)` | `jamiaccount.cpp:4019-4022`、`jamiaccount.h:318` |
| 已具备 | SIP / SDP / TLS 支持 `pj_AF_INET6()`、`PJSIP_TRANSPORT_TLS6`、`UDP6` | `src/sip/sdp.cpp:356,483`；`sipaccount.cpp:655-657`；`src/jamidht/channeled_transport.cpp:43` |
| 已具备 | MTU 直接取 1232（注释写明「it's the IPv6 value」） | `src/sip/siptransport.cpp:166` |
| 已具备 | IPv6 字面量方括号处理 | `src/sip/sipaccount.cpp:1567` |
| 已具备（有前提） | IPv6 传输**跳过 STUN**，注释「STUN is not needed (no NAT)」，直接返回本地地址 | `src/sip/sipvoiplink.cpp:1622-1639` |
| **默认反着来** | `getPublishedIpAddress(AF_UNSPEC)` 注释原文「prefere IPv4 if available. It's more likely to succeed behind NAT」 | `src/sip/sipaccountbase.cpp:295-307` |
| **默认反着来** | 「Try IPv4 first, fallback to IPv6 for IPv6-only networks」 | `src/sip/sipcall.cpp:4296-4300` |
| **默认反着来** | loopback 隧道「prefer IPv4 loopback but fallback to IPv6」 | `src/jamidht/svc_tunnel_channel_handler.cpp:336-345` |
| **官方禁用** | IPv6 TURN 整段被注释：「first test with ipv6 turn was not concluant and resulted in multiple co issues... for now just disable」 | `src/sip/sipaccountbase.cpp:241-249` |
| 看不到 | ICE / TURN / UPnP 实现在外部仓库 **dhtnet**；本工程 Glob `ice_transport.h` / `connection_manager.h` / `pjnath*` 全部无结果 | 主设计文档 ADR-006 `:108,980-982,1268`；`contrib/src/dhtnet/rules.mak:6` |
| 看不到 | pjproject 是 SFL fork，vcpkg port 的 `CMakeLists.txt` 引用 `pjnath/src/pjnath/ice_session.c` 等，但源码在构建期从 tarball 解包，本地 checkout 未展开 | `contrib/vcpkg/ports/pjproject/CMakeLists.txt:144-160`；`contrib/src/pjproject/rules.mak:3-5` |
| 看不到 | `ConnectionManager::Config` 只有 dht / certStore / id / upnp / TURN / cache / factory，**无地址族优先级、无外部地址注入字段** | `jamiaccount.cpp:5097-5115` |

**关键区分**：上表「默认 IPv4 优先」的三处分别属于 SIP 通话初始化、loopback 服务隧道，**不等于 swarm 数据通道的地址族选择**；swarm 通道由 `dhtnet::ConnectionManager` 决定。因此不能简单地说「改这三处就实现 IPv6 优先」，必须先实测 swarm 通道的实际选路行为。

## 4. 标准依据（无需自研）

| 标准 | 与本需求的关系 | 关键条款 |
|---|---|---|
| RFC 8445 | ICE 核心。候选优先级公式 `priority = 2^24 × type_pref + 2^8 × local_pref + (256 − component_ID)` | §5.1.2.1；`type_pref` 推荐 host 126 / prflx 110 / srflx 100 / relayed 0 |
| **RFC 8421** | **本需求的标准答案**。多宿主与 IPv4/IPv6 双栈 ICE 指南（BCP，2018-07） | 明确「应通过调 `local_pref` 让 IPv6 候选排前」，实现 Happy Eyeballs 意图；**不得直接改 checklist**，否则两端检查表失步 |
| RFC 8305 | Happy Eyeballs v2：IPv6 先行 + 短延迟并行 IPv4，取最先成功 | RFC 8421 注明「ICE 的 pacing 会覆盖 8305 的时间建议」，只能借鉴语义 |
| RFC 8838 | Trickle ICE：候选边收边交换，支持运行中增量加远端候选 | 支撑「L1/L2/L3 慢慢吐候选」 |
| RFC 8839 | ICE 的 SDP offer/answer；`ice-options: ice2` 区分 8445 与 5245 对端 | 不带 ice2 按 5245 处理；Jami 是 PJNATH / 5245 语义 |
| RFC 8863 | ICE PAC：暂无候选对时不得立即宣告失败，须等待最短时间 | 正好是「优雅降级、耐心等候选」的标准依据 |
| RFC 6092 | 家用 CE 路由器 IPv6 **默认拒绝入站**（stateful default-deny） | 「有 GUA ≠ 可入站」的标准出处，见 §7 P0-1 |
| RFC 4941 | IPv6 隐私临时地址定期轮换 | 已发布候选会失效，见 §7 P0-2 |

**RFC 8421 的两个必须遵守的细节**：

1. **同类型多个候选的 `local_pref` MUST 唯一**，不能给所有 IPv6 候选同一个值。
2. **用「交织」而不是「全部 v6 排完再排 v4」**。建议公式 `Hi = (N_4 + N_6) / N_4`：若有 2 个 v4 候选、6 个 v6 候选，则每 4 个 v6 后插 1 个 v4。否则大量必然失败的 v6 候选会阻塞 IPv4 srflx 候选的连通性检查，反而拖慢整体收敛。
3. 两端 `local_pref` 算法可以不同：pair priority 公式含 `2^32 × MIN(G,D)`，checklist 排序结果在两端仍一致，**单端升级不会破坏与旧节点互操作**。

## 5. IPv6 优先方案

### 5.1 「IPv6 可用」必须分四级判定

| 级别 | 判据 | 探测手段 | 能否作为「可直连」 |
|---|---|---|---|
| L0 有地址 | 存在非 link-local、非 ULA 的 GUA（`2000::/3`） | 本地接口枚举 | 否 |
| L1 有路由 | 存在 IPv6 默认路由 | 本地路由表 | 否 |
| L2 能出网 | IPv6 出站连通（DHT v6 状态 / STUN over v6 / 探测服务） | `jamiaccount.cpp:2166` 的 s6 状态可复用 | 否 |
| L3 **能入站** | 对端能主动连到本机的 IPv6 地址和端口 | **dial-back 端点证明**：由已连 peer 或超级节点回连一次，带 nonce 签名 | **是，只有这一级算「可用」** |

只到 L0/L1 就宣布 IPv6 可用，是 Jami 现有 `sipvoiplink.cpp:1623`「no NAT on IPv6」注释的隐含假设，必须在实现中补上 L3。

### 5.2 四级来源 × 双栈 = 候选矩阵

所有来源统一产出带签名的元组 `(DeviceId, family, multiaddr, ts, TTL, sig)`，`family` 为本次新增字段。

| 来源 | IPv6 候选 | IPv4 候选 | 备注 |
|---|---|---|---|
| L0 Jami DHT / bootstrap | DHT v6 状态 + `getPublishedIpAddress(AF_INET6)` | DHT v4 状态 + STUN srflx | 现成，默认路径 |
| L1 MQTT 发现 | 带 v6 multiaddr 的签名通告 | 带 v4 multiaddr 的签名通告 | 需放宽硬约束，默认关 |
| L2 导入节点 IP | `[v6]:port` 字面量 | `v4:port` | 裸 IPv6 含冒号，与 `;` 分隔的 bootstrap 列表冲突，**必须方括号** |
| L3 局域网广播 | v6 link-local / ULA | v4 私网 | **link-local 与 ULA 必须降权或剔除**，不能跨网段 |

### 5.3 优先级裁定（对用户字面需求的一处修正）

用户要求「IPv6 可用则优先 IPv6 直连」。按 RFC 8421 落地为 **「IPv6 head start + 交织回退」**，而非「全部 IPv6 优先」：

| 顺序 | 候选类型 | 处理方式 |
|---|---|---|
| 1 | IPv6 host（GUA，已过 L3 入站探测） | `local_pref` 取高位，排在最前 |
| 2 | 少量 IPv4 host / srflx 交织插入 | 按 `Hi = (N_4+N_6)/N_4` 插入，保证坏 v6 路径不阻塞 |
| 3 | IPv6 link-local / ULA | 降到最后或直接剔除 |
| 4 | TURN relayed | `type_pref = 0`，永远兜底；**注意 Jami 的 IPv6 TURN 目前被禁用** |

**理由**：IPv4 侧有成熟的 TURN / UPnP / NAT 穿透兜底；IPv6 侧一旦 GUA 直连被防火墙挡住，当前没有可用的 v6 TURN 中继。若把全部 v6 候选排完再试 v4，最坏情况是每次建连都先等一批注定失败的 v6 检查超时。交织排序既满足「优先 IPv6」的产品语义，又保证快速回退。

### 5.4 建议配置项（daemon 内部，不轻易加 libjami ABI）

| 键 | 类型 | 建议默认 | 作用 |
|---|---|---|---|
| `preferIPv6` | bool | true | 反转 `getPublishedIpAddress(AF_UNSPEC)` 与 SIP 初始化的 IPv4-first |
| `ipv6ProbeMode` | enum | L3（入站探测） | 「可用」判定深度；调试可降到 L2 |
| `ipv6HeadStart` | int | 4 | 交织前连续排列的 v6 候选数 |
| `familyFallbackDelayMs` | int | 250 | v6 检查启动后并行启动 v4 的延迟（Happy Eyeballs 语义，ICE pacing 优先） |
| `ipv6TurnEnabled` | bool | false | 官方曾因 bug 禁用，重新启用前须实测 |

配置落点建议 `account_schema.h` + daemon 内部读取；**是否暴露到 libjami 公共头与 DBus/JNI/Nodejs/Qt 四套绑定待定**，避免无谓 ABI 变更。

### 5.5 降级状态机（沿用 22:51，补地址族维度）

| 阶段 | 行为 |
|---|---|
| 启动 | 四级来源并发 / Happy Eyeballs 式错峰启动，各自产出 v6 + v4 候选，统一入池 |
| 探测 | 对 v6 GUA 做 L3 dial-back；未通过者降权，不作为「可直连」 |
| 选路 | 交由既有 ICE 按 §5.3 优先级逐对检查、提名；**不自行改 checklist** |
| 失败 | 下一跳不通 → 反馈发起方 → 重新发起发现；RFC 8863 要求的最短等待期内不宣告失败 |
| 恢复 | L0 恢复后自动回切；IPv6 前缀变化 / 临时地址轮换触发候选老化与增量重灌（Trickle） |
| 全断 | 明确「离线」提示 + 本地待发队列，不无限重试 |
| 防抖动 | hysteresis + 冷却期，避免「IPv6 只是慢」就跳 MQTT 或跳 v4 |
| 展示 | UI 显示当前通道级别（L0~L3）与地址族（v6 直连 / v4 直连 / 中继） |

## 6. 四级优雅降级链（22:51 结论，本文不重复论证）

| 级 | 手段 | 与 IPv6 的关系 |
|---|---|---|
| L0 | Jami 现有 bootstrap + TURN + ICE | 双栈均可用；多 bootstrap 已支持 `;` 分隔 |
| L1 | 免费 MQTT 仅做发现 | 只交换带签名的 DeviceId ↔ multiaddr 通告，**绝不传消息**；默认关 |
| L2 | 导入节点 IP | 必须导入 multiaddr + DeviceId + 证书指纹，不是裸 IP；v6 用方括号 |
| L3 | 局域网广播 | `dhtPeerDiscovery` / `peerDiscovery` 开关默认 false，打开即可；仅同广播域 |

**铁律不变**：四级只产出候选地址，**业务消息永远走同一条 swarm + TLS 通道**。若 MQTT 变成第二套消息平面，消息不进 Git commit、不签名、不进仓库，切回 Jami 后历史对不上。

## 7. 风险清单

### P0（开工前必须闭环）

| # | 问题 | 后果 | 补救 |
|---|---|---|---|
| 1 | **有 GUA ≠ 可入站**。RFC 6092 家用 CE 路由器默认 stateful default-deny；国内光猫还常默认拦截未请求的 ICMPv6 / TCP / UDP 入站 | 按「有 IPv6 就直连」实现会导致大面积建连失败，且 `sipvoiplink.cpp:1623` 跳过 STUN 后连映射发现都没有 | 强制 §5.1 的 L3 dial-back 端点证明；未通过者只作为低优先级候选 |
| 2 | **地址不稳定**。RFC 4941 临时地址每日轮换；DHCPv6-PD 前缀在重拨 / 租约更新时整体变化 | 已发布候选与防火墙放行规则失效，长连接中断，候选池充满死地址 | 候选带 `ts` + `TTL` 老化；地址变化事件触发增量重灌（Trickle）；不用临时地址做长期通告，优先稳定 SLAAC / DHCPv6 保留地址 |
| 3 | **IPv6 TURN 被官方禁用**（`sipaccountbase.cpp:241-249`），坏 v6 路径目前没有 v6 中继兜底 | v6 直连失败后只能回退 v4 / v4 TURN，双栈优势打折 | 要么实测后重开 v6 TURN（官方注明有 bug），要么明确 V1.0 接受「v6 失败 → v4 兜底」并写进产品说明 |
| 4 | **防火墙 / 配置对称性**：OS 防火墙 v4 与 v6 策略独立；服务若只绑 `0.0.0.0` 不监听 v6；企业网络常只放行 TCP 80/443 + UDP 53 | IPv6 出网正常但入站永远失败；企业环境 STUN / TURN 非标端口全灭 | 双栈 bind 检查；企业场景需 TURN-over-TLS 443；L3 探测覆盖这一情形 |
| 5 | **dhtnet 跨仓库**（ADR-006）。`pj_ice_sess_add_cand()` 有 `local_pref` 参数、`create_check_list` 可传任意远端候选，但 `dhtnet::IceTransport` 是否把 `local_pref` 暴露给上层，本工程内无法确认 | 无法按小改动估算；若需改上游，还须 contrib bump | P0 实测：确认 dhtnet 是否可注入按地址族定制的 `local_pref`；不能则评估在 dhtnet 加一个注入接口 |

### P1（V1.0 前须有明确处理）

| # | 问题 | 处理 |
|---|---|---|
| 1 | link-local `fe80::/10`、ULA `fd00::/8` 混入 host 候选 | 剔除或降到最低优先级，否则产生大量必然失败的候选对，拖慢 ICE |
| 2 | IPv6 地址数量多（临时 + 稳定 + link-local + ULA）导致候选对乘积爆炸 | 候选裁剪 + §5.3 交织；同类型 `local_pref` 保持唯一 |
| 3 | 裸 IPv6 字面量与 `;` 分隔的 bootstrap 列表、`host:port` 解析冲突 | 统一 `[addr]:port`；复用 `sipaccount.cpp:1567` 的方括号处理 |
| 4 | 移动网络 NAT64 / DNS64 单栈 v6，对端 v4-only 无法直连 | 地址族不匹配时直接走 v4 回退 / TURN，不尝试跨族直连 |
| 5 | ICMPv6 被整体封禁会破坏 ND 与 PMTUD，导致大包静默丢失 | 产品文档说明；不要求应用侧解决，但验收需覆盖大消息传输 |
| 6 | **降级攻击**：攻击者阻断 L0 或伪造 v6 不可达，把用户逼到弱验证通道 | 所有通道一律设备证书链 + 签名身份断言，TLS/DTLS 不降级；UI 显示当前通道与地址族 |
| 7 | 反转 IPv4-first 默认值的影响面 | 三处已知位置分属 SIP 通话 / loopback 隧道 / 发布地址；swarm 通道选路须单独实测，不得一概而论 |

### P2（记录，V1.1 后续）

| # | 问题 |
|---|---|
| 1 | 静态随包房间列表无法动态更新，新增官方房间须发新客户端版本 |
| 2 | Linux Qt 客户端从未编译，三端一致性门禁无法全跑 |
| 3 | 四套绑定（DBus / JNI / Nodejs / Qt）若新增公共 API 需 CI 一致性校验 |
| 4 | 种子生成 / 离线签名构建脚本需单元测试与可复现性校验 |
| 5 | Git swarm 并发 commit 无全局全序，产品不得承诺严格时序 |

## 8. 验收用例（V1.1 门禁建议）

| # | 场景 | 期望结果 |
|---|---|---|
| 1 | 双栈 + 双端 v6 入站可达 | 优先建立 v6 直连，UI 显示「IPv6 直连」 |
| 2 | 双栈 + 本机有 GUA 但入站被防火墙挡 | L3 探测失败 → 快速回退 v4 直连或 v4 TURN，不长时间卡住 |
| 3 | v6-only（NAT64/DNS64）+ 对端 v4-only | 不尝试跨族直连，走中继，消息不丢 |
| 4 | v4-only | 行为与现网一致，无回归 |
| 5 | IPv6 前缀重分配 / 临时地址轮换 | 旧候选按 TTL 老化，新候选经 Trickle 增量重灌，长连接自动恢复 |
| 6 | 接口同时有 GUA / ULA / link-local | 后两类不进入有效候选对，ICE 收敛时间不明显变长 |
| 7 | 导入 `[v6]:port` 字面量 | 解析正确，不与 `;` 分隔的 bootstrap 列表冲突 |
| 8 | 企业网络只放行 443 | TURN-over-TLS 443 可用或明确提示不可达，不静默失败 |
| 9 | 与未升级的旧客户端互通 | checklist 仍同步，能建连，不出现半截仓库 |
| 10 | 四级来源全断 | 明确离线提示 + 本地待发队列；恢复后自动回切 L0 |
| 11 | 大消息传输（含文件） | PMTUD 正常，无静默丢包；MTU 1232 设置生效 |
| 12 | 消息平面一致性 | 所有四级通道下消息仍进同一 Git commit / 签名 / 仓库，无第二套状态 |

## 9. 待拍板

| # | 事项 | 建议 |
|---|---|---|
| 1 | 是否正式放宽 17:17「零常开节点」硬约束，允许第三方 MQTT broker **仅作发现、默认关闭** | 若不放宽，L1 整级删除，L0 / L2 / L3 仍成立；注意这不违反 22:34 节点同构（broker 不是网络中的节点） |
| 2 | 本特性列为**独立 V1.1**，还是并入 F-901 | **建议独立**：F-901 规格 v2 一行都不用改，两者解耦 |
| 3 | 「IPv6 优先」按字面「全 v6 优先」，还是按 RFC 8421「v6 head start + 交织回退」 | **建议交织**：既满足优先语义，又避免坏 v6 路径拖慢收敛 |
| 4 | IPv6 TURN 是否重新启用 | 官方曾因实测问题禁用；建议 V1.0 维持禁用，明确「v6 失败 → v4 兜底」 |
| 5 | F-901 的 14 项改动清单与开工授权 | 与前几轮一致，仍待确认；确认后先跑 P0-0 零功能改动全量重编基线 |

## 附录 A 源码索引

| 主题 | 位置 |
|---|---|
| PJNATH 引入与初始化 | `src/connectivity/sip_utils.cpp:26-27`；`src/manager.cpp:775,800`；`src/meson.build:132` |
| IPv4 / IPv6 双栈状态 | `src/jamidht/jamiaccount.cpp:2166-2169` |
| 按地址族取发布地址 | `jamiaccount.cpp:4019-4022`；`jamiaccount.h:318`；`src/sip/sipaccountbase.cpp:295-307` |
| IPv6 跳过 STUN | `src/sip/sipvoiplink.cpp:1622-1639` |
| IPv4 优先默认值 | `src/sip/sipcall.cpp:4296-4300`；`src/jamidht/svc_tunnel_channel_handler.cpp:336-345` |
| IPv6 TURN 禁用 | `src/sip/sipaccountbase.cpp:241-249` |
| IPv6 传输类型 / SDP / MTU | `src/sip/sipaccount.cpp:655-657,1567`；`src/sip/sdp.cpp:356,483`；`src/sip/siptransport.cpp:166`；`src/jamidht/channeled_transport.cpp:43` |
| 连接层配置（无地址族字段） | `src/jamidht/jamiaccount.cpp:5097-5115` |
| ICE 属外部仓库 dhtnet | 主设计文档 ADR-006 `:108,980-982,1268`；`contrib/src/dhtnet/rules.mak:6` |
| pjproject SFL fork / vcpkg port | `contrib/vcpkg/ports/pjproject/CMakeLists.txt:144-160`；`contrib/src/pjproject/rules.mak:3-5` |
| 候选注入入口（swarm 层） | `src/jamidht/conversation.cpp:3523-3549`；`conversation_module.cpp:4176-4181` |

## 附录 B 外部资料（检索日期 2026-11-12）

| 资料 | 用途 |
|---|---|
| RFC 8445 / 8839 / 8838 / 8863 | ICE 核心、SDP offer/answer、Trickle、ICE PAC |
| RFC 8421 | 双栈与多宿主 ICE 指南，IPv6 优先的标准做法 |
| RFC 8305 | Happy Eyeballs v2；RFC 8421 注明其时间建议被 ICE pacing 覆盖 |
| RFC 6092 / RFC 4941 / RFC 4443 | CE 路由器默认拒绝入站、临时地址、ICMPv6 承载作用 |
| PJNATH ICE Session API 文档（pjsip.org） | `pj_ice_sess_add_cand(local_pref)`、`create_check_list(rem_cand[])`、`update_check_list`、trickle 三档、`set_prefs` |
| libnice README | 主流实现「5245 起步 + 8445 对齐」现状 |
| 《中国IPv6发展报告(2026)》、CERNET《IPv6单栈部署发展趋势与推进路径探索》 | §2 数据与「可达不等于好用」判断 |
| APNIC / patchbay《Real-World IPv6 Deployments》 | 家庭 CE 防火墙、NAT64/DNS64、企业只放行 443 等真实部署差异 |

## 10. 21:10 补充裁定：免费 MQTT 发现 + 导入已知节点 IP（用户 2026-11-13 21:06 重提）

> 用户针对 §6 的封锁场景重提两条设想：① 用免费 MQTT 服务器发现节点；② 导入已知用户列表（如好友 IP）接入网络。本节做源码级可行性裁定，**未改任何功能代码**。

### 10.1 结论速览

| # | 判定 | 说明 |
|---|---|---|
| 1 | 两条都**技术上可行**，但都**不能消除冷启动依赖** | 只是把「必须可达的入口」从 `jami.net` 换到别处；P2P 首次入网需 rendezvous 是结构性的 |
| 2 | **思路 2 大部分已原生支持，接近零代码** | `Account.hostname` 已支持 `;` 分隔多个 bootstrap；Android 设置页已有「初始服务器 / Bootstrap」输入框；`[v6]:port` 已被 `splitPort()` 支持 |
| 3 | **思路 1 建议换载体：不用 MQTT，改「签名引导清单 over HTTPS」** | 少一个新依赖、少一条长连接、少一个匿名发布面；`loadCachedUrl()` 已提供缓存 / TTL / 失败回落 |
| 4 | 两者都不改变消息平面 | 四级只产出候选地址，业务消息仍走同一条 Git swarm + 设备签名 + TLS 通道（§6 铁律不变） |
| 5 | 与硬约束的关系 | 思路 2 不新增固定设施，**不违反**；MQTT broker 是第三方常开服务器，**需用户明确放宽**「禁止任何服务器 / 常开节点」 |

### 10.2 源码核准（本轮新增）

| # | 事实 | 依据 | 对方案的影响 |
|---|---|---|---|
| 1 | `loadBootstrap()` 按 `;` 切分 `hostname`，逐个 `dht_->bootstrap(hostService)` | `jamiaccount.cpp:1807-1817,2039-2040` | 导入多个已知 IP **无需改 daemon** |
| 2 | `splitPort()` 支持 `[v6]:port` 方括号；裸 v6 多冒号会被判为「无端口」 | `opendht/src/utils.cpp:40-59`；`dhtrunner.cpp:1010-1016` | 导入 IPv6 必须带方括号 + 显式端口 |
| 3 | `bootstrap(SockAddr)` 只 ping 拿节点 ID，不要求预先知道 NodeId | `dhtrunner.cpp:1028-1049`；`dhtrunner.h:358` | **裸 IP 可作 DHT 引导**，但不能证明对方身份 |
| 4 | **知道 IP 也不能直连**：ICE offer 固定经 `dht()->putEncrypted(peer_request + deviceLongId, …)` 交换 | `dhtnet/src/connectionmanager.cpp:745-788` | 导入 IP 只解决「入 DHT」，**不能绕过 DHT 直接建 TLS 通道** |
| 5 | ICE 请求还要过证书 + 好友 / 会话白名单；`allowPublicIncoming` 默认 false | `jamiaccount.cpp:2390-2417`；`jamiaccount_config.h:68` | 陌生人 IP 可引导 DHT，但建连仍被丢弃；须已是好友或已在会话中 |
| 6 | proxy 模式下 `dht_->run()` 的 bound port 为 0，无监听 socket、无 UPnP；`config.proxy_server` 被置值 | `jamiaccount.cpp:1995-2007,2127-2132` | **Android 默认 DHT proxy，不能当引导节点** |
| 7 | Android 新建账户在推送模式下置 `PROXY_ENABLED=true`；已有 `Account.hostname` 设置项 | `AccountWizardPresenter.kt:56-65`；`ConfigKey.kt:96`；`account_advanced_prefs.xml:30-33` | 桌面可作种子；手机须先关 proxy 且解决 CGNAT |
| 8 | `bootstrapListUrl` 是**死键**：只有声明与 `fromMap()` 解析，daemon 无消费点 | `jamiaccount_config.h:52`；`jamiaccount_config.cpp:185`；`jamiaccount.cpp:3187,3260` | 远程引导清单非零代码，但可复用 `loadCachedUrl()` 小改 |
| 9 | 组播发现已实现「发现对端 → `bootstrap(v.nodeId, addr)`」完整范式 | `dhtrunner.cpp:258-265`；`NodeInsertionPack{nodeId,port,net}` :26-31 | 可作为「导入清单」的现成代码模板 |
| 10 | 依赖树**无 MQTT 客户端库**；HTTP 客户端（`dht::http` / restinio）已存在 | 全树检索；`loadCachedUrl()` :3187 | MQTT 需新增三端依赖 + vcpkg port；HTTPS 清单不需要 |

### 10.3 思路 2（导入已知节点）：成立，但有 5 个硬前提

| 前提 | 原因 / 依据 | 不满足时 |
|---|---|---|
| 对端跑**完整 DHT 节点**（非 proxy 模式） | `jamiaccount.cpp:1995-2007,2127-2132`；Android 默认 proxy | ping 不通，`bootstrap()` 静默失败 |
| 地址**公网可达**且端口稳定 | 国内 v4 多为 CGNAT；v6 GUA 需过 RFC 6092 入站防火墙（§7 P0-1） | 只能同局域网 / 需 TURN；建议优先 IPv6 + `[v6]:port` |
| 导入的是「**一台 DHT 节点的 IP:port**」而非账号 IP | Jami 身份是 RingID / DeviceId，IP 只是传输地址 | 导入账号 IP 无意义 |
| 导入项须含 **DeviceId + 证书指纹 + ts / TTL + 签名** | `bootstrap(SockAddr)` 不验身份，攻击者占该 IP 即可喂假路由表（日蚀攻击） | 有被钓鱼引导 / 路由表污染风险 |
| 关系已建立才能建连 | `onICERequest` 白名单 `isAllowed()` + `isPeerAuthorizedByConversation()`（:2390-2417） | 只能进 DHT，不能给陌生人发消息；F-901 公共房间也需先靠 DHT 完成 JOIN |

**结论**：这是解决「官方 bootstrap 域名全封」最有效、最省事的一条，建议**列首选**。改动集中在客户端：清单文件 / 二维码导入、签名校验、`[v6]:port` 规范化、写回 `Account.hostname`；daemon 基本不动。

### 10.4 思路 1（免费 MQTT 发现）：能做，但不推荐用 MQTT

| 维度 | 判定 | 说明 |
|---|---|---|
| 机制是否成立 | ✅ 成立 | 每个节点把 `(DeviceId, multiaddr, ts, TTL, sig)` 发到约定 topic，订阅者拿到地址后喂 `dht_->bootstrap()`，之后回到标准 DHT / ICE 链路 |
| 是否消除冷启动依赖 | ❌ 否 | 只是把入口换成 broker；broker 域名同样可被封且多为海外域名。**域名级封锁有帮助，普遍出口干扰下同样失效** |
| 是否新增固定设施 | ⚠️ 是 | broker 是第三方常开服务器；虽不违反「节点同构」（它不是网内节点），但违反 17:17「禁止任何服务器 / 常开节点」，且属于 V1.0「上游网络层设施不新增」之外的新增 |
| 工程成本 | ⚠️ 高 | 全树无 MQTT 客户端：需引入 paho / mosquitto、三端编译与 vcpkg port、长连接保活与重连、topic / 签名 / 重放防护 |
| 匿名公共 broker 现实（2026-11-13 检索） | ⚠️ 差 | `broker.emqx.io` / `broker-cn.emqx.io` / `test.mosquitto.org` / `broker.hivemq.com`，1883 / 8883 匿名接入；条款明示仅供学习测试、无 SLA、retained 可能丢失；国内直连海外节点常见 300ms+ 延迟与丢包 |
| 隐私 | ❌ 严重 | broker 是 TLS 终结点，能看到全部订阅关系、IP、上线时间、消息大小；任何人可订 `#` 枚举 |
| 完整性 | ❌ 需自建 | 任何人可发假通告刷屏 / 日蚀；必须设备证书签名 + 时间戳 + nonce + topic 由共享密钥派生，并防降级攻击（§7 P1-6） |

**替代裁定**：把该级从「免费 MQTT pub/sub」改为「**签名引导清单 over HTTPS**」——任意可达 Web 空间 / CDN / 对象存储放一份带签名的节点清单，复用 `loadCachedUrl(url, cachePath, TTL, cb)`（现有原语，已用于 `proxyListUrl`）拉取 + 本地缓存 + 失败回落，再把条目写进 `hostname`。相对 MQTT：无新依赖、无长连接、无匿名发布面、可离线缓存、签名校验天然、国内镜像容易；代价是清单需有人维护更新，**节点不能自发布动态地址**。

### 10.5 修订后的降级链（替换 §6 表格）

| 级 | 手段 | 默认 | 新增改动 | 解决什么 |
|---|---|---|---|---|
| L0 | 现有 `hostname` 多节点 + `dhtstate` + TURN + ICE | 开 | 无 | 正常运行、老用户重启免引导 |
| L1 | **签名节点清单导入**（好友 IP / 域名 / `[v6]:port` + DeviceId + 指纹） | 关 | 客户端导入 UI + 校验 + 写 `hostname` | **官方 bootstrap 域名全封**（首选） |
| L2 | **可达 HTTPS 签名引导清单**（替代原 MQTT 级） | 关 | daemon 补 `bootstrapListUrl` 消费逻辑，复用 `loadCachedUrl()` | 不认识任何在线节点时拿一批候选 |
| L2′ | 免费 MQTT 仅做发现（**可选，仅在明确放宽硬约束后**） | 关 | 新依赖 + 签名通告 + 防枚举 | 需要节点自发布动态地址时 |
| L3 | 局域网组播 `dhtPeerDiscovery` | 关 | 无（现成开关） | 同广播域完全脱离官方设施 |

注意：与原 §6 相比，本表把「导入节点」从 L2 **上移到 L1**，把 MQTT 从 L1 **降为可选 L2′**，依据见 §10.3 / §10.4。**铁律不变**：所有级只产出候选地址；消息永远走同一条 Git swarm + 设备签名 + TLS 通道，不出现第二套消息平面。

### 10.6 待拍板

| # | 事项 | 建议 |
|---|---|---|
| 1 | 是否同意把原 L1 由「免费 MQTT」替换为「签名引导清单 over HTTPS」 | **同意**；MQTT 保留为 L2′ 可选项 |
| 2 | 是否正式放宽「零常开节点」硬约束以保留 MQTT 级 | **默认不放宽**；不放宽则原 §6 的 L1 MQTT 整级删除，其余级仍成立 |
| 3 | 本特性仍作为独立 V1.1，不并入 F-901 | **建议独立**，F-901 规格 v2 一行不改 |
| 4 | 清单签名用设备证书还是另发离线签名密钥 | 建议**离线签名密钥 + 随包公钥**，与 F-901 `rooms.json` 同一套信任根 |

## 11. 22:20 补充裁定：从某个在线用户拉取「网络上活跃的超级节点」

> 用户新设想：不靠静态清单，改从任意一个在线用户那里拉当前活跃节点来入网。本节做源码级裁定，**未改任何功能代码**。

### 11.1 结论速览

| # | 判定 | 说明 |
|---|---|---|
| 1 | **能力 Jami 已经具备，不需要新协议** | DHT 层 `FIND_NODE` / `exportNodes()` 本来就在交换活跃节点；会话层已有 `FIND`/`FOUND` PEX + gossip + 签名租约 |
| 2 | ~~「超级节点」角色必须去掉~~ → **2026-11-14 09:43 订正：概念可保留，只去掉「固定身份」与「正确性依赖」** | 用户澄清：它是**动态变化的节点列表**，节点程序与普通节点完全相同，仅因公网 IP、开机时间长、稳定性好而被临时当作服务器替代品。这与 22:34 硬约束**不冲突**；只有「固化成角色」或「指望某节点永远在线」才冲突。详见 §11.2 与 §12.1 |
| 3 | **不能解决冷启动，这是先有鸡还是先有蛋** | 要「从在线用户拉」，得先连上他；Jami 连设备要先经 DHT 交换 ICE 信令，而 DHT 又要先 bootstrap |
| 4 | **真实价值在「一次引导，长期自给」** | 先导一个可达节点 → 入网 → 从网络拉回活跃节点并持久化 → 下次重启不再依赖官方域名，且列表天然保鲜、免去人工维护清单 |
| 5 | 与硬约束关系 | 不新增常开节点 / 服务器 / 特化角色，**符合**；可替代 §10.5 的 L2 HTTPS 清单（或两者互为兜底） |

### 11.2 概念订正（2026-11-14 09:43 复核）：用户定义不违反硬约束

> 用户原话澄清：「超级节点不是固定的，是一个**动态变化的节点列表**，节点上运行的程序和普通节点**没有区别**，但由于其有公网 IP、开机时间长、稳定性比较好，**被用作服务器的替代品**。」

| 你的说法逐项核对 | 是否冲突 | 源码依据与裁定 |
|---|---|---|
| 程序与普通节点没有区别 | **不冲突** | `dht.h:443-445` 注释原文「Any running node can be used as a bootstrap node. Only nodes running only as bootstrap nodes should be put in bootstrap mode.」；`is_bootstrap` / `public_stable` 默认 `false`（`dht.h:446-448`），`jamiaccount.cpp:2114-2126` 只设 `network / maintain_storage / persist_path / peer_discovery`，**Jami 从不设置这两个标志** ⇒ 网内根本没有特化角色，所有完整节点同构 |
| 动态变化的列表 | **不冲突** | 候选来自 `isGood(now)` 实时判定 + `findClosestNodes()` 按 XOR 距离现算；节点下线由 `isGood` 自动剔除，无任何静态名册 |
| 因公网 IP / 开机长 / 稳定而「被当作服务器用」 | **择优使用不冲突；正确性依赖则冲突** | 只要「拿不到它就换别的」，就是机会主义的引导候选池，符合「可随时上下线」；若某条流程**必须**它在才正确，就回到锚点，违反 22:34 |
| 上一轮「必须去掉超级节点角色」 | **措辞过严，已订正** | 真正要去掉的是「固定身份 + 全网广播排名 + 永远在线假设」，不是「优先挑稳定节点」这个偏好本身 |

| 实现口径 | 做法 |
|---|---|
| 名词 | 对外仍建议叫「动态引导候选池」，避免与历史上「超级节点 = 特化服务器」混淆；用户定义本身成立 |
| 打分 | 每台设备**各自本地**按「可 ping 通 + 公网 / IPv6 GUA + 多次在线」打分取前 N，**不广播、不设固定身份、分数只存本机** |
| 已有偏好 | `isGood()` + bucket 淘汰策略天然偏向长期稳定的可达节点，见 §12.6（涌现偏好，不是配置出来的等级） |
| 缺口 | OpenDHT **不判公网/私网、不看在线时长、不按稳定性排序**，见 §12.6；要做你说的择优，仍需补这段本地打分 |

### 11.3 源码核准：这个能力已经存在（本轮新增）

| # | 事实 | 依据 | 对方案的影响 |
|---|---|---|---|
| 1 | `exportNodes()` **只导出 `isGood(now)` 的健康节点**，v4/v6 双栈、按 bucket 顺序 | `opendht/src/dht.cpp:2485-2516` | 「活跃节点」不用自己算，现成 |
| 2 | `DhtRunner::exportNodes()` 是公开 API；`bootstrap(std::vector<NodeExport>)` 直接逐个 `insertNode()` | `dhtrunner.cpp:506-512,1082-1092`；`dhtrunner.h:343,405` | 「导出 → 注入」接口全现成 |
| 3 | Kademlia `FIND_NODE` 请求会把对端已知节点随 `sendNodesValues` 带回，**每次查询本来就在拉节点** | `network_engine.cpp:740-748`；`dht.cpp:301,493,853-854` | DHT 层 PEX 是既有行为，不是新机制 |
| 4 | `saveState()` 就是 `state.nodes = exportNodes()` 写到 `dhtstate`，启动 `loadState()` → `insertNode()` 恢复 | `dht.cpp:2914-2918,2925-2956`；`jamiaccount.cpp:2116` | 持久化通路现成，只要触发时机 |
| 5 | **会话层已有完整 PEX**：`Query::FIND` 请求 N 个节点 → 对端 `routing_table.closestNodes()` 回 `Response::FOUND` → 本地 `setKnownNodes()` | `swarm_protocol.h:52-95`；`swarm_manager.cpp:803-845,888-902,936` | 可直接借鉴消息格式与限流（`BUCKET_MAX_SIZE`） |
| 6 | 会话层 mobile 节点 gossip 带 `MobileLease` **签名 + 有效期 + `CertRequest/CertResponse` 证书验证**，注释明确「证书不 gossip」 | `swarm_protocol.h:41-49,64-83,101-113` | **防伪造节点列表的现成范式**，可直接复用到 DHT 层清单 |
| 7 | `Conversation::bootstrap()` 现成接受 `knownDevices`，注释说明候选也可由 PresenceManager 在线后注入 | `conversation.cpp:3480-3519,3523` | 「从在线成员补节点」在会话层已实现 |
| 8 | **`NodeExport` 只有 `{id, addr}`，完全无签名**；`bootstrap(vector<NodeExport>)` 不做任何校验 | `opendht/include/opendht/node_export.h:14-33`；`dhtrunner.cpp:1087-1090` | 恶意对端可一次灌满假节点造成日蚀，必须加固 |
| 9 | **proxy 模式没有 DHT 路由表**：`DhtProxyClient::exportNodes()` 返回 `{}`、`insertNode()` 空实现 | `dht_proxy_client.h:277,280` | Android 默认该模式，既不能导出也不能注入；只完整 DHT 节点（桌面 / Linux）参与 |
| 10 | 连设备的 ICE offer 固定经 `dht()->putEncrypted(peer_request + deviceLongId, …)` | `dhtnet/connectionmanager.cpp:745-788` | **冷启动断点**：没入 DHT 就问不了任何在线用户 |

### 11.4 冷启动断点：为什么它不能当「入网方式」

| 步 | 动作 | 前置条件 |
|---|---|---|
| 1 | 想从在线用户拉活跃节点 | 已与该用户建立 TLS 连接 |
| 2 | 建立 TLS 连接（`connectDevice`） | 已用 DHT 交换 ICE 信令（`putEncrypted`） |
| 3 | 能用 DHT | **已经入网**（bootstrap 成功或 `dhtstate` 恢复） |
| 4 | 已经入网 | 循环回到第 1 步 —— 真正的入口只有 L0 官方 / L1 导入已知节点 / L3 局域网组播 |

**结论**：这是「入网后的路由表扩充与保鲜」，不是「入网方式」。把它放在 L1 之后（记作 **L1+**）才成立，放在 L1 之前就是循环论证。

**2026-11-14 09:43 精确化（重要，勿再笼统说「必须先连上好友」）**：上表只对**应用层**成立。若「从在线用户拉节点」指的是 **DHT 层 `FIND_NODE` 问路**，则完全不需要好友关系、不需要 `connectDevice`、不需要 ICE、不需要 TLS——只要有一个可达的 DHT 节点地址，一个 UDP 包就能问回 8 个节点（`network_engine.cpp:916-954,740-748`）。因此这一层**没有先有鸡还是先有蛋的循环**，断点只剩「第一跳地址从哪来」（L0 官方 / L1 导入 / L3 组播 / `dhtstate` 缓存）。两种语义必须分开写，详见 §12.3。

### 11.5 推荐方案 L1+：一次引导，自给自足

| 步 | 做法 | 改动 |
|---|---|---|
| 1 | 导入 **1 个**可达节点（好友 IP / `[v6]:port` / 签名清单任一条）写进 `hostname` | 沿用 §10.5 L1，零 daemon 改动 |
| 2 | `dht_->bootstrap()` 入网；两节点 DHT 也能跑（局域网组播就是这么做的） | 现成 `jamiaccount.cpp:2039-2040` |
| 3 | 入网后由 OpenDHT 自身 `FIND_NODE` 迭代查询自动扩充路由表 | **零代码**，既有行为 |
| 4 | 启动后定时 / 账户停用时 `exportNodes()` 取活跃节点，按 §11.2 打分取前 N | 新增一小段导出逻辑 |
| 5 | 结果写入本地 `dhtstate`，并可选回写 `hostname` 作为下次引导种子 | 复用 `saveState()` / 配置写回 |
| 6 | 节点下线由 `isGood(now)` 自动剔除，无需人工维护清单 | 现成 |

**收益**：一次可达 → 长期不依赖官方域名；节点列表实时保鲜，解决 §10.4「HTTPS 清单会过期、需有人维护」的痛点；不新增常开节点、不新增服务器、节点仍同构；可与 L2 HTTPS 清单互为兜底（清单给第一跳，L1+ 给后续保鲜）。**代价**：第一跳仍需一个可达地址（无解）；Android proxy 模式不参与。

### 11.6 安全边界（必须做，否则等于把路由表交给对端）

| 风险 | 原因 | 补救 |
|---|---|---|
| 日蚀 / Sybil 攻击 | `NodeExport` 无签名，`insertNode()` 直接注入 | 每条带 `ts + TTL + 设备签名 + 证书指纹`；复用 `MobileLease` 范式；单次注入数量上限（参考 `BUCKET_MAX_SIZE` / `MAX_MOBILE_NODE_INFOS=128`） |
| 单一来源投毒 | 从一个用户拉来的整张表都可信 | **≥3 个独立来源交叉验证取交集**，新节点先 `pingNode()` 探活再入表 |
| 降级攻击 | 攻击者阻断 L0 逼用户走弱验证通道 | TLS / 证书校验一律不降级；验签失败只回落 L0 + 本地缓存，绝不静默启用（同 §10.1） |
| 隐私 | 导出路由表等于暴露本机已知节点与在线关系 | 只对已是好友 / 同会话成员开放（沿用 `onICERequest` 白名单），默认关闭 |
| 「超级节点」复辟 | 若按在线时长给节点排名并全网广播 | 打分只存本机、不广播、不设固定身份；产品文案不得出现「超级节点」 |

### 11.7 待拍板

| # | 事项 | 建议 |
|---|---|---|
| 1 | ~~是否去掉「超级节点」角色~~ → 09:43 已澄清，**改为：是否接受「动态候选池、择优不依赖」的口径** | 建议接受；用户定义已符合硬约束，只需锁死「不固化角色 / 不依赖常在线」两条，无需再争论名词 |
| 2 | L1+ 是否替代 §10.5 的 L2 HTTPS 清单 | **建议互补**：L2 只负责冷启动第一跳，L1+ 负责入网后保鲜；若只留一个，留 L1+ |
| 3 | 是否仍保留免费 MQTT（L2′） | **建议删除**：L1+ 已覆盖「动态拿到活跃节点」这个 MQTT 唯一优势，且无需 broker |
| 4 | 导出开关默认开还是关 | **默认关**，仅在用户开启「接入韧性」后启用；隐私与攻击面考虑 |
| 5 | 本特性仍作为独立 V1.1，不并入 F-901 | **建议独立**，F-901 规格 v2 一行不改 |

## 12. 2026-11-14 09:43 补充：`FIND_NODE` / `exportNodes()` 是怎么「自动拿到活跃节点」的

> 用户追问：把「从在线用户拉活跃节点」落到源码上，详细讲清 `FIND_NODE` 与 `exportNodes()` 的自动机制。本节全部为本地 `opendht 4.4.1` + `daemon jami-core 16.0.0` 静态核准，**未改任何功能代码**。

### 12.1 先定性：你说的节点在源码里不是「角色」，是「统计偏好」

| # | 结论 | 依据 |
|---|---|---|
| 1 | Jami 网络里**没有任何超级节点角色**：所有完整 DHT 节点跑同一套程序，`is_bootstrap` 与 `public_stable` 默认 `false`，Jami 从不设置 | `dht.h:443-448`；`jamiaccount.cpp:2114-2126` |
| 2 | `dht.h:443-445` 注释明确「任何运行中的节点都可被用作引导节点，只有**专门只做引导**的节点才该开 bootstrap 模式」 | `dht.h:443-445` |
| 3 | 「公网 IP + 开机长 + 稳定」的节点会**自然**更常出现在别人的路由表里，因为长期保持 `isGood`、bucket 满时优先被保留、更常随 `FIND_NODE` 应答被回出去 | `node.cpp:34-39`；`routing_table.cpp:212-245`；`dht.cpp:2603-2612` |
| 4 | 这是**涌现的统计偏好，不是配置出来的等级**：没有选举、没有注册、没有排名广播，节点随时上下线不影响正确性 | 全树无 `supernode / superpeer / election` 逻辑 |
| 5 | 分界线只有一条：**择优使用**（拿不到就换别人）符合硬约束；**正确性依赖某节点常在线**则违反 22:34 | 项目长期约束第 1 条 |

### 12.2 数据结构与常量（读懂后面链路的前提）

| 名称 | 值 / 定义 | 含义 | 依据 |
|---|---|---|---|
| k-bucket 路由表 | 每桶 `TARGET_NODES = 8`，按节点 ID 的 XOR 距离分桶，IPv4 / IPv6 各一张 | 路由表本体 | `routing_table.h:9`；`dht.cpp:2489-2513` |
| `Node` | `id`、`addr`、`time`（最后听说）、`reply_time`（最后收到正确回复）、`expired_` | 单个路由表条目 | `node.h:145-150` |
| `NodeExport` | 仅 `{InfoHash id, SockAddr addr}`，msgpack 两个字段 | 导出单元，**无签名、无时间戳、无在线时长、无公网标记** | `node_export.h:14-33`；`node.h:73-79` |
| `isGood(now)` | `!expired_` 且 `reply_time >= now - 120min` 且 `time >= now - 10min` | OpenDHT **唯一的活跃判定** | `node.cpp:34-39`；`node.h:135,138` |
| `SEARCH_NODES` | 14 | 一次迭代查询围住目标的最多候选节点数 | `dht.h:333` |
| `MAX_REQUESTED_SEARCH_NODES` | 4 | 每轮并发询问的下一跳上限 | `dht.h:346` |
| `SEND_NODES` | 8 | 单个应答包里最多携带的节点数 | `network_engine.cpp:35,1259` |
| 紧凑节点格式 | IPv4：`32B id + 4B IP + 2B port = 38B`；IPv6：`32B + 16B + 2B = 50B` | 应答里的节点编码，无签名字段 | `network_engine.h:475-477`；`network_engine.cpp:1254-1283` |
| 请求超时 | `MAX_RESPONSE_TIME = 1s`；`BOOTSTRAP_PERIOD` 指数退避 | 判活的节奏 | `node.h:141`；`dht.cpp:2303-2305` |

### 12.3 `FIND_NODE`：一轮问答把别人的「好节点」带回来

| 步 | 动作 | 源码依据 |
|---|---|---|
| 1 · 发起 | 迭代查询 `search()` 在**无 callback** 时发 `find_node`：`sendFindNode(n->node, sr->id, -1, …)`；另有 `neighbourhoodMaintenance` 对随机 ID、`bucketMaintenance` 对稀疏桶周期性发问。`want = -1` 表示同时要 IPv4 与 IPv6 节点 | `dht.cpp:422-434,2083-2089,2141` |
| 2 · 线上格式 | msgpack 单 UDP 包：`q = find`、`a = {id: myid, target: …}`、`want = [AF_INET, AF_INET6]`、附带 `tid` 与 UA；**无连接、无 TLS、无需好友关系** | `network_engine.cpp:916-954` |
| 3 · 对端应答 | 收到 `FindNode` → `onFindNode()` → `findClosestNodes(target, now, 8)`，从 target 所在桶向两侧扩展、**逐桶跳过 `!isGood(now)`**、按 XOR 距离插入排序 | `network_engine.cpp:740-748`；`dht.cpp:2603-2612`；`routing_table.cpp:97-125` |
| 4 · 打包回送 | `bufferNodes()` 按 XOR 重排、截到 `SEND_NODES = 8`、编码成紧凑二进制，由 `sendNodesValues()` 一个包回；**`GET_VALUES` 应答也带同一份节点**，所以日常 put / get 都在顺带交换节点 | `network_engine.cpp:1254-1283,746-747,755-757`；`dht.cpp:2624-2629` |
| 5 · 收包校验 | `deserializeNodes()` 校验长度整除、跳过自己、回环地址改写为来源 IP、`isMartian()` 与黑名单过滤，然后 `cache.getNode(id, addr, now, false)` 并 `onNewNode(node, 0)` | `network_engine.cpp:1065-1103` |
| 6 · 入桶与确认 | `confirm = 0` 只代表「听说」：桶未满直接入，桶满则替换 expired、否则 ping 一个 dubious 节点、必要时 split 桶；**新节点要后续 ping 收到回复**，由 `Node::received()` 把 `reply_time` 置为 now 并触发 `onNewNode(node, 2)`，才变成 `isGood` | `dht.cpp:243-253`；`routing_table.cpp:193-250`；`node.cpp:84-92`；`network_engine.cpp:644-668` |
| 7 · 迭代收敛 | 响应节点进 search 候选（`searchNodeGetDone` / `onGetValuesDone`），`refill()` 再从 node cache 补到 `SEARCH_NODES = 14`，每轮挑最近的 ≤4 个并发问下一跳，几轮后收敛到目标附近 | `dht.cpp:297-320,835-862,2639-2667` |

**必须校准的一点**：一次 `FIND_NODE` 拿到的是「**对方认为健康、且离 target 的 XOR 距离最近的 8 个节点**」，**不是对方整张路由表，也不是全网活跃节点排行榜**。想凑出「动态稳定节点池」，要么像 `bucketMaintenance` 那样对不同 ID 反复问，要么直接读本机 `exportNodes()`。

### 12.4 `exportNodes()`：把本机路由表读成一份快照

| 环节 | 实现 | 依据 |
|---|---|---|
| 遍历顺序 | 先 IPv4 / IPv6 **自己所在 bucket**（离自己最近，最容易被用到），再遍历其余所有 bucket | `dht.cpp:2489-2513` |
| 唯一过滤 | `if (n->isGood(now)) nodes.emplace_back(n->exportNode())` | `dht.cpp:2492-2513`；`node.cpp:34-39` |
| 输出 | `std::vector<NodeExport>`，**无数量上限、无打分、无排序、无签名、无 TTL** | `dht.cpp:2484-2516`；`node_export.h:14-33` |
| 公开 API | `DhtRunner::exportNodes()` 直接转发到 `dht_->exportNodes()`；`bootstrap(std::vector<NodeExport>)` 逐个 `insertNode()` | `dhtrunner.cpp:505-512,1082-1092`；`dht.h:104-105` |
| **现成用途 A** | `saveState()` = `DhtState{ id, nodes = exportNodes(), values = exportValues() }`，msgpack 写 `cachePath_ / dhtstate`；`shutdown()` 触发保存 | `dht.cpp:2914-2922,69-74`；`jamiaccount.cpp:2116` |
| **现成用途 B** | 启动 `loadState()` → 逐个 `network_engine.insertNode(node.id, node.addr)` 恢复路由表（**这就是「老用户重启不必解析官方域名」的机制**） | `dht.cpp:2048-2049,2925-2956` |
| **关键缺口** | Jami daemon 全树 **0 处**显式调用 `exportNodes()` / `bootstrap(vector<NodeExport>)` / `insertNode()` / `saveState()`；持久化完全由 OpenDHT 内部按 `persist_path` 自动完成 | 本轮全树检索 `jami-daemon-master/src`，无匹配 |

**所以「自动」分两层**：`FIND_NODE` 扩表、`isGood` 淘汰、`dhtstate` 落盘与恢复，**全部是既有行为、零代码**；而「把活跃节点显式导出成一份可分享的名单 / 按公网与稳定性打分 / 从某个指定用户拉一份」，Jami **完全没有现成通路，必须新增**。

### 12.5 你的设想与源码现状逐项对照

| 你期望的特性 | 源码现状 | 差距 | 要补什么 |
|---|---|---|---|
| 优先有公网 IP 的节点 | `isMartian()` 只过滤 `0.x`、IPv4 组播、IPv6 多播 / 全零 / v4-mapped，**不过滤 RFC1918 私网** | 私网地址照样进表并被回出去 | 本地判公网 / IPv6 GUA，打分时加权 |
| 优先开机时间长的节点 | `Node` 没有 uptime 字段；`isGood` 只看「120 分钟内回过一次 + 10 分钟内有往来」 | 长时间在线不等于分数高 | 本地累计「多次观察到在线」的历史计数 |
| 优先稳定性好的节点 | bucket 满时先换 expired、再 ping dubious，`isGood` 的被保留；`findClosestNodes` 只回 `isGood` | 有**涌现偏好**，但无显式分数、无跨桶排序 | 本地打分取前 N，分数只存本机 |
| 列表动态变化、节点可随时上下线 | `isGood` 随时间自动失效，`expireBuckets()` 定期清理 | 已满足 | 无 |
| 程序与普通节点无区别、可当服务器替代品 | 任何运行中的节点都能被 bootstrap；Jami 未设 `is_bootstrap` | 已满足 | 无，但产品文案别写成「服务器」 |
| 从「某一个在线用户」拉一份 | **DHT 层**无需好友 / TLS / ICE，一个 UDP `FIND_NODE` 即可问任意已知 DHT 节点；**应用层**走已建连好友才有 §11.4 的循环 | 第一跳地址仍无解；应用层无现成接口 | L1 给第一跳；名单分享可复用会话层 `FIND`/`FOUND` PEX 范式 |
| 名单可信 | `NodeExport` 无签名，`insertNode()` 直接注入 | 恶意对端可灌假节点造成日蚀 | `ts + TTL + 设备签名 + 证书指纹` + ≥3 源交叉 + `pingNode()` 探活（同 §11.6） |
| 三端都能参与 | `DhtProxyClient::exportNodes()` 返回 `{}`、`insertNode()` 空实现；Android 默认 proxy | 手机无 DHT 路由表，既不导出也不注入 | 明确「仅桌面 / Linux 完整节点参与」，不要在 UI 承诺手机可用 |

### 12.6 一句话落到方案上

`FIND_NODE` 负责**持续把别人的好节点吸进自己的路由表**，`exportNodes()` 负责**把本机认定健康的那批节点读成快照**，`isGood()` 是两者共同的活跃闸门，`dhtstate` 是自动持久化。你要的「动态稳定节点池」= 在 `exportNodes()` 结果之上补一层**本地打分 + 签名 + 可选分享**，底座已经全部现成；**唯一无法自动消除的仍是第一跳**：必须有一个可达地址才能发出第一个 `FIND_NODE`。这与 §11.5 的 L1+ 六步完全一致，本节只是把第 3、4 步展开到源码级。

### 12.7 本轮新增源码索引

| 主题 | 位置 |
|---|---|
| `isGood` 定义与常量 | `opendht/src/node.cpp:34-39`；`include/opendht/node.h:66,73-79,135-141,145-150` |
| `FIND_NODE` 请求 / 应答 / 打包 | `opendht/src/network_engine.cpp:916-954,740-748,1254-1301`；`include/opendht/network_engine.h:475-477` |
| 节点解析与入桶 | `network_engine.cpp:1065-1103,626-668`；`opendht/src/routing_table.cpp:97-125,193-250`；`opendht/src/dht.cpp:243-253` |
| 迭代查询与维护 | `dht.cpp:297-320,396-454,835-862,2083-2089,2141-2149,2325-2359,2639-2667` |
| `exportNodes` / 持久化 | `dht.cpp:69-74,2048-2049,2484-2524,2603-2612,2914-2962`；`dhtrunner.cpp:505-512,1028-1092` |
| 无特化角色的证据 | `include/opendht/dht.h:363-364,440-452`；`jamiaccount.cpp:2114-2126` |
| proxy 模式边界 | `include/opendht/dht_proxy_client.h:276-280` |

## 13. 2026-11-16 09:41 补充：国内双节点通讯落地路径

> 用户判断：「通过这两天对代码的分析，jami 在国内两个节点之间建立通讯还是挺难的，你有什么解决方案？」
> 本节给出**零代码优先**的落地路径，并补六条本轮新增源码证据（ICE-TCP 死代码、STUN 缺位、published 地址不可靠、dhtPort 默认随机、TURN 默认值、UPnP 默认开）。全部为静态核准，**未改任何功能代码**。

### 13.1 结论速览

| 判定项 | 结论 | 依据 |
|---|---|---|
| 「难」是否成立 | **成立，但难在两点，不在全程**：①入网第一跳依赖境外 bootstrap；②IPv4 普遍 CGNAT 导致 ICE 打洞失败，兜底 TURN 也在境外 | §13.2 |
| 直连成功后还有没有国际依赖 | **没有**。两节点直连是纯国内 UDP + 双向 TLS/DTLS，运营商只能识别到「疑似 OpenDHT 包」，看不到内容 | §8.7；`tls_session.cpp` |
| 两人场景是否需要接入全球 DHT | **不需要**。互设为引导地址即构成最小 2 节点 overlay，put/listen 直接命中对方 | `dht.h:443-445`；`jamiaccount.cpp:2039-2040` |
| 首选解法 | **A1 互为引导 + A2 IPv6 直连**，均 0 代码、不碰硬约束 | §13.4 |
| 唯一无解场景 | 双端对称 NAT、双方均无公网 IPv6、且拒绝任何映射 —— 只剩境外 TURN，慢且可能不通 | §13.5 |
| 是否需要新增服务端 | **不需要**，且不得新增（硬约束 1：禁锚点/中继/常开节点） | 项目长期约束第 1 条 |

### 13.2 难在哪：只有两环

| 环节 | 为什么难 | 源码 / 事实依据 | 致命性 |
|---|---|---|---|
| ① 入网第一跳 | 默认 `bootstrap.jami.net` 在境外，国内时通时断；拿不到第一跳就发不出第一个 `FIND_NODE`，DHT 根本入不了网 | `jamiaccount.cpp:1807-1817,2039-2040`；§12.6 | 致命，但有 0 代码解 |
| ② ICE 打洞 | 国内家宽 IPv4 基本 CGNAT，双方对称 NAT 时 UDP 打洞必败；SRFLX 依赖的 STUN 在 daemon 侧并不配置，公网候选主要靠 UPnP 映射与 TURN 中继 | `connectionmanager`（dhtnet）；§13.3 第 2、3 条 | 最难的一环 |
| ③ 国际出口 QoS | DHT 控制面明文 UDP，跨境流量易被限速丢包 | §8.2、§8.4 | 只影响跨境部分 |
| ④ 建成之后的通讯 | 直连即纯国内流量，业务通道 TLS/DTLS 双向证书，无国际设施参与 | §8.7 | 不构成问题 |
| ⑤ UDP 被运营商 QoS 时的回退 | dhtnet 有 ICE-TCP 能力，但 daemon 从未启用，**没有 TCP 回退** | §13.3 第 1 条 | 缺能力，非阻断 |

**一句话**：难点被压缩在「第一跳」与「初始打洞」两处，而这两处都有零代码解法；剩下的不是技术问题，是拓扑条件问题。

### 13.3 本轮新增源码核准（决定方案细节的六条）

| # | 结论 | 源码依据 | 对方案的影响 |
|---|---|---|---|
| 1 | dhtnet 的 ICE **有 TCP 能力但默认关闭**：`bool tcpEnable {false}`、`isTCPEnabled()`；daemon 全树 **0 处**给 `tcpEnable` 赋值，`jamiaccount.cpp:166` 的 `ice_tcp_sp` 仅是 `PendingCall` 里的声明、全树无赋值 ⇒ 死字段 | `dhtnet/ice_options.h:99`；`dhtnet/ice_transport.h:188`；`jami-daemon-master/src` 全树检索 `tcpEnable` 零命中 | 现状**没有 ICE-TCP 回退**；若要做，是 V1.2 级 daemon 二开，不是配置项 |
| 2 | daemon **不配置 STUN 列表**：`IceTransportOptions.stunServers` 在 `src/jamidht` 内零填充，公网候选来自 UPnP 映射、`publishedAddress` 与 TURN | `dhtnet/ice_options.h:97`；`src/jamidht` 检索 `stunServers` 零命中；`turnServer` 仅见 4 处赋值 | 打洞高度依赖本地 UPnP 是否生效 ⇒ A3 的价值上升 |
| 3 | `Account.publishedAddress` **不能当可靠开关**：属 `SIPAccountBase` 层（配置 → `setPublishedAddress`），双栈数组且未指定族时**优先 IPv4**（源码注释直言「IPv4 更可能在 NAT 后成功」）；`connectivityChanged` 会清空该缓存 | `sipaccountbase_config.cpp:122,127`；`sipaccountbase.cpp:298-311`；`jamiaccount.cpp:2993` | A3 主手段应是**路由器映射 + 固定端口**，`publishedAddress` 只作 SIP/兜底 |
| 4 | `Account.dhtPort` **默认 0 = 系统随机端口**；`dht_->run(conf.dhtPort,…)` 直接使用；`dhtPortUsed()` 在 UPnP 映射有效时返回映射外网端口 | `jamiaccount_config.h:48`；`jamiaccount.cpp:1995`；`jamiaccount.h:894-897` | 想端口映射，**必须先固定 dhtPort**，否则每次重启映射全失效 |
| 5 | TURN 默认 `turn.jami.net` / 用户 `ring`，写入连接管理器与通话；`Account.turnServer` 可替换 | `jamiaccount_config.h:23-24,35-36`；`jamiaccount.cpp:1019-1022,5103-5107`；`sipaccountbase_config.cpp:30` | 换 TURN 即引入固定节点，违反硬约束 1 ⇒ V1.0 不新增自建中继 |
| 6 | UPnP **默认开启**，且 DHT 端口映射由 UPnP 上下文托管 | `jamiaccount_config.h:40`；`account_config.h` `upnpEnabled {true}` | 家用路由器支持 UPnP 时，A3 可能无需手工映射 |

### 13.4 五个方案（按改动量排序，全部不碰硬约束）

| 编号 | 方案 | 改动量 | 解决哪环 | 做法 | 前提与代价 |
|---|---|---|---|---|---|
| **A1** | 互为引导（= F-903 档 1） | **0 代码** | ① | 双方在高级设置 `hostname` 填对方 `[v6]:端口` 或 `IP:端口`（`;` 分隔，末尾保留官方域名兜底）。两人互为 bootstrap 即成最小 2 节点 DHT | 至少一端首包可达；地址靠微信等带外交换；改 hostname 会触发重注册、短暂断线（§11 已核准） |
| **A2** | IPv6 直连 | **0 代码** | ①+② | 两端开 IPv6 → ICE host 候选直接互通，无 NAT 转换、无 STUN、无 TURN，纯国内流量 | 路由器下发 GUA、防火墙放行、地址漂移需处理（见 13.7 代价） |
| **A3** | 单端端口映射（含 UPnP） | **0 代码** | ② | 先固定 `Account.dhtPort`（如 4222），再在路由器做 UDP 映射；UPnP 默认开，可自动完成；另一端主动连 → 必直连 | 需一台有映射权限的宽带；属连通性配置，不是节点角色特化，不违反节点同构 |
| **A4** | 动态引导候选池 L1+ | 少量代码 | ①的持续化 | 入网后 `exportNodes()` 取 `isGood` 节点，本地打分后并入 `dhtstate`/回写 `hostname`，一次引导长期自给 | §11.5 已设计；需签名与多源交叉，防投毒（§11.6）；待拍板 |
| **A5** | ICE-TCP 点火 | daemon 二开 | ⑤ | 把 dhtnet 的 `tcpEnable` 接出来做 TCP 回退 | 本轮证实是死代码；TCP 打洞能力弱于 UDP；列 V1.2 候选，不进 V1.0 |

### 13.5 场景成功率裁定与降级顺序

| 场景 | 可用路径 | 判定 | 备注 |
|---|---|---|---|
| 双端 IPv6 可用且入站放行 | A1 + A2（host 候选直连） | ✅ 成功率最高，**推荐主路径** | v6 无 NAT 转换，打洞退化为「防火墙状态放行」，成功率显著高于 v4 CGNAT |
| 一端有公网 IPv4 或可映射 | A1 + A3 | ✅ 必直连 | 另一端出站即通 |
| 双端 IPv4 CGNAT、锥形 NAT | A1 + UPnP/打洞 | ⚠️ 部分成功 | 与 NAT 类型强相关，不可承诺 |
| 双端对称 NAT 且无 IPv6 | 仅境外 TURN | ❌ 唯一无解场景 | 慢、可能不通；**不得为此新增自建中继** |

**降级顺序（写进产品文案）**：互为引导入网 → IPv6 直连优先 → 单端映射兜底 → 最后才轮到境外 TURN。

### 13.6 为什么两人场景根本不需要「全球网」

| # | 事实 | 依据 |
|---|---|---|
| 1 | 任何运行中的节点都可被用作引导节点，只有「专门只做引导」的节点才该开 bootstrap 模式 | `opendht/dht.h:443-445` |
| 2 | `loadBootstrap()` 按 `;` 切分多个地址并逐个 `bootstrap()`，不校验对方是否为官方节点 | `jamiaccount.cpp:1807-1817,2039-2040` |
| 3 | 2 节点 overlay 内，`put`/`listen` 的键由对方直接持有，好友请求与 ICE offer 交换均可完成（这是与 F-901 创世种子同一套语义） | §11.5 第 2 步；`connectionmanager.cpp:745-788` |
| 4 | 首跳之外的扩表、保鲜、淘汰全部由 OpenDHT 自动完成，无需额外代码 | §12.3、§12.4 |

**推论**：两人通讯的最小可行条件是「一个可达地址 + 两端能发 UDP」，而不是「能连上官方 bootstrap」。这直接降低了 A1 的门槛。

### 13.7 零代码实测清单（建议按序执行，每步有判据）

| 步 | 操作（Windows 侧） | 期望 / 判据 |
|---|---|---|
| P0-1 | 查公网 IPv6：`Get-NetIPAddress -AddressFamily IPv6`，筛掉 `LinkLocal`/`Temporary`；外部验证访问 `https://api6.ipify.org` | 拿到 2xxx 开头 GUA，且外部站点回显同一地址 |
| P0-2 | 高级设置把 `Account.dhtPort` 固定为 4222；重启账户后 `netstat -ano -p UDP \| findstr 4222`；Windows 防火墙为 jami 放行专用网络 UDP 入站 | 端口稳定监听（默认 0 是随机端口，不固定则映射必失效） |
| P0-3 | 双方 `hostname` 填 `[对方v6]:4222;<对方v4>:4222;bootstrap.jami.net` | 账户重注册成功，不再长时间卡在「连接中」 |
| P0-4 | 观察账户目录下 `dhtstate` 是否生成、大小是否 >0；Wireshark 过滤 `udp.port==4222` | 有 IPv6 直连报文往来；若只见 3478/TURN 流量，说明落到了中继 |
| P0-5 | 互加好友并发 1:1 消息；随后断开一端网络再恢复 | 消息可达；`dhtstate` 生效，重启后无需依赖官方域名 |
| P0-6 | 对照实验：清空 `hostname` 只留官方域名，重复 P0-5 | 若明显更差/不通，即证明 A1 有效（作为基线证据留存） |

**A2 的代价必须如实告知用户**：运营商 SLAAC 隐私地址会周期变化，导致对端保存的地址失效；缓解手段是路由器改稳定后缀（DHCPv6/EUI-64）、自建 DDNS、或每次手工更新 hostname —— 三条都不是零成本。

### 13.8 明确不做

| 不做项 | 原因 |
|---|---|
| 自建 TURN / 中继 / 常开引导节点 | 违反硬约束 1（禁锚点、中继、功能特化节点） |
| 依赖境外 DHT proxy（`dhtproxy.jami.net`） | 固定境外服务，且手机 proxy 模式不参与 DHT 路由表（§12.5） |
| I2P / Tor / SOCKS5 | 源码 0 支持，官方列愿望清单 |
| 底层伪装成 WebRTC / 443 | §8.8 三层裁定均不可行 |
| 免费 MQTT 发现 | §11.7 第 3 条建议删除，L1+ 已覆盖 |

### 13.9 待拍板

| # | 事项 | 建议 |
|---|---|---|
| 1 | A1 是否直接作为 V1.0 交付项（沿用 F-903 档 1 规格） | 建议是；0 代码，只加导入 UI 与文案 |
| 2 | A2 是否随 V1.1 一并做「IPv6 优先」反转 | 建议是，与 §5 方案合流，不单列 |
| 3 | A3 的「固定 dhtPort」是否做进首启引导（首次安装提示填 4222） | 建议做提示、不强制改默认值（改默认会影响已有用户） |
| 4 | A4 是否开工 | 待 §11.7 五项一并拍板后再定 |
| 5 | A5（ICE-TCP）是否进 V1.2 候选清单 | 建议进候选，不急 |

### 13.10 本轮新增源码索引

| 主题 | 位置 |
|---|---|
| ICE 选项与 TCP 开关 | `jami-build/output/*/contrib/include/dhtnet/ice_options.h:88-112`（`tcpEnable` 第 99 行、双栈 public addr 第 103-105 行） |
| TCP 能力查询 | `dhtnet/ice_transport.h:188` `isTCPEnabled()` |
| 死字段 | `src/jamidht/jamiaccount.cpp:166`（仅声明，全树无赋值） |
| TURN 默认值与下发 | `src/jamidht/jamiaccount_config.h:23-24,35-36`；`jamiaccount.cpp:1019-1022,5103-5107`；`sipaccountbase_config.cpp:30` |
| published 地址链路 | `src/sip/sipaccountbase_config.cpp:122,127`；`src/sip/sipaccountbase.cpp:298-322`；`src/jamidht/jamiaccount.cpp:2993` |
| DHT 端口 | `src/jamidht/jamiaccount_config.h:48`；`jamiaccount.cpp:1995`；`jamiaccount.h:894-897`；`account_schema.h:66` |
| UPnP 默认开启 | `src/jamidht/jamiaccount_config.h:40`；`src/account_config.h` `upnpEnabled {true}` |
| 互为引导可行性 | `opendht/include/opendht/dht.h:443-445`；`jamiaccount.cpp:1807-1817,2039-2040` |

---

## 14 多级 NAT 可达性架构与「喷洒 + prflx 注入」降级方案（V1.1 需求输入）

> 本章来源：2026-11-16 下午至深夜连续核准。需求定位已由用户拍板（Q1/Q7/Q8），D1/D2 与第三方依赖口径仍待拍板。**功能代码 0 行，本章为设计输入。**
>
> **⚠ 2026-11-17 重要订正（用户口头澄清）**：本章原把 Q7/Q8 当作「DHT 通、仅 ICE 不通」的降级手段，据此在 14.8 C1 建议「去掉 MQTT、信令走 DHT」。**该前提错误**。用户明确：**Q7/Q8 本来就是基于「DHT 不通」考虑的**；若网络限制导致 DHT 信令通道打不通，就用用户方案（第三方观察者 + MQTT broker + 喷洒 + 带外配对）另行建立信令与数据通道。⇒ C1 结论已撤销并改写；两条路径已拆分，见 14.9 与新增 §15。原 C1 保留为「已撤销裁定」存档。

### 14.1 需求定位（用户已拍板）

| 项 | 用户裁定 |
|---|---|
| Q1 定位 | **给 Jami 用，在现有功能上扩展，不影响现有功能**；只有现有功能无法让两台电脑通讯时才走此流程（失败降级） |
| Q7 喷洒 socket | **复用 ICE 自己的 socket**（保证 NAT 映射端口与注入候选端口一致） |
| Q8 时序 | **方案 B**：ICE 协商失败后销毁重建 transport，带 prflx 候选重新协商 |
| Q2 端口范围探测 | 向第三方服务器发多个包，靠回显推断本机最外层 NAT 端口块 |
| Q3 public port | 对称 NAT 下无用，放弃；只用 PortRange |
| Q4 同步 | MQTT 消息带时戳 + 时间同步流程；超 N 秒判失效重新协商 |
| Q5 确认机制 | 喷洒包 payload 带「对端公网端口号」，回包带「我收到你的端口 P1」；双方确认后锁定端口对通信 |
| Q6 层级 | 探测结果 = **最外层 NAT** |
| **Q0 前提（11-17 订正）** | **Q7/Q8 及整套喷洒方案面向「DHT 不可用」场景**：国内用户少 + bootstrap 被墙 + 双对称 NAT，导致连 DHT 信令层都建不起来。若 DHT 不通，信令不能依赖 DHT，须由独立通道（MQTT broker / 微信带外）承载 |

### 14.2 「多级 NAT 100% 穿透」裁定（需求措辞必须修正）

| 证据 | 说明 |
|---|---|
| WebRTC 标准 | ICE 三候选里 relay(TURN) 是规范强制兜底，没有它无人敢承诺连通率 |
| Tailscale / 向日葵 / ToDesk | 全部内置中继兜底，无例外 |
| 数学 | 双对称 CGNAT + 端口随机 + 端点相关过滤，无第三方观察者时双方无法就彼此外部端口达成一致认知 |

**结论**：「任意多级 NAT 穿透」与「零中继」工程上互斥。本章方案把目标改为：**能直连则自动直连；不能时给确定性诊断 + 替代路径；不出假成功**。IPv6 是对多级 NAT 唯一的确定性解（问题对 v6 不存在），仍是主路径。

### 14.3 已否决路径存档（避免重复绕回）

| 路径 | 否决理由 | 关键数据 |
|---|---|---|
| 端口喷洒 65535 | 先打爆自己的 conntrack；全屋设备共享 ≤16384/65536 | 表满触发 `early_drop`，优先踢未确认 UDP 流 |
| 降频 50 pps | 扫 4096 块要 82s，映射 30s 超时 → 前后映射死掉 | 同步窗口不成立 |
| 降频 500 pps | 8.2s 扫完 4096，窗口解决，但 conntrack 占 92%；且顺序扫描是 IDS 特征 | 时间窗解决，检测未解决 |
| 只扫一个 4096 块 | 16 块盲选，命中率 6.25%；对方块基址需已建通道才能知道 | 自举悖论 |
| 扫全 16 块 | 回到 65535 | 绕回原点 |
| UPnP 跨级 | SSDP 组播 TTL 1~2，路由器不转发；IGD 只管自己那层 | 运营商 CGNAT 层永远碰不到 |
| PCP（RFC 6887） | 协议正解，但国内运营商基本不开，dhtnet 未实现 | 不可依赖 |
| 端口预测 | 现代 NAT 随机化；Jami/dhtnet 全树 0 实现 | 不做 |

**收敛**：速率 / 包量 / 对齐块三个参数已全部调完，天花板在拓扑与协议，不在调参。

### 14.4 端口喷洒命中率的数学修正（修正前文两处过严裁定）

前文说「块内端口随机，扫全块也撞不中」——**不准确**。扫全块恰好覆盖所有随机分配可能。

设 A 的映射函数 α(b)∈BlockA（A 发往 B 块内端口 b 时的外部端口），B 的 β(a)∈BlockB。交叉命中要求 `a = α(β(a))`，即 **α∘β 的不动点**。

| 分配方式 | 命中率 |
|---|---|
| 块内随机双射 | 至少一个不动点概率 ≈ 1−1/e ≈ **63%** |
| 块内顺序递增（非随机） | 不动点可能 0 个 → **可能为 0** |
| DPA 逐会话随机（无块） | 方案前提不成立 → **0** |

⇒ 这是「六成概率打通、四成回退」的**概率方案**，不是确定性方案。Q2 探测必须先能区分 PBA-随机 / PBA-顺序 / DPA 三态，否则无法判断本次尝试值不值得发起。理论值非实测，须 P0 验证。

### 14.5 关键源码核准（决定改动层次）

#### 14.5.1 注入路径是现成的

| 能力 | 位置 | 结论 |
|---|---|---|
| 远端候选唯一入口 | `dhtnet/src/ice_transport.cpp:1367` `startIce(Attribute, vector<IceCandidate>&&)` → `pj_ice_strans_start_ice()` | 一次性数组，**无 trickle / 增量接口** |
| prflx 解析支持 | `ice_transport.cpp:1686` `typ prflx` → `PJ_ICE_CAND_TYPE_PRFLX` | 喷洒端点可直接包装成候选喂入 |
| 本地端点注入先例 | `ice_transport.cpp:1106-1120` `user_mapping`（accountPublicAddr → SRFLX） | 已有「外部告知公网端点 → 注入候选」成熟路径 |
| 调用点 | `connectionmanager.cpp:829`（应答方）、`:1525`（发起方） | 改动点集中，两处 |
| 候选格式 | `getLocalCandidates()` / `getCandidateFromSDP()`（`ice_transport.h:155,164`） | 可复用现成序列化 |

#### 14.5.2 时序冲突（Q8 选 B 的源码依据）

- `startIce()` 失败立即置 `pimpl_->is_stopped_ = true`（`ice_transport.cpp:1372,1380,1428,1447,1481`）；
- 候选一次性传入，无事后追加接口；
- ⇒ 失败后 transport 已废，**必须销毁重建**，不是补丁式注入。正好匹配 Q8=B。

#### 14.5.3 Q7 的技术死锁与破解（本轮最重要发现）

| 层 | 事实 | 影响 |
|---|---|---|
| dhtnet `ComponentIO` | `ice_transport.cpp:191-195` 只有 `mutex / cv / deque<Packet> / recvCb`，**无裸 socket 成员** | 拿不到可随意发包的 UDP fd |
| dhtnet `IceSocket::send` | `ice_transport.cpp:1994-2000` → `IceTransport::send` | 是 ICE 数据通道，非裸 UDP |
| dhtnet `IceTransport::send` | `ice_transport.cpp:1799-1816` 先取 `getRemoteAddress(compId)`，**为空直接 return -1** | 协商前无法用它发包 |
| dhtnet 收包回调 | `ice_transport.cpp:543-544` `const pj_sockaddr_t* /*src_addr*/` **把源地址参数注释丢弃** | 拿不到 peer-reflexive 源地址 |
| **pjnath 文档** | `pjnath/include/pjnath/ice_strans.h:93-99`：「Before ICE negotiation is started, the data will be sent using **default candidate** of the component」 | **协商前 pj_ice_strans_sendto2 本身可发包** ✅ |
| pjnath sendto2 | 签名可指定任意目的地址；协商前走 default candidate、协商后走 nominated pair | 满足「用同一 socket 喷洒」 |
| pjnath 端口绑定 | `pj_ice_strans_stun_cfg.cfg`（`pj_stun_sock_cfg`，含 bound addr/port），`ice_strans.h:234-247` | 可让 **ICE 组件端口 = 喷洒端口** |
| transport 持有者 | `connectionmanager.cpp:867,1554` `std::move(ice)` 进 `IceSocketEndpoint` | 重建需连 endpoint 一起换 |

**裁定**：Q7 不需要改 pjnath，也不需要改 OpenDHT。**卡点全在 dhtnet 封装层**：①补一个「协商前向任意地址发包」接口（绕过 `getRemoteAddress` 检查，直接调 `pj_ice_strans_sendto2`）；②把收包回调的 `src_addr` 透传出来。这两处都是封装层小改，但语义上是新增能力，须走 P0 实测确认。

### 14.6 改动清单（分层，按依赖顺序）

| 层 | 改动 | 具体位置 | 工作量 |
|---|---|---|---|
| **L0 观察者** | 第三方 STUN 只做端口块探测（可选、不进通讯路径、不随包默认分发） | 无源码改动，复用公共 STUN | 0 |
| **L0 信令** | 交换 PortRange / 时戳 / 端口确认。**DHT 通时走 `putEncrypted`（可选优化）；DHT 不通时走独立信令通道（MQTT broker / 微信带外）——这是本章主场景，见 §15** | `connectionmanager.cpp` DHT 信令 + 新增带外模块 | 中；见 14.8 C1 订正 |
| **L1 pjnath** | 不改 | 协商前发送 / 端口绑定 / src_addr 均已具备 | 0 |
| **L2 dhtnet-a** | 收包回调透传 `src_addr`（取消注释 + 新增回调签名） | `ice_transport.cpp:543-544` | 小 |
| **L2 dhtnet-b** | 新增 `sprayProbe(comp_id, dest, payload)`：绕过 remote 检查直调 `pj_ice_strans_sendto2` | `ice_transport.cpp:1799` 附近新增 | 小~中 |
| **L2 dhtnet-c** | 新增 `setOnSprayReply(cb)`，把观察到的源 IP:port 交上层 | `ComponentIO` 旁 | 小 |
| **L2 dhtnet-d** | 工厂新增 `createTransportWithPort(name, port)`，绑定固定本地端口 | `ice_transport_factory.h:41`、`ice_transport.cpp:1950` | 小 |
| **L2 dhtnet-e** | 重建路径：ICE 失败 → 销毁 `IceSocketEndpoint` + transport → 用 prflx 候选重建 → 重协商 | `connectionmanager.cpp:867,1554,829,1525` | **大（状态机）** |
| **L3 daemon** | 喷洒协调器：PortRange 探测、时戳/时间同步、payload 带对端端口、ACK 锁定、63% 失败重试 | `jamidht/` 新增模块 | 中~大 |
| **L3 daemon** | 降级状态机挂载点：ICE fail → spray → rebuild → startIce(prflx) → TLS 双向证书握手 | `connectionmanager.cpp` | 大 |
| **L4 Qt** | 可选诊断显示「NAT 类型 / 端口块 / 本次直连路径」 | QML | 小 |

### 14.7 验收标准（P0 实测，按依赖顺序）

| 编号 | 验证内容 | 判据 |
|---|---|---|
| P0-A | 协商前 `pj_ice_strans_sendto2` 能经 ICE socket 发出探测包 | 抓包见 UDP 出站，源端口 = ICE 组件端口 |
| P0-B | 对端 `on_rx_data` 回调能拿到正确 `src_addr` | 回显的 IP:port = 发送侧最外层 NAT 映射 |
| P0-C | 固定本地端口绑定生效 | `netstat -ano -p UDP` 见端口 = 配置值，重启不变 |
| P0-D | 喷洒发现的端口对包装成 prflx 注入后 `startIce()` 成功 | `on_ice_complete` = SUCCESS，nominated pair 含 prflx |
| P0-E | TLS 双向证书通道在重建后正常，消息可读 | 互发消息成功，证书校验通过 |
| P0-F | 重建路径无资源泄漏 | 旧 socket/订阅计数归零，反复重建 20 次不增长 |
| P0-G | **不影响现有功能** | 关闭喷洒开关后行为与当前版本逐字节一致 |
| P0-H | 双 NAT 实测命中率 | 多轮统计，验证 63% 理论值；若为 0 则判 DPA/顺序分配，方案不适用 |
| P0-I | 与 IPv6 路径共存 | v6 可用时不触发喷洒，优先 v6 |

### 14.8 未决冲突（必须拍板，不可默认）

| # | 冲突 | 说明 | 裁定（11-17 订正后） |
|---|---|---|---|
| **C1** | **MQTT / 带外信令是否允许** | 硬约束 1 禁止常开节点；但 Q7/Q8 场景本就是 DHT 不通，信令**无法**走 DHT | **用户 11-17 明确：DHT 不通时用独立信令通道建立信令与数据通道 ⇒ MQTT/带外被保留，硬约束 1 在此降级路径内视为例外放宽**。原「去掉 MQTT 走 DHT」裁定**撤销**，仅适用于「DHT 通、仅 ICE 不通」的可选优化支线 |
| **C2** | 第三方 STUN 观察者 | 同样触碰硬约束，但用户 Q2 明确要用 | 同 C1：DHT 不通场景下作为端口块探测手段保留；严格限定用途，不宣称「无固定节点依赖」 |
| **C3** | D1 可达性目标 | 「必须穿透」vs「尽力 + 诊断」 | 建议后者（14.2），但 63% 概率仍需用户书面接受（C5） |
| **C4** | D2 IPv6 定位 | 主路径 vs 可选优化 | 建议主路径（多级 NAT 唯一确定解，且能让 DHT 也通） |
| **C5** | 63% 概率方案是否接受 | 四成场景要回退到明确失败态 | 须用户明确接受，否则本章方案不成立 |

**订正后的架构分层**：不再是「一个方案 + 一个开关」，而是**三条互斥路径**，按 DHT/ICE 可达性选择（详见 14.9 与 §15）：

1. **主路径**：DHT 可达 → 原生 Jami（A1 互为引导，0 改动）。
2. **支线 A**（可选优化）：DHT 通、ICE 不通 → 喷洒 + prflx 注入，信令走 DHT `putEncrypted`（原 C1 建议仅限此支线）。
3. **支线 B**（用户主诉，Q7/Q8 本意）：DHT 不通 → 独立信令通道（MQTT/微信带外）+ 喷洒 + prflx 注入 + 重建 transport，见 §15。

**硬约束 1 的边界订正**：严格版「禁止任何锚点/常开节点/服务器」**仅在主路径与支线 A 内成立**；支线 B 因前提就是 DHT 不可用，**必须**允许第三方信令通道（MQTT）或纯人工带外（微信），否则该场景物理上无解。这是对 2026-11-12 硬约束的**场景化例外**，需作为方向性变更在规格中备案。

### 14.9 三条路径分界（11-17 订正，避免方案混淆）

| 路径 | 触发条件 | 信令通道 | 数据通道 | 喷洒/prflx | 硬约束 1 | 改动量 |
|---|---|---|---|---|---|---|
| **主路径** | DHT 可达（官方引导或 A1 互为引导） | DHT 原生 | ICE/TLS 原生 | 不需要 | 完全遵守 | 0 |
| **支线 A**（可选优化） | DHT 通、仅 ICE 不通 | DHT `putEncrypted` | 喷洒 + prflx 注入 + 重建 | 需要 | 遵守 | 中~大（本章 L2/L3） |
| **支线 B**（Q7/Q8 本意，用户主诉） | **DHT 完全不通**（国内无节点 + 双对称 NAT + 引导被墙） | **独立通道：MQTT broker / 微信带外** | 喷洒 + prflx 注入 + 重建 | 需要 | **场景化例外放宽** | 大（新增配套协议 + §15） |

**关键因果**：支线 B 里 DHT 不通，好友请求 / 证书 / ICE offer / 消息便条四步 DHT 信令**全部失效**，独立信令通道不是「备份」而是**唯一信令总线**——这就是用户流程图里 MQTT broker 的角色，也是「DHT 不通仍维持最低通讯」需求的落点。完整流程见 §15。

### 14.10 本章源码索引

| 主题 | 位置 |
|---|---|
| dhtnet 源码根目录 | `toolchain/vcpkg/buildtrees/dhtnet/src/537e5ab957-4f1cd1cce5.clean/` |
| pjnath 源码根目录 | `toolchain/vcpkg/buildtrees/pjproject/src/ae59ebd26c-0721bc1cd3.clean/pjnath/` |
| ICE 候选入口 / prflx / 本地注入 | `src/ice_transport.cpp:1367,1686,1106-1120` |
| 发送前置 / 收包丢 src_addr | `src/ice_transport.cpp:1799-1816,543-544` |
| ComponentIO / transport 停止态 | `src/ice_transport.cpp:191-195,1372-1481` |
| 工厂 / 端口绑定配置 | `include/ice_transport_factory.h:41-45`；`include/pjnath/ice_strans.h:234-247` |
| pjnath 协商前发送契约 | `pjnath/include/pjnath/ice_strans.h:93-99` |
| transport 持有与调用点 | `src/connectionmanager.cpp:867,1554,829,1525` |

---

## 15. DHT 不通时的带外配对方案（支线 B · 用户 11-17 确认立项）

> **定位**：本章是 §14.9 三条路径里的**支线 B**——DHT 完全不可用时，用用户流程图里的方案**另行建立信令通道与数据通道**。与 §14 支线 A（DHT 通、仅 ICE 不通）是两套不同方案，不可混用。
>
> **触发条件**：bootstrap 被墙 + 无可达引导地址 + 双对称 NAT ⇒ DHT 建不起来，好友请求/证书/ICE offer/便条四步 DHT 信令全部失效。
>
> **用户口径（11-17）**：「Q7/Q8 本来就是基于 DHT 不通考虑的；如果网络限制导致无法打通信令通道，就用我说的方案来建立信令与数据通道。」⇒ 带外信令从「备份」升格为**主信令总线**，硬约束 1 在本支线内场景化放宽。
>
> **状态**：仅设计输入，**功能代码 0 行**。

### 15.1 三条路径的选用判据（先判 DHT，再选路径）

| 判据 | 结果 | 走哪条 |
|---|---|---|
| 官方引导 / A1 互为引导后 `dhtstate` 非空、DHT put/listen 往返成功 | DHT 通 | 主路径（0 改动）或支线 A（ICE 不通才喷洒） |
| DHT 反复建不起来、无第一跳、`putEncrypted` 无往返 | **DHT 不通** | **支线 B（本章）** |
| 两人有微信等实时带外渠道 | 恒为真 | 支线 B 的信令载体候选 |

**关键**：支线 B 的判定必须在连接流程**早期**完成（建 DHT 阶段），不能等到 ICE 失败——因为 DHT 不通时根本走不到 ICE 那一步。

### 15.2 用户流程图六项确认（Q1~Q6，已并入设计）

| # | 用户裁定 | 落入本方案的位置 |
|---|---|---|
| Q1 | 给 Jami 扩展，不影响现有功能；仅现有功能不通才走 | 15.6 降级状态机 + P0-G 逐字节不变 |
| Q2 | 向第三方服务器发多包，回显推断**最外层** NAT 端口块 | 15.3 观察者 + 15.4 阶段① |
| Q3 | 对称 NAT 下 public port 无用，只用 PortRange | 15.4 阶段①只交换 PortRange |
| Q4 | MQTT 带时戳 + 时间同步；超 N 秒判失效重协商 | 15.4 信令总线时效 |
| Q5 | 喷洒包 payload 带对端公网端口，回包带「收到你的端口 P1」，锁定端口对 | 15.4 阶段② ACK 锁定 |
| Q6 | 探测结果 = 最外层 NAT | 15.3 |

### 15.3 三个第三方设施的角色与硬约束例外

| 设施 | 用户流程图角色 | 是否常开节点 | 硬约束 1 处理 |
|---|---|---|---|
| 3rd Test server（观察者） | 多包回显，测本机最外层 PortRange | 第三方服务 | 场景化例外：仅探测、可选、不进通讯路径 |
| 3rd Server（public IP/port 回显） | STUN 式回显公网端点 | 第三方服务 | 同上；对称 NAT 下 port 字段无用，可降级为只取 IP |
| 3rd MQTT broker | 交换 id / 公网 IP / PortRange / 时戳 / 端口确认 | **第三方常开节点** | **场景化例外放宽**——DHT 不通时它是唯一信令总线，无它则本支线无解 |

**必须备案的变更**：严格版「禁止任何锚点/常开节点/服务器」（2026-11-12 硬约束）**在支线 B 内不成立**。MQTT broker 与观察者是外部依赖，无 SLA、国内可达性不稳、随时可变。若产品对外宣称「完全 P2P / 无固定节点依赖」，本支线必须排除在宣称范围外。

### 15.4 四阶段完整流程（微信/MQTT 全程当信令总线）

| 阶段 | 做什么 | 复用 / 新写 | 源码依据 |
|---|---|---|---|
| ① 交换初始信息 | 微信/MQTT 交换证书 PEM、Jami ID、PortRange、约定开始时刻 | **新写**证书/参数导入导出 UI；Jami 全树 0 处证书 PEM 导出接口 | — |
| ② 喷洒打洞 | 复用 ICE socket 扫对方 PortRange；payload 带对端端口，回包带「收到你的端口 P1」，双方锁定端口对 | Q7/Q8 已定 + §14 L2 dhtnet-a~d | `ice_strans.h:93-99` 协商前可发包；`ice_transport.cpp:543` src_addr 需透传 |
| ③ 注入候选 + ICE 协商 | 喷洒端点包装 prflx；用微信传的 ufrag/pwd 做远端参数，`startIce()` | 复用 §14.5.1 `startIce` + `SDP` 文本结构 | `ice_transport.h:62-70` `MSGPACK_DEFINE`，可序列化走带外 |
| ④ 加密通讯 | TLS 双向证书握手 → Git swarm 消息 | **几乎不改**，全套复用 | `peer_connection.h:93,99` TLS 只接受 IceSocketEndpoint（必须经 ICE，不能跳过） |

**时序同步（Q4）**：MQTT 消息带时戳；双方做一次时间同步；若 `now − ts > N` 判失效，重协商。喷洒阶段要求双方在约定时刻 ±窗口内同时启动，否则端口映射过期（§14.3 已量化 30s/120s 窗口）。

### 15.5 关键源码核准（决定本方案能否复用加密栈）

| 事实 | 源码 | 对本方案的含义 |
|---|---|---|
| TLS 端点**只接受** `IceSocketEndpoint` | `peer_connection.h:93,99` | 喷洒裸端口喂不进 TLS，**必须**经 ICE 注入（阶段③不可省） |
| `IceSocketEndpoint` 包 `IceTransport` | `connectionmanager.cpp:867` | TLS→ICE→dhtnet 是不可跳的链 |
| `connectDevice(cert,...)` 接受证书对象但仍发 DHT offer | `connectionmanager.cpp:931,785` | DHT 不通时该路径失效，证书交换必须带外 |
| ICE offer/answer 是文本结构 | `ice_transport.h:62-70` `SDP` `MSGPACK_DEFINE` | ufrag/pwd/candidates 可 base64 走微信/MQTT |
| Jami 无证书 PEM 导出/导入接口 | 全树 0 命中 | 阶段①需新写导出/导入 |
| 喷洒命中率 | §14.4 | 63%（PBA 随机）/ 0（顺序或 DPA），概率方案 |

### 15.6 改动清单（在 §14.6 基础上，支线 B 新增项）

| 层 | 改动 | 说明 | 工作量 |
|---|---|---|---|
| **L0 观察者** | 端口块探测客户端（STUN 式多包回显取 PortRange） | 用户 Q2；仅支线 B 启用 | 小 |
| **L0 信令** | 带外信令总线：MQTT 客户端（或微信人工导入）交换 证书/id/PortRange/时戳/offer/answer/端口确认 | 用户 Q4/Q5；**替代 DHT 四步信令** | 中~大 |
| **L1 证书** | 证书 PEM 导出/导入 UI + 带外信任建立 | Jami 无现成接口 | 中 |
| **L2 dhtnet** | 复用 §14.6 的 dhtnet-a~e（src_addr 透传、sprayProbe、回显回调、固定端口工厂、重建路径） | 与支线 A 共用 | 大（状态机） |
| **L3 daemon** | 降级状态机：DHT 建不起来 → 切带外信令 → 喷洒 → 重建 → startIce(prflx) → TLS | 入口在**建 DHT 阶段**，非 ICE 失败后 | 大 |
| **L4 Qt** | 引导/配对向导：导入对端参数、显示 NAT 类型/端口块/直连路径、失败原因 | 体验关键（人工粘贴不可避免） | 中 |

### 15.7 验收标准（支线 B · P0）

| 编号 | 验证内容 | 判据 |
|---|---|---|
| P0-B1 | DHT 不通能被早期判定 | 断开所有引导、`putEncrypted` 无往返时，在建 DHT 阶段切换到带外流程 |
| P0-B2 | 带外信令四步全通 | 证书/id/PortRange/时戳/offer/answer 经 MQTT 或人工导入完整送达 |
| P0-B3 | 证书带外导入后 TLS 校验通过 | 双向证书握手成功，证书指纹与带外交换一致 |
| P0-B4 | 喷洒锁定端口对 | 双方 payload 回显确认同一端口对，63% 场景下命中 |
| P0-B5 | prflx 注入后 `startIce()` 成功 | nominated pair 含喷洒发现的 prflx，on_ice_complete=SUCCESS |
| P0-B6 | 端到端消息可读 | 走 TLS→Git，历史进仓库、签名有效 |
| P0-B7 | 不影响现有功能 | 主路径与支线 A 行为与当前版本逐字节一致 |
| P0-B8 | 时戳失效重协商 | 人为延迟 > N 秒，双方判失效并重协商，不出现假成功 |

### 15.8 遗留问题（须用户拍板才能进 P0）

| # | 问题 | 影响 |
|---|---|---|
| **B-Q1** | 带外信令载体选 **MQTT broker（自动）** 还是 **微信人工粘贴**？ | 决定是否新增 MQTT 依赖 + 是否接受第三方常开节点；微信方案零依赖但体验差、offer 易过期 |
| **B-Q2** | 接受 63% 命中率、四成回退吗？（同 C5） | 概率方案，非确定性 |
| **B-Q3** | 支线 B 的场景化例外放宽硬约束 1，是否写进规格 v3 备案？ | 影响对外「完全 P2P」宣称口径 |
| **B-Q4** | 观察者和 MQTT 是否随包默认配置？ | 触碰硬约束；建议默认关、仅支线 B 触发时启用并提示用户 |

**结论（历史状态，已被 §16 取代）**：用户方向已定（支线 B 立项），但 B-Q1~B-Q4 未拍板前**不进 P0**；功能代码维持 0 行。

---

## 16. 支线 B 定稿：双通道带外信令与配对串规范（B-Q1~B-Q4 已拍板）

> **本节效力**：§15.8「遗留问题」与上文「B-Q1~B-Q4 未拍板前不进 P0」的历史状态，在本轮被用户裁定取代：B-Q1（MQTT 自动 + 微信人工，**两种都要**）、B-Q2（接受 63% 命中率 / 四成回退）、B-Q3（硬约束 1 场景化例外写入规格 v3 备案）、B-Q4（观察者与 MQTT 随包分发、默认关闭、仅支线 B 触发启用）**全部通过**。§14.9 三条路径分界、§15.1~§15.5 源码核准事实与四阶段流程**保持不变**，本节只做支线 B 的落地规格细化。
>
> **状态**：仍是设计输入，**功能代码 0 行**。下文所有「改法 / 新增」均为待实现规格，非已存在实现。

### 16.1 总体架构定稿

**结论先行**：MQTT（自动）与微信（人工）**不是主备关系**，而是同一 `PairingSession` 状态机的两种**等价承载**；任一条通即可完成四步信令，两条都在时按 `seq + nonce` 去重合流；承载不可用时互相降级，但**不自动把数据送上第三方**——支线 B 由用户发起配对时明示当前用哪条，避免无提示地触发 broker。

| 通道 | 触发方式 | 承载内容 | 适用场景 | 降级关系 |
|---|---|---|---|---|
| MQTT broker（自动） | 支线 B 激活后连接，配对串生成时 publish 到约定 topic | 完整 payload：身份公钥、完整证书 PEM、PortRange、ts、offer/answer 全量 candidates | 双方可达同一 broker、追求一次粘贴完成 | broker 不可达 → 5s 内提示切微信；微信串只带指纹，PEM 需要时由 MQTT 补全（不可达则走 TLS 握手后指纹 TOFU，不阻断） |
| 微信人工串（零依赖） | 用户点「生成配对串」→ 复制 → 微信发送；对方点击或粘贴 | 紧凑 payload：指纹/公钥、PortRange、ts、精简 candidates；**不放完整 PEM** | 无 broker / broker 被墙 / 不愿引入第三方常开节点 | 串过期 → 重新生成（新 nonce）；candidates 过多超长 → 分段串或改走 MQTT |
| 两者合流 | 同一 `session_id` | 统一信封 `{session_id, seq, type, ts, nonce, channel, body}` | 一条先到先用，后到去重 | 状态机只认消息不认通道 ⇒ 等价（P0-B13） |

**技术选型总表**（每项一句理由；匹配 GPLv3 增量二开、小团队维护量，不引入无人维护的组件）：

| 层 | 选定方案 | 一句话选择理由 | 主要备选 / 为何不选 |
|---|---|---|---|
| 序列化 | MessagePack | `SDP` 已用 `MSGPACK_DEFINE`（`ice_transport.h:67-73`），项目已链接，新增成本最低 | JSON 体积大 1.5~2×；Protobuf 要新增 schema 工具链，MVP 不值 |
| 文本编码 | base64url（RFC4648 §5，`-_`、无 `+/=`） | URL path 与微信输入框都安全，避免转义/改写 | 标准 base64 的 `+/=` 易被 URL/微信改写；hex 体积翻倍 |
| 压缩 | zlib deflate 级别 6，仅当收益 >10% 才启用 | 依赖 ubiquitous，开关避免小包压缩后反而变大 | zstd 需新增依赖，本场景包体 <1KB 收益有限 |
| 自动传输 | MQTT 3.1.1 over TLS 8883，QoS1 | 轻量、现成 broker 多、支持离线积压；**不自建服务器**（无运维投入） | 自建信令服务违背「不引入服务端」底线且要长期运维 |
| 人工传输 | 微信单条纯文本 token（复制→手动粘贴） | 零依赖、国内可达性最高；**不依赖 App Links 域名验证**（二开签名必失败，约束 A） | `jami:` 在微信被拦（约束 B）；官方 `link.jami.net` 点击不拉起二开包，故不作为拉起载体 |
| 完整性/防篡改 | Ed25519 签名（公钥随串携带） | Jami 身份体系本就基于 Ed25519，64B 签名 + 32B 公钥可离线验签 | HMAC 首连无共享秘密；仅时间戳可任意伪造 |
| 信任锚 | 证书 SHA-256 指纹（16B 展示 / 32B 校验） | 不导出完整 PEM 即可 TOFU，绕开「Jami 无 PEM 导入接口」（§15.5） | 完整 PEM 入串超微信长度上限，不可行 |

**拓扑与链路（仅支线 B 触发时）**：`本机 Jami → 观察者(取 PortRange) → 配对串[微信 / MQTT topic] → 对端 Jami → 喷洒 PortRange → prflx 注入 → startIce() → TLS(IceSocketEndpoint) → Git swarm`。与 §15.4 四阶段一一对应；MQTT/微信**只承担阶段①③的信令**，喷洒与 TLS 完全复用 §14/§15，**不新增数据通道**。

**多端适配逐端核对**（目标端 Win10 / Linux / Android；不写「一套代码全端通用」）：

| 端 | 点击自动拉起 | 手动粘贴 | 端侧特有问题与规避 |
|---|---|---|---|
| Android | **微信内不可自动拉起**（约束 A+B）：P0 复制→粘贴；P1 短信/邮件/浏览器点 `jami:pair?d=`（`AndroidManifest.xml:164` scheme，免域名验证）；P2 自建域 https + assetlinks.json 才可微信零点击 | 设置/连接页新增「从配对串连接」输入框（P0 基线） | `autoVerify=true`（`AndroidManifest.xml:166-174`）因二开签名不匹配**必然失败**；微信又拦自定义 scheme ⇒ 不宣称微信点击拉起；现成 `isJamiLink()/toJamiLink()`（`ContentUri.kt:68-76`、`HomeActivity.kt:452-467`）仅服务 P2 自建域分支 |
| Win10 桌面 | 增强项 P1：安装器注册 `jami:`（HKCU\Software\Classes），`main.cpp` argv 分发 | **P0 主路径**：粘贴整串进配对对话框 | Qt 端**当前无对等 deep-link 接收**（全 src 仅 `core/callmodel.cpp:306` 注释「replaced by jami://」）⇒ 必须新增，不能假设已有；不注册 https 默认处理器 |
| Linux 桌面 | 增强项 P1：`.desktop` 写 `MimeType=x-scheme-handler/jami` | P0 主路径：同 Win10 | 发行版 scheme 注册位置差异大，P0 不依赖它 |
| iOS / 鸿蒙 / 小程序 | **不在本项目目标端范围** | — | 不宣称支持；后续扩展需重做 Universal Links / ArkTS 工程 / 包体与域名白名单核对，不在本方案预算内 |

**非功能性基线（支线 B 附加项；主路径与支线 A 基线不变）**：

| 维度 | 具体数值 | 说明 |
|---|---|---|
| 串生成 / 解析 | 生成 P95 ≤50ms；解析+验签 P95 ≤30ms（中端 Android / 桌面） | 纯本地 CPU，无网络 |
| 串长度 | 目标 ≤480 字符，硬上限 ≤700 字符（含 `JAMI-B1:` 前缀与中文说明行） | 按微信单条约 1000 汉字 / 2000 英文的保守下沿（约束 C），留转义余量 |
| MQTT 信令往返 | P95 ≤500ms（同区域 broker）；broker 不可达 5s 内提示切微信 | 第三方无 SLA，不承诺可用性 |
| 喷洒窗口 | 约定时刻 ±30s（可配 120s），与 §14.3 一致 | 超时即 EXPIRED 重协商 |
| 会话成功率 | 63%（§14.4 概率方案，B-Q2 已接受）；四成落明确失败态 | 不接受「假成功」 |
| 崩溃率 | 配对串解析路径崩溃率 0（P0-B11） | 畸形串必须被 catch，纳入上线门禁 |
| 安全 | 验签通过率 100%；指纹不一致即中止；密钥不硬编码、不落日志 | 底线不可妥协 |
| 持久化 / 备份 | 配对状态仅本地 `PairingSession` 记录，无中心库、**不引入 DB** | TTL 到期清理，无需备份策略 |

### 16.2 配对串（pairing string）格式规范 —— 本次核心

**结论先行（按团长新核准的三条约束修正，原「单一 https 链接兼顾点击与粘贴」的设计作废）**：**微信内不存在可靠的「点击文本串自动拉起二开 Jami」路径**——App Links 域名验证因重签名必然失败（约束 A），微信又拦截自定义 scheme（约束 B）。故配对串改为**同一 payload token + 按渠道加外壳**：微信渠道给纯文本 token（复制→手动粘贴，P0 唯一可靠基线）；短信/邮件/系统浏览器渠道给 `jami:pair?d=<token>`（P1 可点击拉起）；微信内零点击拉起须二开自建域名 + assetlinks.json（P2 未决，与「无服务端」诉求冲突需产品权衡）。完整 PEM 不入串（约束 C）。

**三条决定性约束与对策**：

| 约束 | 核准事实 / 源码依据 | 对配对串的直接影响 | 对策 | 等级 |
|---|---|---|---|---|
| **A** App Links 域名验证必然失败 | `AndroidManifest.xml:166-174` `autoVerify="true"` + `host="link.jami.net"`；上游 `https://link.jami.net/.well-known/assetlinks.json` 声明的是**官方**签名指纹，二开签名不同 ⇒ 验证必失败 | 微信点 `https://link.jami.net/<串>` **不会**自动拉起二开 Jami，而是弹浏览器/应用选择器 | ①微信串改为纯文本 token，文案明示「复制→粘贴」；②自动拉起改用 `jami:` scheme（`AndroidManifest.xml:164` 已有 intent-filter，免域名验证）；③微信内零点击需自建域名 + assetlinks.json | P0 降级 / P1 增强 / P2 未决 |
| **B** 微信拦截自定义 scheme | 微信内置浏览器默认拦截 `jami:` 等自定义 URL scheme（除非域名在白名单） | 即便串用 `jami:`，微信内点击也大概率拉不起 App | 微信渠道**不把 scheme 当点击路径**，只做可复制文本；自动拉起仅在短信/邮件/系统浏览器等放行自定义 scheme 的渠道声明可行 | P0 |
| **C** 微信单条文本长度上限 | 单条约 1000 汉字 / 2000 英文字符（各版本 2000~5000 浮动），超出提示「消息过长」；完整证书 PEM ≈1~2 KB | 完整 PEM + 全量 candidates 塞不进一条串 | payload 只放短字段（Jami ID 20B、IP/PortRange、ts+TTL、**SHA256 前 16B 短指纹**、Ed25519 公钥 32B、签名 64B、精简 candidates）；PEM 走 MQTT 补全或短指纹 TOFU 校验 | P0 |

**统一 token 与渠道外壳**（同一 payload，各路径共用 `PairingPayload.parse()`）：

| 渠道 | 生成形态 | 点击行为 | 手动粘贴 / 导入 | 等级 |
|---|---|---|---|---|
| 微信·短内容（P0 基线） | `JAMI-B1:<token>` + 一行说明（token ≤480 字符） | **不宣称自动拉起**：约束 A+B 叠加 ⇒ 按「复制→切 Jami→粘贴」处理 | 支持：输入框接受整串或裸 token | P0 |
| **微信·长内容（P0，11-17 新增）** | **生成 `pair.jamipair` 文件（JSON 信封），微信以文件发送** | 微信点文件 → 用 Jami「导入配对文件」打开（或先存盘再在 Jami 内选文件） | **导入文件**：文件携带完整 PEM + 全量 candidates，不受文本长度限制 | **P0** |
| 短信 / 邮件 / 系统浏览器 / 放行自定义 scheme 的 IM（P1 增强） | `jami:pair?d=<token>` | 点击经 `AndroidManifest.xml:164` 的 `jami:` intent-filter 拉起二开 Jami（无域名验证） | 支持 | P1 |
| 二开自建域名（P2 未决） | `https://<自有域>/p1.<token>` + 自建 assetlinks.json | 微信内蓝链 + autoVerify 成功 ⇒ 可零点击拉起；代价是固定域名/静态托管依赖，需产品权衡 | 支持 | P2 |
| 桌面 Win10/Linux | 同 token 文本 / 同 `.jamipair` 文件 | 无对等 deep-link（仅 `core/callmodel.cpp:306` 注释）⇒ P1 scheme 注册后才可点击 | **P0 主路径**：粘贴文本或导入文件 | P0/P1 |

**载体自动切换判据**：程序估算「全部字段（含完整证书 PEM 与全量 candidates）编串后字符数」，≤480 走文本串；超过则**自动生成 `.jamipair` 文件**让微信以文件发送。文件内 JSON 信封含 `magic=JAMIPAIR1` + 短 token + 完整 PEM + 全量 candidates + 生成时戳；`importPairingFile()` 校验 magic 后按同一 `PairingPayload.parse()` 状态机处理（POC 已验证，见 §16.9）。

**生成的微信文本模板**（一次复制即可发送，文案不再误导为「点击即拉起」）：

```
[Jami 支线B配对串 v1 · 60秒内有效]
JAMI-B1:<token>
微信内：复制本串 → 打开 Jami → 设置 → 支线B配对 → 粘贴。
自动拉起请用短信/邮件发送 jami:pair?d=<token> 版本（P1 scheme）。
```

`token = base64url(deflate(msgpack(fields)))`；`parsePairingString()` 统一剥离 `JAMI-B1:` / `jami:pair?d=` / `https://<域>/p1.` 三种外壳与裸 token，落到同一状态机。

**长内容文件载体模板**（token 超 480 字符时由程序自动生成，微信发送文件而非长文本）：

```
文件名：pair.jamipair
内容（JSON 信封）：
{
  "magic": "JAMIPAIR1",          // 格式标识，不符即拒绝
  "version": 177,                // 0xB1
  "type": "pair-v1",
  "carrier": "file",
  "token": "<短 token，含身份/端口块/时戳/签名>",
  "cert_pem": "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----",  // 完整证书，文本串放不下的部分
  "full_candidates": [{"ip":"203.0.113.2","port":50001,"prio":1,"typ":"srflx"}, ...],
  "generated_ms": 1763310000000
}
接收方：微信点文件 →「用 Jami 打开」→ Jami 校验 magic + 验签 + 时戳 → 导入配对状态机。
```

文件载体因为走微信文件通道（GB 级上限），**彻底绕开约束 C 的文本长度限制**，可携带完整证书与全量候选；短文本串则保持精简指纹以便快速复制。

**payload 字段清单**（`msgpack` 定长数组按序排列以省 key；版本决定字段数）：

| # | 字段 | 类型 | 必需 | 长度量级 | 依据 / 用途 |
|---|---|---|---|---|---|
| 1 | 格式版本 magic | uint8 | 是 | 1 B（0xB1） | 解析分派；未知版本→提示升级，不尝试解码 |
| 2 | pairing 类型标记 | uint8 | 是 | 1 B（0x01=pair-v1；0x00 保留普通加好友） | 与 §16.3 `isJamiLink()` 的普通搜索分支区分 |
| 3 | 会话 nonce | 8 B 随机 | 是 | 8 B | 防重放；与 seq 组成去重键 |
| 4 | Jami ID | 20 B 二进制（或 40 hex） | 是 | 20 B | 对端标识；`ContentUri.kt:76` 现取 `lastPathSegment`，本串取 `p1.` 之后部分 |
| 5 | 身份公钥 Ed25519 | 32 B | 是 | 32 B | 离线验签（字段13）；SHA-256 后即信任指纹 |
| 6 | TLS 证书短指纹 | 16 B（SHA256 前 16 字节）/ 32 B 全量 | 是 | 16~32 B | 约束 C 指定的短指纹；TOFU 锚点，`peer_connection.h:93,99` TLS 握手后比对，绕开无 PEM 导入接口（§15.5） |
| 7 | IP + 地址族 | family 1B + 4/16 B | 是 | 5~17 B | 最外层 NAT 映射 IP；对称 NAT 下 port 无用（§15.2 Q3），PortRange 才是关键 |
| 8 | PortRange | uint16 × 2 | 是 | 4 B | 观察者测得端口块（§15.3）⇒ 喷洒目标 |
| 9 | 时戳 ts + TTL | uint64(ms) + uint16(s) | 是 | 10 B | Q4 时效；默认 TTL 60s，喷洒窗口 ±30s（§14.3） |
| 10 | ICE ufrag / pwd | string / string | 是 | 4~16 B / 16~24 B | `ice_transport.h:67-73` `SDP{ufrag,pwd,candidates}` `MSGPACK_DEFINE`，可带外传（§15.5） |
| 11 | candidates | array{ip:port:prio:typ} | 是 | 精简 2~4 条，40~120 B | host/prflx 各留最优；全量 candidate 走 MQTT 补全 |
| 12 | 标志 / 保留 | uint8 | 否 | 1 B | 仅预留已明确方向：`need_mqtt=1` 表示需补全 PEM；不为空想需求堆字段 |
| 13 | 签名 | Ed25519 64 B（或截断 HMAC 16 B） | 是 | 16~64 B | 覆盖 #1~#12；公钥在 #5，收方离线可验 |

**payload 字节预算估算（约束 C 的量化依据）**：

| 组成 | 字节 / 字符 | 取值依据 | 备注 |
|---|---|---|---|
| 版本 + pairing 标记 + nonce | 1 + 1 + 8 = 10 B | 字段 #1~#3 | 定的数组开销另计 |
| Jami ID | 20 B | 字段 #4，二进制形态 | 不用 40 hex，省 20 B |
| Ed25519 身份公钥 | 32 B | 字段 #5，验签用 | 不可省，否则无法离线验签 |
| TLS 证书短指纹 | 16 B | 字段 #6，SHA256 前 16 字节 | 约束 C 指定；32 B 全量作可选 |
| IP + 地址族 | 5~17 B | 字段 #7，IPv6 上限 17 B | symmetric NAT 仍传 IP |
| PortRange | 4 B | 字段 #8，2 × uint16 | §15.2 Q3 只交换端口块 |
| ts + TTL | 10 B | 字段 #9，uint64 + uint16 | Q4 |
| ufrag + pwd | 4~16 + 16~24 = 20~40 B | 字段 #10，`ice_transport.h:67-73` | 取上限 40 B |
| candidates | 40~120 B | 字段 #11，host/prflx 各 1~2 条 | 全量走 MQTT 补全 |
| 标志 + 签名 | 1 + 64 = 65 B | 字段 #12~#13 | Ed25519 全签名 |
| **msgpack 小计** | **≈228~327 B** | 上列求和 + 数组头 ≈10 B | 未压缩上限 |
| deflate 后 | ≈180~270 B | 重复前缀可压，收益 <10% 跳过 | — |
| base64url | ≈240~360 字符 | ×4/3，无 `+/=` | token 长度 |
| 外壳 `JAMI-B1:` + 中文说明行 | ≈60~80 字符 | 模板固定文案 | 微信按字符计数 |
| **串总长** | **≈300~440 字符** | 全部相加 | **目标 ≤480，硬顶 ≤700** |

> 完整证书 PEM ≈1~2 KB，**单独就超出微信单条上限约一倍**，因此绝不入串；只在 MQTT 通道补全，或以 16 B 短指纹在带外 TOFU 校验。

**微信单条文本长度限制评估与对策**（B-Q1 微信通道能否落地的硬约束）：

| 事实 / 触发 | 处置 | 兜底 |
|---|---|---|
| 微信单条文本**无官方统一承诺**，实测口径散布：早期约 300 字、主流客户端约 1000 汉字 / 2000 英文、部分客户端 2048 字符，个别资料称 5000 字；超长表现为「消息过长无法发送」或被截断 | 按**最保守的 1000 字符 / 2000 字节**预算设计：目标 ≤480、硬顶 ≤700 字符 | 生成后 `length` 校验 >700 → 拒绝生成并提示「改用 MQTT 或分段串」 |
| 完整证书 PEM ≈1~2 KB，单独就逼近上限 | **PEM 永不入微信串**，只放 16 B 短指纹 + 32 B 公钥（约束 C） | 需严格 pinning 时 PEM 走 MQTT 补全；无 MQTT 时用 TLS 握手后指纹比对（TOFU），不阻断建链 |
| candidates 全量 10+ 条可达数百字节 | 串内**只留 host/prflx 各 1~2 条最优**，置 `need_mqtt=1` | 超 700 字符或 candidate >4 条 → 分段串（信封加 `seg_no/seg_total`）或改走 MQTT |
| 微信把长串识别为链接/手机号并改写 | 全串仅 base64url 字符，不含 `+/=`、不含裸手机号形态；微信载体用 `JAMI-B1:` 纯文本而非 https（约束 A/B） | 被改写时提示「改用复制整串粘贴」路径 |

**与普通加好友链接的兼容性（老版本不崩溃）**：

| 场景 | 老版本 Jami 的处理链 | 结果 |
|---|---|---|
| 微信收到 `JAMI-B1:<token>` 纯文本 | 不是 URI，无 Intent 分发；仅作为聊天文本存在 | 老版本无感知、无崩溃（连解析都不会触发） |
| 短信/浏览器点 `jami:pair?d=<token>` | `AndroidManifest.xml:164` 命中 `jami:` scheme → `HomeActivity.kt:452-467` 的 `ACTION_VIEW` 分支：`isJamiLink()` 为假（scheme=jami）→ `ConversationPath.fromUri()`（`ConversationPath.kt:99-113`）因 host=`pair` 非 `AUTHORITY_URL`（`:49,106`）返回 null | 落入无对话路径的静默忽略；**无解析异常、无崩溃** |
| 自建域 https 收到 `https://<域>/p1.<token>` | 老版本 `isJamiLink()` 仅认 `link.jami.net`（`ContentUri.kt:68-74,49`）⇒ 为假 → `fromUri()` 亦返回 null | 静默忽略，不崩溃 |
| 旧版官方 `link.jami.net/<串>`（若沿用） | `HomeActivity.kt:452-457` 命中 `isJamiLink()` → `toJamiLink()`（`ContentUri.kt:76` 取 `lastPathSegment`）→ 作为 `ACTION_SEARCH` QUERY 交 `HomeFragment.handleIntent()` | 走普通搜索、空结果、不崩溃 |
| 用户可理解性 | 生成文本固定带一句「需 Jami 支线B 版本，老版本请升级」 | 不误判为加好友失败 |
| 未来格式防护 | 字段1 版本 0xB1 未知时直接提示升级，不进入解码 | 防止畸形解析 |

### 16.3 点击拉起的技术路径

**结论先行（按约束 A/B 修正）**：**微信内不提供点击自动拉起**，P0 一律「复制→手动粘贴」；自动拉起仅在**放行自定义 scheme 的渠道**（短信/邮件/系统浏览器）通过 `jami:pair?d=` 实现（P1）；微信零点击需二开自建域名 + assetlinks.json（P2）。所有路径最终调用**同一个** `PairingPayload.parse()` + `PairingCoordinator.start()`。

**渠道可行性（自动拉起 vs 必须手动粘贴）**：

| 渠道 | 载体形态 | 能否自动拉起二开 Jami | 依据 | 结论 |
|---|---|---|---|---|
| 微信聊天 | `JAMI-B1:<token>` | **否** | 约束 A（autoVerify 必失败）+ 约束 B（scheme 被拦） | **必须手动复制粘贴**（P0 唯一可靠路径） |
| 短信 / 邮件 | `jami:pair?d=<token>` | **能**（P1） | 自定义 scheme 未被拦；`AndroidManifest.xml:164` intent-filter | 点击拉起 |
| 系统浏览器 / 放行 scheme 的 IM | 同上 | **能**（P1） | 同上 | 点击拉起 |
| 微信内自建域 https | `https://<自有域>/p1.<token>` | **能但 P2 未决** | 需自有域名 + assetlinks.json 声明二开签名；引入固定域名依赖 | 与「无服务端」诉求冲突，待产品权衡 |
| 桌面 Win10/Linux | token 文本 | 否（P0） | Qt 无 deep-link（仅 `core/callmodel.cpp:306` 注释） | 必须手动粘贴；P1 注册 scheme 后可点击 |

**具体改法（分层）**：

| 端 / 入口 | 具体改法 | 源码依据 | 与现网兼容性 |
|---|---|---|---|
| Android 手动粘贴（P0 主路径） | 设置/连接页新增「从配对串连接」输入框：接受 `JAMI-B1:<token>`、裸 token、整串 URL 三种输入 → 统一 `PairingPayload.parse()` | 新 UI；复用 §15.6 L4 配对向导 | 与点击路径共用解析器，避免两套逻辑漂移 |
| Android 点击（`jami:`，P1） | `ACTION_VIEW` 分支内对 `uri.scheme=="jami" && uri.host=="pair"` 单独识别（`jami:pair?d=<token>`）；其余 `jami:` URI 仍走 `ConversationPath.fromUri()` | `AndroidManifest.xml:164`（`<data android:scheme="jami"/>`，同 filter `:157` VIEW、`:160` BROWSABLE）、`ConversationPath.kt:99-113` | 普通 jami: 会话 URI host 非 `pair`，不冲突（P0-B7） |
| Android 点击（自建域 https，P2） | `ContentUri.kt` 新增 `isPairingLink()`：在 `isJamiLink()` 条件上再判 `lastPathSegment?.startsWith("p1.")`；`HomeActivity.kt:452` 分支内**先**判 `isPairingLink()` → 启动 `PairingActivity(extra=token)`；否则原样走 `:454-457` 普通搜索 | `ContentUri.kt:68-76,49`、`HomeActivity.kt:452-467`、`AndroidManifest.xml:166-174` | 现网 `link.jami.net/<id>` 无 `p1.` 前缀 → 逐字节不变；**官方 link.jami.net 因约束 A 不用于拉起** |
| 桌面点击 P1（Win10/Linux） | 安装器注册 `jami:`（Win: HKCU\Software\Classes\jami\shell\open\command；Linux: `.desktop` `MimeType=x-scheme-handler/jami`）→ `main.cpp` 读 argv[1] → 判 `jami:pair` → 交 `PairingCoordinator` | Qt 端当前无 deep-link 接收（仅 `core/callmodel.cpp:306` 注释）⇒ **必须新增** | 不注册 https 默认处理器，避免侵入浏览器 |
| 桌面手动粘贴 P0 | 配对向导文本框粘贴整串 → 本地剥 `JAMI-B1:` / URL 外壳后解码 | §15.6 L4 | 微信桌面端 / 手机复制均可粘贴 |
| 二维码（可选增强） | `QRCodeProvider.kt:21-33` 已能把 `contactUri` 编成二维码（`:29-31` `encodeStringAsQRCodeData`）⇒ 同一 token 也能出二维码供面对面扫码，**绕开微信 scheme 拦截** | `QRCodeProvider.kt:21-33`、`QRCodeFragment.kt:53,180-191` | 纯增强，不进 P0 |

**新增接口（API）清单**（daemon / 客户端边界，均幂等、带 `session_id`）：

| 接口 | 方向 | 鉴权 / 校验 | 幂等 |
|---|---|---|---|
| `getLocalPairingBlob(version, ttl)` → 串 + 原始字段 | UI → 配对模块 | 仅本地、无网络 | 是（同 nonce 返回同串） |
| `parsePairingString(text)` → Payload 或升级提示 | 点击 / 粘贴 → 配对模块 | 验签 + 版本 + TTL 三重校验 | 是 |
| `publishPairing(session_id, envelope)` | 配对模块 → MQTT | topic ACL + 载荷签名 | 是（`seq+nonce` 去重） |
| `subscribePairing(topic)` → stream | MQTT → 配对模块 | 同上 | 是 |
| `getLocalCertFingerprint()` / `verifyPeerFingerprint(fp)` | 配对模块 → TLS | 本地 TOFU（§15.5：Jami 无 PEM 导入接口，需新写最小接口） | 是 |
| `onSprayPortRange(range, ts)` / `onIceParams(ufrag, pwd, cands)` | 配对模块 → dhtnet | 复用 §14.6 dhtnet-a~e | 是 |

### 16.4 双通道等价性与时戳同步

**结论先行**：通道只是「信箱」，状态机只有一套；MQTT 收全量、微信收紧凑串，两者写入同一 `PairingSession` 邮箱，**先到有效消息先推进**，后到按 `(session_id, seq, nonce)` 丢弃。

| 状态 | 进入条件 | 产出 / 动作 | 两通道差异 |
|---|---|---|---|
| IDLE | 主路径 / 支线 A 判定失败，用户确认进支线 B（§15.1 早期判定） | 加载随包配置（默认关 → 本次显式启用，§16.5） | 无差异 |
| COLLECT | 启用观察者 | 取最外层 PortRange、导出身份公钥 / 证书指纹、生成本端 ufrag/pwd/cands | 无差异 |
| OFFER_READY | 本地参数齐 | 生成微信形态串 + 可选 publish MQTT 全量信封 | 微信=紧凑串；MQTT=全量信封 |
| WAIT_PEER | 串已发出 | 订阅 MQTT topic + 等待用户粘贴对端串 | 两通道并行监听 |
| PEER_PARAMS_RCVD | 收到对端有效 payload（验签 + TTL 通过） | 写入邮箱；`need_mqtt=1` 且 MQTT 可用时补全 PEM / 全量 cands | 微信缺的字段由 MQTT 补，反之亦然 |
| SPRAY → INJECT → ICE → TLS_VERIFY | 双方 ts 落在同一窗口 | 复用 §15.4 阶段②③④：`peer_connection.h:93,99` TLS 只吃 `IceSocketEndpoint`，喷洒端点**必须**经 ICE 注入；`ice_transport.h:67-73` SDP 带外同步 | 无差异（数据通道与信令通道解耦） |
| CONNECTED / EXPIRED / FAILED | 校验通过 / `now-ts>TTL` / 验签失败或 63% 未命中 | CONNECTED 保留会话；其余清理本地状态并提示重协商 | 无差异 |

| 时戳与失效规则（Q4） | 规则 | 依据 |
|---|---|---|
| 时钟基准 | 有 MQTT 时以其时间为参考做一次偏移校准；无 MQTT 时用双方约定时刻 + 本地单调钟 | §15.4 时序同步 |
| 有效期 | payload 自带 `ts(ms)+TTL(s)`；收方先校验 `\|now-ts\|`，超 TTL 即 EXPIRED，不进喷洒 | Q4；默认 TTL 60s |
| 喷洒窗口 | 双方约定时刻 ±30s（可配 120s）内**同时**启动喷洒，否则 NAT 映射过期 | §14.3 量化 |
| 重协商 | EXPIRED/FAILED → 回 COLLECT 生成新串（新 nonce）；旧串因 nonce 去重失效 | 防重放 |
| 双通道去重 | 同一 `(session_id, seq, nonce)` 只处理一次；MQTT 后到的全量消息只补字段，**不回退状态** | 保证等价性 |

### 16.5 随包配置与默认关闭（B-Q4）

| 语义 | 规定 | 工程落点 |
|---|---|---|
| 产品语义 | 观察者客户端与 MQTT 客户端代码/配置**随包分发**，但默认 `enabled=false`；只有支线 B 被早期判定且用户确认后才激活 | 配置命名空间 `branch_b.*`；编译开关 `JAMI_BRANCH_B_ENABLED` + 运行时开关**双闸** |
| 默认关闭可验证 | 关闭时：不建 broker 连接、不启观察者、不读相关配置；主路径 / 支线 A 与当前版本逐字节一致 | P0-B14；对应 §15.7 P0-B7 |
| 激活提示 | 激活瞬间明确提示「当前 DHT 不可用，将启用第三方信令（MQTT）/ 带外配对；本场景不属于『完全 P2P』宣称范围」，用户可拒绝并退出支线 B | §15.3 必须备案的变更 |
| 配置内容 | broker 地址 / TLS 证书 / topic 前缀 / 观察者端点 / TTL / 喷洒窗口；均可覆盖，不硬编码密钥 | 安全基线：密钥走配置或系统凭据，不入库、不硬编码 |
| 硬约束 1 例外备案措辞（写入规格 v3） | 建议原文：「严格版『禁止任何锚点 / 常开节点 / 服务器』（2026-11-12 硬约束）在**主路径与支线 A** 内完全成立；**支线 B**（DHT 完全不可通）下，为建立最低限度通讯，允许引入**用户可知情、默认关闭、仅本支线临时启用**的第三方 MQTT broker 与端口探测观察者作为**信令 / 探测设施**——它们**不承载任何消息正文、不持久化用户数据、不构成数据通道**；产品对外『完全 P2P』宣称明确**排除支线 B**。」 | 与 §14.9 硬约束边界订正、§15.3 备案要求一致 |

### 16.6 改动清单（分层）

| 层 | 改动项 | 依赖 | 工作量 |
|---|---|---|---|
| Android | `ContentUri.isPairingLink()` + `toPairingPayload()`；`HomeActivity.handleIntent()` 插入配对分支（在 `isJamiLink()` 之前） | 无（纯客户端） | 小 |
| Android | `PairingActivity` + 设置页「从配对串连接」粘贴入口 + 串生成 / 复制按钮 | 上层 `PairingPayload` | 中 |
| Android | 复用 `QRCodeProvider`（可选）：`encodeStringAsQRCodeData(串)` | `QRCodeProvider.kt:21-33` 现成 | 小 |
| Qt 桌面 Win10/Linux | 配对向导 UI（导入 / 生成串、显示 NAT 类型 / PortRange / 失败原因）；argv scheme handler（P1） | daemon 接口 | 中（P1 另加小） |
| daemon | 降级状态机入口前移到建 DHT 阶段（§15.1）；`PairingCoordinator` 状态机；§16.3 新接口；证书指纹最小导出 / 校验接口 | dhtnet 喷洒能力 | 大 |
| daemon | MQTT 客户端（TLS / QoS1 / 重连）+ 观察者客户端，均受 `branch_b.*` 门控 | 第三方 broker | 中 |
| dhtnet | 复用 §14.6 dhtnet-a~e：`src_addr` 透传（`ice_transport.cpp:543`）、sprayProbe、回显回调、固定端口工厂、重建路径；`startIce()` 接带外 SDP | 与支线 A 共用 | 大（状态机） |
| 公共 | 序列化 / 压缩 / base64url / 签名工具模块（MessagePack 已在 `ice_transport.h:73` 使用，Ed25519 复用 dht crypto） | 无新增重量依赖 | 小~中 |

**非功能观测**：配对串解析次数 / 失败原因 / TTL 命中 / MQTT 断连 / 喷洒命中率需打埋点（本地日志留存 ≥6 个月、脱敏、不含密钥），规避「无观测 = 盲飞」。

### 16.7 验收标准（P0-B 系列扩充）

| 编号 | 验证内容 | 判据 |
|---|---|---|
| P0-B1~B8 | 沿用 §15.7 全部八项 | 不变 |
| P0-B9 | **配对串点击拉起成功（按渠道限定）** | 短信/邮件/系统浏览器点击 `jami:pair?d=<token>` → 进 `PairingActivity`（P1，依赖 scheme 注册）；微信内**不要求**自动拉起（约束 A/B），改由 P0-B17 验收；自建域 https 零点击拉起列 P2、不进 P0 |
| P0-B10 | **手动粘贴成功** | 桌面 / 微信内 Android 粘贴 `JAMI-B1:<token>` → 同一解析器 → 状态机推进到 `PEER_PARAMS_RCVD`（微信场景的 P0 主路径） |
| P0-B11 | **老版本不崩溃 / 畸形串不崩溃** | 老版本收到 `JAMI-B1:` 纯文本、`jami:pair?d=`、自建域 https 三种形态均无解析异常；新版喂截断 / 超长 / 非法 base64 / 未知版本串，全部被 catch 并提示，崩溃率 0 |
| P0-B12 | **微信长度限制内** | 生成串含模板 ≤480 字符、硬顶 ≤700；主流微信客户端实发不被截断；超长时正确拒绝并提示 |
| P0-B13 | **双通道等价** | 同一会话 MQTT 与微信分别送达，合流后状态机只推进一次；字段互补可完成 TLS；`seq+nonce` 去重生效 |
| P0-B14 | **默认关闭不影响主路径** | `branch_b.*=false` 时无 broker / 观察者连接，主路径与支线 A 逐字节一致（同 P0-B7） |
| P0-B15 | 时戳签名防篡改 | 改 ts / 改 PortRange / 换公钥任一 → 验签失败并中止；延迟 >TTL → EXPIRED 重协商，不假成功 |
| P0-B16 | 证书指纹 TOFU | TLS 握手后对端证书指纹与串内 16 B 短指纹一致才放行；不一致立即断链 |
| P0-B17 | **微信渠道诚实降级（约束 A/B）** | 微信内生成的串为 `JAMI-B1:<token>` 纯文本，文案**不含**「点击自动拉起（微信）」误导；复制→粘贴到 Jami 可完成配对；二开签名包实测点击 `https://link.jami.net/...` 不承诺拉起、点击 `jami:` 不拉起也不卡死 |
| P0-B18 | **自动拉起可行渠道（P1）** | 短信/邮件/系统浏览器点击 `jami:pair?d=<token>` 能拉起二开 Jami 并解析 payload；若 P1 scheme 未实现则登记为已知限制，不得计入 P0 通过 |
| P0-B19 | **payload 字节预算达标（约束 C）** | 生成串实测 ≤480 字符（硬顶 ≤700）；指纹为 SHA256 前 16 B；串内**无完整 PEM**；PEM 补全仅走 MQTT 或 TOFU 指纹比对 |
| P0-B20 | **长内容自动转文件载体（11-17 新增）** | 字段估算超 480 字符时程序自动生成 `pair.jamipair`；微信以文件发送；对方导入后完整 PEM + 全量 candidates 还原、验签通过；magic 不符的文件被拒绝 |

### 16.8 风险与未决项

| # | 风险 / 未决 | 触发条件 | 预案 |
|---|---|---|---|
| R1 | **约束 A（已核准，非风险而是事实）**：App Links autoVerify **必然失败**——`AndroidManifest.xml:166-174` 的官方域名 assetlinks.json 声明官方签名，二开签名不匹配 | 用自有签名密钥出包（必然） | P0 降级为微信复制粘贴（§16.2 对策①，P0-B17）；P1 用 `jami:` scheme 在非微信渠道拉起；P2 自建域名 + assetlinks.json 可实现微信零点击但引入固定域名依赖，**需团长/产品确认是否接受**（未决） |
| R2 | **约束 B（已核准）**：微信拦截自定义 scheme，`jami:` 在微信内点击拉不起 | 微信内发送 `jami:` 串 | 微信载体固定 `JAMI-B1:` 纯文本 + 手动粘贴（P0-B17）；scheme 仅在短信/邮件/浏览器声明可行（P0-B18）；二维码为面对面替代（绕开微信拦截） |
| R3 | **63% 概率方案四成失败** | 顺序端口分配或 DPA 导致命中率 0（§14.4） | B-Q2 已接受；失败必须落明确 FAILED 态并引导重协商 / 换网络，禁止假成功 |
| R4 | **约束 C（已核准）**：微信单条长度上限，完整 PEM 塞不进 | 串 >700 字符或误把 PEM 入串 | 只放短字段 + 16 B 短指纹，PEM 走 MQTT / TOFU；超长拒绝生成并提示（P0-B12 / P0-B19） |
| U1 | broker 选型、topic 命名、是否多 broker 容灾 | 进入实现前 | 本轮不拍板；默认单 broker + 微信兜底，不引入多 broker 复杂度 |
| U2 | 完整 PEM 经 MQTT 补全的字段与证书链格式 | 严格 pinning 需求出现时 | 默认 TOFU 指纹校验即满足 P0；PEM 补全列 P1 |
| U3 | 规格 v3 备案措辞的法务 / 产品终审 | 对外宣称「完全 P2P」时 | §16.5 已给建议原文，待团长转交确认 |

**结论**：支线 B 双通道架构与配对串规范已定稿（B-Q1~B-Q4 全部落地），并按团长新核准的**约束 A/B/C** 修正载体设计——**微信内点击自动拉起不可行，P0 明确采用「复制→手动粘贴」**；自动拉起仅短信/邮件/系统浏览器（`jami:` scheme，P1）与自建域（P2 未决）可行。可进入实现排期；**功能代码仍为 0 行**。R1 的自建域名取舍与 U1 的 broker 选型是实现前需团长 / 产品确认的外部依赖，未确认前不影响本节作为设计输入定稿。

### 16.9 独立 POC 验证结果（2026-11-17，脱离 Jami 源码）

> 按用户要求「先用单独程序验证测试，先不放在 Jami 代码里」，在 `jami-build/tools/branch_b_poc/branch_b_poc.py` 写自包含验证程序（Python 3.13 + msgpack 1.2.4 + cryptography 50.0.2），**不修改、不链接任何 Jami 代码**。运行 `python3 branch_b_poc.py`，**22 项断言全部 PASS（PASS 22 / FAIL 0）**。这是机制验证，不等同于 Jami 内集成验证。

| 测试组 | 验证内容 | 实测结果 | 判定 |
|---|---|---|---|
| **T1 喷洒命中率** | α∘β 不动点蒙特卡洛（block=4096, trials=2000） | 命中率 **63.55%**（理论 63.21%）；平均不动点 0.986（理论≈1.0）；顺序映射 0 命中；DPA 无块前提不成立 | ✅ 证实 §14.4 数学裁定 |
| **T2 配对串编解码** | msgpack→deflate→base64url 往返 + 长度 | token 336 字符、含外壳 **344 字符**（预算≤480）；字段往返一致；验签通过；无 `+/=` 字符 | ✅ 短串方案成立 |
| **T3 文件载体** | 长内容自动切 `.jamipair` + 导入还原 | 短串 False / 长内容 True 正确切换；文件 2955 B；完整 PEM 1203 字符 + 12 条 candidates 全还原；验签通过；magic 不符文件被拒绝 | ✅ 印证用户 11-17 新增需求 |
| **T4 安全与时戳** | 防篡改 / 过期 / 双通道去重 | 改 PortRange、改 ts 均验签失败；过期判 EXPIRED；同 `(session,seq,nonce)` 双通道只推进一次（处理数=2） | ✅ 印证 §16.4 状态机 |

**POC 修正了两处文档估算偏差**（以实测为准）：
1. 配对串实测 **344 字符**，优于文档原估 370~460 —— 预算 ≤480 更宽裕。
2. 压缩率仅 96%（deflate 对小 payload 收益有限），印证 §16.1「收益 >10% 才启用压缩」的开关设计有必要；本例实际走「不压缩」分支。

**POC 局限（必须声明）**：仅验证算法与数据结构的正确性，**未验证**——真实 NAT 设备行为、MQTT broker 可达性、dhtnet 的 `sprayProbe`/prflx 注入、ICE/TLS 集成、三端 UI。这些仍需按 §14.7 / §16.7 在真实环境 P0 实测。POC 程序随方案交付，供后续移植到 Jami 时作为参考实现与回归基线。
