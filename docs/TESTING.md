# 0.8.3-preview 安全修复验证（2026-09-28）

本节记录安全修复在 0.8.2/versionCode 17 工作树完成的本地验证，发布版本为 0.8.3-preview/versionCode 18。版本提升不将本地测试变成真实安装升级验收；CI 发布状态以该标签的 GitHub Actions 和 Release 为准。下面 0.6 / 0.5 报告仅保留为各自版本的历史证据，不能替代本节故障覆盖。所有新增请求测试使用虚构 Token、假平台/Transport 和确定性时钟；Mac 安装脚本只在 macOS 临时目录运行，Windows 明确跳过这 5 项，hdiutil/ditto/mv/open 等由模拟命令控制。

## 复现与修复证据

| 项目 | 修复前复现与触发条件 | 修复及定向验证 | 剩余限制 |
| --- | --- | --- | --- |
| 桌面账户切换 | `ReviewSafetyTest.credentialWriteInterruptionNeverLeavesOldAuthority` 失败：凭据写入后抛错，重读磁盘仍是旧账户授权且未暂停 | 两端共用 AccountCredentials：先落盘无授权暂停状态再更换凭据；覆盖安全状态保存失败、凭据写前/写后失败、最终状态保存失败、未知身份、同 Token 保留；桌面用真实 FileStore 临时文件制造写入故障 | JVM 故障注入证明提交顺序，不是物理断电、真实钥匙串/DPAPI 故障验收 |
| Mac 替换 | `MacUpdateSafetyTest` 初始 5 项中仅正常成功通过；备份失败删除原应用、owner 未退出仍替换、复制失败最终退出码为成功、恢复失败仍尝试打开 | 分离退出确认、唯一备份、复制、恢复；5 条路径全部通过，断言原应用/备份材料、早先 `.old` 材料和是否启动 | 只运行实际脚本文本配模拟系统命令，未操作真实 DMG、安装目录或运行安装升级；无签名/notarization 新验收 |
| POST 429 | `ReviewSafetyTest.post429HoldsAcrossManualConnectionCheckAndRestart` 初始失败：桌面 runCheck → Po0Platform → LayoutSync 的 POST 429 后，重启并手动检查连接越过等待 | 独立持久化 serverNotBefore；7,200 秒等待覆盖手动、连接检查、状态文案变化、同 Token 保存、重建 FileStore；到期只 GET 恢复且 POST 数仍为 1；另测全局只读复核 GET 429 的等待不丢失 | 没有修改 Retry-After 解析或加一小时截断；旧文件缺字段时只能保守沿用可见 nextAllowed；系统时钟、持久存储永久不可写及硬件断电不由此测试证明 |
| 并发及全局保护 | `LayoutSyncTest.peerChangeAfterPostCanBeReviewedWithoutReplayButNeverIgnored` 初始失败：写后另一设备合法更新非目标槽，永久拦截只读复核 | pending 保留，显式只读双 GET 复核；仅已标注 peer/共管槽的非空替换可接受，目标单独核验、成功后暂停；未知/无编号变化、普通本机槽变化、记录丢失、账户错配、重复槽号、两次读取变化、提交失败均保留保护与 pending，零重放 POST | 标注不是平台写入者认证；无 CAS，不能消除两次读取之后的新竞态；原因未解决仍阻止恢复，不提供“忽略全部冲突” |
| 文案、导出、版本 | 核对源码确认导出保留设备名、备注、管理者；文档错误写兼容 versionCode 7；全局存储保护提示自动重试；前台服务有绝对承诺 | 两端导出对话框及 JSON 共用明确自由文本风险说明；发布模块/APK 均 18；保护入口说明只读、仍暂停；移除不能保证的自动重试/常驻承诺 | 不对自由文本作绝对隐私保证；前台服务、OEM/Doze 生存能力仍需真机单独验收 |

首次桌面故障测试共 7 项、6 项失败；并发复核另 1 项失败。随后修复并补充边界覆盖，不以历史大量通过的用例替代这些失败路径。

## 本轮结果与复现命令

- JVM 最后完整回归：core 144 项、desktop 34 项，报告零 failures/errors。报告计数包含原有 opt-in 测试的提前返回：未启用 `FUCKPO0JIXIAN_LIVE`，不把名称带 live 的用例计作真实平台/出口/下载验收；截图也另行显式运行。
- Android：全新临时数据盘、隔离 AVD 注册目录的 API36 ARM64 `emulator-5580`，由 SafeTestRunner 守卫；`ReviewSafetyUiTest` 3 项通过，涵盖恢复按钮、等待持久化和两端 Token 行为一致性。测试断言 `NetworkTransport.requests` 无增加。不是 POST429 的 Android HTTP 实测，POST429 端到端故障由桌面真实控制器入口加假 Transport 覆盖。
- 恢复入口显示：Mac 浅色/深色离屏渲染已人工查看；Android 小屏、深色/2 倍字体按钮可见可操作。最终 APK 在 1280×720 宽屏模拟器再次运行 3 项定向测试通过，并人工检查截图；未重跑大量不相关界面套件。
- 构建：Debug、Preview（含 R8）、DebugAndroidTest APK、Preview Lint 通过；已有 deprecated API 警告，不影响本轮结果。
- 本地修复验证阶段未访问真实 Po0 账户、修改真实白名单或手机网络，也未运行真实安装升级。后续用户授权提交、推送和发布 0.8.3-preview；发布使用标签 CI 产物，历史 dist 文件不含此轮验证保证。未部署。

```sh
# 定向故障覆盖
sh scripts/build.sh :core:test --tests '*AccountCredentialsTest' --tests '*LayoutSyncTest' \
  :desktop:test --tests '*ReviewSafetyTest' --tests '*MacUpdateSafetyTest'
# 必要回归与构建
sh scripts/build.sh :core:test :desktop:test :app:assembleDebug :app:assembleDebugAndroidTest :app:assemblePreview :app:lintPreview
# Mac 恢复界面，只有虚构状态、不启动网络监控
FUCKPO0JIXIAN_SCREENSHOTS=1 sh scripts/build.sh :desktop:test --tests '*ScreenshotTest.renderProtectionRecovery'
# 仅在核对 qemu=1、SDK=36、arm64-v8a 且无凭据的专用模拟器安装测试 APK 后
adb -s emulator-5580 shell am instrument -w -e class app.fuckpo0jixian.ReviewSafetyUiTest app.fuckpo0jixian.test/app.fuckpo0jixian.SafeTestRunner
```

本地报告：`core/build/reports/tests/test/`、`desktop/build/reports/tests/test/`、`app/build/reports/lint-results-preview.html`。截图测试产物在 `desktop/build/screenshots/recovery-*.png`；Android 截图仅含演示数据。只读复核和手动检查遵守同一个服务端等待截止，恢复入口按“明确下一步、区分只读与恢复运行”的交互原则呈现。

# 历史：0.6.0-preview 验证记录

2026-09-28。本版改动：去掉 2 分钟最短间隔（手动检查不受限，仅保留自动检查 10 秒防循环、失败退避和平台 429），切网约 3 秒检查，10 分钟兜底改为本机对比出口、变化或满一小时才查询 Po0；免 root 唤起（网络 PendingIntent、Doze 允许的闹钟、开机与更新恢复）；固定槽可选“后台识别”（始终允许位置）；模块同步支持本机 IPv4 变化与 10 分钟兜底唤起，版本号改由 module.prop 读取。

| 范围 | 本轮证据 |
| --- | --- |
| JVM | 112 项通过，新增：兜底跳过 Po0 的判定条件、手动检查绕过防循环但遵守 429、默认节奏 |
| API36 ARM64 专用模拟器 | 23 项通过，新增 WakeTest：由系统 dumpsys 确认兜底闹钟与网络唤起各登记一份、重复登记不叠加、暂停或无 Token 时全部撤销 |
| 构建 | Debug / Preview + R8 / Preview Lint 通过；APK 与模块版本号一致性由 package.sh 检查 |
| OPPO PKU110 Android 16 真机（0.5.0） | 复现后台脱敏：仅前台位置权限时，后台检查返回 WIFI_UNAVAILABLE，同时 AppOps 记录 Reject |
| OPPO PKU110 Android 16 真机（0.6 后续修正） | 应用在后台、未打开：移动数据重连两次，均在网络变化后约 3 秒开始检查、约 9 秒内完成移动槽写入与核对（SLOT_UPDATED）；本机 IPv4 变化同样触发。发现并修正网络 PendingIntent 为一次性（发送后约 5 秒被系统释放，模拟器同样复现），改为按当前网络只等待“下一次变化”，并在检查完成、启动、打开和兜底时重新登记；登记在真机上持续有效。“不显示后台任务”经 dumpsys 与最近任务界面确认生效。已授权槽位显示“已授权”。固定 Wi-Fi 槽的后台切换未在本轮单独计时 |

# 历史：0.5.0-preview 验证记录

2026-09-27 本轮重新执行，历史 0.4 真机结果不作为新版通过依据。

| 范围 | 本轮证据 |
| --- | --- |
| JVM | 109 项通过，其中 LayoutSyncTest 23 项覆盖多槽业务与故障恢复；其余含协议、调度、历史兼容与 SafeTestRunner |
| API36 ARM64 专用模拟器 | 20 项 UI / 迁移 / 运行模式证据测试通过 |
| 视觉矩阵 | 深色、360dp 小屏 + 2 倍字体、横屏各 1 项测试通过；人工检查槽位、授权、保存/取消按钮 |
| 构建 | Debug / 非 debug Preview + R8 / Preview Lint 通过；签名策略未变 |
| 小米 Android16 真机 | 原位升级可读；拒绝/撤销权限不可用、授权后明确前台 WifiInfo 可用，均零 HTTP；最终安装非 debug Preview 并恢复原权限/暂停 |
| 真实 Po0 自动固定更新 / 跨槽迁移 | 未进行；没有生产写入证据，未知迁槽语义仍受阻 |
| Doze / 自然待机功耗 / 多 OEM / 旧 API | 本轮未完成真机验收；不承诺即时发现或低耗电 |

## 复现

```sh
sh scripts/build.sh :core:test :app:assembleDebug :app:assemblePreview :app:lintPreview
sh scripts/test-emulator.sh
sh scripts/visual-matrix.sh
sh scripts/check-restart.sh
sh scripts/package.sh
(cd dist && shasum -a 256 -c SHA256SUMS-runtime05)
```

模拟器脚本只接受 emulator-*，核对 qemu/API36/ARM64，并由 SafeTestRunner 拒绝配置了凭据的重置套件。默认 emulator-5580。普通套件包含 UiTest、SlotUiTest、RuntimeEvidenceTest、VisualTest，不包含 AuthorizedAccountTest。截图保存在忽略的 docs/qa/，仅含虚构数据。

## 核心覆盖

- 家庭跨 /16 和公司独立更新；蜂窝只选本机移动槽，多次通勤原地替换、全局限频。
- 同名新 AP、相同出口不授予身份；明确 Mesh AP 切换、歧义、缺权限/占位字段/过期/安全降级。
- 同 handle 的身份变化、账户/配置/暂停变化、过期手动确认和再次出口验证阻止 POST。
- 单槽冲突隔离、历史不回收写权、备用/其他设备/外部/无编号记录保护。
- 满额合法替换、空目标满额拒绝、跨槽覆盖零 POST。
- 丢响应、进程重建、写前/写后存储故障、pending 配置变化、旧候选不重放、非目标丢失与无 CAS 竞态检测。
- v1 非默认编号、未初始化、旧 pending，v2 往返；模拟器原位迁移、重复读取、损坏原文件保护。
- 配置/备注无远端写入、账户/演示清权、同网络后续复核不被成功缓存挡住。

纯 core 无 Android 依赖；假平台不证明真实 Po0 的重复 CIDR、搬移或原子语义。手动预览和确认均沿用相同安全门禁。

## 真机受限检查

需要机主明确授权，逐个指定 AuthorizedAccountTest 的方法和对应 opt-in，不能运行整个类。inspectUpgradeAndProxyGuard 仅检查已加载状态和可见 VPN 的阻止行为；没有活动 VPN 时跳过网络调用并报告 UNKNOWN，最后保持暂停。inspectWifiIdentityRead 只读取本次 Network 的 WifiInfo，不做 HTTP、不绑定、不改白名单；使用 expectUsable 指明当前权限预期。权限测试后恢复原 fine/coarse 权限。

其他原有真实账户测试仍可能发真实请求或写入，必须另行授权并核对 targetSlot；本轮未运行。没有 token 时不寻找或导入其他项目凭据。
