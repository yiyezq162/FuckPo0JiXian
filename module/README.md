# AllowMate 0.6 运行模式模块

（如果您不知道这个是什么，那么就不用理会）

模块辅助后台唤起：网络变化时尝试叫醒应用检查，减少漏触发；不是让 APK 永久常驻的保活工具，不能绕过强行停止或保证深度休眠中的即时同步。普通用户可直接使用标准模式。管理器静态描述和 Action 更新后的描述均保留上述提示。

适用于 Magisk / KernelSU 模块安装器；KernelSU已有条件性真机证据，最新修订及Magisk仍需对应真机验收。APK仍可完全独立使用。模块仅维护一个 root app_process 事件 helper，所有国内出口和 Po0 请求仍由 APK UID 执行。无需挂载、metamodule、SELinux规则、全局Doze修改或root授权弹窗。

service.sh 为共同 late_start 入口；KernelSU boot-completed.sh 可再次尝试启动，文件锁保证唯一实例。失败仅重试4次、15/30/45/60秒退避，不承诺永久恢复。网络变化（含同一网络上本机 IPv4 变化）驱动固定短时服务，3 秒内合并重复事件；另每 10 分钟唤起一次兜底检查，APK 只在本机对比出口，变化或满一小时才请求 Po0。60秒定时器只检查生命周期，不发HTTP、不持有唤醒锁。门禁文件事件及生命周期检查发现新的可运行代次时，至多发出一次恢复触发，涵盖解除暂停、模式启用、首次解锁及APK更新；没有变化则不唤起APK。Android隐藏systemContext API不保证每个ROM可用，失败显示降级。

helper仅读取APK专用runtime-v1.json（协议、安装随机ID、选择/暂停门禁与代次），不读取state-v1.json或credential.enc。固定abstract Unix socket接受已固定签名且UID匹配的主用户APK的STATUS/INFO/CLAIM；额外只允许root诊断进程调用DIAGNOSTICS，root不能用该接口领取APK票据。APK也验证服务端UID0。没有任意命令或地址参数。APK更新、helper重启和模式门禁改变后旧票据无效。

INFO包含开机编号与helper启动的elapsedRealtime。APK成功证明绑定开机编号/安装实例/helper代次；开机编号未知时不承认成功证明。旧纯文本回执仅作为归属未知的历史记录。实时连接、历史连接报告、历史请求结果分开展示，历史数据不证明当前在线。

Action现在只做一次只读诊断：目录/disable/remove标志、root身份socket握手、历史生命周期报告；不启动APK或helper、不发HTTP、不解除强停。helper仅在生命周期状态变化时写模块自己的last-report.json，含开机编号/时间/状态，不含token；禁用/移除前尽力留下最后报告，SIGKILL等不保证回执。该文件仅供管理器Action诊断，APK不直接读取，不能据此推断当前状态。模块卸载后它随目录删除，不留下外部服务。

KernelSU Action可通过官方module config --temp更新override.description，只写“最近人工检查”和时间；不代表持续在线，支持失败时保留控制台诊断。Magisk不使用此配置。Action可能因模块禁用或卸载而无法使用，此时在管理器列表核对；APK维持未知降级，不虚构未安装。helper读取BOOT_COUNT使用固定只读系统settings命令，成功后缓存；有2秒超时，没有任意shell输入或周期网络请求。

每个helper代次必须先完成APK内只读请求才允许后续使用原自动同步开关。仅握手、排队、缓存或限频不算完成。模块禁用/remove被每次事件和票据核验检查；FileObserver/60秒生命周期检查退出helper。首次解锁前不接触凭据；强停标记阻止自动拉起，只有用户主动打开应用才恢复。

安装前先核对dist/SHA256SUMS-runtime06和APK签名。当前只支持主用户0；APK versionCode 须与模块 module.prop 的 versionCode 一致（本版 6），更新APK需同时更新模块。协议仍为1；固定网络身份与稳定槽位全部由APK维护，模块不接收SSID/BSSID或授权。不支持工作资料或多用户。不得把“撤销APK su权限”误当作模块禁用：此APK不申请su，必须在管理器禁用/卸载模块。管理器直接剥夺模块root能力仍未真机验证。

回退：先在APK选标准模式（保留所有配置与限频）；必要时在「概览」页暂停。再在管理器禁用/卸载allowmate_helper；观察短时服务退出（90秒协程超时，深Doze单独降级退出，不保证深睡墙钟上限），待授权重启确认卸载结果。无需清数据/卸载APK，不删除Po0条目，不修改家宽更新器。不要为回退卸载APK或强行降版本；旧包可保留作参考，旧版不理解新字段。

公开安装与验证说明见仓库 docs/INSTALL.md 和 docs/TESTING.md。设备测试需由机主确认安装、重启和强停范围；插电短样本不能证明自然待机低耗电。
