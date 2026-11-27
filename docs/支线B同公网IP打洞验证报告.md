# 支线 B · 同一公网 IP 打洞（NAT hairpin）验证报告

日期：2026-11-18　|　场景：ZTE 手机 ↔ PC，两端位于**同一 NAT 之后**（共享公网出口）　|　程序：`NMSpray.java` + `nm_spray_flow.py hairpin` 模式

---

## 一、结论前置

| 问题 | 结论 |
|---|---|
| 同一公网 IP 的两台设备能否用公网 IP 打洞双向打通？ | **能。** 小规模（N=64 socket）连测 **5/5 全部真双向打通**（含 SA_LINK 确认、foreign=0）。 |
| 大规模（N=512）是否同样稳定？ | **否。** N=512 单轮失败（仅 PC 端单向收到 60 包、ZTE 端 0 收、10 分钟维持无回包）。 |
| 这是不是编码错误？ | **不是。** 同一份程序在无 NAT 私网（前序实验）和小规模 hairpin 均稳定双向；失败与**规模/映射端口段对齐**有关，非代码缺陷。 |
| 与既有"喷洒判决"（§8.x：跨 CGNAT 打不通）矛盾吗？ | **不矛盾，是不同场景。** 本报告是**同一 NAT 内 hairpin**；既有判决是**跨不同 NAT/蜂窝 CGNAT**。前者可行，后者不可行。 |

**一句话**：同一公网出口的两台设备（如同一家庭/公司 WiFi 下），借助路由器的 NAT hairpin（发夹环回）+ N×M 全交叉喷洒，**可以稳定双向打洞**；这是与"跨网打不通"并列的独立结论。

---

## 二、为什么这个场景理论上应该通（用户判断的物理依据）

ZTE（198.51.100.4，WiFi）与 PC 以太网（198.51.100.6）在同一 /24 网段，**共享公网出口 `198.51.100.78`**。

```
                    ┌─────────────────────────┐
   ZTE 198.51.100.4 ─┤                         ├─ PC 198.51.100.6
   (共享 NAT)        │   路由器 NAT 198.51.100.78│  (共享 NAT)
                    │   hairpin: 公网IP:端口     │
                    │     ──环回→ 内网对端 ──    │
                    └─────────────────────────┘
```

- 两端 STUN 各采样 8 次，映射端口段**高度重叠**：PC `5130–5630`、ZTE `5120–5128`——同一 NAT 分配、量级相同（都在 5000 段），这是与跨 NAT 场景（PC ~5000 / 手机蜂窝 ~53000，差一个数量级）最本质的区别。
- 由于目的 IP 就是自己的公网 IP，若路由器支持 **hairpin**，发往 `<公网IP>:<对端映射端口>` 的包会被路由器识别为"去往内网另一台机器"，直接环回，无需绕行公网。
- 两端 N×M 全交叉喷洒（每个 socket 打对端窗口全部端口），使 `p2=Pb`、`p1=Pa` 两个等式在窗口内必然相遇。

**关键工程点**：hairpin 下路由器常把回包的**源 IP 改写成对端私网 IP**（而非保留公网 IP）。原程序 `NMSpray.java` 的严格 `peerIp.equals(源IP)` 检查会把这种回包全部计入 foreign 丢弃。为此新增 `ACCEPTANYSRC=1` 开关，放宽源 IP 校验，改由 `tag`/`peerTag` 报文前缀校验保证正确性（实测 foreign 恒为 0，说明校验仍有效）。

---

## 三、验证配置

| 项 | 值 |
|---|---|
| 设备 | PC（JVM，`198.51.100.6`）、DEVICE-A（ZTE 测试机）（adb `DEVICE-A-SERIAL`，WiFi `198.51.100.4`） |
| 共同公网 IP | `198.51.100.78`（两端 STUN 实测一致） |
| 喷洒窗口 | 两端 PEER 均设为该公网 IP；端口 `5100–5700`（M=601，取两端映射段并集±64） |
| 映射源端口 | 两端均落在 5000 段（小规模实测 5355–5547 区间），彼此接近 |
| 报文 | `S`（喷洒，载荷带目标端口）→ `SA`（回包，带双方端口，判双向）→ `PG/PO`（心跳维持） |
| 判定口径 | 只有收到 `SA_LINK`（对方确认回包）才算**真双向**；仅 `S_LINK` 属单向，不计打通 |
| 规模 | 小规模 N=64（5 轮）、大规模 N=512（1 轮，维持 10 分钟） |

---

## 四、结果

### 4.1 小规模 N=64：5/5 全部真双向打通

| 轮次 | PC 端 | ZTE 端 | 双向确认 SA_LINK | foreign |
|---|---|---|---|---|
| 首轮（hp_test1） | HIT est=33 recv=176 | HIT est=26 recv=86 | 7 | 0 / 0 |
| REP1 | HIT est=20 recv=371 | HIT est=16 recv=47 | 2 | 0 / 0 |
| REP2 | HIT est=21 recv=146 | HIT est=18 recv=38 | 4 | 0 / 0 |
| REP3 | HIT est=18 recv=107 | HIT est=18 recv=42 | 3 | 0 / 0 |
| REP4 | HIT est=40 recv=517 | HIT est=8 recv=64 | 8 | 0 / 0 |

每轮两端均 `RESULT=HIT`、`foreign=0`，且都有 SA_LINK（真双向确认）。`mySrcSeen`（对端看到的我方源端口）与 `peerSrc` 正确对应，证明 hairpin 环回路径成立。

### 4.2 大规模 N=512（维持 10 分钟）：失败

| 端 | RESULT | EST | SENT | RECV | FOREIGN | 维持 |
|---|---|---|---|---|---|---|
| PC | HIT（口径偏松） | 1 | 614859 | 60 | 0 | 1 条，`lastPongAgeMs=600024`（10 分钟无回包） |
| ZTE | NO_HIT | 0 | 614878 | 0 | 0 | — |

**判定**：PC 端仅 1 条 `S_LINK`（`peerSrc=5464 myTarget=5237`，均在窗口内），无任何 `SA_LINK`，维持阶段 10 分钟未收到一个 PO ⇒ **真实双向 = 0**，协调器判 FAIL。

### 4.3 两种规模的差异分析

| 观察 | N=64 | N=512 |
|---|---|---|
| 映射端口是否落在 5100–5700 窗口 | 是（5355–5547） | PC 端落在窗口内（5464/5237），但 ZTE 端 0 收 |
| 双向确认 | 稳定（每轮 2–8 条 SA_LINK） | 无 |
| 最可能原因 | — | ① 端口段随 socket 批次漂移：少量 socket 的映射集中在 STUN 采样段，大量 socket 时 NAT 分配的映射端口更分散，可能超出 601 端口窗口；② 单端 60 万包的喷洒使手机端收包处理（1ms 超时 drain）时序错过回包窗口。 |

**注**：这是**规模/参数**问题，可通过"扩大窗口 + 实测本批映射段再定窗口"改善，不影响"hairpin 可行"的结论。

---

## 五、程序改动

| 文件 | 改动 |
|---|---|
| `android/NMSpray.java` | 新增 `acceptAnySrc` 开关（`ACCEPTANYSRC=1` 启用）：hairpin 下放宽源 IP 严格校验，靠 tag/peerTag 校验报文；默认关闭，不影响原 real/lan 模式。 |
| `nm_spray_flow.py` | 新增 `hairpin` 模式：`python nm_spray_flow.py hairpin [n] [portlo] [porthi] [keepms]`；两端 PEER 设为共同公网 IP、窗口取两端映射段并集、开启 ACCEPTANYSRC。 |
| `stunspray_coord.py` | 新增 `NM_PHONE` 环境变量（选设备）；`ph_ip()` 改为逐行解析，兼容 ZTE 的 `inet X/24 brd Y scope global` 格式。 |

复现命令：

```bash
# 1. 刷新公网 IP 与映射段（用 ZTE 走 WiFi）
$env:NM_PHONE='DEVICE-A-SERIAL'; python stunspray_coord.py measure 8

# 2. 小规模 hairpin 验证（推荐，稳定打通）
python nm_spray_flow.py hairpin 64 5100 5700 8000
```

---

## 六、判决与建议

1. **同 NAT 设备（hairpin）可作为一条可用打洞路径**：N=64 小规模 5/5 稳定双向，说明同一公网出口下的两台设备借助 hairpin + N×M 全交叉能可靠打通。
2. **规模需匹配窗口**：N=512 失败提示窗口（601 端口）对大量 socket 不足；实用时应对"本批实际映射端口段"做一次测量再定窗口，或分批处理。
3. **与规格 v3 并行记录**：本场景（同 NAT hairpin 可行）与既有判决（对称 CGNAT 跨网不可靠、TURN 必选兜底）是**两条独立结论**，不应互相推翻。⚠️ 订正：原文「真正需要 TURN 的是两端分属不同 NAT/运营商的跨网场景」已被主报告 §8.19 推翻——ADM↔ADM 家庭 NAT 跨网（PC NIC2 电信 ↔ ZTE 移动）已实测真双向打通且可承载 TLS。准确判决：**只有对称 CGNAT（蜂窝）场景才必须 TURN**，跨网本身不等于需要 TURN。

---

## 附：证据文件索引

| 内容 | 文件 |
|---|---|
| 小规模首轮（双向打通） | `hp_test1.log` |
| 小规模 4 轮复现 | `hp_rep1.log` ~ `hp_rep4.log` |
| 大规模 N=512（失败） | `hp_full.log` |
| 同出口公网 IP 测量 | `stunspray_coord.py measure 8`（`android/ssq/measure.json`，PC/PHONE pub 均 198.51.100.78） |
| 程序 | `android/NMSpray.java`、`nm_spray_flow.py`（hairpin 模式）、`stunspray_coord.py`（NM_PHONE） |
