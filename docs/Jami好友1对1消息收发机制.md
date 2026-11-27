# Jami 好友 1:1 消息收发机制（当前源码核准版）

> 核准对象：本工作区源码树 `jami-src/jami-daemon-master`（`CMakeLists.txt:8` → `project(jami-core VERSION 16.0.0)`）与 `jami-src/jami-client-qt-master`。本文只描述**官方原生 1:1 好友会话（ConversationMode::ONE_TO_ONE）**，不含本项目 F-901 公共聊天室二开、不含已放弃的好友导出导入。所有结论均逐行回源码核对，行号以本工作区快照为准。
>
> 核准方法：从 Qt 发送入口 → LRC → libjami 公共 API → daemon `ConversationModule` / `Conversation` / `ConversationRepository` → `JamiAccount` 网络层 → 接收侧 `handleMessage` → `fetchNewCommits` → `pull/fetch/merge` → UI 上屏，逐段读原文。

## 0. 结论速览

| # | 结论 | 关键证据 | 影响 | 备注 |
|---|---|---|---|---|
| 1 | **消息正文不通过网络发送**。正文只写进发送者本地 Git 仓的一条签名 commit，网络只传一个轻量「便条」 | `conversation.cpp:2519`；`conversation_module.cpp:1455-1464` | 决定了整套架构 | 便条 MIME 为 `application/im-gitmessage-id` |
| 2 | 接收方收到便条后**主动回连对方拉 commit**（`git fetch`），再本地 merge 验签上屏 | `jamiaccount.cpp:4292-4308`；`conversation.cpp:2826-2869` | 拉模式，不是推模式 | `git_remote_fetch(..., nullptr, ...)` 全量拉 |
| 3 | 两个好友各自持有**同一份完整会话仓库副本**，互为源 | `conversation_module.cpp:1879`；`conversationrepository.cpp:3027-3035` | 离线可读历史、无中心存储 | 任一设备下线不丢数据 |
| 4 | 每条 commit 由**当前设备私钥签名**，接收方用成员表里的设备证书公钥验签 | `conversationrepository.cpp:2331-2335`、`:2076` | 防篡改、防冒名 | `git commit -S` 风格，签名作为 gpgsig 字段 |
| 5 | 传输层是 **TLS/DTLS 的 P2P 通道**，但 commit message 里的正文是**明文 JSON** | `commit_message.h:116-119`；`jamiaccount.cpp:3883-3933` | 落盘不加密 | 拿到本地仓库副本即可读全部聊天 |
| 6 | **离线消息不是放 DHT `inbox:`**，而是便条在发送方本地 `MessageEngine` 队列持久化，等对方上线重发 | `sipaccountbase.h:190`；`message_engine.cpp:48-83,86-119,122-159` | 重要口径订正 | DHT `inbox:` 只用于好友请求/会话邀请 |
| 7 | 定序靠 **Git commit 父子指针（DAG）**，墙上时钟只是展示标签 | `conversationrepository.cpp:2513,2570` | 时钟不同步不乱序 | 并发消息不保证全局全序 |

**一句话**：Jami 的 1:1 消息 = 「本地写一条签名 Git commit → 发一条不含正文的便条通知对方 → 对方开 TLS Git 通道把 commit 拉过去 → 验签合并 → 各自上屏」。消息本体从不 fan-out，网络上流动的只有「某某会话有新 commit」这句话和随后被拉取的 Git 对象。

## 1. 范围与阅读约定

| 项 | 说明 | 依据/备注 |
|---|---|---|
| 会话类型 | `ConversationMode::ONE_TO_ONE = 0`，两人 swarm，会话 ID = 创世 commit hash | `commit_message.h:84`；`conversationrepository.cpp:3027-3035` |
| 前置条件 | 双方已互为好友（trust request 已接受），且各自完成会话仓库 clone | 见第 3 节 |
| 不涉及 | 群组会话、公共房间、SIP 老式 `sendTextMessage` 直发文本、通话/文件传输细节 | 老式 legacy 路径在 `conversationmodel.cpp:1150-1175` 另有分支 |
| 参与层 | Qt UI → LRC（lib Ring Client）→ libjami（C ABI/DBus/JNI）→ daemon → OpenDHT / dhtnet TLS 通道 → Git 仓 | 跨四套绑定，本文按 Windows Qt 客户端路径叙述 |
| 术语 | 「便条」= `MIME_TYPE_GIT` 通知；「commit」= Git 提交对象；「上屏」= 写入本地历史并触发 UI 信号 | 避免与网络「发送」混淆 |

## 2. 端到端主链路（一表看全）

| 阶段 | A（发送方） | 网络载荷 | B（接收方） | 关键源码 |
|---|---|---|---|---|
| 0 建会话 | 发好友请求时创建 ONE_TO_ONE 会话与创世 commit | DHT `inbox:` 加密邀请 + 确认 | 接受请求 → clone 整仓 | `jamiaccount.cpp:3806-3815`；`account_manager.cpp:711-752`；`conversation_module.cpp:2513-2537` |
| 1 输入 | 输入框回车 → UI 调 LRC | 无 | 无 | `messagesadapter.cpp:159-167`；`conversationmodel.cpp:1137-1147` |
| 2 入 API | libjami `sendMessage(accountId, convId, body, replyTo, flag=0)` | 无 | 无 | `conversation_interface.cpp:173-189` |
| 3 组包 | `CommitMessage::text()` 生成 `{"body":"...","type":"text/plain"}` | 无 | 无 | `conversation_module.cpp:1551`；`commit_message.h:154-161` |
| 4 写本地仓 | `git commit -S` 风格：设备私钥签整个 commit buffer，移到 `refs/heads/main` | 无 | 无 | `conversation.cpp:2507-2527`；`conversationrepository.cpp:2262-2344` |
| 5 本地上屏 | `announce(commit, true)` → `addToHistory()` + UI 信号 | 无 | 无 | `conversation.cpp:663-697`；`:2523` |
| 6 发便条 | `sendMessageNotification()` 四路投递 `{MIME_TYPE_GIT: 便条JSON}` | 便条（约百字节，无正文） | 无 | `conversation_module.cpp:1455-1541` |
| 7 过网络 | `sendMsgCb_` → `MessageEngine` 持久化 → `MessageChannelHandler` TLS 通道；不在线则本地排队 | 便条经 TLS；移动端可转 push | `handleMessage()` 收便条 | `jamiaccount.cpp:2623-2633,3857-3991`；`message_engine.cpp:48-83` |
| 8 触发拉取 | 无 | 无 | 解析 JSON → `fetchNewCommits()` → 证书/授权/去重校验 | `jamiaccount.cpp:4278-4309`；`conversation_module.cpp:742-867` |
| 9 建 Git 通道 | 接受对端连接 | TLS Git socket | `onNeedSocket_` → `addGitSocket()` → `sync()` | `conversation_module.cpp:870-890`；`jamiaccount.cpp:2801-2809` |
| 10 拉取合并 | 作为 P2P Git remote 供对端 fetch | Git 对象流 | `fetch()` → `git_remote_fetch` → `mergeHistory()` → 逐 commit 验签 | `conversation.cpp:2826-2869`；`conversationrepository.cpp:3748,2076` |
| 11 对端上屏 | 无 | 无 | `announce(commits)` → `addToHistory()` → UI 显示 | `conversation.cpp:2868`；`:663-697` |
| 12 状态/回执 | `onMessageSent` 更新 sent 状态；IMDN 已读回执另发 | `message/imdn+xml` | 显示已读 | `jamiaccount.cpp:3995-4003,3413` |

## 3. 前置：好友关系与「同一个会话仓库」从哪来

两个好友能互发消息的前提，是**双方各自持有一份 ID 相同的 Git 仓库**。这一步在加好友时就完成了，不在发消息时做。

| 步骤 | 动作 | 源码 |
|---|---|---|
| 1 | A 调 `JamiAccount::sendTrustRequest(to, payload)`，先把 vCard 载荷缓存到 `cachePath_/requests/<uri>`（含时间戳，重试复用同一邀请时间） | `jamiaccount.cpp:3767-3805` |
| 2 | 查 `getOneToOneConversation(to)`（实际是读联系人记录里的 `conversationId`）；为空则 `startConversation(ConversationMode::ONE_TO_ONE, h)` 新建会话 | `jamiaccount.cpp:3806-3808`；`conversation_module.cpp:1207-1220,2555-2604` |
| 3 | 新会话构造 `Conversation(acc, mode, otherMember)` → `bootstrap()` → `initial_commit()` 生成**创世 commit**，写入初始成员表（自己 + 对方）；该 commit 的 hash 即 `conversationId`，也是磁盘目录 `conversations/<id>/` 的名字 | `conversation_module.cpp:2563,2581-2603`；`conversationrepository.cpp:3027-3035` |
| 4 | `AccountManager::sendTrustRequest` → `forEachDevice(toH)` 找出 B 的所有设备 → 对每个设备 `dht_->putEncrypted("inbox:"+deviceId, dev, TrustRequestMsg{conversationId,...})` 写入 DHT | `account_manager.cpp:711-752` |
| 5 | B 上线从自己的 DHT inbox 取到请求；点接受 → `acceptConversationRequest()` → 同时 `acceptTrustRequest()` 并 `cloneConversationFrom(*request)`，从 A 的设备把整仓克隆过来 | `conversation_module.cpp:2513-2537` |
| 6 | B 回 `sendTrustRequestConfirm` → 同样 `putEncrypted("inbox:"+deviceId, dev, answer)`，A 收到后 `acceptConversation(convId)` | `account_manager.cpp:756-773` |

**要点**：`inbox:` 是**离线邀请邮箱**，装的是好友请求 / 会话邀请 / 确认，不是聊天消息。会话仓库本身靠 P2P `cloneConversationFrom()` 传输，之后两端各自持有完整副本。这也解释了为什么删好友 / 移除会话后，对方发来的便条会触发 `shouldReadd` 检查并按需重新 clone（`conversation_module.cpp:757-785`）。

## 4. 发送侧逐步拆解

### 4.1 从 UI 到 daemon 的四跳

| 跳 | 调用 | 载荷 | 源码 |
|---|---|---|---|
| 1 | `MessagesAdapter::sendMessage(message)` → `ConversationModel::sendMessage(convUid, body, replyToId_)` | `QString` 正文、回复锚点 | `messagesadapter.cpp:159-167` |
| 2 | LRC 判 `!conversation.isLegacy()` → `ConfigurationManager::sendMessage(owner.id, uid, body, parentId, MessageFlag::Text)` | uid = conversationId | `conversationmodel.cpp:1137-1147` |
| 3 | libjami 公共 API：`flag==0` 走普通消息 / 回复，`flag==1` 编辑，`flag==2` 表情回应 | `commitId` 参数复用为 replyTo / 被编辑 ID | `conversation_interface.cpp:173-189` |
| 4 | `ConversationModule::Impl::sendMessage` → `CommitMessage::text(message, replyTo)` → `createCommit` | 明文 JSON 字符串 | `conversation_module.cpp:1544-1553` |

### 4.2 消息体：明文 JSON，不是加密载荷

`commit_message.h:116-153` 原文规定用户消息就是 commit message 里的一段 JSON：

```json
{"body":"你好","type":"text/plain"}
```

变体全部靠追加字段、且都是**新 commit**（历史不可改写）：

| 语义 | JSON 形态 | 说明 |
|---|---|---|
| 普通消息 | `{"body":"你好","type":"text/plain"}` | 基础形态 |
| 回复 | `{"body":"同意","reply-to":"<原commit>","type":"text/plain"}` | `reply-to` 指向原消息，不是指向编辑 |
| 编辑 | `{"body":"改后的话","edit":"<原commit>","type":"text/plain"}` | 多次编辑始终指向原消息 |
| 删除 | `{"body":"","edit":"<原commit>","type":"text/plain"}` | 空 body 的编辑，不是物理删除 |
| 表情回应 | `{"body":"👍","react-to":"<原commit>","type":"text/plain"}` | 取消回应同样是空 body 编辑 |

`edit` / `reply-to` / `react-to` 三者互斥（`commit_message.h:140,153`）。**没有任何字段承载密文**：正文对任何拿到 Git 仓的人可读。

### 4.3 写 commit + 设备私钥签名

| 步骤 | 实现 | 源码 |
|---|---|---|
| 入 IO 线程池、加 `writeMtx_` 写锁 | 避免并发写仓 | `conversation.cpp:2515-2519` |
| 生成 Git 签名对象 | `git_signature_new(name, deviceId, std::time(nullptr), 0)`；时间仅作标签 | `conversationrepository.cpp:903-923` |
| 组装 commit buffer | `git_commit_create_buffer(..., msg.c_str(), tree, 1, &head_ref[0])`，parent 指向当前 HEAD | `conversationrepository.cpp:2324-2329` |
| **签名** | `account->identity().first->sign(to_sign_vec)` → base64 → `git_commit_create_with_signature(..., "signature")` | `conversationrepository.cpp:2331-2339` |
| 移动分支 | `git_reference_create("refs/heads/main", commit_id, force=true)` | `conversationrepository.cpp:2342-2344` |

`addUserDevice()` 会先把自己的设备证书写进仓（`conversationrepository.cpp:4101-4105`），保证接收方能拿到验签公钥。

### 4.4 本地上屏：`announce()` 不是网络发送

`createCommit` 成功后依次执行 `onCommit(commit)` → `pimpl_->announce(commit, true)` → `cb()`（`conversation.cpp:2521-2525`）。`announce()` 内部是 `addToHistory(loadedHistory_, commits, true, commitFromSelf)`（`conversation.cpp:663-697`），即**只写本地历史视图并触发 UI 信号**。网络动作全部在 `cb()` 里由 `sendMessageNotification()` 发起（`conversation_module.cpp:1564-1577`）。这是最容易误读的一处：本地消息「立刻显示」与「对方收到」是两件事。

## 5. 便条：结构、语义与四路投递

### 5.1 便条内容

`sendMessageNotification()` 组出的 JSON 只有三个字段（`conversation_module.cpp:1455-1464`）：

```json
{"id":"<conversationId>","commit":"<新commit的hash>","deviceId":"<发送设备Id>"}
```

MIME 类型为 `application/im-gitmessage-id`（`conversation_module.h:31`）。**不含正文、不含作者姓名、不含时间**：对端只知道「这个会话多了一个 commit，去拉」。

### 5.2 四条投递路径

| 路径 | 收件对象 | 作用 | 源码 |
|---|---|---|---|
| ① 自己账号的其他设备 | `username_` 名下所有设备 | 同一账号多设备同步 | `conversation_module.cpp:1466-1472` |
| ② 未直连成员（最多 2 个） | `memberUris(username_, {BANNED})` 与 `peersToSyncWith()` 求差后随机取 2 | 让没有 DRT 连接、或用旧备份恢复的设备也能同步 | `:1474-1514` |
| ③ DRT 选出的直连设备 | `conversation.peersToSyncWith()`，跳过自己 deviceId | 主路径，直接通知在线对端 | `:1516-1524` |
| ④ 移动端节点 | `mobileNodesToNotify()` | 不挂 DRT 的手机靠 DHT proxy 转成推送通知 | `:1526-1540` |

补充保护：会话尚未 `bootstrap()` 完成时，把 `(conversationId, commit)` 暂存进 `notSyncedNotification_`，等 bootstrap 后重放（`conversation_module.cpp:1499-1507`）。`refreshMessage` 用 `refreshToken` 复用同一条待发消息，避免重复入队（`:1470-1471`）。

## 6. 便条如何过网络：在线、离线、推送

| 场景 | 链路 | 源码 |
|---|---|---|
| 组装回调 | `ConversationModule` 构造时注入 `sendMsgCb_` → `JamiAccount::conversationSendMessage(uri, device, msg, token)` → `sendTextMessage(...)` | `jamiaccount.cpp:2794-2800,2623-2633,3831-3854` |
| 入队 | `SIPAccountBase::sendTextMessage` 非 `onlyConnected` 时调 `messageEngine_.sendMessage(to, deviceId, payloads, refreshToken)`；消息以 `MessageStatus::IDLE` 持久化到 `<cache_dir>/<accountId>/messages`，随后 `retrySend` | `sipaccountbase.h:179-191`；`sipaccountbase.cpp:52`；`message_engine.cpp:48-83` |
| 在线发送 | `JamiAccount::sendMessage` 取 `MessageChannelHandler`（`Uri::Scheme::MESSAGE`），有现成通道就直接 `MessageChannelHandler::sendMessage(conn, *msg)`；`msg->t` = MIME，`msg->c` = 便条 JSON | `jamiaccount.cpp:3857-3937`；`:5129` |
| 无通道 | 对 B 的每个设备调 `requestMessageConnection(to, deviceId, payload_type)` 请求建连；`MIME_TYPE_GIT` 时把 `payload_type` 扩成 `application/im-gitmessage-id/<convId>`，供 iOS 通知扩展定位会话 | `jamiaccount.cpp:3963-3991`；`:4444` |
| 离线重试 | 发送失败留 `IDLE`；`onPeerOnline(peer, deviceId)` / `onRegistrationResumed()` 触发 `retrySend()`，状态 `IDLE→SENDING` 并重发；成功经 `onMessageSent` 清状态 | `message_engine.cpp:86-119,122-161`；`jamiaccount.cpp:3995-4003` |
| 移动端 | 便条经 DHT 代理转成 push（不传正文） | `conversation_module.cpp:1526-1533` |
| 已读回执 | 另一条 `MIME_TYPE_IMDN` 消息，用 `sendInstantMessage(... onlyConnected=true)` 发出，不进重试队列 | `jamiaccount.cpp:3413,4261-4275` |

> **重要订正**：早期口径把「离线消息」笼统说成放 DHT `inbox:`。核实后应区分——`dht_->putEncrypted("inbox:"+deviceId, ...)` 只出现在 `account_manager.cpp:742,772`，装的是 `TrustRequestMsg` / 确认；**聊天便条不写 DHT**，而是发送方本地 `MessageEngine` 队列 + 上线重发。这意味着便条在 A 下线前没发出去就一直留在 A 本机，B 上线也不保证立刻拿到（要等 A 上线重发）；但**消息正文早在 A 本地 commit 里，绝不会因此丢失**。

## 7. 接收侧逐步拆解

| 步骤 | 动作 | 校验/分支 | 源码 |
|---|---|---|---|
| 1 | `JamiAccount::handleMessage(cert, from, m)` 收到便条 | 无证书或 `cert->issuer->getId() != from` 直接丢弃 | `jamiaccount.cpp:4278-4291` |
| 2 | 命中 `MIME_TYPE_GIT` 分支，解析出 `deviceId / id / commit` | JSON 解析失败返回；解析成功丢进 IO 线程池，避免阻塞 SIP socket | `jamiaccount.cpp:4292-4309` |
| 3 | `ConversationModule::fetchNewCommits(peer, deviceId, id, commit)` 公共包装 | `isValidConversationId` 校验 ID，非法直接忽略 | `conversation_module.cpp:3242-3255` |
| 4 | `Impl::fetchNewCommits`：会话被移除时判 ONE_TO_ONE 好友是否已重新确认；已 declined 的请求忽略；找不到会话就回一条 `MIME_TYPE_INVITE` 索要邀请 | 防止未知会话、防止被强塞邀请 | `conversation_module.cpp:742-830` |
| 5 | 已有该 commit → 直接返回（去重）；会话正在删除 → 忽略；`isPeerAuthorized(peer, deviceId, true)` 校验设备是否属于该会话成员 | 未授权设备不能触发拉取 | `conversation_module.cpp:834-850` |
| 6 | `startFetch(deviceId)` 防并发；忙则 `deferFetch()` 排队 | 同一设备同时多次通知不重复开拉取 | `conversation_module.cpp:859-867`；`conversation.cpp:2798-2823` |
| 7 | `onNeedSocket_(convId, deviceId, ...)` → `JamiAccount::onConversationNeedSocket/NeedSwarmSocket` 建 TLS Git socket → `conversation->addGitSocket()` → `conversation->sync()` | 通道类型为 `application/im-gitmessage-id` | `conversation_module.cpp:870-890`；`jamiaccount.cpp:2801-2809`；`conversation.cpp:3598` |
| 8 | `Conversation::pull(deviceId, cb, commitId)` → `Impl::pull()`：`repo->hasCommit()` 短路 → 否则 `repo->fetch(deviceId)` | 一次拉取处理队列里所有待拉 commit | `conversation.cpp:2798-2823,2826-2860` |
| 9 | `fetch()` 内 `git_remote_fetch(remote, nullptr, &fetch_opts, "fetch")`（nullptr refspec = 默认全分支，不是增量补丁） | 单次 256MB 上限，超出直接失败 | `conversationrepository.cpp:3671-3748`；`:64` |
| 10 | `mergeHistory(deviceId, ...)` 合并远端历史：逐 commit 用 `deviceCert->getPublicKey().checkSignature()` 验签，检查成员/权限/JOIN 合法性，必要时生成 merge commit | 验签失败整仓回滚，不留半截仓库 | `conversation.cpp:2862-2869`；`conversationrepository.cpp:2076,1553-1628` |
| 11 | `announce(commits)` → `addToHistory()` → UI 信号；`linearizedParent` / `relinearizeAfterMerge()` 把 DAG 排成线性视图 | 分叉合并后重排显示链 | `conversation.cpp:2868,663-697,1763-1777,1842-1939` |
| 12 | Qt 侧首屏分页加载（`loadChunkSize_ = 20`），上滑继续 | 只是 UI 分页，daemon 侧全量 | `messagesadapter.h:197` |

## 8. 消息在磁盘上长什么样

| 项 | 事实 | 依据 |
|---|---|---|
| 存放位置 | 每个会话一个 Git 仓，目录名 = 创世 commit hash；路径形如 `<data_dir>/<accountId>/conversations/<conversationId>/` | `conversationrepository.cpp:3027-3035`；`conversation_module.cpp:1995-2044` |
| 内容载体 | 正文作为 Git commit message 明文 JSON；签名放在 `signature` 字段（gpgsig 等价物） | `commit_message.h:116-119`；`conversationrepository.cpp:2331-2335` |
| 历史可改写性 | 不能。编辑/删除/表情都是新 commit，旧 commit 永远留在 DAG 里 | `commit_message.h:121-153`；设计文档 ADR-001 |
| 时序字段 | `git_signature_new(..., std::time(nullptr), 0)` 写入提交时间；`git_commit_time()` 读出用于 UI 展示 | `conversationrepository.cpp:914,2910` |
| 成员与证书 | 仓内保存成员文件与设备证书，用于验签和权限判断 | `conversationrepository.cpp:2076`；`conversation_module.cpp:1530-1532` |
| 单仓上限 | clone / fetch 单次 256MB，超限直接中止失败（不是降级） | `conversationrepository.cpp:64,3096-3105,3733-3746` |

## 9. 时序、去重与并发

| 机制 | 作用 | 源码 |
|---|---|---|
| Git parent 指针形成 DAG | 保证**因果序**：回复必在被回复消息之后，所有节点一致 | `conversationrepository.cpp:2324-2329` |
| `GIT_SORT_TOPOLOGICAL` 与 `GIT_SORT_TIME` 组合 | 遍历以拓扑序为主、时间为辅；时间只作同层次级参考 | `conversationrepository.cpp:2513,2570` |
| `linearizedParent` + `relinearizeAfterMerge()` | 把分叉合并后的 DAG 线性化成 UI 显示链，回放 `SwarmMessageUpdated` | `conversation.cpp:1763-1777,1842-1939` |
| `hasCommit(commitId)` / `startFetch` / `pull` 队列 | 便条重复到达、并发通知不重复拉取、不重复上屏 | `conversation_module.cpp:836-838,859-867`；`conversation.cpp:2798-2823,2850-2853` |
| 官方显式关闭证书时间校验 | `GNUTLS_VERIFY_DISABLE_TIME_CHECKS` 规避设备时钟漂移导致的证书误判 | `conversationrepository.cpp:113` |

**诚实边界**：并发且时间戳相同的两条 commit，相对顺序不保证，不同机器可能显示不同（源码注释 `conversation.cpp:1867` 附近明确写出）。时钟不同步**不会让回复跑到原消息前面**，只会让「同时发出的两条」排序不一致；显示时间则可能因为本机时钟错误而显示成未来/过去。

## 10. 安全边界（能防什么、不能防什么）

| 威胁 | 是否防住 | 机制 / 缺口 |
|---|---|---|
| 网络窃听便条与 Git 对象 | 防住 | P2P 通道 TLS/DTLS 加密（`jamiaccount.cpp:3883-3933`） |
| 伪造消息、篡改历史 | 防住 | 每 commit 设备私钥签名 + 接收方 `checkSignature()` 验签（`conversationrepository.cpp:2076,2331-2335`） |
| 非成员设备触发拉取 | 防住 | 证书链 `cert->issuer == from` + `isPeerAuthorized()`（`jamiaccount.cpp:4285`；`conversation_module.cpp:843`） |
| 拿到本地仓库副本的人读历史 | **不防** | commit message 为明文 `text/plain` JSON，无内容加密 |
| 好友双方之外的第三方服务器读消息 | **不适用** | 无中心服务器；但上游 DHT bootstrap / TURN 参与连接建立，不接触仓库内容 |
| 消息撤回 / 彻底删除 | **做不到** | 历史不可改写，删除只是空 body 的新 commit |
| 元数据（谁和谁在通信、何时） | 不完全防 | DHT 层可见订阅与在线关系；内容加密不覆盖元数据 |
| 发送方长期离线导致便条发不出 | 部分缓解 | 便条只在 A 本地排队，等 A 上线重发；正文已在 A 本地仓不会丢，但 B 可能长时间看不到 |

## 11. 常见误解订正

| 误解 | 核准后的事实 | 依据 |
|---|---|---|
| 「消息发给了对方」 | 只发了便条；正文是对方主动来拉的 | `conversation_module.cpp:1455-1464`；`conversation.cpp:2826-2856` |
| 「`announce()` 是把消息发上网」 | 它是本地 `addToHistory()` 上屏，网络出口只有 `sendMessageNotification()` | `conversation.cpp:663-697`；`conversation_module.cpp:1573` |
| 「一条消息群发/转发给多个设备」 | 消息本体只有一份（本地 commit），多设备收到的是同一个 commit hash 的便条，各自拉取 | `conversation_module.cpp:1466-1540` |
| 「离线聊天消息存 DHT `inbox:`」 | `inbox:` 只装好友请求与会话邀请；聊天便条由发送方本地 `MessageEngine` 队列持久化 + 上线重发 | `account_manager.cpp:742,772`；`message_engine.cpp:48-83` |
| 「消息加密了所以本地看不懂」 | 传输加密 ≠ 落盘加密，commit message 是明文 JSON | `commit_message.h:116-119` |
| 「靠时间戳排序，时钟不同步会乱」 | 主序是 Git DAG 父子指针，时间只是展示标签 | `conversationrepository.cpp:2513,2570` |
| 「删掉消息就没了」 | 只是追加一条空 body 编辑 commit，原 commit 仍在历史里 | `commit_message.h:131-133` |

## 12. 与 F-901 公共聊天室的差异（供二开对照）

| 维度 | 官方 1:1（本文） | F-901 公共房间（方案，未实现） |
|---|---|---|
| 成员准入 | 必须先互加好友、trust request 携带 convId、对端接受后 clone | 免邀请自由进入，靠随包签名 `rooms.json` + 创世 bundle |
| 会话来源 | 由 A 现场创建并邀请 B | 随安装包分发 `seeds/<roomId>.bundle`，不依赖任何在线节点 |
| 便条投递 | 四路（自己设备 / 2 个未直连 / DRT / 移动端） | R1 只投成员表有效在房成员；R2 单成员时不外发、不写待发队列 |
| 成员过滤 | `memberUris(username_, {BANNED})` | 需收窄为 `{INVITED, BANNED, LEFT}`，并对 mode=3 追加 `banned/` 守卫 |
| 历史可见 | 双方各自全量历史 | 已拍板「新人加入后 UI 只显加入后」，但底层仍全量明文落盘 |
| 管理员 | 1:1 双方天然对等 | 创世私钥打包即销毁，无 ADMIN |

## 附录 A 源码索引

| 文件 | 行号 | 作用 |
|---|---|---|
| `src/client/conversation_interface.cpp` | 173-189 | libjami 公共 `sendMessage`，按 flag 分普通/编辑/表情 |
| `src/jamidht/conversation_module.cpp` | 742-830 | 接收侧 `fetchNewCommits` 前置校验、未知会话索要邀请 |
| 同上 | 1207-1220 | `getOneToOneConversation` 从联系人记录取会话 ID |
| 同上 | 1455-1541 | 便条组装 + 四路投递 + `notSyncedNotification_` |
| 同上 | 1544-1579 | `sendMessage` → `CommitMessage::text` → `createCommit` → 通知回调 |
| 同上 | 2513-2537 | 接受会话请求并 clone 整仓 |
| 同上 | 2555-2604 | `startConversation(ONE_TO_ONE, ...)` 建会话、bootstrap、登记 convInfo |
| 同上 | 3242-3255 | `fetchNewCommits` 公共包装与 ID 合法性校验 |
| 同上 | 3725-3741 | 联系人会话被移除时重建 ONE_TO_ONE 会话 |
| `src/jamidht/conversation.cpp` | 663-697 | `announce()` 本地上屏 `addToHistory()` |
| 同上 | 2507-2527 | `createCommit`：写 commit → onCommit → announce → cb |
| 同上 | 2798-2823 | `pull()` 排队去重 |
| 同上 | 2826-2869 | `Impl::pull()`：fetch → mergeHistory → announce |
| 同上 | 1763-1777, 1842-1939 | 线性化与合并后重排 |
| `src/jamidht/conversationrepository.cpp` | 2262-2344 | 普通 commit 创建、设备私钥签名、移动 main 分支 |
| 同上 | 2076 | 接收侧公钥验签 |
| 同上 | 2513, 2570 | `GIT_SORT_TOPOLOGICAL` 与 `GIT_SORT_TIME` 组合 |
| 同上 | 3027-3035 | 创世 commit hash = conversationId |
| 同上 | 3748 | `git_remote_fetch(nullptr refspec)` 全量拉 |
| 同上 | 1553-1628 | `checkValidJoins()` 成员/JOIN 合法性 |
| `src/jamidht/commit_message.h` | 51, 84, 116-161 | `text/plain`、`ConversationMode`、消息 JSON 全部变体 |
| `src/jamidht/jamiaccount.cpp` | 2623-2633 | `conversationSendMessage` 把便条交给 `sendTextMessage` |
| 同上 | 2783-2815 | `ConversationModule` 构造与四组回调注入 |
| 同上 | 3767-3820 | `sendTrustRequest` 建 1:1 会话并发邀请 |
| 同上 | 3831-3855 | `sendTextMessage` 分流 |
| 同上 | 3857-3991 | `sendMessage` / `MessageChannelHandler` / `requestMessageConnection` |
| 同上 | 3995-4003 | `onMessageSent` 更新发送状态 |
| 同上 | 4261-4275 | `sendInstantMessage`（成员广播 / IMDN） |
| 同上 | 4278-4309 | `handleMessage` + `MIME_TYPE_GIT` 触发拉取 |
| `src/jamidht/account_manager.cpp` | 688-773 | trust request 接受/发送/确认，DHT `inbox:` 投递 |
| `src/sip/sipaccountbase.h` | 179-191 | `sendTextMessage` 是否走 `MessageEngine` 的分流 |
| `src/im/message_engine.cpp` | 48-161 | 便条本地持久化、上线重发、状态机 |
| `src/jamidht/conversation_module.h` | 31 | `MIME_TYPE_GIT = "application/im-gitmessage-id"` |
| `jami-client-qt-master/src/app/messagesadapter.cpp` | 159-177 | Qt 发送入口 |
| `jami-client-qt-master/src/core/conversationmodel.cpp` | 1137-1175 | LRC 发送分流（swarm / legacy） |
| `jami-client-qt-master/src/app/messagesadapter.h` | 197 | 首屏分页 `loadChunkSize_ = 20` |

## 附录 B 关键常量与语义

| 常量/符号 | 值/含义 | 位置 |
|---|---|---|
| `ConversationMode::ONE_TO_ONE` | `0`，两人会话 | `commit_message.h:84` |
| `MIME_TYPE_GIT` | `application/im-gitmessage-id`，新 commit 便条 | `conversation_module.h:31` |
| `MIME_TYPE_INVITE` / `MIME_TYPE_INVITE_JSON` | 会话邀请 | `jamiaccount.cpp:121-122,4310-4313` |
| `MIME_TYPE_IMDN` | `message/imdn+xml`，已读回执 | `jamiaccount.cpp:120,4349` |
| `MAX_FETCH_SIZE` | 256MB，单次 clone/fetch 上限 | `conversationrepository.cpp:64` |
| `MessageStatus::IDLE / SENDING` | 便条待发状态机 | `message_engine.cpp:141-145` |
| `notSyncedNotification_` | bootstrap 未完成时暂存的便条 | `conversation_module.cpp:1499-1507` |

## 附录 C 「Git 通道」是什么，与 GitHub 有什么关系（2026-11-13 补）

正文第 3、5 节出现的「TLS Git 通道」是本文档唯一未展开的术语，此处补全。**一句话**：它不是一条新的网络连接，也不是一个服务，而是 Jami 在**两台设备之间已有的一条 TLS 长连接上开的一个命名子通道**，把标准 Git 传输协议（pkt-line / `git-upload-pack` / packfile）的字节流直接读写到这条通道上。libgit2 以为对面是个 Git 服务器，实际上对面是好友设备内存里的一个 `GitServer` C++ 对象。

### C.1 分层构成（自下而上）

| 层 | 实体 | 作用 | 关键源码 |
|---|---|---|---|
| 连接层 | 每对设备**一条** TLS 连接，ICE 协商 / DHT 信令 / 设备证书双向认证 | 早已建好，开子通道不重新握手 | `doc/adr/ADR-002-multiplexed-channels-per-device-connection.md:15-36`；`jamiaccount.cpp:5097-5115` |
| 复用层 | `MultiplexedSocket` 上多个命名通道：channel 0 控制，`sip` / `git://` / `data-transfer://` / `sync://` / `swarm://` / `msg:` 等 | 每个功能各占一个通道名，不新开连接 | 同上 `:17-22`；`conversation_channel_handler.cpp:37` |
| 通道层 | 通道名 `git://<对端DeviceId>/<conversationId>` | 既当通道标识，也当 libgit2 的 remote URL | `jamiaccount.cpp:2669-2671`；`conversationrepository.cpp:3703,3077` |
| 协议层 | 标准 Git smart protocol v1：refs advertisement → want/have → ACK → packfile | 复用 Git 线协议，不自造报文 | `gittransport.cpp:40-85`；`gitserver.cpp:180-212,436-478` |
| 实现层 | 客户端 libgit2 `git_remote_fetch`；服务端 `GitServer` + `git_packbuilder_new` | **纯库实现，不起 git 进程、不监听端口** | `manager.cpp:744-750`；`conversationrepository.cpp:3748` |

### C.2 关键机制：把 `git://` 这个 scheme 劫持了

| 事实 | 说明 | 为什么重要 | 源码 |
|---|---|---|---|
| 启动时注册自定义 transport | `git_transport_register("git", p2p_transport_cb, nullptr)` 覆盖 libgit2 标准 `git://` 实现 | 同名 scheme 被换成了 P2P 实现 | `manager.cpp:744-750` |
| remote URL 不是网络地址 | `git://{deviceId}/{conversationId}`，host 段是**设备公钥 hash**，路径段是**创世 commit hash** | 不是域名、不是 `owner/repo`，无处可解析 | `conversationrepository.cpp:3703`；`gittransport.cpp:215-222` |
| 读写直接落到 socket | `P2PStreamRead/Write` 调 `sock->waitForData/read/write` | 没有 DNS、没有 TCP connect、没有新 TLS 握手 | `gittransport.cpp:118-174` |
| 服务端是内存对象 | 收到 `git://` 通道 → `isPeerAuthorized` 成员校验 → `new GitServer(...)`，自己解析 pkt-line 并打 pack；只支持 protocol v1 | 非成员连通道都开不了 | `jamiaccount.cpp:2475-2511,2548`；`gitserver.cpp:180-212,436-478` |
| 通道按需、临时、可复用 | socket 存 `gitSocketList_` 缓存复用，断线 `onShutdown` 注销 | 不是常驻监听服务 | `conversation.cpp:924-962`；`jamiaccount.cpp:2689-2699` |

### C.3 与 GitHub 的关系：只共用「Git」，不共用「Hub」

| 维度 | Git（工具 / 数据模型 / 线协议） | GitHub（中心化托管服务） | Jami 的 Git 通道 |
|---|---|---|---|
| 本质 | 一个版本控制系统及其对象格式、传输协议 | 一家公司运营的中心化仓库托管网站 | 两台设备间的一条 P2P 加密字节流 |
| Jami 是否用到 | **是**：commit/tree/DAG/签名、pack protocol、libgit2 | **否** | — |
| 仓库在哪 | — | GitHub 的服务器 | 每个参与者**本地各一份完整副本** |
| 身份与鉴权 | — | 账号、OAuth、SSH key、仓库权限 | 设备证书链 + `isPeerAuthorized` 成员校验 + commit 签名 |
| 网络地址 | — | `https://github.com/<owner>/<repo>`、`git@github.com:...` | 伪 URL `git://<DeviceId>/<conversationId>`，被自定义 transport 截获，**不出本机解析** |
| 是否需装 git 命令行 | 常规使用需要 | — | **不需要**，libgit2 直接链进 daemon |
| 可用性依赖 | — | GitHub 在线 | 任意在线成员；**0 人在线则无人可拉** |
| Web / PR / Issue / 权限管理 | — | 有 | 完全没有 |
| 唯一间接接触 | Git 官方协议文档 | `gitserver.cpp:181` 注释引用 GitHub 上的 `git/git` 协议文档镜像 | 只当文档站看，运行时不联网访问 |

**类比**：Git 像「书的排版与编目规则」，GitHub 像「一家图书馆」。Jami 是两个人各自保存一本不断续写的同一本书，通过一条私密电话线互相报「我多了哪一页」并把缺页寄过去——**全程没有进过图书馆，也没有馆员**。

### C.4 三个最容易混的点

| 误解 | 订正 | 依据 |
|---|---|---|
| 「`git://` 说明用了标准 Git daemon 协议（TCP 9418）」 | 同名不同物。标准 `git://` 是明文、无认证、9418 端口的 git daemon 协议；Jami 把同名 scheme **注册覆盖**成自己的 P2P transport，跑在已认证的 TLS 通道上，9418 端口完全不涉及 | `manager.cpp:746`；`gittransport.cpp:289-295` |
| 「消息发到 GitHub / 云端仓库了」 | 没有任何外网仓库地址。`git_remote_fetch(remote.get(), nullptr, ...)` 拉到的对象来自对端设备的 `GitServer`，落到本机 `<data_dir>/<accountId>/conversations/<convId>/` | `conversationrepository.cpp:3748`；`gitserver.cpp:436-478` |
| 「`file://` 那条是产品路径」 | 不是。`FETCH_FROM_LOCAL_REPOS` 只在 `LIBJAMI_TEST` 宏下供单元测试使用，正式构建不启用 | `conversationrepository.cpp:76,3079-3082,3705-3709` |

正文提到的 256MB 上限正挂在这条通道的 `transfer_progress` 回调里（`conversationrepository.cpp:3733-3747`），超限直接中止 fetch——是**单次传输**约束，不是仓库容量上限。

## 附录 D 为什么用 Git：官方动机与效率裁定（2026-11-13 补）

### D.1 前提订正：当前版本已没有「1:1 直发文本」这条路

用户常见设想是「纯文字走 1:1 直发、不必经过 Git」。但本源码树里 **1:1 会话本身就是 swarm**，官方在 2022 年主动拆掉了旧的直发消息路径。

| 事实 | 依据 |
|---|---|
| Qt 发送入口 `!conversation.isLegacy()` 直接调 `ConfigurationManager::sendMessage` → 写 swarm commit | `conversationmodel.cpp:1141-1148` |
| `isLegacy()` 等价于 `mode == Mode::NON_SWARM`；当前 1:1 是 `Mode::ONE_TO_ONE`，`isSwarm()` 为真 | `api/conversation.h:112-125` |
| Qt 里残留的 `sendDhtMessage` / `sendSipMessage` 分支只服务 `NON_SWARM` 老会话与 SIP 账号兼容 | `conversationmodel.cpp:1191-1197` |
| **ADR-005**（2022-04）：会话消息**只**作为 swarm commit 存在；DHT 不再承载即时消息，只做发现与信令 | `doc/adr/ADR-005-retire-dht-instant-messages.md:14-21` |
| 2022-04 `d352fb8f1` 移除非 swarm 会话降级，2024-04 `7463a8a26` 移除 DHT 消息交换 | 同上 `:31-36` |
| 现在 `sendTextMessage` / `MessageEngine` 承载的是**便条、typing、已读回执**等 ephemeral 消息，不是聊天正文 | `jamiaccount.cpp:3831-3854,4261-4275`；ADR-008 `:10-21` |

### D.2 官方给出的设计理由

**ADR-001 Context** 原文把目标写成一句话：跨设备与跨成员的**有序、可同步历史**，**无服务器**，**离线分叉后能合并**，**作者身份可验证**（`ADR-001:10-12`）。官方文档 `Swarm → Git → Why this choice` 列了五条选型理由：

| 官方理由（原文要点） | 解决的问题 | 对应本文档结论 |
|---|---|---|
| Merkle Tree 是同步与排序消息的合适结构，可通过合并分支线性化；Git 让设备间同步容易 | 时序不依赖可伪造的 commit 时间戳 | §10 DAG 因果序、§11 并发不保证全序 |
| Git 天生分布式、使用广泛、后端可插拔 | 无中心权威、网络分区可用 | 零服务端约束 |
| 可通过 hooks 验证 commit，使用成熟密码学 | 作者身份可验证、不可否认 | §8 每个 commit 设备私钥签名 |
| 必要时可存入数据库 | 存储后端灵活性 | — |
| 消息放 **commit message 而非文件**，避免合并冲突 | 并发写不产生文件内容冲突 | `commit_message.h:116-161`；`ADR-001:21-22` |

**被替换掉的旧 DHT 消息机制的痛点**（官方文档 `!! OLD DRAFT !!` 需求清单与流程章节）：

| 旧机制问题 | 官方原文要点 |
|---|---|
| 持久性差 | DHT 上的值只活 **10 分钟**，要持久化就得每 10 分钟 re-put；离线时靠多节点代放会指数放大（8 节点 → 64 请求） |
| 无完整历史与排序 | 需要 message ordering、history sync，且要把历史数据库从客户端搬进 daemon |
| 无人在线即丢 | 「If nobody is connected, the synchronization can not be done, and the person will never see the conversation」 |
| 前向保密不足 | 证书一旦泄露，该会话此前 DHT 值可被解密；官方文档讨论过引入 Double Ratchet |
| 文件传输脆弱 | 旧方式是发送方直推，断连即失败、需手工重试 |

**统一模型收益**：`ADR-001:28-35` 写明后续能力（通话 ADR-004、资料、协同文档 ADR-010）都复用同一套 commit type 或仓库文件；编辑、表情、撤回、成员变更也都只是新的 commit 类型（`commit_message.h:31-82`）。1:1 与群聊共用一条代码路径，不再维护两套消息平面——这正是 **ADR-005 消除 "two message paths" 的目的**。

### D.3 效率裁定：直觉没错，但「每次全量重传」是误解

| 环节 | 实际行为 | 是否全量 | 源码 |
|---|---|---|---|
| 本地写消息 | 顺序写一条 commit object + 一次设备私钥签名 | 与文字长度基本无关的固定成本 | `conversation.cpp:2507-2527`；`conversationrepository.cpp:2262-2344` |
| 通知 | 约百字节便条，只含 `id` / `commit` / `deviceId`，**不含正文** | 否 | `conversation_module.cpp:1464,1455-1541` |
| 建通道 | 复用该设备对已有的 TLS 连接，开一个命名子通道；socket 缓存复用 | 否，不重新握手 | `jamiaccount.cpp:2669-2671`；`conversation.cpp:924-962`；ADR-002 |
| 拉取协商 | 标准 want/have：`git_revwalk_hide()` 隐藏对端已有的 commit | **否，增量** | `gitserver.cpp:480-494` |
| 打包 | `git_packbuilder_insert_walk()` 只打缺失对象，连相关 tree/blob 一起跳过 | **否，增量** | `gitserver.cpp:480-500`；注释明写「rather than sending the whole tree, every certificate included, with each new commit」 |
| 首次加入会话 | `git_clone` 拉全量并逐 commit 验签 | **是**，但只在首次 | `conversationrepository.cpp:3077-3115,4821-4827` |

所以「一条文字消息 = 重新传一遍整个仓库」并不成立。**真实开销**是每条消息一个 commit object 与签名、并发分叉的 merge 重排、以及仓库随历史无限增长后的首次 clone 与 256MB 单次拉取上限。

### D.4 官方自己承认的代价

| 代价 | 官方原文 / 位置 |
|---|---|
| 仓库无界增长，打包与拉取开销是内生的 | `ADR-001:33` "Repositories grow without bound; pack and fetch costs are inherent." |
| 历史不可改写、不可删除；删除等于离开会话 | `ADR-001:32`；官方文档 `Limits` |
| 改仓库布局 / commit 类型 / 校验规则 = **协议变更**，旧节点会拒收 | `ADR-001:30-31` |
| 不支持 swarm 的客户端无法交换消息；投递必须先建 P2P 连接 | `ADR-005:25-27` |
| 性能当年即列为待验证项（`git.lock` 可能成为瓶颈、并发 pull、libgit2 hooks） | 官方文档 `What we have to validate` |

### D.5 能否对纯文字绕开 Git

**裁定：不能作为局部优化来做。** 绕开等于把 ADR-005 淘汰的路径请回来：消息不进 commit 就不签名、不进历史、不被自己其他设备同步、离线即丢、编辑/撤回/引用失去锚点，切回 swarm 时历史对不上；同时重新制造 ADR-005 明确要消除的「两套消息平面」。改 commit 类型或校验规则按 `ADR-001:30-31` 属协议变更，需要重写 `conversationrepository.cpp` 并保证旧节点兼容，工作量远大于 F-901 本身。

| 可行优化方向 | 是否保留 swarm 模型 | 说明 |
|---|---|---|
| ephemeral 消息不进 Git（typing、已读回执） | 是，**官方已做** | 官方文档 `Limits` 明说这类非永久消息走 DRT 特殊消息；ADR-008 已改走 msgpack `msg:` 通道 |
| 通知层减负 / 通道复用调参 | 是 | ADR-008 已用 msgpack 取代 SIP MESSAGE，去掉 SIP 头开销与 pjsip 大小限制 |
| 批量 commit / 合并短消息、Trickle 候选 | 是 | 属实现层优化，不碰协议 |
| 按版本轮换 `conversationId` + 历史窗口 | 是 | 把单仓压在 256MB 内，本项目 F-901 已按此方向建议 |
| 换掉 Git 存储 | **否** | 协议级重写，见上 |

---

## 附录 E 本地 Git 消息库是否加密（2026-11-13 补）

### E.1 结论：**不加密，明文落盘**

`<data_dir>/<accountId>/conversations/<conversationId>/` 是一个**标准 libgit2 仓库**，`git_repository_open()` 直接打开（`conversationrepository.cpp:243-250,3001-3035`）。全文件检索 `conversationrepository.cpp`，`aesEncrypt` / `encrypt()` **0 命中**，所有 `crypto::` 调用都是 `Certificate`（证书解析与验签），不是加密。

| 存的内容 | 落盘形态 | 是否加密 | 依据 |
|---|---|---|---|
| 聊天正文 | commit message 里的明文 JSON `{"body":"Hello!","type":"text/plain"}` | **否** | `commit_message.h:51,93-96,116-161`；`conversationrepository.cpp:2324-2325` |
| commit 签名 | base64 后作为 header `signature` 附在**同一条 commit** 上 | 否，签名不是密文 | `conversationrepository.cpp:2331-2335,2931-2938` |
| 成员 / 设备证书、`invited`、`banned`、`profile.vcf` | Git tree 内的 PEM 证书与 vCard 明文文件 | **否** | `conversationrepository.cpp:379-424,1196-1198,1852` |
| 附件 / 协同文档 checkpoint 文件 | 文件本身进 Git tree（`body` 置空） | **否** | `commit_message.h:65-69` |
| 待发便条队列 | `msgpack::pack(file, messages_)` 明文序列化到缓存文件 | **否** | `message_engine.cpp:243-314` |

**Git 对象是被 zlib 压缩的，压缩不等于加密**：`git cat-file -p <commit-hash>` 或 `zlib` 解包一行就能读出完整正文，不需要任何密钥。

### E.2 四个最容易混成「加密」的机制

| 机制 | 真实作用 | 能否挡住本地读明文 | 依据 |
|---|---|---|---|
| TLS / DTLS 传输加密 | 只保护 Git 通道与便条**在网线上**的字节 | **不能**，落地即解密 | `jamiaccount.cpp:3350,3430`；ADR-002 |
| 每个 commit 设备私钥签名 | 防篡改、可验证作者、历史不可否认 | **不能**，签名与正文并列存放 | `conversationrepository.cpp:2333-2335,2074-2081` |
| 账户归档密码（AES） | 只加密**导出的** `*.jac` / `archive.gz` 备份文件 | **不能**，运行时仓库不受影响 | `fileutils.cpp:433-465`；`jamiaccount.cpp:1049-1056,1104` |
| 私钥密码 | 只保护私钥 PEM 本身 | **不能** | `tlsvalidator.cpp:267-274` |

账户归档 `toJson()` 里确实有 `conversations` 字段，但只是 `ConversationInfo` **元数据**（id、成员、请求列表），**不含 Git 仓库与聊天正文**（`accountarchive.cpp:105-141`）。所以「备份账号设了密码」与「本机聊天记录被加密」是两件事。

### E.3 历史侧面：加密曾经存在过，被 swarm 换掉了

`ADR-005:10-12` 原文：「**Before swarm (ADR-001), text messages were encrypted values put on the DHT under each device's `inbox:` key.**」旧机制把消息作为加密值放 DHT；2022 年统一到 swarm 后，正文改存本地 commit，加密值这一层随之消失。`ADR-001` 全文检索 `encrypt` / `plaintext` / `confidentiality` **0 命中**——官方设计目标是「可验证、可同步、有序」，不是「落盘保密」。

### E.4 Qt 客户端可能还有第二份明文（同样不加密）

| 事实 | 依据 |
|---|---|
| Qt 有 `interactions` 表含 `body TEXT`，普通 `QSQLITE` 的 `.db` 文件，无 SQLCipher、无 `setPassword` | `database.cpp:55-71,134-146` |
| 写入点在 `sendSipMessage` / `sendDhtMessage` 的 legacy 分支，以及按 peer URI 建旧会话的路径 | `conversationmodel.cpp:1191-1202`；`conversationmodel_messaging.cpp:270-284` |
| swarm 消息不走此库；正文的权威副本只有 daemon 的 Git 仓库 | 与附录 D.1 的 `isLegacy()` 判定一致 |

若本机曾用过 legacy / SIP 老会话，磁盘上可能**额外**多一份明文聊天记录，且同样未加密。

### E.5 对 F-901 与加固路径的意义

v2 规格里「免邀请自由进入 + 全量 fetch + 明文 commit」三者叠加，任何加入者拿到的就是**可读的全量历史**。本地库加密救不了这一点——它防的是「别人碰我硬盘」，不是「进房的人读我消息」。

| 加固方案 | 挡住谁 | 挡不住谁 | 是否协议变更 |
|---|---|---|---|
| 全盘 / 目录加密（BitLocker、LUKS、把 `data_dir` 放 VeraCrypt 容器） | 设备丢失、其他本地账户、离线取证 | 已登录本机的进程、任何加入房间的人 | **否，唯一不改代码的方案** |
| libgit2 自定义 ODB 加密后端（at-rest） | 同上，可只保护 `conversations/` | 同账号多设备同步时对方仍拿明文；密钥需常驻内存 | 否，但要改 daemon，验签 / merge 前须解密 |
| 加密 commit `body` 字段（内容级 E2EE） | 未持密钥者、后来进房者（视密钥轮换） | 需解决新成员密钥分发、前向保密、旧节点兼容 | **是**，`ADR-001:30-31` 属协议变更，须走规格 v3 |

**边界必须写死**：at-rest 加密是「本机自己能解开」，对端 fetch 时通道里传的仍是明文 commit（否则无法验签、无法 merge、无法上屏）；要挡「非房间成员」只能做内容级加密，而那是 §2.11 保密档位 B / C 讨论的协议级问题，当前未立项。

---

> 文档状态：源码核准完成（2026-11-13），仅描述官方现行实现，**未修改任何功能代码**。F-901 公共聊天室仍为「方案已定、待开工，功能代码 0 行」。
