# 去他妈的鸡险 · FuckPo0JiXian

这个项目原本是一个独立应用，在公网 IPv4 变化时自动更新 Po0 白名单。
平时已经开着 FlClash RS，只需要维护一个槽位的话，直接用下面的模块就能少装一个 App。
模块会在核心启动和切网时检查出口，也会定时补查（默认每分钟一次）。

## 用 FlClash RS 自动加白

1. 下载 [FlClash RS v0.9.5-rs.1](https://github.com/chen08209/FlClash/releases/tag/v0.9.5-rs.1)，启动核心并选「规则模式」。在「工具 → 进阶配置」打开「HTTPS 解密」。如提示生成证书，生成即可，本模块无需安装系统证书。
2. 进入「模块 → + → 通过URL导入」，复制 [Po0 自动加白模块链接](https://cdn.jsdelivr.net/gh/yiyezq162/FuckPo0JiXian@main/flclash-module/po0-whitelist.remote.sgmodule) 粘贴进去。
3. 在模块参数里填 `token` 和自己的 `slot`（从 `0` 开始），先打开 `dry`（只读），再启用模块并确认运行脚本。
4. 在模块详情的「规则 → 脚本」里对 `po0-check` 点「立即运行」。核对出口和槽位后关闭 `dry`。出口已在目标槽位时，脚本会安静退出。

[模块参数、离线导入与使用说明](flclash-module/README.md)
