# 白名单随行 · AllowMate

用于 Po0 IPv4 白名单管理的 Android 应用，使用 Kotlin 与 Jetpack Compose，提供中文界面、只读观察、家宽保护、手机专用槽位同步，以及可选的 KernelSU / Magisk 运行辅助模块。标准模式无需 root。

当前版本：**0.4.0-preview（预发布测试版）**。适合分阶段真机测试，不代表所有 ROM、后台场景或业务连接均已验收。

## 下载

- [直接下载 APK](https://github.com/yiyezq162/AllowMate/releases/download/v0.4.0-preview/AllowMate-0.4.0-preview.apk)：普通安装选此包，非 debug，已启用 R8 与资源压缩。
- [Release 页面与 SHA256 校验文件](https://github.com/yiyezq162/AllowMate/releases/tag/v0.4.0-preview)：包含 APK、可选运行模块 ZIP 和校验和。
- [可选运行模块](https://github.com/yiyezq162/AllowMate/releases/download/v0.4.0-preview/AllowMate-Runtime-0.4.0-preview.zip)：仅供已有兼容 KernelSU / Magisk 环境的设备测试；首次使用先测试标准模式。

包名 `app.allowmate`，versionName `0.4.0-preview`，versionCode `4`。仍使用开发签名，与此前本项目同证书测试包可以覆盖升级；不是正式生产签名。自行构建会使用自己的开发证书，通常不能覆盖下载的 APK。不要通过卸载或清数据解决签名冲突而丢失配置。

## 功能与边界

- Po0 连接页：在手机本地输入 token 或粘贴官方接口链接，本地提取凭据；“检查连接”只读。
- 白名单保护：指定家宽与手机的不同槽位；自动同步仅更新本机手机槽位，保护家宽和其他已有条目。家宽尚未固定时，仅在校验通过的家宽网络初始化。
- 出口核对：写入前核对同一网络近期国内探测与 API 看到的 IPv4 `/24`；不一致时阻止写入，不把 IPv6 或 `/16` 扩大成授权范围。
- 调度：切网约 15 秒去抖，检查至少间隔 2 分钟，网络不变时约 30 分钟检查；系统休眠可能延后。失败退避与限频持久保存。
- 运行模式：标准模式使用 Android 网络回调和系统任务；模块增强只做事件监听与有界唤起，token、网络请求和同步逻辑仍在 APK 内执行。
- 本地安全：Android Keystore 加密凭据、禁用备份与正常界面截图保护；提供脱敏诊断和本机演示模式。

当前真实同步界面是“一个家宽 + 一个手机专用槽位”，不是任意多设备槽位调度器。槽位编号可配置，容量以平台返回为准；不会使用无槽位的自动淘汰添加接口。固定槽位接口没有原子比较交换保护，其他客户端不得共用本机手机槽位。

## 首次安装与另一台手机测试

1. 下载 APK 并安装，必要时允许浏览器或文件管理器安装未知应用；打开“白名单随行”。Android 9（API 28）以上可安装，当前主要测试环境为 Android 16。
2. 保持“标准模式”，首次默认暂停且仅观察。可先在 Po0 → 高级选项启用本机演示；切换演示模式会清除本机快照、关联和槽位配置，但保留凭据。
3. 在真实模式下仅在手机本地输入 token，点击“检查连接”，核对白名单和容量；不要把 token、完整鉴权链接或含凭据日志发到 GitHub。
4. 如需真实自动同步，先配置已在白名单内的家宽 `/24` 和两个不同槽位。**第二台手机必须使用独立、空闲的手机槽位，不能照抄第一台手机的槽位。** 没有空闲名额时保持只读，不占用或删除未知条目。
5. 保存后仍为暂停；确认目标无误，再开启“自动同步”并到首页恢复检查。自动模式会真实更新平台白名单。
6. 分别检查前台查询、Wi-Fi / 移动网络切换、后台恢复；记录时间、系统版本与脱敏错误码。限频、排队或缓存不算新请求成功。测试后恢复原网络和同步设置。

详见 [安装与回退](docs/INSTALL.md)、[验证状态](docs/TESTING.md)。

## 已知限制

- 小米 HyperOS 在关闭自启动许可时可能无法冷启动；模块不会代开系统权限。其他 ROM（包括 ColorOS）需要独立验证。
- 深度 Doze 下不保证及时请求，目前采取安全降级；整夜保活、自然待机功耗与省电优势均未证明。
- helper 退出后，APK 无法可靠区分模块未安装、禁用或异常退出；界面保留“未知/降级”。历史连接不证明当前在线。
- KernelSU 有条件性真机证据；Magisk、管理器撤销模块 root 能力、工作资料和多用户没有完整验收。模块当前只支持主用户 0。
- 国内出口与 API 出口一致、请求成功或条目更新，都不能单独证明最终业务入口可连接。

## 隐私与网络

本项目不提供账户注册或自建分析后端。真实模式请求固定 Po0 HTTPS API；国内出口检查会联系第三方 IP 查询服务（默认 `ip.3322.net`，诊断还可使用 `4.ipw.cn` / `myip.ipip.net`），这些服务会看到请求出口 IP。token 仅在本机保存并用于 Po0 鉴权，模块不保存 token。请不要公开原始设备日志、网段或鉴权链接，提交问题前自行复核脱敏结果。

## 从源码构建

| 工具 | 版本 |
| --- | --- |
| JDK | 17 |
| Gradle Wrapper / Android Gradle Plugin | 8.13 / 8.13.2 |
| Kotlin / Compose compiler | 2.2.21 |
| Compose BOM | 2025.11.00 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 28 |
| Android Build Tools | 36.0.0 |

安装 JDK 17 与 Android SDK，安装 `platforms;android-36`、`build-tools;36.0.0`、`platform-tools`，并自行接受相应许可。设置 `JAVA_HOME` 和 `ANDROID_HOME` 后：

```sh
git clone https://github.com/yiyezq162/AllowMate.git
cd AllowMate
sh scripts/build.sh :core:test :app:assembleDebug :app:assemblePreview :app:lintPreview
sh scripts/package.sh
(cd dist && shasum -a 256 -c SHA256SUMS-runtime04)
```

首次构建需要联网下载依赖。脚本支持本地 `.tools/jdk-17*/Contents/Home`；其他环境请设置 `JAVA_HOME`。产物在 `dist/`，不放入 Git 历史。模块打包会绑定本次 preview APK 的签名摘要，因此自建 APK 应搭配同次构建的模块。仓库不含发布所用私钥。Android Studio 是可选 IDE，将项目 Gradle JDK 设为 JDK 17 即可。

可选模拟器测试：创建并启动专用 API 36 ARM64 AVD（默认序列号 `emulator-5580`，可用 `ALLOWMATE_EMULATOR_SERIAL` 覆盖），运行 `sh scripts/test-emulator.sh`。需要 emulator 与对应系统镜像；辅助脚本需要 `rg`、`jq`、`zip`、`shasum`。测试使用虚构数据；不要对已配置真实凭据的设备运行普通 UI 重置套件。真机专用测试类有独立授权门禁，不应默认运行整个 instrumentation 套件。

## 工程结构

- `core/`：状态机、槽位保护、预算、平台协议与 JVM 测试。
- `app/`：Compose UI、凭据存储、网络调度与 Android 测试。
- `module/`：最小 root 事件 helper 与安装脚本，见 [模块说明](module/README.md)。
- `scripts/`：构建、打包与专用模拟器验证。
- `docs/INSTALL.md` / `docs/TESTING.md`：公开安装与验证说明。

原始真机证据、账户操作记录、私人开发笔记、缓存和密钥不纳入公开仓库。反馈请附机型、Android / ROM 版本、应用版本、运行模式、操作步骤与脱敏结果，注明“只读查询”或“自动同步”。
