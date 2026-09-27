# 0.6.0-preview 验证记录

2026-09-28。本版改动：去掉 2 分钟最短间隔（手动检查不受限，仅保留自动检查 10 秒防循环、失败退避和平台 429），切网约 3 秒检查，10 分钟兜底改为本机对比出口、变化或满一小时才查询 Po0；免 root 唤起（网络 PendingIntent、Doze 允许的闹钟、开机与更新恢复）；固定槽可选“后台识别”（始终允许位置）；模块同步支持本机 IPv4 变化与 10 分钟兜底唤起，版本号改由 module.prop 读取。

| 范围 | 本轮证据 |
| --- | --- |
| JVM | 112 项通过，新增：兜底跳过 Po0 的判定条件、手动检查绕过防循环但遵守 429、默认节奏 |
| API36 ARM64 专用模拟器 | 23 项通过，新增 WakeTest：由系统 dumpsys 确认兜底闹钟与网络唤起各登记一份、重复登记不叠加、暂停或无 Token 时全部撤销 |
| 构建 | Debug / Preview + R8 / Preview Lint 通过；APK 与模块版本号一致性由 package.sh 检查 |
| OPPO PKU110 Android 16 真机（0.5.0） | 复现后台脱敏：仅前台位置权限时，后台检查返回 WIFI_UNAVAILABLE，同时 AppOps 记录 Reject |
| OPPO PKU110 Android 16 真机（0.6 后续修正） | 应用在后台、未打开：移动数据重连两次，均在网络变化后约 3 秒开始检查、约 9 秒内完成移动槽写入与核对（SLOT_UPDATED）；本机 IPv4 变化同样触发。发现并修正网络 PendingIntent 为一次性（发送后约 5 秒被系统释放，模拟器同样复现），改为按当前网络只等待“下一次变化”，并在检查完成、启动、打开和兜底时重新登记；登记在真机上持续有效。“不显示后台任务”经 dumpsys 与最近任务界面确认生效。已授权槽位显示“已授权”。固定 Wi-Fi 槽的后台切换未在本轮单独计时 |

# 0.5.0-preview 验证记录

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
