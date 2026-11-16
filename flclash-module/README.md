# Po0 自动加白 · FlClash 模块

把本机当前的 /24 出口**钉进 Po0 白名单的一个固定槽位**。语义照 po0fw：

1. 先只读 `GET`，拿到服务端看到的当前出口与整张白名单；
2. 当前 /24 已经在名单里（无论哪一格）→ 什么都不做；
3. 不在 → `POST /add?slot=N` 写进固定槽位，或用 POST 返回的快照回读核验；
4. 没生效就不报成功，失败 6 小时内不重复轰炸通知。

触发时机：切网（`network-changed`）、代理启动（`engine-started`）、定时兜底（`cron`，默认 1 分钟，可用 `every` 参数调）。

- `po0-whitelist.remote.sgmodule` — 单文件版，脚本走 jsDelivr，**一键导入用这个**
- `po0-whitelist.sgmodule` — 离线版，脚本靠「附件」挂上去
- `po0-whitelist.js` — 脚本
- `harness.mjs` — 19 组离线场景（假的 `$httpClient` + 假的 Po0 后端），不访问网络

```bash
node flclash-module/harness.mjs
```

## 前提：只有 rs 预览版能用

稳定版（v0.9.3，Go 核心）的内核没有 MITM，`mitm.*` 返回 `not_implemented`，界面在 `FEATURE_MITM`
后面默认关闭。模块只对 `-rs` 预览版（Rust 核心 `concord`）有意义。

## 一键导入

工具 → 进阶配置 → 模块 → 右上角 **+** → **通过URL导入**，粘贴：

```
https://cdn.jsdelivr.net/gh/yiyezq162/FuckPo0JiXian@main/flclash-module/po0-whitelist.remote.sgmodule
```

（仓库合并到 `main` 后生效；国内被墙时把 `cdn.jsdelivr.net/gh/` 换成 `raw.githubusercontent.com/`，格式
`raw.githubusercontent.com/yiyezq162/FuckPo0JiXian/main/...`。）

## 装法

前提：`-rs` 预览版，且**先打开 HTTPS 解密**（工具 → 进阶配置 → HTTPS 解密）。会弹一次「生成证书」，
生成即可——**不用装进系统信任库**：脚本不解密任何流量，`decrypt-tls` 是 false，443 直接跳过，
cron / event 照样跑。不打开这个开关，模块脚本根本不会运行（`run_script` 会报 MITM 未开启）。

1. **工具 → 进阶配置 → 模块 → 右上角 +**，三种导入方式选一种：
   - **新建模块**：把 `po0-whitelist.sgmodule` 的内容整段粘进去，保存；
   - **通过文件导入**：把 `.sgmodule` 传到手机再选它（Android 用系统文件选择器）；
   - **通过URL导入**：把模块托管到 HTTPS，填 URL（可自动更新）。
2. 点进这个模块（列表里点一下标题），**附件 → 添加本地文件**，选 `po0-whitelist.js`。
   不挂附件的话，「规则 → 脚本」下面会显示「忽略的行：此行引用的文件不是附件，请改用 URL 或添加为附件」。
3. 同一页往下是**参数**，填 `token`、`slot`（默认 `0`）、`endpoint`；**第一次把 `dry` 打开**。
4. 返回列表，把模块的开关打开，会弹「运行此模块的脚本？」——确认。
5. 再点进模块，「规则 → 脚本」里对 `po0-check` 点**立即运行**（这个菜单只在模块开启后出现），
   看通知里的出口对不对。确认后把 `dry` 关掉。

> 想省掉「附件」这一步，就把 `po0-whitelist.js` 托管到 HTTPS，把模块里三行 `script-path=` 换成那个
> URL；这样模块本身就是单文件，可以直接「通过URL导入」并自动更新。

## 参数

| 参数 | 说明 |
| --- | --- |
| `token` | `pgnfw_` 开头的 token，或完整链接 `https://124.221.69.228/api/firewall/pgnfw_xxx/add` |
| `slot` | 钉死的槽位号，`0` 起，上限看平台返回的 `limit`。**留空** = po0fw 默认模式（不钉槽位，坑满 FIFO，被挤掉下轮自愈） |
| `endpoint` | 服务器地址，默认 `https://124.221.69.228` |
| `dry` | 只读模式。`true` 时只查询、只报告，不写。第一次验证用，验完改回 `false` |
| `every` | 定时兜底的间隔，选 1 / 2 / 5 / 10 / 30 分钟，默认 **1 分钟**。切网与代理启动不受它影响 |

## 行为

| 情况 | 动作 |
| --- | --- |
| 当前 /24 已在白名单（任何槽位） | 只查询，不写；如果钉的槽位与它在的那格不同，提醒你「要换槽位先删旧记录」 |
| 当前 /24 不在，钉了槽位 | `POST /add?slot=N`，回读核验 |
| 当前 /24 不在，没钉槽位 | `POST /add`，服务端分配空位或 FIFO 淘汰最旧 |
| 平台 `enabled: false` | 不写，通知一次 |
| `429` | 按 `Retry-After` 退避（最长 6 小时），退避期内一个请求都不发 |
| 写入返回的快照里没生效 | 通知「未生效」，不谎报成功 |
| `dry: true` | 只查询，报告「本应写入什么」，不 POST |

## 网络变化能侦测到什么程度

| 情况 | 能不能立刻发现 |
| --- | --- |
| 切 Wi-Fi、开关移动数据、换网络 | ✅ `network-changed` 事件，核心一收到网络更新就通知脚本 |
| 代理启动 / 重开 | ✅ `engine-started` 事件 |
| 家宽重拨、运营商换 IP，但 Wi-Fi 没断 | ❌ 没有任何事件，只能等 cron |
| 同一个 Wi-Fi 内漫游到另一个 AP | ❌ 通常不触发（网络实例没变） |

事件链路是现成的：Android 的 `NetworkObserver` 在首选网络变化时调 `Core.resetNetwork()`，
桌面端网络变化走 `updateNetwork(interfaceChanged: true)`，两者都进核心的 `reset_network()`
→ `engine.network_changed()` → `mitm.notify("network-changed")`。

「Wi-Fi 没断但公网 IP 变了」这件事**任何客户端都拿不到事件**——po0fw 的文档也这么写，
它的兜底也是定时。所以 cron 不是主路径，只是补一个没人能感知的缺口。

### 兜底频率

用 `every` 参数选，默认 **1 分钟**：

| `every` | 每 token 每天约 |
| --- | --- |
| 1（默认） | 1,440 次 GET |
| 2 | 720 次 |
| 5 | 288 次 |
| 10 | 144 次 |
| 30 | 48 次 |

每次只是一个只读 GET，出口真变了才多一次 POST；切网和代理启动走事件，不受这个参数影响。

1 分钟是 5 段 cron 的最小粒度。想更快就手改 `po0-check` 那一行为 6 段（第一段是秒）：
`cronexp="*/30 * * * * *"`。再往下不建议——Android 上脚本并发只有 2，每次都要起一次 QuickJS，
而「Wi-Fi 没断但 IP 变了」这种场景本来就不差这半分钟。

## 三个坑

**全局模式下这条规则不生效。** `IP-CIDR,124.221.69.228/32,DIRECT` 只在**规则模式**下起作用；
FlClash 切到**全局模式**时所有连接都走节点，脚本的请求也会，Po0 看到的就成了节点 IP——
白名单会被写脏。用这个模块就留在规则模式。（po0-clash 靠内核里的专用 DIRECT listener 绕开这一点，
模块拿不到 listener，只能靠约定。）

**每台设备钉不同槽位。** 钉死意味着「这一格归我」，两台设备钉同一格会互相覆盖。
换槽位号之前先去面板把旧记录删掉，否则脚本会拒绝写入并提醒你。

顺带一提：po0-clash 后来**把固定槽位整个去掉了**（ADR 0007），理由是「同一个 /24 只能出现一次，
出口已在别处时 `add?slot=N` 会被拒并每轮重发」「槽位被占时要么顶掉别人要么写不进去」「接口没有删除和移动」。
本模块按 po0fw 的 `token@N` 保留固定槽位，第一条冲突已经挡住（已在名单就不写），
后两条只能靠「每台设备自己一格、换格先删旧记录」这个约定。

**代理关了就停。** 脚本挂在核心的 MITM 引擎上，核心停 = 脚本停。

## 为什么不用 FlClash 自带的出口探测

有：核心的 `probe.outboundIp`（Dart 侧 `probeOutboundIp`），仪表盘「出站 IP」卡片和 IP 质量用它，
也能指定 `DIRECT` 去测物理出口。但它用不上：

- **脚本够不到**：它是核心 RPC，模块沙箱里只有 `$httpClient` / `$environment` 那套 Surge API，
  `$environment` 里只有 system、language、device-model，没有出口字段；外部控制器也没有这个接口。
- **不是后台常驻**：由界面 watch 驱动，路由版本变了才重测，没有「出口变了」的事件可以订阅。
- **测的是路由后的出口**：规则/全局模式下就是节点 IP，而加白要的恰恰是物理出口。

所以模块直接问 Po0：一次 `GET` 同时拿到服务端看到的出口和整张白名单，只在需要时 `POST`。
这已经是最省的一次请求——再加一个 IP 回显只会变成两次。

## 安全

- token 是明文存在 FlClash 的 `mitm_modules` 表里，界面可读、备份里有；脚本存储是全局共享的
  `store.json`，任何模块的脚本都能读。**只装你信得过的模块。**
- 模块脚本能读它解密到的流量、也能向任意地址发请求；FlClash 的提示原话是「开启后，它之后的更新会直接生效」。
- 这个模块自己**不装 CA、不解密流量**，只做定时任务。

## 不做什么

- 不认识 Wi-Fi / 位置，所以不做「槽位跟着地点走」；
- 不做多设备共管、槽位保护、写前重读与待确认记录。

这些是 [FuckPo0JiXian](../README.md) 本身在做的事。
